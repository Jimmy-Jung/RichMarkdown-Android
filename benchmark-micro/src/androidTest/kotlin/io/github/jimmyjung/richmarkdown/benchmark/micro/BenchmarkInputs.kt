// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.benchmark.micro

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry

/**
 * 양 플랫폼 공통 벤치마크 입력 (p2/design.md «공통 입력»).
 *
 * - 파싱 입력 4종: samples 폴더의 `.md` 파일을 파일명 사전순으로 `\n\n---\n\n`로 이어 붙이고, 모자라면 같은 묶음을 반복한 뒤
 *   1·10·50·256 KiB(UTF-8 바이트)에서 마지막 온전한 code point 경계로 자른다. 파일은 커밋하지 않고 여기서 만든다.
 * - 수식 2종: 단순 `x^2`, 복합(데모 챗 fixture의 array + underbrace 보상 함수).
 * - 코드 입력: `code-kotlin-coroutines.md`의 kotlin 펜스 본문을 반복해 1,000 / 100,000 UTF-16 단위로 자른다
 *   (100,000은 `PrismHighlighter.maxCodeUtf16Units` 상한과 같다).
 *
 * 샘플은 `richmarkdown-core/src/test/resources/fixtures/samples`를 테스트 APK assets로 그대로 묶은 것이다.
 */
object BenchmarkInputs {
    private const val SEPARATOR = "\n\n---\n\n"
    private const val KIB = 1024

    val parse1k: String by lazy { sizedMarkdown(1 * KIB) }
    val parse10k: String by lazy { sizedMarkdown(10 * KIB) }
    val parse50k: String by lazy { sizedMarkdown(50 * KIB) }
    val parse256k: String by lazy { sizedMarkdown(256 * KIB) }

    val longEssay: String by lazy { readAsset("long-essay-mixed.md") }

    val kotlin1k: String by lazy { sizedKotlin(1_000) }
    val kotlin100k: String by lazy { sizedKotlin(100_000) }

    const val SIMPLE_MATH = "x^2"

    /** demo `SampleMarkdown.complexMath`의 `\[ … \]` 본문. 데모 모듈(application)에 의존할 수 없어 복사했다. */
    val COMPLEX_MATH: String = """
        r_t=\left\{\begin{array}{ccc}
        \underbrace{\begin{array}{c}
        1+\frac{\bar{R}_Q(t+\Delta t)-R_Q(t)}{2\Delta t/T_{\mathrm{single}}}\\
        0\\
        0
        \end{array}}_{r_t^{(1)}}&
        \underbrace{\begin{array}{c}
        \vphantom{\frac{\bar{R}_Q}{T_{\mathrm{single}}}}+0\\
        -P\\
        +0
        \end{array}}_{r_t^{(2)}}&
        \begin{array}{l}
        \vphantom{\frac{\bar{R}_Q}{T_{\mathrm{single}}}}\mathrm{if}\ \bar{R}_Q(t+\Delta t)>0\\
        \mathrm{if}\ \bar{R}_Q(t)\ne0\ \mathrm{and}\ R_Q(t+\Delta t)=0\\
        \mathrm{if}\ R_Q(t)=0
        \end{array}
        \end{array}\right.
    """.trimIndent()

    private val assets: AssetManager
        get() = InstrumentationRegistry.getInstrumentation().context.assets

    private val joinedSamples: String by lazy {
        val names = assets.list("").orEmpty().filter { it.endsWith(".md") }.sorted()
        check(names.isNotEmpty()) { "samples/*.md assets가 없다" }
        names.joinToString(SEPARATOR) { readAsset(it) }
    }

    private fun readAsset(name: String): String = assets.open(name).bufferedReader().use { it.readText() }

    private fun sizedMarkdown(utf8Limit: Int): String {
        val builder = StringBuilder(joinedSamples)
        while (builder.toString().toByteArray(Charsets.UTF_8).size < utf8Limit) builder.append(SEPARATOR).append(joinedSamples)
        return cutUtf8(builder.toString(), utf8Limit)
    }

    /** UTF-8 바이트 `limit` 이하의 마지막 온전한 code point 경계에서 자른다. */
    private fun cutUtf8(text: String, limit: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limit) return text
        var end = limit
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private val kotlinFenceBody: String by lazy {
        val lines = readAsset("code-kotlin-coroutines.md").lines()
        val open = lines.indexOfFirst { it.startsWith("```kotlin") }
        check(open >= 0) { "kotlin 펜스를 찾지 못했다" }
        val close = (open + 1 until lines.size).first { lines[it] == "```" }
        lines.subList(open + 1, close).joinToString("\n")
    }

    /** UTF-16 단위 `limit`까지 반복·절단한다. 서로게이트 쌍은 가르지 않는다. */
    private fun sizedKotlin(limit: Int): String {
        val builder = StringBuilder()
        while (builder.length < limit) builder.append(kotlinFenceBody).append('\n')
        var end = limit
        if (Character.isHighSurrogate(builder[end - 1])) end--
        return builder.substring(0, end)
    }
}
