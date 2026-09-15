// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

import java.net.URI
import java.net.URISyntaxException

/**
 * 코어 내부 모델. 공개 AST product가 아니다 (iOS DEVELOPMENT.md §1 비목표).
 * 렌더러(Compose·View)는 이 모델만 읽는다.
 */
@InternalRichMarkdownApi
public data class ParsedDocument(
    val blocks: List<ParsedBlock>,
    val wasTruncated: Boolean = false,
    val diagnostics: List<MathDiagnostic> = emptyList(),
) {
    /** hydration 대상 수식 segment (문서 순서, 중복 제거). */
    val allMathSegments: List<MathSegment>
        get() {
            val seen = LinkedHashSet<MathSegment>()
            fun visitRuns(runs: List<InlineRun>) {
                for (run in runs) {
                    val content = run.content
                    if (content is InlineContent.Math) seen.add(content.segment)
                }
            }
            fun visit(blocks: List<ParsedBlock>) {
                for (block in blocks) {
                    when (block) {
                        is ParsedBlock.Paragraph -> visitRuns(block.runs)
                        is ParsedBlock.Heading -> visitRuns(block.runs)
                        is ParsedBlock.BlockMath -> seen.add(block.segment)
                        is ParsedBlock.BlockQuote -> visit(block.children)
                        is ParsedBlock.UnorderedList -> block.items.forEach(::visit)
                        is ParsedBlock.OrderedList -> block.items.forEach(::visit)
                        is ParsedBlock.Table -> {
                            block.table.header.forEach(::visitRuns)
                            block.table.rows.forEach { row -> row.forEach(::visitRuns) }
                        }
                        is ParsedBlock.CodeBlock, ParsedBlock.ThematicBreak -> Unit
                    }
                }
            }
            visit(blocks)
            return seen.toList()
        }
}

@InternalRichMarkdownApi
public sealed interface ParsedBlock {
    public data class Paragraph(val runs: List<InlineRun>) : ParsedBlock
    public data class Heading(val level: Int, val runs: List<InlineRun>) : ParsedBlock
    public data class CodeBlock(val language: String?, val code: String) : ParsedBlock
    public data class BlockMath(val segment: MathSegment) : ParsedBlock
    public data class BlockQuote(val children: List<ParsedBlock>) : ParsedBlock
    public data class UnorderedList(val items: List<List<ParsedBlock>>) : ParsedBlock
    public data class OrderedList(val start: Int, val items: List<List<ParsedBlock>>) : ParsedBlock
    public data class Table(val table: ParsedTable) : ParsedBlock
    public data object ThematicBreak : ParsedBlock
}

@InternalRichMarkdownApi
public data class ParsedTable(
    /** 열마다 지정 정렬. 지정이 없으면 null. */
    val columnAlignments: List<ColumnAlignment?>,
    val header: List<List<InlineRun>>,
    val rows: List<List<List<InlineRun>>>,
) {
    public enum class ColumnAlignment { Left, Center, Right }
}

@InternalRichMarkdownApi
public data class MathSegment(
    /** 원래 구분자를 포함한 원문. 렌더 실패 시 이 값을 표시한다. */
    val source: String,
    /** 구분자 제거 LaTeX. */
    val latex: String,
    val kind: MathKind,
) {
    public constructor(span: ProtectedMathSpan) : this(span.source, span.latex, span.kind)
}

@InternalRichMarkdownApi
public data class InlineRun(
    val content: InlineContent,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
)

@InternalRichMarkdownApi
public sealed interface InlineContent {
    public data class Text(val text: String) : InlineContent
    public data class Code(val code: String) : InlineContent
    public data class Math(val segment: MathSegment) : InlineContent
    public data class Link(val text: String, val destination: URI) : InlineContent
    public data object HardBreak : InlineContent
    public data object SoftBreak : InlineContent
}

/** 자동 링크 allowlist. `https`, `http`, `mailto`만 허용 (iOS DEVELOPMENT.md §5). */
@InternalRichMarkdownApi
public object LinkPolicy {
    public val allowedSchemes: Set<String> = setOf("https", "http", "mailto")

    /** 허용 scheme의 절대 URL만 돌려준다. 상대 URL·다른 scheme·파싱 실패는 null. */
    public fun allowedUrl(destination: String?): URI? {
        if (destination.isNullOrEmpty()) return null
        val uri = try {
            URI(destination)
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        return if (scheme in allowedSchemes) uri else null
    }
}
