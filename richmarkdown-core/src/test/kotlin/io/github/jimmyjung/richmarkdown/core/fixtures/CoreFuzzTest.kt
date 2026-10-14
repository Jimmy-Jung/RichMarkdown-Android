// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.core.fixtures

import io.github.jimmyjung.richmarkdown.core.DollarMathOptions
import io.github.jimmyjung.richmarkdown.core.InputLimits
import io.github.jimmyjung.richmarkdown.core.MathDiagnostic
import io.github.jimmyjung.richmarkdown.core.MathProtector
import io.github.jimmyjung.richmarkdown.core.MathScanner
import io.github.jimmyjung.richmarkdown.core.ParsedBlock
import io.github.jimmyjung.richmarkdown.core.ParsedDocument
import io.github.jimmyjung.richmarkdown.core.ParsedTable
import io.github.jimmyjung.richmarkdown.core.RichMarkdownParser
import io.github.jimmyjung.richmarkdown.core.Utf16Range
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.fail

/**
 * 시드 퍼즈 (iOS `Tests/Fixtures/README.md` §6). 같은 시드에서 iOS와 같은 입력열이 나오도록
 * RNG·알파벳·변이·RNG 소비 순서를 계약 그대로 둔다.
 *
 * 계약이 정하지 않은 선택(보고서에도 기록):
 * - 파싱 옵션은 `Single + InlineDouble`(가장 넓은 경로).
 * - 중복(op 2)은 길이 0이면 삭제(op 1)처럼 RNG를 소비하지 않고 건너뛴다(`pick(0)` 회피).
 * - `<seed>`는 10진수로 적는다(`0x5EED` → `24301`). `n`은 0부터 세는 반복 번호다.
 */
class CoreFuzzTest {

    /** SplitMix64. 64-bit wrapping, 부호 없는 비교·나눗셈. */
    private class SplitMix64(private var state: ULong) {
        fun next(): ULong {
            state += 0x9E3779B97F4A7C15uL
            var z = state
            z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
            z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
            return z xor (z shr 31)
        }

        fun pick(n: Int): Int {
            require(n > 0) { "pick($n)" }
            return (next() % n.toULong()).toInt()
        }
    }

    private class Outcome(val doc: ParsedDocument?, val reason: String?)

    private class Finding(val input: String, val reason: String)

    private companion object {
        val OPTIONS = DollarMathOptions.Single + DollarMathOptions.InlineDouble
        const val PARSE_BUDGET_MS = 500L
        const val PREFIX_COUNT = 5
        const val MINIMIZE_BUDGET_NANOS = 60_000_000_000L

        /** 알파벳 29개, 순서 고정. `é`는 README 원문대로 NFD(`e` + U+0301)다. */
        val ALPHABET = listOf(
            "a", "한", "🙂", "👨‍👩‍👧", "é", "ע",
            "\\", "(", ")", "[", "]", "$", " ", "\n", "\r\n", "*", "_", "`", "~", "#", ">", "-", "|", "<", "!",
            "​", "﻿", "1", ".",
        )
        val DELIMITERS = listOf("\\(", "\\)", "\\[", "\\]", "$$", "$")

        init {
            check(ALPHABET.size == 29)
            RichMarkdownParser.parse("warm \\(x\\) \$y\$", OPTIONS)
        }
    }

    @Test
    fun fuzz() {
        val seed = parseSeed(System.getenv("RICHMARKDOWN_FUZZ_SEED") ?: "0x5EED")
        val iterations = System.getenv("RICHMARKDOWN_FUZZ_ITERATIONS")?.toInt() ?: 10_000
        val rng = SplitMix64(seed)
        val corpus = listOf("math", "markdown", "unicode", "samples")
            .flatMap(FixtureSupport::markdownIds).sorted().map(FixtureSupport::readMarkdown)
        check(corpus.isNotEmpty()) { "퍼즈 코퍼스가 비어 있다" }

        val started = System.nanoTime()
        openDump(seed).use { dump ->
            for (n in 0 until iterations) {
                val input = mutate(rng, corpus[rng.pick(corpus.size)])
                val outcome = check(input)
                val finding = outcome.reason?.let { Finding(input, it) } ?: prefixFinding(rng, input)
                dump?.write(MiniJson.write(linkedMapOf("n" to n, "input" to input, "doc" to outcome.doc?.let(FixtureNormalizer::normalize))))
                dump?.write("\n")
                if (finding != null) report(seed, n, finding)
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        println("fuzz: seed=$seed iterations=$iterations elapsed=${"%.0f".format(elapsedMs)} ms (${"%.3f".format(elapsedMs / iterations)} ms/iteration)")
    }

    // MARK: - 변이

    private fun mutate(rng: SplitMix64, base: String): String {
        var cps = base.codePoints().toArray()
        repeat(1 + rng.pick(8)) {
            cps = when (rng.pick(6)) {
                0 -> insert(cps, rng)
                1 -> delete(cps, rng)
                2 -> duplicate(cps, rng)
                3 -> toggleDelimiter(cps, rng)
                4 -> newline(cps)
                else -> cps.copyOf(rng.pick(cps.size + 1))
            }
        }
        return cps.toText()
    }

    private fun insert(cps: IntArray, rng: SplitMix64): IntArray {
        val position = rng.pick(cps.size + 1)
        val piece = ALPHABET[rng.pick(ALPHABET.size)].codePoints().toArray()
        return cps.copyOfRange(0, position) + piece + cps.copyOfRange(position, cps.size)
    }

    private fun delete(cps: IntArray, rng: SplitMix64): IntArray {
        if (cps.isEmpty()) return cps
        val position = rng.pick(cps.size)
        return cps.copyOfRange(0, position) + cps.copyOfRange(position + 1, cps.size)
    }

    private fun duplicate(cps: IntArray, rng: SplitMix64): IntArray {
        if (cps.isEmpty()) return cps
        val a = rng.pick(cps.size)
        val b = a + rng.pick(minOf(64, cps.size - a) + 1)
        return cps.copyOfRange(0, b) + cps.copyOfRange(a, b) + cps.copyOfRange(b, cps.size)
    }

    private fun toggleDelimiter(cps: IntArray, rng: SplitMix64): IntArray {
        val delimiter = DELIMITERS[rng.pick(DELIMITERS.size)]
        val text = cps.toText()
        val index = text.indexOf(delimiter)
        if (index < 0) return cps
        return text.removeRange(index, index + delimiter.length).codePoints().toArray()
    }

    private fun newline(cps: IntArray): IntArray {
        val text = cps.toText()
        val mutated = if (text.contains('\n')) text.replaceFirst("\n", "\r\n") else text + "\n"
        return mutated.codePoints().toArray()
    }

    private fun IntArray.toText(): String = buildString(size) { for (cp in this@toText) appendCodePoint(cp) }

    // MARK: - 검사 ①~⑤

    /** ① 무예외 ② 시간 ③ protect/restore 왕복 ④ 제한값. 실패면 이유를 돌려준다. */
    private fun check(input: String): Outcome {
        val started = System.nanoTime()
        val doc = try {
            RichMarkdownParser.parse(input, OPTIONS)
        } catch (t: Throwable) {
            return Outcome(null, "parse 예외: $t")
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        if (elapsedMs >= PARSE_BUDGET_MS) {
            // 공유 CI 러너의 일시 정지가 한 번의 측정을 부풀릴 수 있다(iOS GitHub Actions 2.15 s, 같은 입력 로컬 2 ms).
            // 같은 입력을 세 번 다시 재서 가장 빠른 값으로 판정한다. 입력 자체가 느리면 세 번 모두 상한을 넘는다.
            val fastestMs = (1..3).minOf {
                val start = System.nanoTime()
                RichMarkdownParser.parse(input, OPTIONS)
                (System.nanoTime() - start) / 1_000_000
            }
            if (fastestMs >= PARSE_BUDGET_MS) {
                return Outcome(doc, "parse $fastestMs ms ≥ $PARSE_BUDGET_MS ms (재측정 3회 최소값, 첫 측정 $elapsedMs ms)")
            }
        }
        roundTripFailure(input)?.let { return Outcome(doc, it) }
        limitViolation(doc)?.let { return Outcome(doc, it) }
        return Outcome(doc, null)
    }

    /** ③ 입력 전체를 한 paragraph로 스캔해 `restore(protect(chars)) == chars`를 code unit 단위로 확인한다. */
    private fun roundTripFailure(input: String): String? {
        val chars = input.toCharArray()
        val spans = try {
            MathScanner(
                text = input,
                forbiddenRanges = emptyList(),
                paragraphRanges = listOf(Utf16Range(0, input.length)),
                dollarMath = OPTIONS,
            ).scan().spans
        } catch (t: Throwable) {
            return "scanner 예외: $t"
        }
        val restored = MathProtector.restore(MathProtector.protect(chars, spans), chars, spans)
        return if (restored.contentEquals(chars)) null else "restore(protect(input)) != input"
    }

    /** ④ 블록 인용 깊이·표 열·셀 상한, 그리고 latex가 4 KiB를 넘는 수식에는 `oversizedMathSource` 진단이 있어야 한다 (README §6). */
    private fun limitViolation(doc: ParsedDocument): String? {
        val oversized = doc.allMathSegments.any { FixtureSupport.utf8ByteCount(it.latex) > InputLimits.MAX_MATH_SOURCE_UTF8_BYTES }
        if (oversized && doc.diagnostics.none { it.kind == MathDiagnostic.Kind.OversizedMathSource }) {
            return "latex > ${InputLimits.MAX_MATH_SOURCE_UTF8_BYTES} bytes 수식에 oversizedMathSource 진단이 없다"
        }
        return blocksViolation(doc.blocks, quoteDepth = 0)
    }

    private fun blocksViolation(blocks: List<ParsedBlock>, quoteDepth: Int): String? {
        for (block in blocks) {
            val reason = when (block) {
                is ParsedBlock.BlockQuote ->
                    if (quoteDepth + 1 > InputLimits.MAX_BLOCK_QUOTE_DEPTH) "블록 인용 깊이 > ${InputLimits.MAX_BLOCK_QUOTE_DEPTH}"
                    else blocksViolation(block.children, quoteDepth + 1)
                is ParsedBlock.UnorderedList -> block.items.firstNotNullOfOrNull { blocksViolation(it, quoteDepth) }
                is ParsedBlock.OrderedList -> block.items.firstNotNullOfOrNull { blocksViolation(it, quoteDepth) }
                is ParsedBlock.Table -> tableViolation(block.table)
                else -> null
            }
            if (reason != null) return reason
        }
        return null
    }

    private fun tableViolation(table: ParsedTable): String? {
        val cells = table.header.size + table.rows.sumOf { it.size }
        return when {
            table.columnAlignments.size > InputLimits.MAX_TABLE_COLUMNS -> "표 열 ${table.columnAlignments.size} > ${InputLimits.MAX_TABLE_COLUMNS}"
            cells > InputLimits.MAX_TABLE_CELLS -> "표 셀 $cells > ${InputLimits.MAX_TABLE_CELLS}"
            else -> null
        }
    }

    /** ⑤ prefix 5개(길이 `pick(len+1)` 스칼라)도 무예외. 실패한 prefix 자체를 실패 입력으로 돌려준다. */
    private fun prefixFinding(rng: SplitMix64, input: String): Finding? {
        val cps = input.codePoints().toArray()
        repeat(PREFIX_COUNT) {
            val prefix = cps.copyOf(rng.pick(cps.size + 1)).toText()
            try {
                RichMarkdownParser.parse(prefix, OPTIONS)
            } catch (t: Throwable) {
                return Finding(prefix, "prefix parse 예외: $t")
            }
        }
        return null
    }

    // MARK: - 실패 처리

    /** 최소화해 `regressions/<seed>-<n>.md`로 저장하고 경로를 출력한 뒤 실패한다. */
    private fun report(seed: ULong, n: Int, finding: Finding): Nothing {
        val minimal = minimize(finding.input)
        val file = File(System.getProperty("user.dir"), "src/test/resources/fixtures/regressions/$seed-$n.md")
        file.parentFile.mkdirs()
        file.writeText(minimal, StandardCharsets.UTF_8)
        println("fuzz regression: ${file.absolutePath}")
        fail("fuzz #$n (seed $seed) 실패: ${finding.reason}\n최소화 입력(${minimal.codePointCount(0, minimal.length)} scalars) → ${file.absolutePath}")
    }

    /** 스칼라를 하나씩 지우며 실패가 유지되는 가장 짧은 입력. ponytail: 60 s 상한, 시간 초과 실패는 느릴 수 있다. */
    private fun minimize(input: String): String {
        var current = input.codePoints().toArray()
        val deadline = System.nanoTime() + MINIMIZE_BUDGET_NANOS
        var progressed = true
        while (progressed && System.nanoTime() < deadline) {
            progressed = false
            var i = 0
            while (i < current.size && System.nanoTime() < deadline) {
                val candidate = current.copyOfRange(0, i) + current.copyOfRange(i + 1, current.size)
                if (check(candidate.toText()).reason != null) {
                    current = candidate
                    progressed = true
                } else {
                    i += 1
                }
            }
        }
        return current.toText()
    }

    // MARK: - 환경

    private fun parseSeed(text: String): ULong =
        if (text.startsWith("0x") || text.startsWith("0X")) text.substring(2).toULong(16) else text.toULong()

    /** `RICHMARKDOWN_FUZZ_DUMP=1`이면 `<out>/android/fuzz-<seed>.jsonl` writer, 아니면 null. */
    private fun openDump(seed: ULong): java.io.Writer? {
        if (System.getenv("RICHMARKDOWN_FUZZ_DUMP") != "1") return null
        val dir = FixtureSupport.outputDir ?: error("RICHMARKDOWN_FUZZ_DUMP=1에는 ${FixtureSupport.OUT_ENV}가 필요하다")
        dir.mkdirs()
        return File(dir, "fuzz-$seed.jsonl").bufferedWriter(StandardCharsets.UTF_8)
    }
}
