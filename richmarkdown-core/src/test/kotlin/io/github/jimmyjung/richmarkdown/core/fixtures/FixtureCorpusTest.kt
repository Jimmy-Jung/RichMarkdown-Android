// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.core.fixtures

import io.github.jimmyjung.richmarkdown.core.DollarMathOptions
import io.github.jimmyjung.richmarkdown.core.InputLimits
import io.github.jimmyjung.richmarkdown.core.ParsedDocument
import io.github.jimmyjung.richmarkdown.core.RichMarkdownParser
import io.github.jimmyjung.richmarkdown.core.fixtures.FixtureSupport.asMap
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 공용 fixture 코퍼스 러너 (iOS `Tests/Fixtures/README.md` §4·§5).
 * fixture마다 DynamicTest 하나: 파싱 시간 예산 → 정규화 → `RICHMARKDOWN_FIXTURE_OUT/android/<id>.json` 출력
 * → `<id>.expected.json`(있을 때) 구조 비교 → `meta.expect` 검사. CommonMark 예제는 무크래시·시간만 본다.
 */
class FixtureCorpusTest {

    private companion object {
        val CATEGORIES = listOf("math", "unicode", "markdown", "streaming", "security", "samples", "regressions")

        init {
            // 첫 fixture에 클래스 로딩·JIT 비용이 실리지 않도록 한 번 데운다. 계약의 예산은 파싱 시간만 잰다.
            RichMarkdownParser.parse("warm \\(x\\) \$y\$ | a | b |\n|---|---|\n| 1 | 2 |", DollarMathOptions.Single)
        }
    }

    @TestFactory
    fun corpus(): List<DynamicTest> = CATEGORIES.flatMap(FixtureSupport::markdownIds).map { id ->
        DynamicTest.dynamicTest(id) {
            verify(id, FixtureSupport.readMarkdown(id), FixtureSupport.meta(id), FixtureSupport.expected(id))
        }
    }

    @TestFactory
    fun commonmark(): List<DynamicTest> {
        val spec = MiniJson.parse(FixtureSupport.readText("commonmark/spec-0.31.2.json") ?: error("spec 없음")) as List<*>
        return spec.map { example ->
            val fields = example.asMap()
            val id = "commonmark/${(fields["example"] as Number).toLong()}"
            DynamicTest.dynamicTest(id) { verify(id, fields["markdown"] as String, FixtureMeta.DEFAULT, expected = null) }
        }
    }

    @TestFactory
    fun pathological(): List<DynamicTest> = FixtureSupport.generatorIds().map { id ->
        DynamicTest.dynamicTest(id) {
            val rule = FixtureSupport.generatorRule(id)
            val input = FixtureSupport.generate(rule)
            val doc = verify(id, input, FixtureMeta.from(rule), FixtureSupport.expected(id))
            if (FixtureSupport.utf8ByteCount(input) > InputLimits.MAX_INPUT_UTF8_BYTES) {
                assertTrue(doc.wasTruncated, "$id: 256 KiB 초과 입력은 truncated == true여야 한다")
            }
        }
    }

    /** README §4의 fixture 한 건 절차. */
    private fun verify(id: String, input: String, meta: FixtureMeta, expected: Any?): ParsedDocument {
        val started = System.nanoTime()
        val doc = RichMarkdownParser.parse(input, meta.dollarMath)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        assertTrue(elapsedMs < meta.budgetMs, "$id: 파싱 ${"%.1f".format(elapsedMs)} ms ≥ 예산 ${meta.budgetMs} ms")

        val normalized = FixtureNormalizer.normalize(doc)
        FixtureSupport.writeOutput(id, normalized)
        if (expected != null) {
            val ignored = FixtureSupport.ignoredFields(id)
            assertEquals(
                FixtureSupport.masking(expected, ignored),
                FixtureSupport.masking(MiniJson.parse(MiniJson.write(normalized)), ignored),
                "$id: expected.json과 다르다",
            )
        }
        checkExpect(id, meta.expect, doc, normalized)
        return doc
    }

    /** `meta.expect`의 각 키는 있을 때만 검사한다 (README §2). */
    private fun checkExpect(id: String, expect: Map<String, Any?>, doc: ParsedDocument, normalized: Map<String, Any?>) {
        for ((key, value) in expect) {
            when (key) {
                "truncated" -> assertEquals(value, doc.wasTruncated, "$id: truncated")
                "mathLatex" -> assertEquals(value, doc.allMathSegments.map { it.latex }, "$id: mathLatex")
                "mathKinds" -> assertEquals(value, doc.allMathSegments.map { FixtureNormalizer.lowerCamel(it.kind.name) }, "$id: mathKinds")
                "blockTypes" -> assertEquals(value, (normalized["blocks"] as List<*>).map { (it as Map<*, *>)["t"] }, "$id: blockTypes")
                "diagnostics" -> assertEquals(value, normalized["diagnostics"], "$id: diagnostics")
                "linkCount" -> assertEquals((value as Number).toLong(), FixtureNormalizer.linkCount(normalized).toLong(), "$id: linkCount")
                "plainTextContains" -> {
                    val text = FixtureNormalizer.plainText(normalized)
                    for (needle in value as List<*>) assertTrue(text.contains(needle as String), "$id: plain text에 '$needle' 없음")
                }
                else -> fail("$id: 알 수 없는 expect 키 '$key'")
            }
        }
    }
}
