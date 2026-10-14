// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.highlight

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jimmyjung.richmarkdown.RichMarkdownHighlightSpan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 번들된 Prism 문법 전체의 토큰 범위 계약과 큰 입력 시간 예산 (iOS `PrismLanguageCoverageTests.swift`).
 *
 * 언어 목록은 assets `prism/prism-*.js`에서 읽는다. 문법을 추가하고 대표 조각을 넣지 않으면
 * [everyBundledGrammarHasASnippet]가 실패한다.
 */
@RunWith(AndroidJUnit4::class)
class PrismLanguageCoverageTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val highlighter = PrismHighlighter.shared(context)

    /** assets의 `prism-<언어>.js`. `prism-core`는 문법이 아니라 엔진이다. */
    private val bundledGrammars: List<String> = context.assets.list("prism").orEmpty()
        .filter { it.startsWith("prism-") && it.endsWith(".js") && it != "prism-core.js" }
        .map { it.removePrefix("prism-").removeSuffix(".js") }
        .sorted()

    @Test
    fun everyBundledGrammarHasASnippet() {
        assertEquals("번들 문법 수: $bundledGrammars", 19, bundledGrammars.size)
        assertEquals(snippets.keys, bundledGrammars.toSet())
    }

    @Test
    fun everyBundledGrammarTokenizesKoreanAndEmojiInsideTheSource() = runBlocking {
        for (language in bundledGrammars) {
            val code = snippets.getValue(language)
            val spans = highlighter.spans(code, language)
            assertTrue("$language 문법이 색 범위를 만들지 못했다", spans.isNotEmpty())
            assertWellFormed(spans, code, language)
        }
    }

    // MARK: - 미지원 언어

    /**
     * 미지원 이름은 빈 리스트(원문 표시)다. `Prism.languages`의 메서드·Object 프로토타입 이름도
     * 문법으로 취급하지 않고, 그 뒤 같은 context가 정상 문법을 계속 처리한다.
     */
    @Test
    fun unknownAndPrototypeLanguagesFallBackToPlainWithoutPoisoningTheEngine() = runBlocking {
        val code = "let 인사 = \"안녕 👋\" // 🇰🇷"
        val names = listOf(
            "", "brainfuck", "swift5", "extend", "insertBefore", "DFS",
            "__proto__", "constructor", "toString", "hasOwnProperty", "valueOf", "prototype",
        )
        for (language in names) {
            val spans = highlighter.spans(code, language)
            assertTrue("'$language'는 미지원 언어로 원문을 두어야 한다: $spans", spans.isEmpty())
        }
        val swift = highlighter.spans(code, "swift")
        assertTrue("미지원 요청 뒤에도 같은 context가 정상 문법을 처리해야 한다", swift.isNotEmpty())
        assertWellFormed(swift, code, "swift")
    }

    // MARK: - 큰 입력

    /** 상한 바로 아래(UTF-16 100,000 이하)가 실제 최악 토큰화 비용이다. */
    @Test
    fun largestAcceptedInputTokenizesWithinBudget() = runBlocking {
        val code = swiftSource(PrismHighlighter.maxCodeUtf16Units)
        assertTrue(code.length <= PrismHighlighter.maxCodeUtf16Units)
        highlighter.spans("let a = 1", "swift") // context 초기화 제외

        val (spans, elapsedMs) = timed { highlighter.spans(code, "swift") }

        Log.i(TAG, "max-accepted: ${code.length} UTF-16 units → %.1f ms, spans ${spans.size}".format(elapsedMs))
        assertTrue(spans.isNotEmpty())
        assertWellFormed(spans, code, "swift")
        assertTrue("상한 크기 토큰화가 %.1f ms 걸렸다".format(elapsedMs), elapsedMs < MAX_ACCEPTED_BUDGET_MS)
    }

    /** 1 MB 입력은 블록 상한 밖이다. 토큰화하지 않고 바로 원문으로 돌아와야 한다. */
    @Test
    fun oneMegabyteInputReturnsPlainWithinBudget() = runBlocking {
        val code = swiftSource(1_048_576)
        assertTrue(code.toByteArray().size >= 1_000_000)
        highlighter.spans("let a = 1", "swift")

        val (spans, elapsedMs) = timed { highlighter.spans(code, "swift") }

        Log.i(TAG, "1MB: ${code.toByteArray().size} bytes → %.3f ms".format(elapsedMs))
        assertTrue("상한 초과 입력은 색을 입히지 않는다", spans.isEmpty())
        // 길이 검사만 하므로 수 ms다. 1초는 토큰화가 섞였다는 신호다.
        assertTrue("1 MB 거부가 %.1f ms 걸렸다".format(elapsedMs), elapsedMs < 1_000)
    }

    // MARK: - 도움 함수

    private suspend fun <T> timed(block: suspend () -> T): Pair<T, Double> {
        val start = SystemClock.elapsedRealtimeNanos()
        val value = block()
        return value to (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    }

    /** 범위가 원문 안에 있고, 비어 있지 않고, 겹치지 않는 오름차순이며, 서러게이트 쌍을 가르지 않는다. */
    private fun assertWellFormed(spans: List<RichMarkdownHighlightSpan>, code: String, language: String) {
        var previousEnd = 0
        for (span in spans) {
            val range = span.range
            assertTrue("$language: 빈 범위 $range", range.length > 0)
            assertTrue("$language: 겹치거나 역순 $range", range.start >= previousEnd)
            assertTrue("$language: 원문 밖 $range", range.end <= code.length)
            assertFalse("$language: 서러게이트 쌍을 갈랐다 $range", Character.isLowSurrogate(code[range.start]))
            assertFalse("$language: 서러게이트 쌍을 갈랐다 $range", Character.isHighSurrogate(code[range.end - 1]))
            previousEnd = range.end
        }
    }

    private companion object {
        const val TAG = "RichMarkdownCoverage"

        /** iOS와 같은 넉넉한 예산(에뮬레이터 실측 수백 ms). 실측값은 로그(`max-accepted`)로 남긴다. */
        const val MAX_ACCEPTED_BUDGET_MS = 10_000.0

        /** 한글·이모지가 섞인 Swift 줄을 UTF-16 목표 길이까지 반복한다. */
        fun swiftSource(utf16Target: Int): String {
            val line = "let 값 = \"한글 👩‍💻\" + String(42) // 주석 🇰🇷\n"
            return line.repeat(utf16Target / line.length)
        }

        /** 모든 조각에 한글·이모지(서러게이트 쌍, ZWJ, 국기)를 문자열이나 주석으로 넣는다. iOS와 같은 조각이다. */
        val snippets = mapOf(
            "clike" to "if (a) { return \"한글 👩‍💻\"; } // 주석 🇰🇷",
            "markup" to "<p class=\"인사\">안녕 👋</p><!-- 주석 🇰🇷 -->",
            "css" to "/* 한글 🎨 */ .제목 { color: red; content: \"👩‍💻\"; }",
            "javascript" to "const 인사 = `안녕 \${name} 👋`; // 주석 🇰🇷",
            "jsx" to "const a = <div title=\"한글 👩‍💻\">안녕 🇰🇷</div>;",
            "typescript" to "const 값: string = \"한글 👋\"; // 주석 🇰🇷",
            "tsx" to "const a: JSX.Element = <b>안녕 👩‍💻</b>; // 🇰🇷",
            "swift" to "// 주석 🇰🇷\nlet 인사 = \"안녕하세요 👋 \\(name)\"",
            "python" to "# 주석 🇰🇷\ndef 인사():\n    return f\"안녕 👩‍💻 {name}\"",
            "json" to "{\"이름\": \"한글 👋\", \"값\": 7, \"참\": true}",
            "bash" to "echo \"안녕 👩‍💻\" # 주석 🇰🇷",
            "kotlin" to "val 인사: String = \"안녕 👋 \$name\" // 🇰🇷",
            "java" to "String s = \"한글 👩‍💻\"; // 주석 🇰🇷",
            "c" to "/* 한글 🇰🇷 */ int main(void) { puts(\"안녕 👋\"); return 0; }",
            "cpp" to "#include <string>\nauto s = std::string(\"한글 👩‍💻\"); // 🇰🇷",
            "go" to "func main() { fmt.Println(\"안녕 👋\") } // 주석 🇰🇷",
            "rust" to "fn main() { let s = \"한글 👩‍💻\"; } // 주석 🇰🇷",
            "sql" to "SELECT '한글 👋' AS 인사 FROM t WHERE id = 1; -- 주석 🇰🇷",
            "yaml" to "이름: \"한글 👩‍💻\"\n버전: 7 # 주석 🇰🇷",
        )
    }
}
