// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.benchmark.macro

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 데모 런처 화면(MainActivity) 콜드·웜 런치 × 컴파일 모드(None / Partial). */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startup_cold_none() = startup(StartupMode.COLD, CompilationMode.None())

    @Test
    fun startup_cold_partial() = startup(StartupMode.COLD, DemoApp.partial)

    @Test
    fun startup_warm_none() = startup(StartupMode.WARM, CompilationMode.None())

    @Test
    fun startup_warm_partial() = startup(StartupMode.WARM, DemoApp.partial)

    private fun startup(mode: StartupMode, compilation: CompilationMode) = rule.measureRepeated(
        packageName = DemoApp.PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = mode,
        iterations = DemoApp.ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }
}
