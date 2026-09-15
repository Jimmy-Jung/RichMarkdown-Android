// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import io.github.jimmyjung.richmarkdown.LatexEquationAlignment
import io.github.jimmyjung.richmarkdown.MathRenderKey
import io.github.jimmyjung.richmarkdown.MathRenderService
import io.github.jimmyjung.richmarkdown.MathVectorLayout
import io.github.jimmyjung.richmarkdown.R
import io.github.jimmyjung.richmarkdown.RenderedMath
import io.github.jimmyjung.richmarkdown.RichMarkdownCodeBlockOptions
import io.github.jimmyjung.richmarkdown.RichMarkdownStreamingOptions
import io.github.jimmyjung.richmarkdown.RichMarkdownTheme
import io.github.jimmyjung.richmarkdown.core.DollarMathOptions
import io.github.jimmyjung.richmarkdown.core.InlineRun
import io.github.jimmyjung.richmarkdown.core.MathSegment
import io.github.jimmyjung.richmarkdown.core.ParsedBlock
import io.github.jimmyjung.richmarkdown.core.ParsedTable
import io.github.jimmyjung.richmarkdown.resolveTypeface
import io.github.jimmyjung.richmarkdown.textSizePx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val TAG = "RichMarkdownView"

/** 복사 버튼. 스트리밍 in-place 갱신이 복사할 원문(`payload`)만 바꾼다 (iOS `RichMarkdownCopyButton`). */
internal class CopyButton(context: Context) : ImageButton(context) {
    var payload: String = ""
}

/** 블록 수식 벡터 뷰 (iOS `BlockMathVectorView`). `MathVectorLayout`을 bounding box 좌상단 원점에 그린다. */
internal class BlockMathView(context: Context) : View(context) {
    var mathLayout: MathVectorLayout? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val layout = mathLayout
        val width = layout?.let { ceil(it.widthPx).toInt() } ?: 0
        val height = layout?.let { ceil(it.ascentPx + it.descentPx).toInt() } ?: 0
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        mathLayout?.draw(canvas)
    }
}

/** 코드 블록 in-place 갱신용 부품. 다이어그램으로 대체된 블록은 tag가 없다. */
private class CodeBlockParts(val body: ChipTextView, val copy: CopyButton)

/** 표 in-place 갱신용 부품. */
private class TableParts(val cells: List<List<ChipTextView>>)

/** `LinearLayout` 자식 간격. iOS `UIStackView.spacing` 대응 — 투명 divider의 고유 크기로 띄운다. */
internal fun LinearLayout.setSpacing(px: Int) {
    dividerDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setSize(px, px)
    }
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
}

/**
 * `ParsedBlock` → 자식 View (iOS `RichMarkdownUIView` "Blocks"·"Inline runs"·"View factories").
 *
 * 한 rebuild의 겉모습(테마·다크·폰트 크기)을 생성 시점에 해석해 들고 있으므로 rebuild마다 새로 만든다.
 * 숫자 상수는 iOS pt 값을 dp로 옮겼다. 헤어라인(테두리·구분선)은 iOS `1 / displayScale` = 1px.
 */
internal class BlockViewBuilder(
    private val context: Context,
    private val theme: RichMarkdownTheme,
    private val isDark: Boolean,
    private val dollarMath: DollarMathOptions,
    private val codeBlocks: RichMarkdownCodeBlockOptions,
    private val scope: CoroutineScope,
    openLink: (Uri) -> Unit,
    private val onSizeChange: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val textColor = theme.textColor.resolve(isDark)

    /** 수식 raster 기준 크기. `AppearanceKey`에도 들어간다. */
    val bodyPx: Float = theme.bodyFont.textSizePx(context)
    private val bodyTypeface: Typeface = theme.bodyFont.resolveTypeface()
    private val codePx: Float = theme.codeFont.textSizePx(context)
    private val codeTypeface: Typeface = theme.codeFont.resolveTypeface()
    private val spans = InlineSpanBuilder(theme, isDark, codePx, codeTypeface, openLink)

    private fun dp(value: Float): Int = (value * density).roundToInt()

    // MARK: - Blocks

    fun blockView(block: ParsedBlock, images: Map<MathSegment, RenderedMath>, tail: RichMarkdownStreamingOptions?): View {
        val view = when (block) {
            is ParsedBlock.Paragraph -> runsTextView(block.runs, images, bodyPx, bodyTypeface, tail)

            is ParsedBlock.Heading -> {
                val font = theme.headingFont(block.level)
                runsTextView(block.runs, images, font.textSizePx(context), font.resolveTypeface(), tail).also {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.isAccessibilityHeading = true
                }
            }

            is ParsedBlock.CodeBlock -> codeBlockView(block.language, block.code)
            is ParsedBlock.BlockMath -> blockMathView(block.segment)
            is ParsedBlock.BlockQuote -> blockQuoteView(block.children, images, tail)
            is ParsedBlock.UnorderedList -> listView(block.items, images, tail, monospacedDigits = false) { "•" }
            is ParsedBlock.OrderedList -> listView(block.items, images, tail, monospacedDigits = true) { "${block.start + it}." }
            is ParsedBlock.Table -> tableView(block.table, images)

            ParsedBlock.ThematicBreak -> View(context).apply {
                // iOS `.separator` 색 = 테마 `inlineCodeBorder`. 높이 1px.
                setBackgroundColor(theme.inlineCodeBorder.resolve(isDark))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
            }
        }
        if (view.layoutParams == null) view.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        return view
    }

    /**
     * 같은 종류의 블록이면 기존 뷰의 내용만 바꾸고 true (iOS `updateBlockInPlace`). 구조(언어·열·행·항목 수)가
     * 달라지면 false — 호출자가 그 블록 하나만 새로 만든다.
     */
    fun updateBlockInPlace(
        view: View,
        previous: ParsedBlock,
        block: ParsedBlock,
        images: Map<MathSegment, RenderedMath>,
        tail: RichMarkdownStreamingOptions?,
    ): Boolean = when {
        previous is ParsedBlock.Paragraph && block is ParsedBlock.Paragraph ->
            (view as? ChipTextView)?.let { configure(it, block.runs, images, bodyPx, bodyTypeface, tail); true } ?: false

        previous is ParsedBlock.Heading && block is ParsedBlock.Heading && previous.level == block.level ->
            (view as? ChipTextView)?.let {
                val font = theme.headingFont(block.level)
                configure(it, block.runs, images, font.textSizePx(context), font.resolveTypeface(), tail)
                true
            } ?: false

        previous is ParsedBlock.CodeBlock && block is ParsedBlock.CodeBlock && previous.language == block.language ->
            updateCodeBlock(view, block.language, block.code)

        previous is ParsedBlock.Table && block is ParsedBlock.Table ->
            updateTable(view, previous.table, block.table, images)

        previous is ParsedBlock.BlockQuote && block is ParsedBlock.BlockQuote -> {
            val row = view as? LinearLayout
            val stack = row?.takeIf { it.childCount == 2 }?.getChildAt(1) as? LinearLayout
            stack != null && updateChildren(stack, previous.children, block.children, images, tail)
        }

        previous is ParsedBlock.UnorderedList && block is ParsedBlock.UnorderedList ->
            updateListItems(view, previous.items, block.items, images, tail)

        previous is ParsedBlock.OrderedList && block is ParsedBlock.OrderedList && previous.start == block.start ->
            updateListItems(view, previous.items, block.items, images, tail)

        else -> false
    }

    /** 자식 수가 같은 컨테이너의 자식을 자리에서 갱신한다. 하나라도 실패하면 false — 호출자가 컨테이너 전체를 새로 만든다. */
    private fun updateChildren(
        stack: LinearLayout,
        previous: List<ParsedBlock>,
        children: List<ParsedBlock>,
        images: Map<MathSegment, RenderedMath>,
        tail: RichMarkdownStreamingOptions?,
    ): Boolean {
        if (previous.size != children.size || stack.childCount != children.size) return false
        for (index in children.indices) {
            val isLast = index == children.lastIndex
            val old = previous[index]
            val new = children[index]
            // 값이 같고 수식도 없는 자식은 바뀔 것이 없다. 마지막 자식은 tail 표시가 바뀔 수 있어 항상 갱신한다.
            if (old == new && !isLast && !containsMath(new)) continue
            if (!updateBlockInPlace(stack.getChildAt(index), old, new, images, if (isLast) tail else null)) return false
        }
        return true
    }

    private fun updateListItems(
        view: View,
        previous: List<List<ParsedBlock>>,
        items: List<List<ParsedBlock>>,
        images: Map<MathSegment, RenderedMath>,
        tail: RichMarkdownStreamingOptions?,
    ): Boolean {
        val list = view as? LinearLayout ?: return false
        if (previous.size != items.size || list.childCount != items.size) return false
        for (index in items.indices) {
            val isLast = index == items.lastIndex
            val old = previous[index]
            val new = items[index]
            if (old == new && !isLast && new.none(::containsMath)) continue
            val row = list.getChildAt(index) as? LinearLayout ?: return false
            val children = row.takeIf { it.childCount == 2 }?.getChildAt(1) as? LinearLayout ?: return false
            if (!updateChildren(children, old, new, images, if (isLast) tail else null)) return false
        }
        return true
    }

    // MARK: - Text

    fun textView(): ChipTextView = ChipTextView(context).apply {
        setTextColor(textColor)
        setChipColors(theme.inlineCodeBackground.resolve(isDark), theme.inlineCodeBorder.resolve(isDark))
    }

    private fun runsTextView(
        runs: List<InlineRun>,
        images: Map<MathSegment, RenderedMath>,
        px: Float,
        typeface: Typeface,
        tail: RichMarkdownStreamingOptions?,
    ): ChipTextView = textView().also { configure(it, runs, images, px, typeface, tail) }

    /** 생성·in-place 갱신이 함께 쓰는 텍스트 블록 구성. tail이면 미닫힌 opener를 숨기고 꼬리를 페이드한다 (iOS `configure`). */
    fun configure(
        view: ChipTextView,
        runs: List<InlineRun>,
        images: Map<MathSegment, RenderedMath>,
        px: Float,
        typeface: Typeface,
        tail: RichMarkdownStreamingOptions?,
    ) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        view.typeface = typeface
        view.text = spans.build(StreamingDisplay.plan(runs, tail, dollarMath), images)
    }

    /** 원문 fallback. 재사용 인스턴스이므로 색·폰트를 매번 현재 값으로 다시 준다 (iOS `fallbackTextView.attributedText = …`). */
    fun configureFallback(view: ChipTextView, markdown: String) {
        view.setTextColor(textColor)
        view.setChipColors(theme.inlineCodeBackground.resolve(isDark), theme.inlineCodeBorder.resolve(isDark))
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx)
        view.typeface = bodyTypeface
        view.text = markdown
        if (view.layoutParams == null) view.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    // MARK: - Code block

    private fun codeBlockView(language: String?, code: String): View {
        val title = TextView(context).apply {
            text = language ?: "code"
            // secondary 회색은 작은 텍스트 대비 기준(4.5:1) 미달 — 테마 텍스트 색을 쓴다 (iOS 동일).
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.codeLabelFont.textSizePx(context))
            typeface = theme.codeLabelFont.resolveTypeface()
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val copy = copyButton(code, "코드 복사")
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(12f), 0)
            setBackgroundColor(theme.codeHeaderBackground.resolve(isDark))
            addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(copy, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        }

        val diagram = codeBlocks.diagramRenderer(language)
        val body: View
        var parts: CodeBlockParts? = null
        if (diagram != null) {
            // 다이어그램 높이는 렌더가 끝나야 정해진다. 셀 self-sizing을 다시 돌린다.
            body = diagram.createView(context, code, theme, onSizeChange)
            body.setBackgroundColor(theme.codeBlockBackground.resolve(isDark))
        } else {
            val text = textView().apply {
                setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, codePx)
                typeface = codeTypeface
            }
            setCode(text, code, language)
            body = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(text)
            }
            parts = CodeBlockParts(text, copy)
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 8f * density // iOS cornerRadius 8
                setColor(theme.codeBlockBackground.resolve(isDark))
            }
            clipToOutline = true
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            tag = parts
        }
    }

    /** 같은 언어의 코드 블록이면 본문·복사 원문·색 범위 요청만 바꾼다 (iOS `updateCodeBlock`). */
    private fun updateCodeBlock(view: View, language: String?, code: String): Boolean {
        val parts = view.tag as? CodeBlockParts ?: return false
        parts.copy.payload = code
        setCode(parts.body, code, language)
        return true
    }

    private fun setCode(view: ChipTextView, code: String, language: String?) {
        view.highlightJob = null
        view.text = code
        applyHighlight(view, code, language)
    }

    /** 색 범위가 도착하면 같은 글자·같은 폰트 위에 색만 덮는다 (iOS `applyHighlight`). 실패·미지원은 plain 유지. */
    private fun applyHighlight(view: ChipTextView, code: String, language: String?) {
        val highlighter = codeBlocks.highlighter ?: return
        if (language == null || code.isEmpty()) return
        val colors = theme.syntax
        view.highlightJob = scope.launch {
            val highlights = try {
                highlighter.spans(code, language)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "하이라이트 실패: $language", e)
                return@launch
            }
            // 뷰가 재사용으로 다른 코드를 담고 있으면 늦게 온 색을 적용하지 않는다.
            if (highlights.isEmpty() || view.text.toString() != code) return@launch
            val spannable = SpannableString(code)
            for (span in highlights) {
                val start = span.range.start.coerceIn(0, code.length)
                val end = span.range.end.coerceIn(start, code.length)
                if (end > start) {
                    spannable.setSpan(
                        ForegroundColorSpan(colors.color(span.kind).resolve(isDark)),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            view.text = spannable
        }
    }

    // MARK: - Block math

    /**
     * 블록 수식은 raster가 아니라 벡터 경로로 그린다 (iOS `blockMathView`). `layout()`이 suspend라 도착 전까지
     * 원문(`segment.source`)을 codeFont로 보이고, 실패(preflight 초과·parse 오류)면 그대로 남긴다.
     */
    private fun blockMathView(segment: MathSegment): View {
        val gravity = when (theme.equationAlignment) {
            LatexEquationAlignment.Leading -> Gravity.START
            LatexEquationAlignment.Center -> Gravity.CENTER_HORIZONTAL
            LatexEquationAlignment.Trailing -> Gravity.END
        }
        val fallback = textView().apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, codePx)
            typeface = codeTypeface
            text = segment.source
        }
        // 정렬은 콘텐츠가 뷰포트보다 좁을 때만 의미가 있다. fillViewport로 slot이 뷰포트를 채우고 넓으면 스크롤한다.
        val slot = FrameLayout(context).apply {
            addView(fallback, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, gravity))
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(slot, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        val key = MathRenderKey(
            latex = segment.latex,
            mathFont = theme.mathFont,
            fontSizePx = bodyPx,
            colorArgb = textColor,
            isDisplay = segment.kind.isDisplay,
        )
        scope.launch {
            val layout = try {
                MathRenderService.shared.layout(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "블록 수식 레이아웃 실패", e)
                null
            } ?: return@launch
            slot.removeAllViews()
            slot.addView(
                BlockMathView(context).apply {
                    mathLayout = layout
                    // 벡터 드로잉은 텍스트로 읽히지 않는다. raster와 같은 표현을 준다.
                    contentDescription = "수식: ${segment.latex}"
                },
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, gravity),
            )
            onSizeChange()
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.TOP)
            addView(scroll, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })
            addView(copyButton(segment.source, "수식 원문 복사"), LinearLayout.LayoutParams(dp(48f), dp(48f)))
        }
    }

    // MARK: - Containers

    private fun blockQuoteView(
        children: List<ParsedBlock>,
        images: Map<MathSegment, RenderedMath>,
        tail: RichMarkdownStreamingOptions?,
    ): View {
        val bar = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2f * density // iOS cornerRadius 2
                setColor(theme.quoteBar.resolve(isDark))
            }
        }
        val stack = verticalStack(8f, children.mapIndexed { index, child ->
            blockView(child, images, if (index == children.lastIndex) tail else null)
        })
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // iOS bar width 4pt, spacing 8.
            addView(bar, LinearLayout.LayoutParams(dp(4f), MATCH_PARENT).apply { marginEnd = dp(8f) })
            addView(stack, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    private fun listView(
        items: List<List<ParsedBlock>>,
        images: Map<MathSegment, RenderedMath>,
        tail: RichMarkdownStreamingOptions?,
        monospacedDigits: Boolean,
        marker: (Int) -> String,
    ): View = verticalStack(4f, items.mapIndexed { index, item ->
        listRow(marker(index), monospacedDigits, item, images, if (index == items.lastIndex) tail else null)
    })

    private fun listRow(
        marker: String,
        monospacedDigits: Boolean,
        item: List<ParsedBlock>,
        images: Map<MathSegment, RenderedMath>,
        tail: RichMarkdownStreamingOptions?,
    ): View {
        val label = TextView(context).apply {
            text = marker
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx)
            typeface = bodyTypeface
            if (monospacedDigits) fontFeatureSettings = "tnum" // iOS monospacedDigitVariant
        }
        val children = verticalStack(4f, item.mapIndexed { index, child ->
            blockView(child, images, if (index == item.lastIndex) tail else null)
        })
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // ponytail: 중첩 스택은 baseline이 불안정하다. top 정렬로 고정한다 (iOS 알려진 제약 동일).
            gravity = Gravity.TOP
            addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8f) })
            addView(children, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    private fun verticalStack(spacingDp: Float, views: List<View>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setSpacing(dp(spacingDp))
        views.forEach { addView(it) }
    }

    // MARK: - Table

    private fun tableView(table: ParsedTable, images: Map<MathSegment, RenderedMath>): View {
        val rows = listOf(table.header) + table.rows
        val borderColor = StreamingDisplay.withAlpha(textColor, 0.2) // iOS textColor alpha 0.2, 0.5pt → 1px
        val columnCount = rows.maxOfOrNull { it.size } ?: 0
        val cells = rows.mapIndexed { rowIndex, cellRuns ->
            cellRuns.mapIndexed { column, runs ->
                tableCell(rowIndex, column, runs, table.columnAlignments.getOrNull(column), images, borderColor)
            }
        }
        val tableLayout = TableLayout(context).apply {
            cells.forEach { row ->
                addView(
                    TableRow(context).apply {
                        // 셀 높이를 행에 맞춘다 — 수식이 든 셀과 텍스트 셀의 높이가 달라 테두리가 어긋나던 결함(데모 실측).
                        row.forEach { cell ->
                            addView(cell, TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.MATCH_PARENT))
                        }
                    },
                )
            }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                @Suppress("DEPRECATION")
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(rows.size, columnCount, false)
                }
            }
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tableLayout)
            tag = TableParts(cells)
        }
    }

    private fun tableCell(
        rowIndex: Int,
        column: Int,
        runs: List<InlineRun>,
        alignment: ParsedTable.ColumnAlignment?,
        images: Map<MathSegment, RenderedMath>,
        borderColor: Int,
    ): ChipTextView = textView().apply {
        configure(this, if (rowIndex == 0) runs.map { it.copy(bold = true) } else runs, images, bodyPx, bodyTypeface, null)
        gravity = Gravity.CENTER_VERTICAL or when (alignment) {
            ParsedTable.ColumnAlignment.Center -> Gravity.CENTER_HORIZONTAL
            ParsedTable.ColumnAlignment.Right -> Gravity.END
            ParsedTable.ColumnAlignment.Left, null -> Gravity.START
        }
        setPadding(dp(10f), dp(8f), dp(10f), dp(8f)) // iOS textContainerInset 8/10
        minWidth = dp(96f) // iOS 열 폭 clamp 96…240pt
        maxWidth = dp(240f)
        background = GradientDrawable().apply {
            setStroke(1, borderColor)
            if (rowIndex == 0) setColor(theme.codeHeaderBackground.resolve(isDark))
        }
        if (rowIndex == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            @Suppress("DEPRECATION")
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.collectionItemInfo =
                    AccessibilityNodeInfo.CollectionItemInfo.obtain(rowIndex, 1, column, 1, rowIndex == 0)
            }
        }
    }

    /** 열 수·행 수·정렬이 같은 표면 내용이 바뀐 셀만 다시 채운다 (iOS `updateTable`). 행이 늘어나는 tick은 false. */
    private fun updateTable(view: View, previous: ParsedTable, table: ParsedTable, images: Map<MathSegment, RenderedMath>): Boolean {
        val parts = view.tag as? TableParts ?: return false
        val rows = listOf(table.header) + table.rows
        val previousRows = listOf(previous.header) + previous.rows
        if (previous.columnAlignments != table.columnAlignments) return false
        if (rows.size != previousRows.size || parts.cells.size != rows.size) return false
        if (rows.indices.any { rows[it].size != previousRows[it].size || rows[it].size != parts.cells[it].size }) return false

        for ((rowIndex, cells) in rows.withIndex()) {
            for ((column, runs) in cells.withIndex()) {
                if (runs == previousRows[rowIndex][column] && !containsMath(runs)) continue
                configure(
                    parts.cells[rowIndex][column],
                    if (rowIndex == 0) runs.map { it.copy(bold = true) } else runs,
                    images,
                    bodyPx,
                    bodyTypeface,
                    null,
                )
            }
        }
        return true
    }

    // MARK: - Copy button

    /** 48dp 히트 타깃(iOS 44pt → Material 48dp). 체크 표시는 다시 누를 때까지 유지한다 (iOS 동일 규칙). */
    private fun copyButton(payload: String, description: String): CopyButton = CopyButton(context).apply {
        this.payload = payload
        contentDescription = description
        setImageResource(R.drawable.richmarkdown_ic_copy)
        imageTintList = ColorStateList.valueOf(textColor)
        scaleType = ImageView.ScaleType.CENTER
        minimumWidth = dp(48f)
        minimumHeight = dp(48f)
        val ripple = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
            setBackgroundResource(ripple.resourceId)
        } else {
            background = null
        }
        setOnClickListener {
            // 생성 시점의 원문을 캡처하지 않는다 — 스트리밍 in-place 갱신은 `payload`만 바꾼다.
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("RichMarkdown", this.payload))
            setImageResource(R.drawable.richmarkdown_ic_check)
        }
    }
}
