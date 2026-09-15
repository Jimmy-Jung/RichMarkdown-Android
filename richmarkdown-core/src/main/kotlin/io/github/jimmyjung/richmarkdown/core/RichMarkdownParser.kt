// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

import org.commonmark.Extension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.renderer.markdown.MarkdownRenderer

/**
 * 2-pass 파이프라인 (iOS DEVELOPMENT.md §3):
 * 사전 byte 상한 → 1차 파싱(금지 범위/paragraph 수집) → 원문 수식 스캔
 * → 길이 보존 mask → 2차 파싱 → [ParsedDocument].
 *
 * 파서는 commonmark-java(D3). `SourceSpan.inputIndex`가 UTF-16 code unit이라 mask 단위도 같다.
 */
@InternalRichMarkdownApi
public object RichMarkdownParser {
    private val extensions: List<Extension> = listOf(TablesExtension.create(), StrikethroughExtension.create())

    // commonmark Parser/Renderer는 빌드 후 재사용·스레드 공유가 가능하다.
    private val parser: Parser = Parser.builder()
        .extensions(extensions)
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build()
    private val markdownRenderer: MarkdownRenderer = MarkdownRenderer.builder().extensions(extensions).build()

    public fun parse(markdown: String, dollarMath: DollarMathOptions = DollarMathOptions.None): ParsedDocument =
        parse(InputLimits.bound(markdown), dollarMath)

    /**
     * UI ingress에서 한 번 제한한 입력을 재검사 없이 파싱한다.
     * `wasTruncated`는 원문 제한 상태이므로 결과에 그대로 보존한다.
     */
    public fun parse(bounded: InputLimits.BoundedInput, dollarMath: DollarMathOptions): ParsedDocument {
        val text = bounded.text
        val scan = scanMath(text, dollarMath)

        // 보호 버퍼로 2차 파싱. 길이가 같아 위치를 원문에 그대로 쓴다.
        val masked = MathProtector.protect(text, scan.spans)
        val secondPass = parser.parse(masked)

        val builder = ModelBuilder(text, scan.spans)
        val blocks = secondPass.children().mapNotNull { builder.convertBlock(it) }

        return ParsedDocument(
            blocks = blocks,
            wasTruncated = bounded.wasTruncated,
            diagnostics = scan.diagnostics,
        )
    }

    /**
     * 편집기처럼 원문 위치가 필요한 클라이언트용 내부 API.
     * code/HTML/link/image barrier와 delimiter 규칙을 렌더러와 동일하게 적용한다.
     */
    public fun scanInlineMathSpans(
        markdown: String,
        dollarMath: DollarMathOptions,
        excludingRanges: List<Utf16Range> = emptyList(),
    ): List<ProtectedMathSpan> =
        scanMath(markdown, dollarMath, excludingRanges).spans.filter { !it.kind.isDisplay }

    private fun scanMath(
        text: String,
        dollarMath: DollarMathOptions,
        additionalForbiddenRanges: List<Utf16Range> = emptyList(),
    ): MathScanner.Result {
        // 1차 파싱은 code/HTML/link/image 금지 문맥과 paragraph 범위만 수집한다.
        val collector = Pass1Collector(text.length)
        parser.parse(text).accept(collector)

        return MathScanner(
            text = text,
            forbiddenRanges = collector.forbiddenRanges + additionalForbiddenRanges,
            softRanges = collector.softRanges,
            paragraphRanges = collector.paragraphRanges,
            dollarMath = dollarMath,
        ).scan()
    }

    // MARK: - 공용 노드 헬퍼

    private fun Node.children(): List<Node> {
        val result = ArrayList<Node>()
        var child = firstChild
        while (child != null) {
            result.add(child)
            child = child.next
        }
        return result
    }

    /** `sourceSpans`의 합집합 → 원문 UTF-16 범위. span이 없거나 위치를 모르면 null. */
    private fun Node.utf16Range(textLength: Int): Utf16Range? {
        var start = Int.MAX_VALUE
        var end = Int.MIN_VALUE
        for (span in sourceSpans) {
            if (span.inputIndex < 0) continue
            start = minOf(start, span.inputIndex)
            end = maxOf(end, span.inputIndex + span.length)
        }
        if (start > end) return null
        return Utf16Range(start, minOf(end, textLength))
    }

    /** 자식 텍스트 노드를 이어 붙인 plain text (swift-markdown `plainText` 대응). */
    private fun Node.plainText(): String {
        val out = StringBuilder()
        fun visit(node: Node) {
            when (node) {
                is Text -> out.append(node.literal)
                is Code -> out.append(node.literal)
                is HtmlInline -> out.append(node.literal)
                // ponytail: 줄바꿈은 공백 하나로 접는다. 라벨 안 줄바꿈 표시가 문제가 되면 재검토.
                is SoftLineBreak, is HardLineBreak -> out.append(' ')
                else -> node.children().forEach(::visit)
            }
        }
        children().forEach(::visit)
        return out.toString()
    }

    // MARK: - Pass 1

    private class Pass1Collector(private val textLength: Int) : AbstractVisitor() {
        /** hard barrier: code/HTML 전체 범위. */
        val forbiddenRanges = ArrayList<Utf16Range>()
        /** soft range: link/image 전체 범위 (수식이 완전히 포함하면 수식이 이긴다). */
        val softRanges = ArrayList<Utf16Range>()
        val paragraphRanges = ArrayList<Utf16Range>()

        override fun visit(paragraph: Paragraph) {
            paragraph.utf16Range(textLength)?.let(paragraphRanges::add)
            visitChildren(paragraph)
        }

        // 금지 범위: 전체 범위를 기록하고 내부로 내려가지 않는다.
        override fun visit(fencedCodeBlock: FencedCodeBlock) = addForbidden(fencedCodeBlock)
        override fun visit(indentedCodeBlock: IndentedCodeBlock) = addForbidden(indentedCodeBlock)
        override fun visit(code: Code) = addForbidden(code)
        override fun visit(htmlBlock: HtmlBlock) = addForbidden(htmlBlock)
        override fun visit(htmlInline: HtmlInline) = addForbidden(htmlInline)
        override fun visit(link: Link) = addSoft(link)
        override fun visit(image: Image) = addSoft(image)

        private fun addForbidden(node: Node) {
            node.utf16Range(textLength)?.let(forbiddenRanges::add)
        }

        private fun addSoft(node: Node) {
            node.utf16Range(textLength)?.let(softRanges::add)
        }
    }

    // MARK: - Pass 2 model build

    private class ModelBuilder(private val original: String, spans: List<ProtectedMathSpan>) {
        private val inlineSpans = spans.filter { !it.kind.isDisplay }
        private val displaySpans = spans.filter { it.kind.isDisplay }

        private fun Node.range(): Utf16Range? = utf16Range(original.length)

        fun convertBlock(node: Node): ParsedBlock? = when (node) {
            is Paragraph -> {
                val range = node.range()
                val display = range?.let { r -> displaySpans.firstOrNull { r.overlaps(it.originalRange) } }
                if (display != null) ParsedBlock.BlockMath(MathSegment(display))
                else ParsedBlock.Paragraph(inlineRuns(node))
            }

            is Heading -> ParsedBlock.Heading(level = node.level, runs = inlineRuns(node))

            is FencedCodeBlock -> {
                val language = node.info?.trim()?.takeIf { it.isNotEmpty() }
                ParsedBlock.CodeBlock(language = language, code = node.literal.orEmpty().removeSuffix("\n"))
            }

            is IndentedCodeBlock -> ParsedBlock.CodeBlock(language = null, code = node.literal.orEmpty().removeSuffix("\n"))

            is BlockQuote -> ParsedBlock.BlockQuote(node.children().mapNotNull(::convertBlock))

            is BulletList -> ParsedBlock.UnorderedList(items = listItems(node))

            is OrderedList -> ParsedBlock.OrderedList(start = node.markerStartNumber ?: 1, items = listItems(node))

            is TableBlock -> convertTable(node)

            is ThematicBreak -> ParsedBlock.ThematicBreak

            // HTML은 실행하지 않고 문자 그대로 표시한다.
            is HtmlBlock -> ParsedBlock.Paragraph(
                listOf(InlineRun(InlineContent.Text(node.literal.orEmpty().removeSuffix("\n")))),
            )

            else -> fallbackBlock(node)
        }

        private fun listItems(list: Node): List<List<ParsedBlock>> =
            list.children().filterIsInstance<ListItem>().map { item -> item.children().mapNotNull(::convertBlock) }

        private fun convertTable(table: TableBlock): ParsedBlock? {
            val headRow = table.children().filterIsInstance<TableHead>().firstOrNull()
                ?.children()?.filterIsInstance<TableRow>()?.firstOrNull()
            val headCells = headRow?.children()?.filterIsInstance<TableCell>().orEmpty()
            val bodyRows = table.children().filterIsInstance<TableBody>()
                .flatMap { body -> body.children().filterIsInstance<TableRow>() }
                .map { row -> row.children().filterIsInstance<TableCell>() }

            val columnCount = bodyRows.fold(headCells.size) { acc, row -> maxOf(acc, row.size) }
            val cellCount = bodyRows.fold(headCells.size) { acc, row -> acc + row.size }
            if (columnCount > InputLimits.MAX_TABLE_COLUMNS || cellCount > InputLimits.MAX_TABLE_CELLS) {
                return fallbackBlock(table)
            }

            return ParsedBlock.Table(
                ParsedTable(
                    columnAlignments = headCells.map { cell ->
                        when (cell.alignment) {
                            TableCell.Alignment.LEFT -> ParsedTable.ColumnAlignment.Left
                            TableCell.Alignment.CENTER -> ParsedTable.ColumnAlignment.Center
                            TableCell.Alignment.RIGHT -> ParsedTable.ColumnAlignment.Right
                            null -> null
                        }
                    },
                    header = headCells.map(::inlineRuns),
                    rows = bodyRows.map { row -> row.map(::inlineRuns) },
                ),
            )
        }

        /** 미지원 노드와 렌더 상한을 넘는 표는 조용히 삭제하지 않고 plain text로 낮춘다. */
        private fun fallbackBlock(node: Node): ParsedBlock? {
            val range = node.range()
            val source = if (range != null) original.substring(range.start, range.end) else markdownRenderer.render(node)
            val fallback = source.trim()
            if (fallback.isEmpty()) return null
            return ParsedBlock.Paragraph(listOf(InlineRun(InlineContent.Text(fallback))))
        }

        private fun inlineRuns(container: Node): List<InlineRun> {
            val runs = ArrayList<InlineRun>()
            for (child in container.children()) {
                appendRuns(child, bold = false, italic = false, strikethrough = false, runs = runs)
            }
            return runs
        }

        private fun appendRuns(
            node: Node,
            bold: Boolean,
            italic: Boolean,
            strikethrough: Boolean,
            runs: MutableList<InlineRun>,
        ) {
            fun run(content: InlineContent) = InlineRun(content, bold = bold, italic = italic, strikethrough = strikethrough)

            when (node) {
                is Text -> appendTextRuns(node, ::run, runs)

                is Code -> runs.add(run(InlineContent.Code(node.literal.orEmpty())))

                is Link -> {
                    val label = node.plainText()
                    val url = LinkPolicy.allowedUrl(node.destination)
                    // 상대 URL과 비허용 scheme은 plain text로 표시한다.
                    runs.add(if (url != null) run(InlineContent.Link(label, url)) else run(InlineContent.Text(label)))
                }

                // 이미지 문법은 alt text만 표시한다.
                is Image -> {
                    val alt = node.plainText()
                    if (alt.isNotEmpty()) runs.add(run(InlineContent.Text(alt)))
                }

                is HtmlInline -> runs.add(run(InlineContent.Text(node.literal.orEmpty())))

                is HardLineBreak -> runs.add(run(InlineContent.HardBreak))

                is SoftLineBreak -> runs.add(run(InlineContent.SoftBreak))

                is StrongEmphasis -> node.children().forEach {
                    appendRuns(it, bold = true, italic = italic, strikethrough = strikethrough, runs = runs)
                }

                is Emphasis -> node.children().forEach {
                    appendRuns(it, bold = bold, italic = true, strikethrough = strikethrough, runs = runs)
                }

                is Strikethrough -> node.children().forEach {
                    appendRuns(it, bold = bold, italic = italic, strikethrough = true, runs = runs)
                }

                else -> {
                    val fallback = markdownRenderer.render(node)
                    if (fallback.isNotEmpty()) runs.add(run(InlineContent.Text(fallback)))
                }
            }
        }

        /** masked Text 노드를 원문 slice로 되돌리고, 겹치는 inline 수식 span을 math run으로 쪼갠다. */
        private fun appendTextRuns(
            text: Text,
            style: (InlineContent) -> InlineRun,
            runs: MutableList<InlineRun>,
        ) {
            val range = text.range()
            if (range == null) {
                // 위치를 모르면 fail-open: masked 문자열 대신 노드 문자열 그대로.
                runs.add(style(InlineContent.Text(text.literal.orEmpty())))
                return
            }

            // span이 없으면 2차 파싱의 Text가 Markdown entity/escape를 이미 해제했다.
            // 단, 수식 구분자 escape는 malformed LaTeX를 원문으로 보여 주는 fail-open 계약이라
            // 원문 slice 경로를 유지한다. 이 fast path가 entity Text마다 파서를 다시 돌리는 경로를 막는다.
            val overlapping = inlineSpans.filter { it.originalRange.overlaps(range) }
            val source = original.substring(range.start, range.end)
            if (overlapping.isEmpty() && !containsLiteralMathDelimiterEscape(source)) {
                runs.add(style(InlineContent.Text(text.literal.orEmpty())))
                return
            }

            if (source.contains('&') &&
                appendEntityDecodedRuns(range, source, text.literal.orEmpty(), overlapping, style, runs)
            ) {
                return
            }

            var cursor = range.start
            for (span in overlapping) {
                val spanRange = span.originalRange
                // 부분 겹침은 발생하지 않아야 한다. fail-open으로 원문 텍스트에 남긴다.
                if (spanRange.start < cursor || spanRange.end > range.end) continue
                if (cursor < spanRange.start) runs.add(style(InlineContent.Text(slice(cursor, spanRange.start))))
                runs.add(style(InlineContent.Math(MathSegment(span))))
                cursor = spanRange.end
            }
            if (cursor < range.end) runs.add(style(InlineContent.Text(slice(cursor, range.end))))
        }

        /**
         * entity가 수식 span의 앞뒤에 있어도 2차 파싱과 같은 의미 해석을 유지한다.
         * marker는 Markdown 파서가 바꾸지 않는 PUA 문자로 만들어 수식 source와 분리한다.
         */
        private fun appendEntityDecodedRuns(
            range: Utf16Range,
            source: String,
            decodedText: String,
            spans: List<ProtectedMathSpan>,
            style: (InlineContent) -> InlineRun,
            runs: MutableList<InlineRun>,
        ): Boolean {
            if (!spans.all { range.contains(it.originalRange) }) return false

            val markers = OpaqueMarkerFactory(source, decodedText)
            val markdown = StringBuilder()
            var cursor = range.start
            val mathMarkers = ArrayList<Pair<String, ProtectedMathSpan>>()

            for (span in spans) {
                markdown.append(original, cursor, span.originalRange.start)
                val marker = markers.next()
                markdown.append(marker)
                mathMarkers.add(marker to span)
                cursor = span.originalRange.end
            }
            markdown.append(original, cursor, range.end)

            val protected = protectLiteralMathDelimiterEscapes(markdown.toString(), markers)
            var decoded = decodedParagraphText(protected.markdown) ?: return false
            for ((marker, value) in protected.restorations) decoded = decoded.replace(marker, value)

            var textStart = 0
            for ((marker, span) in mathMarkers) {
                val markerIndex = decoded.indexOf(marker, textStart)
                if (markerIndex < 0) return false
                if (textStart < markerIndex) runs.add(style(InlineContent.Text(decoded.substring(textStart, markerIndex))))
                runs.add(style(InlineContent.Math(MathSegment(span))))
                textStart = markerIndex + marker.length
            }
            if (textStart < decoded.length) runs.add(style(InlineContent.Text(decoded.substring(textStart))))
            return true
        }

        private fun containsLiteralMathDelimiterEscape(source: String): Boolean =
            source.contains("\\(") || source.contains("\\)") || source.contains("\\[") || source.contains("\\]")

        /** span 밖 텍스트는 원문 slice에서 Markdown escape를 해제해 표시한다. */
        private fun slice(start: Int, end: Int): String =
            original.substring(start, end).unescapingMarkdownPunctuation()
    }

    /**
     * raw/decoded corpus에 모두 없는 Plane 15/16 private-use scalar를 marker prefix로 쓴다.
     * HTML numeric entity가 이전 marker 문자열로 decode되는 충돌을 막는다.
     */
    private class OpaqueMarkerFactory(rawSource: String, decodedText: String) {
        private val sentinel: String
        private var serial = 0

        init {
            val occupied = HashSet<Int>()
            rawSource.codePoints().forEach { occupied.add(it) }
            decodedText.codePoints().forEach { occupied.add(it) }
            // 한 입력은 256 KiB로 제한된다. 131k개가 넘는 Plane 15/16 scalar 전부를 corpus에
            // 동시에 넣을 수 없으므로 충돌 없는 scalar가 항상 존재한다.
            val free = (0xF0000..0x10FFFD).first { it !in occupied }
            sentinel = String(Character.toChars(free))
        }

        fun next(): String = "${sentinel}richmarkdown-${serial++}$sentinel"
    }

    private class LiteralMathEscapeProtection(
        val markdown: String,
        val restorations: List<Pair<String, String>>,
    )

    /** `\(` 등의 malformed 수식 구분자는 Markdown 파서가 escape를 벗기기 전에 보호한다. */
    private fun protectLiteralMathDelimiterEscapes(
        markdown: String,
        markers: OpaqueMarkerFactory,
    ): LiteralMathEscapeProtection {
        val protected = StringBuilder(markdown.length)
        val restorations = ArrayList<Pair<String, String>>()
        var index = 0

        while (index < markdown.length) {
            if (markdown[index] != '\\') {
                protected.append(markdown[index])
                index += 1
                continue
            }

            val runStart = index
            while (index < markdown.length && markdown[index] == '\\') index += 1
            val delimiter = markdown.getOrNull(index)
            if (delimiter != '(' && delimiter != ')' && delimiter != '[' && delimiter != ']') {
                protected.append(markdown, runStart, index)
                continue
            }

            val marker = markers.next()
            protected.append(marker)
            restorations.add(marker to markdown.substring(runStart, index + 1).unescapingMarkdownPunctuation())
            index += 1
        }

        return LiteralMathEscapeProtection(protected.toString(), restorations)
    }

    /** `Text.literal`과 같은 entity/escape 의미 해석이 필요한 text fragment의 최소 경로. */
    private fun decodedParagraphText(markdown: String): String? {
        val paragraph = parser.parse(markdown).children().firstOrNull { it is Paragraph } ?: return null
        return paragraph.plainText()
    }
}
