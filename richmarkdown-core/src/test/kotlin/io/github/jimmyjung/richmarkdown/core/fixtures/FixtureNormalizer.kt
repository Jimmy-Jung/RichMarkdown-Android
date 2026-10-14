// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.core.fixtures

import io.github.jimmyjung.richmarkdown.core.InlineContent
import io.github.jimmyjung.richmarkdown.core.InlineRun
import io.github.jimmyjung.richmarkdown.core.MathSegment
import io.github.jimmyjung.richmarkdown.core.ParsedBlock
import io.github.jimmyjung.richmarkdown.core.ParsedDocument
import io.github.jimmyjung.richmarkdown.core.ParsedTable

/**
 * [ParsedDocument] → 플랫폼 중립 정규화 JSON 트리 (iOS `Tests/Fixtures/README.md` §3).
 * enum은 이름의 첫 글자만 소문자로(`InlineParen` → `inlineParen`), 거짓인 `b`/`i`/`s`는 생략,
 * `href`는 `URI.toString()`, diagnostics는 종류만 문서 순서(범위 시작 기준 안정 정렬)로 나열한다.
 */
internal object FixtureNormalizer {

    fun normalize(document: ParsedDocument): Map<String, Any?> = linkedMapOf(
        "truncated" to document.wasTruncated,
        "diagnostics" to document.diagnostics.sortedBy { it.range.start }.map { lowerCamel(it.kind.name) },
        "blocks" to document.blocks.map(::block),
    )

    fun lowerCamel(name: String): String = name.replaceFirstChar { it.lowercaseChar() }

    /** 정규화 트리 전체의 `link` run 수 (README §2 `linkCount`). */
    fun linkCount(node: Any?): Int = when (node) {
        is Map<*, *> -> (if (node["c"] == "link") 1 else 0) + node.values.sumOf(::linkCount)
        is List<*> -> node.sumOf(::linkCount)
        else -> 0
    }

    /**
     * 정규화 트리의 plain text (README §2 `plainTextContains`). run의 `v`·수식 `source`·코드 블록 `code`를
     * 문서 순서로 잇고 블록 사이에는 줄바꿈을 둔다. 계약이 plain text의 정의를 고정하지 않아 포함 검사에만 쓴다.
     */
    fun plainText(document: Map<String, Any?>): String =
        (document["blocks"] as List<*>).joinToString("\n") { blockText(it as Map<*, *>) }

    private fun blockText(block: Map<*, *>): String = when (block["t"]) {
        "code" -> block["code"] as String
        "math" -> block["source"] as String
        "quote" -> (block["blocks"] as List<*>).joinToString("\n") { blockText(it as Map<*, *>) }
        "ul", "ol" -> (block["items"] as List<*>).joinToString("\n") { item ->
            (item as List<*>).joinToString("\n") { blockText(it as Map<*, *>) }
        }
        "table" -> {
            val cells = (block["header"] as List<*>) + (block["rows"] as List<*>).flatMap { it as List<*> }
            cells.joinToString("\n") { runsText(it as List<*>) }
        }
        "hr" -> ""
        else -> runsText(block["runs"] as List<*>)
    }

    private fun runsText(runs: List<*>): String = runs.joinToString("") { run ->
        run as Map<*, *>
        when (run["c"]) {
            "math" -> run["source"] as String
            "br" -> "\n"
            "sbr" -> " "
            else -> run["v"] as String
        }
    }

    // MARK: - 블록

    private fun block(block: ParsedBlock): Map<String, Any?> = when (block) {
        is ParsedBlock.Paragraph -> linkedMapOf("t" to "p", "runs" to runs(block.runs))
        is ParsedBlock.Heading -> linkedMapOf("t" to "h", "level" to block.level, "runs" to runs(block.runs))
        is ParsedBlock.CodeBlock -> linkedMapOf("t" to "code", "lang" to block.language, "code" to block.code)
        is ParsedBlock.BlockMath -> linkedMapOf<String, Any?>("t" to "math").also { it.putAll(math(block.segment)) }
        is ParsedBlock.BlockQuote -> linkedMapOf("t" to "quote", "blocks" to block.children.map(::block))
        is ParsedBlock.UnorderedList -> linkedMapOf("t" to "ul", "items" to items(block.items))
        is ParsedBlock.OrderedList -> linkedMapOf("t" to "ol", "start" to block.start, "items" to items(block.items))
        is ParsedBlock.Table -> table(block.table)
        ParsedBlock.ThematicBreak -> linkedMapOf("t" to "hr")
    }

    private fun items(items: List<List<ParsedBlock>>): List<List<Map<String, Any?>>> =
        items.map { item -> item.map(::block) }

    private fun table(table: ParsedTable): Map<String, Any?> = linkedMapOf(
        "t" to "table",
        "align" to table.columnAlignments.map { alignment ->
            when (alignment) {
                ParsedTable.ColumnAlignment.Left -> "left"
                ParsedTable.ColumnAlignment.Center -> "center"
                ParsedTable.ColumnAlignment.Right -> "right"
                null -> null
            }
        },
        "header" to table.header.map(::runs),
        "rows" to table.rows.map { row -> row.map(::runs) },
    )

    // MARK: - run

    private fun runs(runs: List<InlineRun>): List<Map<String, Any?>> = runs.map(::run)

    private fun run(run: InlineRun): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        when (val content = run.content) {
            is InlineContent.Text -> { out["c"] = "text"; out["v"] = content.text }
            is InlineContent.Code -> { out["c"] = "code"; out["v"] = content.code }
            is InlineContent.Math -> { out["c"] = "math"; out.putAll(math(content.segment)) }
            is InlineContent.Link -> { out["c"] = "link"; out["v"] = content.text; out["href"] = content.destination.toString() }
            InlineContent.HardBreak -> out["c"] = "br"
            InlineContent.SoftBreak -> out["c"] = "sbr"
        }
        if (run.bold) out["b"] = true
        if (run.italic) out["i"] = true
        if (run.strikethrough) out["s"] = true
        return out
    }

    private fun math(segment: MathSegment): Map<String, Any?> = linkedMapOf(
        "kind" to lowerCamel(segment.kind.name),
        "latex" to segment.latex,
        "source" to segment.source,
    )
}
