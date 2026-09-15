// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.highlight

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jimmyjung.richmarkdown.RichMarkdownHighlightKind
import io.github.jimmyjung.richmarkdown.RichMarkdownHighlightSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 번들 Prism을 실제 Android QuickJS에서 실행해 검증한다. 모킹하지 않는다
 * (iOS `Tests/RichMarkdownHighlightTests/PrismHighlighterTests.swift` 포팅).
 */
@RunWith(AndroidJUnit4::class)
class PrismHighlighterTest {

    private val highlighter = PrismHighlighter.shared(InstrumentationRegistry.getInstrumentation().targetContext)

    /** iOS `bundledGrammarsLoad` + swift·json 샘플. 문법 로드에 실패하면 빈 리스트가 되므로 그것으로 판정한다. */
    private val samples = mapOf(
        "javascript" to "const a = 1; // c",
        "typescript" to "const a: number = 1;",
        "python" to "def f(x):\n    return 'a'",
        "bash" to "echo \"hi\" # c",
        "kotlin" to "val a: Int = 1",
        "java" to "class A { int b = 1; }",
        "c" to "int main(void) { return 0; }",
        "cpp" to "#include <vector>\nint main() { return 0; }",
        "go" to "func main() { println(\"hi\") }",
        "rust" to "fn main() { let x = 1; }",
        "sql" to "SELECT id FROM users WHERE id = 1;",
        "yaml" to "name: RichMarkdown\nversion: 7",
        "css" to ".a { color: red; }",
        "markup" to "<p class=\"a\">hi</p>",
        "jsx" to "const a = <div className=\"b\">hi</div>;",
        "tsx" to "const a: JSX.Element = <div>hi</div>;",
        "swift" to "struct Circle {\n    let radius: Double\n    func area() -> Double { .pi * radius }\n}",
        "json" to "{\"name\": \"RichMarkdown\", \"version\": 7, \"beta\": true}",
    )

    private fun texts(code: String, spans: List<RichMarkdownHighlightSpan>, kind: RichMarkdownHighlightKind) =
        spans.filter { it.kind == kind }.map { code.substring(it.range.start, it.range.end) }

    /** 범위가 원문 안에 있고, 비어 있지 않고, 서로 겹치지 않고, 서러게이트 쌍을 가르지 않는다. */
    private fun assertWellFormed(code: String, spans: List<RichMarkdownHighlightSpan>) {
        for (span in spans) {
            val r = span.range
            assertTrue("원문 밖 범위: $span", r.start >= 0 && r.end <= code.length && r.length > 0)
            assertFalse("서러게이트 쌍을 갈랐다: $span", Character.isLowSurrogate(code[r.start]))
            assertFalse("서러게이트 쌍을 갈랐다: $span", Character.isHighSurrogate(code[r.end - 1]))
        }
        spans.zipWithNext { a, b -> assertTrue("겹침: $a / $b", a.range.end <= b.range.start) }
    }

    // MARK: - 실제 문법

    @Test
    fun `번들_문법_18종이_색_범위를_만든다`() = runBlocking {
        for ((language, code) in samples) {
            val spans = highlighter.spans(code, language)
            assertTrue("$language 문법이 색 범위를 만들지 못했다", spans.isNotEmpty())
            assertWellFormed(code, spans)
        }
    }

    @Test
    fun `별칭은_정식_이름과_같은_범위를_만든다`() = runBlocking {
        val pairs = listOf(
            "JS" to "javascript", "ts" to "typescript", "Py" to "python", "c++" to "cpp", "rs" to "rust",
            "sh" to "bash", "yml" to "yaml", "kt" to "kotlin", "golang" to "go", "postgres" to "sql", "html" to "markup",
        )
        for ((alias, canonical) in pairs) {
            val code = samples.getValue(canonical)
            val viaAlias = highlighter.spans(code, alias)
            assertTrue("$alias 가 문법을 찾지 못했다", viaAlias.isNotEmpty())
            assertEquals(alias, highlighter.spans(code, canonical), viaAlias)
        }
    }

    // MARK: - 역할 매핑

    @Test
    fun `Kotlin_키워드_문자열_주석_숫자_함수가_역할로_매핑된다`() = runBlocking {
        val code = "fun greet(): String { // 주석\n    val count = 42\n    return format(\"hi\")\n}\n"
        val spans = highlighter.spans(code, "kotlin")

        assertTrue(texts(code, spans, RichMarkdownHighlightKind.Keyword).containsAll(listOf("fun", "val", "return")))
        assertEquals(listOf("\"hi\""), texts(code, spans, RichMarkdownHighlightKind.String))
        assertEquals(listOf("// 주석"), texts(code, spans, RichMarkdownHighlightKind.Comment))
        assertEquals(listOf("42"), texts(code, spans, RichMarkdownHighlightKind.Number))
        assertTrue(texts(code, spans, RichMarkdownHighlightKind.Function).containsAll(listOf("greet", "format")))
        assertWellFormed(code, spans)
    }

    @Test
    fun `Swift_클래스_이름은_type이다`() = runBlocking {
        val code = samples.getValue("swift")
        val spans = highlighter.spans(code, "swift")
        assertTrue(texts(code, spans, RichMarkdownHighlightKind.Type).containsAll(listOf("Circle", "Double")))
        assertEquals(listOf("area"), texts(code, spans, RichMarkdownHighlightKind.Function))
    }

    @Test
    fun `JSON은_property와_string을_구분한다`() = runBlocking {
        val code = samples.getValue("json")
        val spans = highlighter.spans(code, "json")
        assertEquals(listOf("\"name\"", "\"version\"", "\"beta\""), texts(code, spans, RichMarkdownHighlightKind.Property))
        assertEquals(listOf("\"RichMarkdown\""), texts(code, spans, RichMarkdownHighlightKind.String))
        assertEquals(listOf("7"), texts(code, spans, RichMarkdownHighlightKind.Number))
        assertEquals(listOf("true"), texts(code, spans, RichMarkdownHighlightKind.Keyword))
    }

    @Test
    fun `operator와_punctuation은_색을_주지_않는다`() {
        assertNull(PrismHighlighter.kindFor("operator"))
        assertNull(PrismHighlighter.kindFor("punctuation"))
        assertNull(PrismHighlighter.kindFor("plain"))
    }

    @Test
    fun `점_표기_토큰은_카테고리로_매핑한다`() {
        assertEquals(RichMarkdownHighlightKind.String, PrismHighlighter.kindFor("string.special"))
        assertEquals(RichMarkdownHighlightKind.Type, PrismHighlighter.kindFor("class-name"))
        assertEquals(RichMarkdownHighlightKind.Function, PrismHighlighter.kindFor("function-variable"))
    }

    // MARK: - Unicode

    @Test
    fun `한글_이모지_범위가_원문의_UTF16_위치를_가리킨다`() = runBlocking {
        val code = "// 한글 주석 🎯 이모지\nval 인사 = \"안녕하세요 👋 RichMarkdown\""
        val spans = highlighter.spans(code, "kotlin")

        assertEquals(listOf("// 한글 주석 🎯 이모지"), texts(code, spans, RichMarkdownHighlightKind.Comment))
        assertEquals(listOf("\"안녕하세요 👋 RichMarkdown\""), texts(code, spans, RichMarkdownHighlightKind.String))
        assertWellFormed(code, spans)
    }

    // MARK: - fallback 계약

    @Test
    fun `미지원_언어는_빈_리스트다`() = runBlocking {
        assertTrue(highlighter.spans("SOME TEXT", "brainfuck").isEmpty())
    }

    @Test
    fun `빈_코드는_빈_리스트다`() = runBlocking {
        assertTrue(highlighter.spans("", "swift").isEmpty())
    }

    @Test
    fun `상한을_넘는_코드는_빈_리스트다`() = runBlocking {
        val code = "let a = 1\n".repeat(20_000)
        assertTrue(code.length > PrismHighlighter.maxCodeUtf16Units)
        assertTrue(highlighter.spans(code, "swift").isEmpty())
    }

    @Test
    fun `release_뒤에도_같은_결과를_만든다`() = runBlocking {
        val code = "let a = 1 // c"
        val before = highlighter.spans(code, "swift")
        highlighter.release()
        val after = highlighter.spans(code, "swift")

        assertTrue(before.isNotEmpty())
        assertEquals(before, after)
    }

    // MARK: - 동시성

    @Test
    fun `동시_호출_8개가_모두_성공한다`() = runBlocking {
        val code = samples.getValue("kotlin")
        val expected = highlighter.spans(code, "kotlin")
        val results = coroutineScope {
            List(8) { i ->
                async(Dispatchers.Default) {
                    val language = if (i % 2 == 0) "kotlin" else "kt"
                    highlighter.spans(code, language)
                }
            }.awaitAll()
        }
        assertTrue(expected.isNotEmpty())
        results.forEach { assertEquals(expected, it) }
    }
}
