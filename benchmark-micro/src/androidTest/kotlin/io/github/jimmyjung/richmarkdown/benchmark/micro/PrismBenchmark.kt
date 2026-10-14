// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.benchmark.micro

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jimmyjung.richmarkdown.highlight.PrismHighlighter
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prism(QuickJS) kotlin 토큰화. 공개 API `PrismHighlighter.spans`(mutex + Dispatchers.Default hop 포함)를 잰다.
 * 엔진은 `@Before`에서 한 번 데워 둔다 — 번들 로드(≈20 스크립트)는 앱 수명에 한 번뿐이라 측정에서 뺀다.
 */
@RunWith(AndroidJUnit4::class)
class PrismBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val highlighter = PrismHighlighter.shared(InstrumentationRegistry.getInstrumentation().targetContext)

    @Before
    fun warmEngine() {
        check(tokenize(BenchmarkInputs.kotlin1k).isNotEmpty()) { "kotlin 토큰화 결과가 비었다 — 엔진·문법 로드 실패" }
    }

    @Test
    fun prismTokenize_kotlin_1k() = measure(BenchmarkInputs.kotlin1k)

    @Test
    fun prismTokenize_kotlin_100k() = measure(BenchmarkInputs.kotlin100k)

    private fun measure(code: String) {
        check(tokenize(code).isNotEmpty()) { "${code.length} UTF-16 입력이 상한을 넘어 토큰화되지 않는다" }
        benchmarkRule.measureRepeated { tokenize(code) }
    }

    private fun tokenize(code: String) = runBlocking { highlighter.spans(code, "kotlin") }
}
