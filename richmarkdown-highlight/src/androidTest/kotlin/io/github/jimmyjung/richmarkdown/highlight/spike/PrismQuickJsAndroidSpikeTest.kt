// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.highlight.spike

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException

/**
 * P0 spike (에뮬레이터): 호스트 spike `PrismQuickJsSpikeTest`와 같은 판정을
 * 실제 Android QuickJS `.so` 위에서 반복하고, so 로드·첫 evaluate·100KB 토큰화 시간을 실측한다.
 * 수치는 logcat 태그 `RichMarkdownSpike`와 stdout에 남긴다.
 */
@RunWith(AndroidJUnit4::class)
class PrismQuickJsAndroidSpikeTest {

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

    /** Prism 문법 파일이 스스로 등록하는 별칭. */
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
    fun `Prism_번들이_Android_QuickJS에_로드되고_토큰화된다`() = runBlocking<Unit> {
        val quickJs = QuickJs.create(Dispatchers.Default)
        try {
            val loadMs = loadPrism(quickJs)
            log("[spike] Prism 로드 ${scripts.size}파일: ${"%.1f".format(loadMs)} ms")

            for (language in grammars + prismAliases) {
                val has = quickJs.evaluate<Boolean>("nativeHasGrammar(${jsString(language)})")
                assertTrue("문법 없음: $language", has)
            }
            assertFalse(quickJs.evaluate<Boolean>("nativeHasGrammar('no-such-language')"))

            for ((language, source) in samples) {
                val call = "nativeTokenize(${jsString(source)}, ${jsString(language)})"
                val t0 = SystemClock.elapsedRealtimeNanos()
                val joined = quickJs.evaluate<String>("$call.map(function (c) { return c.content; }).join('')")
                val tokenizeMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
                assertEquals("content 왕복 불일치: $language", source, joined)

                val kinds = quickJs.evaluate<String>(
                    "$call.map(function (c) { return c.kind; }).filter(function (k) { return k !== 'plain'; }).join(',')",
                )
                val kindSet = kinds.split(',').filter { it.isNotEmpty() }.toSet()
                assertTrue("토큰 없음: $language", kindSet.isNotEmpty())
                log("[spike] $language: ${"%.2f".format(tokenizeMs)} ms, kinds=${kindSet.sorted()}")
            }
        } finally {
            quickJs.close()
        }
    }

    @Test
    fun `so_로드와_첫_evaluate_지연`() = runBlocking<Unit> {
        // create가 .so 로드(System.loadLibrary)를 유발한다. 첫 evaluate까지 묶어 콜드 지연으로 본다.
        val t0 = SystemClock.elapsedRealtimeNanos()
        val quickJs = QuickJs.create(Dispatchers.Default)
        try {
            val result = quickJs.evaluate<Int>("1+1")
            val coldMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            assertEquals(2, result)
            val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
            log("[spike] QuickJs.create + 첫 evaluate: ${"%.1f".format(coldMs)} ms, pageSize=$pageSize")
        } finally {
            quickJs.close()
        }
    }

    @Test
    fun `100KB_코드_토큰화_시간`() = runBlocking<Unit> {
        val snippet = """
            |class Repo(private val api: Api) {
            |    private val cache = mutableMapOf<String, Item>() // 메모리 캐시
            |    suspend fun load(id: String): Item? {
            |        cache[id]?.let { return it }
            |        val item = api.fetch(id) ?: return null
            |        cache[id] = item
            |        return item
            |    }
            |    fun clear() = cache.clear()
            |}
            |""".trimMargin()
        val source = buildString { while (length < 100_000) append(snippet) }

        val quickJs = QuickJs.create(Dispatchers.Default)
        try {
            loadPrism(quickJs)
            val t0 = SystemClock.elapsedRealtimeNanos()
            val joined = quickJs.evaluate<String>(
                "nativeTokenize(${jsString(source)}, 'kotlin').map(function (c) { return c.content; }).join('')",
            )
            val tokenizeMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            assertEquals(source, joined)
            log("[spike] kotlin ${source.length} UTF-16 units 토큰화: ${"%.1f".format(tokenizeMs)} ms")
        } finally {
            quickJs.close()
        }
    }

    @Test
    fun `유니코드_원문_UTF16_왕복`() = runBlocking<Unit> {
        val quickJs = QuickJs.create(Dispatchers.Default)
        try {
            loadPrism(quickJs)
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

    /** 번들 21파일을 순서대로 evaluate하고 총 소요 ms를 돌려준다. */
    private suspend fun loadPrism(quickJs: QuickJs): Double {
        val start = SystemClock.elapsedRealtimeNanos()
        for (name in scripts) quickJs.evaluate<Any?>(readAsset(name) + SCRIPT_TERMINATOR, filename = "$name.js")
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    }

    /** 라이브러리 모듈은 main assets가 테스트 APK에 병합되므로 targetContext → context 순으로 찾는다. */
    private fun readAsset(name: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = "prism/$name.js"
        val stream = try {
            instrumentation.targetContext.assets.open(path)
        } catch (e: FileNotFoundException) {
            instrumentation.context.assets.open(path)
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        println(message)
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

    private companion object {
        const val TAG = "RichMarkdownSpike"

        /**
         * Prism 파일은 IIFE 결과(Prism 객체, 순환 참조)를 완료값으로 남긴다. quickjs-kt는 `evaluate` 결과를
         * Kotlin 값으로 변환하므로 `TypeError: circular reference`가 난다(host spike 실측). 완료값을 undefined로 덮는다.
         */
        const val SCRIPT_TERMINATOR = "\n;undefined;\n"
    }
}
