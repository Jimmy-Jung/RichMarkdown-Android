// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.spike

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P0 spike: Prism 1.30.0 스크립트 21개(문법 18 + core·clike + 브리지)가 QuickJS에서 클래식 스크립트로 로드되고,
 * `nativeTokenize`가 iOS와 같은 조각 배열을 돌려주는지 확인한다.
 *
 * 판정 기준(iOS `RichMarkdownHighlightTests`와 동일):
 * - 문법 16종 + Prism 자체 등록 별칭이 `nativeHasGrammar`로 보인다.
 * - 조각 `content`를 이어 붙이면 원문과 UTF-16 단위로 같다 (범위 계산의 전제).
 * - 각 언어 샘플에서 `plain`이 아닌 역할 토큰이 최소 하나 나온다.
 * 로드·토큰화 시간은 콘솔에 남긴다 (수치 목표는 실측 뒤 정한다).
 */
class PrismQuickJsSpikeTest {
    /**
     * Prism 파일은 IIFE 결과(Prism 객체, 순환 참조)를 완료값으로 남긴다. quickjs-kt는 `evaluate` 결과를
     * Kotlin 값으로 변환하므로 `TypeError: circular reference`가 난다(실측). 완료값을 undefined로 덮는다.
     * D6 구현 규칙: 번들 로드 시 항상 이 접미사를 붙인다.
     */
    private val SCRIPT_TERMINATOR = "\n;undefined;\n"

    private val assetsDir = File(System.getProperty("richmarkdown.prismAssets") ?: error("richmarkdown.prismAssets 미설정"))

    /** iOS `PrismHighlighter.scripts`와 같은 순서. `extend` 의존 때문에 바꾸면 안 된다. */
    private val scripts = listOf(
        "prism-core", "prism-clike", "prism-markup", "prism-css", "prism-javascript", "prism-jsx",
        "prism-typescript", "prism-tsx", "prism-swift", "prism-python", "prism-json", "prism-bash",
        "prism-kotlin", "prism-java", "prism-c", "prism-cpp", "prism-go", "prism-rust", "prism-sql",
        "prism-yaml", "native-tokenize",
    )

    private val grammars = listOf(
        "markup", "css", "javascript", "jsx", "typescript", "tsx", "swift", "python", "json", "bash",
        "kotlin", "java", "c", "cpp", "go", "rust", "sql", "yaml",
    )

    /** Prism 문법 파일이 스스로 등록하는 별칭 (iOS Docs §4.2). */
    private val prismAliases = listOf("js", "ts", "py", "sh", "shell", "kt", "kts", "yml", "html", "xml", "svg")

    private val samples: Map<String, String> = mapOf(
        "kotlin" to "fun main() {\n    val x: Int = 42 // 주석\n    println(\"값 = \$x\")\n}\n",
        "swift" to "struct Point { let x: Double }\nfunc f() -> Int { return 1 }\n",
        "python" to "def f(x):\n    return x * 2  # 주석\n\nclass A:\n    pass\n",
        "typescript" to "interface P { x: number }\nconst f = (p: P): string => `\${p.x}`;\n",
        "javascript" to "function f(a) { return a + 1; }\nconst s = 'str';\n",
        "bash" to "#!/bin/bash\nfor f in *.txt; do echo \"\$f\"; done\n",
        "json" to "{\"a\": 1, \"b\": [true, null, \"s\"]}\n",
        "yaml" to "name: rich\nitems:\n  - one\n  - two\n",
        "sql" to "SELECT id, name FROM users WHERE id = 1;\n",
        "markup" to "<div class=\"a\"><span>텍스트</span></div>\n",
        "css" to ".a { color: #fff; margin: 0 auto; }\n",
        "c" to "#include <stdio.h>\nint main(void) { return 0; }\n",
        "cpp" to "template <typename T> class A { public: T x; };\n",
        "go" to "package main\nfunc main() { fmt.Println(\"hi\") }\n",
        "rust" to "fn main() { let x: u32 = 1; println!(\"{}\", x); }\n",
        "java" to "public class A { private int x = 1; }\n",
        "jsx" to "const A = () => <div className=\"a\">{x}</div>;\n",
        "tsx" to "const A = (p: {x: number}) => <div>{p.x}</div>;\n",
    )

    @Test
    fun `Prism 스크립트 21개가 QuickJS에 로드되고 18 문법이 토큰화된다`() = runBlocking {
        val quickJs = QuickJs.create(Dispatchers.Default)
        try {
            val loadStart = System.nanoTime()
            for (name in scripts) {
                val file = File(assetsDir, "$name.js")
                assertTrue(file.isFile, "번들 파일 없음: ${file.path}")
                quickJs.evaluate<Any?>(file.readText() + SCRIPT_TERMINATOR, filename = "$name.js")
            }
            val loadMs = (System.nanoTime() - loadStart) / 1_000_000.0
            println("[spike] Prism 로드 ${scripts.size}파일: ${"%.1f".format(loadMs)} ms")

            for (language in grammars + prismAliases) {
                val has = quickJs.evaluate<Boolean>("nativeHasGrammar(${jsString(language)})")
                assertTrue(has, "문법 없음: $language")
            }
            val missing = quickJs.evaluate<Boolean>("nativeHasGrammar('no-such-language')")
            assertEquals(false, missing)

            for ((language, source) in samples) {
                val call = "nativeTokenize(${jsString(source)}, ${jsString(language)})"
                val t0 = System.nanoTime()
                val joined = quickJs.evaluate<String>("$call.map(function (c) { return c.content; }).join('')")
                val tokenizeMs = (System.nanoTime() - t0) / 1_000_000.0
                assertEquals(source, joined, "content 왕복 불일치: $language")

                val kinds = quickJs.evaluate<String>(
                    "$call.map(function (c) { return c.kind; }).filter(function (k) { return k !== 'plain'; }).join(',')",
                )
                val kindSet = kinds.split(',').filter { it.isNotEmpty() }.toSet()
                assertTrue(kindSet.isNotEmpty(), "토큰 없음: $language")
                println("[spike] $language: ${"%.2f".format(tokenizeMs)} ms, kinds=${kindSet.sorted()}")
            }
        } finally {
            quickJs.close()
        }
    }

    @Test
    fun `유니코드 원문도 UTF-16 단위로 왕복한다`() = runBlocking {
        val quickJs = QuickJs.create(Dispatchers.Default)
        try {
            for (name in scripts) quickJs.evaluate<Any?>(File(assetsDir, "$name.js").readText() + SCRIPT_TERMINATOR, filename = "$name.js")
            // 한글·이모지(surrogate pair)·결합 문자. JS 문자열은 UTF-16이라 iOS NSRange와 단위가 같다.
            val source = "val s = \"한글 😀 é\" // 주석 🇰🇷\n"
            val joined = quickJs.evaluate<String>(
                "nativeTokenize(${jsString(source)}, 'kotlin').map(function (c) { return c.content; }).join('')",
            )
            assertEquals(source, joined)
            assertEquals(source.length, joined.length)
        } finally {
            quickJs.close()
        }
    }

    /** JS 문자열 리터럴로 안전하게 인코딩한다. 실제 모듈은 함수 바인딩으로 넘기고 문자열 결합을 쓰지 않는다. */
    private fun jsString(value: String): String {
        val sb = StringBuilder(value.length + 2).append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
