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
        if (destination.isNullOrEmpty() || containsInvisibleFormatCharacter(destination)) return null
        val uri = parse(destination) ?: parse(encodingIllegalCharacters(destination)) ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in allowedSchemes) return null
        // http·https는 호스트(authority)가 있어야 한다. 비ASCII 호스트는 getHost()가 null이라 authority로 본다 (iOS와 같다).
        if (scheme != "mailto" && uri.rawAuthority.isNullOrEmpty()) return null
        return uri
    }

    /**
     * 주소에 보이지 않는 서식 문자(U+200B~U+200F, U+202A~U+202E, U+2060~U+2064, U+2066~U+2069, U+FEFF)가 있으면
     * 링크로 만들지 않는다. 화면의 주소와 실제 대상이 달라 보이게 하는 속임수를 막는다 (iOS `LinkPolicy`와 같다).
     */
    public fun containsInvisibleFormatCharacter(text: String): Boolean = text.any { c ->
        c in '\u200B'..'\u200F' || c in '\u202A'..'\u202E' || c in '\u2060'..'\u2064' || c in '\u2066'..'\u2069' || c == '\uFEFF'
    }

    private fun parse(text: String): URI? = try {
        URI(text)
    } catch (_: URISyntaxException) {
        null
    }

    /**
     * 경로·질의·조각에서 `java.net.URI`가 거부하는 ASCII(`\\`, 공백, `"`, `<`, `>`, `[`, `]`, `^`, `` ` ``, `{`, `|`, `}`)만
     * 퍼센트 인코딩한다. 호스트는 건드리지 않는다 — 호스트에 쓸 수 없는 글자가 있으면 iOS `URL`처럼 링크로 받지 않는다(예제 346).
     * CommonMark 자동 링크 `<https://example.com?find=\\*>`처럼 명세상 유효한 링크가 Android에서만 글자로 떨어지던 차이를 없앤다
     * (CommonMark 예제 20·603, iOS URL은 같은 입력을 인코딩해 받아들인다). 비ASCII는 URI가 허용하므로 그대로 둔다.
     */
    private fun encodingIllegalCharacters(text: String): String {
        val authorityStart = text.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val pathStart = text.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) text.length else it }
        return buildString {
            append(text, 0, pathStart)
            var sawHash = false
            for (c in text.substring(pathStart)) {
                // 조각(#) 안의 두 번째 `#`는 URI가 거부한다. iOS URL처럼 인코딩해 받는다(퍼즈 패리티).
                val illegal = c in ILLEGAL_ASCII || (c == '#' && sawHash)
                if (c == '#') sawHash = true
                if (illegal) append('%').append("%02X".format(c.code)) else append(c)
            }
        }
    }

    private const val ILLEGAL_ASCII = "\\ \"<>[]^`{|}"
}
