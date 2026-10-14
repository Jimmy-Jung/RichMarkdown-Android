// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.benchmark.micro

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jimmyjung.richmarkdown.editor.BlockEditorModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 블록 편집기 Markdown 코덱 왕복: `BlockEditorModel(markdown)`(블록 분할·인라인 마크 파싱) → `.markdown`(직렬화). */
@RunWith(AndroidJUnit4::class)
class EditorCodecBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @Test
    fun codecRoundTrip_longEssay() {
        val source = BenchmarkInputs.longEssay
        benchmarkRule.measureRepeated { BlockEditorModel(source).markdown }
    }
}
