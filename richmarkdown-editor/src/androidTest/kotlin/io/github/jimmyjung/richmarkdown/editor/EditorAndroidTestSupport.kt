// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jimmyjung.richmarkdown.RenderedMath
import io.github.jimmyjung.richmarkdown.RichMarkdownFont
import io.github.jimmyjung.richmarkdown.RichMarkdownFontWeight
import io.github.jimmyjung.richmarkdown.RichMarkdownTextStyle
import io.github.jimmyjung.richmarkdown.RichMarkdownTheme
import kotlin.math.roundToInt

internal val instrumentation get() = InstrumentationRegistry.getInstrumentation()
internal val targetContext: Context get() = instrumentation.targetContext

/** iOS `NSRange(location:length:)` 축약. */
internal fun r(location: Int, length: Int) = EditorRange(location, length)

/** iOS 매개변수화 테스트의 overflow·음수 선택 4종. */
internal val OVERFLOW_SELECTIONS = listOf(r(Int.MAX_VALUE, 1), r(1, Int.MAX_VALUE), r(0, Int.MAX_VALUE), r(-1, 1))

internal fun dp(value: Int): Int = (value * targetContext.resources.displayMetrics.density).roundToInt()

internal fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    instrumentation.runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
}

/** 렌더 전 이미지 대용. 스타일러 규칙만 볼 때 쓴다. */
internal val FAKE_MATH = RenderedMath(Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888), 30f, 14f, 6f)

/** [offset]의 한 글자에 걸린 span (경계에서만 닿는 span 제외). */
internal inline fun <reified T> Spanned.spansAt(offset: Int): List<T> = getSpans(offset, offset + 1, T::class.java).toList()

internal fun Spanned.paragraphAt(offset: Int): BlockParagraphSpan = spansAt<BlockParagraphSpan>(offset).single()

/** [offset] 글자에 걸린 문자 span을 삽입 순서대로 적용한 paint. 실제 그리기와 같은 우선순위다. */
internal fun Spanned.paintAt(offset: Int): TextPaint {
    val paint = TextPaint()
    for (span in spansAt<CharacterStyle>(offset)) span.updateDrawState(paint)
    return paint
}

internal val TextPaint.isBold: Boolean get() = isFakeBoldText || typeface?.isBold == true
internal val TextPaint.isItalic: Boolean get() = textSkewX != 0f || typeface?.isItalic == true
internal val TextPaint.isSerif: Boolean get() = typeface == Typeface.SERIF

/** iOS 테스트 테마 `.testLarge`·`.testSerif`·`.testTinted`. Android에는 Georgia가 없어 serif는 `Design.Serif`. */
internal val TEST_LARGE = RichMarkdownTheme(
    bodyFont = RichMarkdownFont(relativeTo = RichMarkdownTextStyle.Body, sizeSp = 24f),
    heading1Font = RichMarkdownFont(relativeTo = RichMarkdownTextStyle.Title1, sizeSp = 38f, weight = RichMarkdownFontWeight.Bold),
    heading2Font = RichMarkdownFont(relativeTo = RichMarkdownTextStyle.Title2, sizeSp = 30f, weight = RichMarkdownFontWeight.Bold),
    heading3Font = RichMarkdownFont(relativeTo = RichMarkdownTextStyle.Title3, sizeSp = 26f, weight = RichMarkdownFontWeight.Semibold),
    codeFont = RichMarkdownFont(design = RichMarkdownFont.Design.Monospaced, relativeTo = RichMarkdownTextStyle.Body, sizeSp = 20f),
)
internal val TEST_SERIF = RichMarkdownTheme(bodyFont = RichMarkdownFont(design = RichMarkdownFont.Design.Serif))
internal val TEST_TINTED = RichMarkdownTheme(textColor = io.github.jimmyjung.richmarkdown.RichMarkdownColor.rgb(light = 0x33298C, dark = 0xB3A8FF))

/** 앱 모델을 소유한 View 호스트. 편집 콜백 안에서 모델을 바꾸고 바로 [publish]한다(뷰의 호스트 계약). */
internal class ModelHost(
    val view: BlockDocumentEditText,
    val model: BlockEditorModel,
    private val parsesDollarMath: Boolean = false,
    private val theme: RichMarkdownTheme = RichMarkdownTheme.Default,
) {
    val replacements = mutableListOf<Pair<EditorRange, String>>()
    val selections = mutableListOf<EditorRange>()
    val actions = mutableListOf<Pair<EditorToolbarAction, EditorRange>>()
    val pastedBlocks = mutableListOf<List<EditorBlock>>()

    init {
        view.onReplaceText = { range, text ->
            replacements += range to text
            model.replaceDocumentText(range, text).also { publish() }
        }
        view.onSelectionChange = {
            selections += it
            model.updateDocumentSelection(it)
        }
        view.onToolbarAction = { action, range -> actions += action to range }
        view.onReplaceDocumentBlocks = { range, blocks ->
            pastedBlocks += blocks
            model.replaceDocumentBlocks(range, blocks).also { publish() }
        }
        publish()
    }

    fun publish() = view.setState(
        model.blocks,
        model.currentDocumentSelection,
        model.canUndo,
        model.canRedo,
        parsesDollarMath = parsesDollarMath,
        theme = theme,
        sourceMarkdown = model.markdown,
    )
}
