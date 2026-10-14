// Author: JunyoungJung
// Date: 2026-10-14

package io.github.jimmyjung.richmarkdown.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.github.jimmyjung.richmarkdown.editor.EditorBlockKind.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 실기기 QA(2026-10-08)에서 사람이 화면을 보고 찾은 두 결함의 회귀 테스트.
 * F-1 아래 패딩 구간 글자 잘림, F-2 드래그 스크롤이 캐럿을 옮겨 수식이 원문으로 바뀜.
 * 포커스·터치 경로가 실제와 같도록 Activity 창에 붙여 검사한다(창 밖의 뷰는 포커스를 받지 못해 F-2 경로가 돌지 않는다).
 */
class BlockDocumentScrollRegressionTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val viewWidth = 720
    private val viewHeight = 900

    /** 높이가 고정된 스크롤 편집기를 창에 붙인다. 문단 60개라 화면보다 훨씬 길다. */
    private fun attachScrollingEditor(): BlockDocumentEditText {
        lateinit var view: BlockDocumentEditText
        compose.runOnUiThread {
            view = BlockDocumentEditText(compose.activity).apply {
                setBackgroundColor(Color.WHITE)
                val blocks = (1..60).map { EditorBlock(kind = Paragraph, text = "문단 $it — 스크롤 회귀 확인용 본문입니다") }
                setState(blocks, r(0, 0), canUndo = false, canRedo = false)
            }
            val root = FrameLayout(compose.activity)
            root.addView(view, FrameLayout.LayoutParams(viewWidth, viewHeight))
            compose.activity.setContentView(root)
        }
        compose.waitForIdle()
        return view
    }

    private fun maxScrollY(view: BlockDocumentEditText): Int =
        view.layout.height - (view.height - view.compoundPaddingTop - view.compoundPaddingBottom)

    /** 맨 위·맨 아래가 아닌 중간 스크롤 위치에서 아래 패딩 구간에도 글자 픽셀이 그려진다. */
    @Test
    fun textIsDrawnInsideBottomPaddingWhileScrolledMidway() {
        val view = attachScrollingEditor()
        compose.runOnUiThread {
            val maxScroll = maxScrollY(view)
            assertTrue("문서가 화면보다 길어야 한다 (max=$maxScroll)", maxScroll > 200)
            assertTrue("아래 패딩이 있어야 검사가 의미 있다", view.paddingBottom >= dp(64))
            view.scrollTo(0, maxScroll / 2)

            val bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
            view.draw(canvas)

            val ink = countInkPixels(bitmap, viewHeight - view.paddingBottom, viewHeight)
            assertTrue("아래 패딩 구간(${view.paddingBottom}px)에 글자 픽셀이 없다", ink > 0)
        }
    }

    /** 그리기 밖에서는 아래 패딩을 그대로 보고한다 — 마지막 줄을 아래 여백만큼 위로 올려 볼 수 있다. */
    @Test
    fun bottomInsetStillExtendsScrollRange() {
        val view = attachScrollingEditor()
        compose.runOnUiThread {
            assertEquals(view.paddingBottom, view.extendedPaddingBottom)
            view.scrollTo(0, maxScrollY(view))
            val lastLineBottom = view.layout.getLineBottom(view.layout.lineCount - 1) + view.totalPaddingTop - view.scrollY
            assertTrue("맨 아래로 스크롤하면 마지막 줄 아래에 여백이 남아야 한다", view.height - lastLineBottom >= view.paddingBottom - 1)
        }
    }

    /** 캐럿을 문서 처음에 두고 위로 드래그 스크롤해도 선택이 바뀌지 않는다 (iOS UITextView와 같은 동작). */
    @Test
    fun dragScrollDoesNotMoveCaret() {
        val view = attachScrollingEditor()
        compose.runOnUiThread {
            assertTrue("포커스를 받아야 F-2 경로가 돈다", view.requestFocus())
            view.setSelection(0)
        }
        compose.waitForIdle()
        val down = SystemClock.uptimeMillis()
        val x = viewWidth / 2f
        fun send(action: Int, y: Float, at: Long) = compose.runOnUiThread {
            val event = MotionEvent.obtain(down, at, action, x, y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 800f, down)
        for (step in 1..10) send(MotionEvent.ACTION_MOVE, 800f - step * 60f, down + step * 16L)
        send(MotionEvent.ACTION_UP, 200f, down + 200L)
        compose.waitForIdle()
        compose.runOnUiThread {
            assertTrue("드래그로 실제로 스크롤되어야 검사가 의미 있다 (scrollY=${view.scrollY})", view.scrollY > 0)
            assertEquals("스크롤만으로 캐럿이 옮겨졌다", 0, view.selectionStart)
        }
    }

    private fun countInkPixels(bitmap: Bitmap, fromY: Int, toY: Int): Int {
        var count = 0
        for (y in fromY until toY step 2) {
            for (x in 0 until bitmap.width step 2) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) < 128 && Color.alpha(pixel) > 0) count++
            }
        }
        return count
    }
}
