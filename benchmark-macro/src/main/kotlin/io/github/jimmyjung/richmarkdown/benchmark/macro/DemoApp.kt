// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.benchmark.macro

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode

/**
 * 측정 대상 데모 앱. 화면은 전부 exported라 Intent로 바로 연다 (demo AndroidManifest, `capture-demo-screens.sh`와 동일).
 * extra 이름은 각 Activity companion의 `EXTRA_*`와 같다.
 */
object DemoApp {
    const val PACKAGE = "io.github.jimmyjung.richmarkdown.demo"
    const val ITERATIONS = 5

    /**
     * 데모에는 baseline profile이 없다. 기본 `Partial()`은 `BaselineProfileMode.Require`라 실패하므로
     * 웜업 3회 뒤 `speed-profile` 컴파일(JIT 프로파일 기반)로 잰다. 프로파일이 생기면 그대로 반영된다.
     */
    val partial: CompilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable, warmupIterations = 3)

    fun intent(activity: String, vararg extras: Pair<String, Any>): Intent {
        val intent = Intent().setComponent(ComponentName(PACKAGE, "$PACKAGE.$activity"))
        for ((key, value) in extras) {
            when (value) {
                is Boolean -> intent.putExtra(key, value)
                else -> intent.putExtra(key, value.toString())
            }
        }
        return intent
    }
}
