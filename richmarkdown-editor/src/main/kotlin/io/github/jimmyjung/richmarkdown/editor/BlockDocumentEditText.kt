// Author: JunyoungJung
// Date: 2026-10-08

package io.github.jimmyjung.richmarkdown.editor

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.text.style.ParagraphStyle
import android.text.style.SuggestionSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import io.github.jimmyjung.richmarkdown.MathRenderKey
import io.github.jimmyjung.richmarkdown.MathRenderService
import io.github.jimmyjung.richmarkdown.RichMarkdownTheme
import io.github.jimmyjung.richmarkdown.resolveTypeface
import io.github.jimmyjung.richmarkdown.resolvedTextColor
import io.github.jimmyjung.richmarkdown.textSizePx
import io.github.jimmyjung.richmarkdown.view.InlineCodeChipPainter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 논리 블록 전체를 `EditText` 문서 하나로 투영하는 편집 뷰. iOS `BlockDocumentUITextView` + `BlockDocumentTextEditor.Coordinator`.
 *
 * 앱이 [BlockEditorModel]을 소유한다. 뷰는 [setState]로 블록·선택을 받고, 사용자 편집을 연속 문서 UTF-16 범위 교체로 바꿔
 * [onReplaceText]로 보낸다. 내부에 두 번째 모델을 두지 않는다. Compose에서는 `compose.BlockDocumentTextEditor`를 쓴다.
 *
 * **호스트 계약**: 편집 콜백([onReplaceText]·[onReplaceDocumentBlocks]) 안에서 모델을 바꿨으면 같은 호출 안에서 [setState]로
 * 새 상태를 넘긴다. 새 상태 없이 콜백이 끝나면 모델이 편집을 반영하지 않은 것으로 보고 마지막 상태로 다시 그린다.
 *
 * iOS와 다른 점 (의도적):
 * 1. 표시 문자열이 `documentText`와 정확히 같다(U+2063 보충 문자 없음). 수식은 원문 위 `ReplacementSpan`이라 원문 전환이 span 교체다.
 * 2. 편집 콜백마다 최신 상태로 다시 그린다 — 모델이 무시·변형한 편집(빈 문단 Enter, 코드 블록 뒤 Backspace, null 반환)에서도
 *    화면이 모델 문서와 같다. iOS는 이 경우 화면과 모델이 어긋날 수 있다.
 * 3. IME는 `InputConnection`을 감싸 commit·조합 종료·삭제 직후 동기화한다(commit은 텍스트가 그대로일 수 있어 TextWatcher만으로 부족).
 *    조합 중에는 모델 알림·재스타일·텍스트 교체를 하지 않고 확정 시 조합 시작 범위로 한 번 보낸다.
 * 4. 전체 문서 복사의 블록 payload는 `ClipDescription` extras + [BLOCK_DOCUMENT_MIME_TYPE]로 싣는다.
 * 5. EditText 자체 undo는 쓰지 않는다 — Ctrl+Z·Ctrl+Shift+Z·메뉴 undo/redo는 [EditorToolbarAction.Undo]·[EditorToolbarAction.Redo]로 보낸다.
 *
 * 기본 padding은 iOS `textContainerInset`(16, 16, 96, 16)에 맞춘 좌·위·우 16dp, 아래 96dp다. 앱이 `setPadding`으로 바꾼다.
 * 텍스트는 인스턴스 상태로 저장하지 않는다(모델이 원본이다).
 */
class BlockDocumentEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    // MARK: - 호스트 API

    /** 문서 범위 교체 콜백. 반환값은 새 선택(caret). null이면 모델이 편집을 거절한 것이다. */
    var onReplaceText: (EditorRange, String) -> EditorRange? = { _, _ -> null }

    /** 사용자가 바꾼 선택(UTF-16 문서 범위). */
    var onSelectionChange: (EditorRange) -> Unit = {}

    /** 도구 모음 명령과 그 시점의 선택. 실행은 앱이 한다. */
    var onToolbarAction: (EditorToolbarAction, EditorRange) -> Unit = { _, _ -> }

    /** 블록 payload 붙여넣기. null이거나 null을 반환하면 일반 텍스트로 붙여넣는다. */
    var onReplaceDocumentBlocks: ((EditorRange, List<EditorBlock>) -> EditorRange?)? = null

    /** 앱 도구 모음. 지정하면 명령 전달 함수를 `bind`로 받고 선택 블록이 바뀔 때마다 `update`를 받는다. */
    var inputAccessory: BlockEditorInputAccessory? = null
        set(value) {
            field = value
            value?.bind(::performToolbarAction)
            updateAccessory(state.selection)
        }

    /**
     * 블록·선택·옵션을 반영한다. iOS `BlockDocumentTextEditor`의 매개변수와 같다.
     * IME 조합 중이면 확정 뒤에 반영한다. [sourceMarkdown]은 전체 선택 복사 때 일반 텍스트로 싣는 원문(보통 `model.markdown`)이다.
     */
    fun setState(
        blocks: List<EditorBlock>,
        selection: EditorRange?,
        canUndo: Boolean,
        canRedo: Boolean,
        parsesDollarMath: Boolean = false,
        theme: RichMarkdownTheme = RichMarkdownTheme.Default,
        alignment: BlockAlignmentConfiguration = BlockAlignmentConfiguration.Default,
        sourceMarkdown: String? = null,
    ) {
        state = HostState(blocks, selection, canUndo, canRedo, parsesDollarMath, theme, alignment, sourceMarkdown)
        updateAccessory(selection)
        if (isInHostCallback) {
            stateChangedInCallback = true
            return
        }
        if (isComposing()) {
            renderAfterComposition = true
            return
        }
        if (syncScheduled) {
            // 아직 보내지 않은 사용자 편집을 먼저 보낸다. 그 결과 상태가 다음 setState로 오면 이 상태는 낡았다.
            syncNow()
            if (awaitingHostState) return
        }
        awaitingHostState = false
        val requested = DocumentEdits.clamped(selection ?: currentSelection(), documentLength(blocks))
        // 화면이 모델 문서와 다르면(모델이 무시·변형한 편집 뒤 Compose 재구성 등) 키가 같아도 다시 그린다.
        if (renderKey(requested) != lastRenderKey || !text.contentEquals(blocks.joinToString("\n") { it.text })) {
            render(requested)
        } else if (currentSelection() != requested) {
            applySelection(requested)
        }
    }

    /**
     * 도구 모음 명령을 현재 선택과 함께 [onToolbarAction]으로 보낸다. iOS `Coordinator.handleToolbarAction`.
     * 한글 등 IME 조합 중이면 무시하고 안내를 읽는다. [EditorToolbarAction.Done]은 포커스를 놓고 키보드를 닫는다.
     */
    fun performToolbarAction(action: EditorToolbarAction) {
        if (isComposing()) {
            @Suppress("DEPRECATION")
            announceForAccessibility(COMPOSING_ANNOUNCEMENT)
            return
        }
        syncNow()
        val range = currentSelection()
        if (action == EditorToolbarAction.Done) {
            clearFocus()
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        }
        onToolbarAction(action, range)
    }

    // MARK: - 상태

    private data class HostState(
        val blocks: List<EditorBlock>,
        val selection: EditorRange?,
        val canUndo: Boolean,
        val canRedo: Boolean,
        val parsesDollarMath: Boolean,
        val theme: RichMarkdownTheme,
        val alignment: BlockAlignmentConfiguration,
        val sourceMarkdown: String?,
    )

    /** 이 값이 같으면 다시 스타일링할 필요가 없다 (iOS `last*` 비교 묶음). */
    private data class RenderKey(
        val blocks: List<EditorBlock>,
        val editingEquationIds: Set<UUID>,
        val editingInlineMathRanges: List<EditorRange>,
        val parsesDollarMath: Boolean,
        val theme: RichMarkdownTheme,
        val alignment: BlockAlignmentConfiguration,
        val isDark: Boolean,
        val contentWidthPx: Int,
    )

    private var state = HostState(emptyList(), null, false, false, false, RichMarkdownTheme.Default, BlockAlignmentConfiguration.Default, null)

    /** Compose 래퍼처럼 상태를 콜백 뒤(다음 재구성)에 넘기는 호스트. 이때는 콜백 직후 다시 그리지 않고 다음 [setState]를 기다린다. */
    internal var hostUpdatesAfterCallback = false

    /** 마지막으로 그린 문서 문자열. 사용자 편집은 이 문자열 기준 범위로 환원한다. iOS `baselineText`. */
    private var baselineText = ""
    private var lastRenderKey: RenderKey? = null
    private var lastEditingEquationIds = emptySet<UUID>()
    private var lastEditingInlineMathRanges = emptyList<EditorRange>()
    private var lastCommittedSelection = EditorRange(0, 0)
    private var lastReportedSelection: EditorRange? = null
    private var appliedSpans: Array<Any> = emptyArray()
    private var hasDisplayMath = false

    private var isApplyingUpdate = false
    private var isInHostCallback = false
    private var stateChangedInCallback = false
    private var awaitingHostState = false
    private var renderAfterComposition = false

    /** TextWatcher가 본 baseline 이후 변경. 한 번뿐일 때만 실제 편집 범위로 믿는다 (iOS `pendingTextChange`). */
    private var pendingChange: EditorRange? = null
    private var pendingChangeCount = 0
    private var compositionStarted = false
    private var compositionRange: EditorRange? = null

    /** IME batch·호출 깊이. 0일 때만 동기화한다(조합 도중 Editable 교체 금지). */
    private var imeEditDepth = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var syncScheduled = false
    private val syncRunnable = Runnable {
        syncScheduled = false
        syncFromView()
    }
    private var restyleScheduled = false
    private val restyleRunnable = Runnable {
        restyleScheduled = false
        restyleIfIdle()
    }

    /** 수식 raster 요청 수명. attach 동안만 살아 있다 (RichMarkdownView 패턴). */
    private var scope: CoroutineScope? = null

    /** 진행 중이거나 실패한 raster key. 실패 key는 attach 동안 다시 요청하지 않는다. */
    private val requestedMath = HashSet<MathRenderKey>()

    private var isDark = context.isNightMode()
    private val chipPainter = InlineCodeChipPainter(resources.displayMetrics.density)
    private var chipFill = 0
    private var chipBorder = 0
    private var chipTextBounds: Paint.FontMetricsInt? = null
    private var typingKey: Triple<Float, Int, Any>? = null

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (isApplyingUpdate) return
            pendingChange = EditorRange(start, before)
            pendingChangeCount += 1
        }

        override fun afterTextChanged(s: Editable) {
            if (isApplyingUpdate) return
            // IME 호출 안의 변경은 호출이 끝날 때 동기화한다. 하드웨어 키·잘라내기 등은 다음 메시지에서 한 번에 처리한다.
            if (imeEditDepth == 0) scheduleSync()
        }
    }

    /** TextView 생성자가 override를 부르는 동안에는 필드가 아직 없다. */
    private var ready = false

    init {
        MathRenderService.shared.ensureFontsLoaded(context)
        val density = resources.displayMetrics.density
        background = null
        setPadding((16 * density).roundToInt(), (16 * density).roundToInt(), (16 * density).roundToInt(), (96 * density).roundToInt())
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        tag = VIEW_TAG
        addTextChangedListener(watcher)
        ready = true
        render(EditorRange(0, 0))
    }

    // MARK: - 사용자 편집 → 모델 (iOS Coordinator)

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!ready || isApplyingUpdate) return
        if (imeEditDepth == 0) scheduleSync()
    }

    private fun scheduleSync() {
        if (syncScheduled) return
        syncScheduled = true
        mainHandler.post(syncRunnable)
    }

    private fun syncNow() {
        if (syncScheduled) {
            mainHandler.removeCallbacks(syncRunnable)
            syncScheduled = false
        }
        syncFromView()
    }

    /** iOS `reconcileTextChange` + `textViewDidChangeSelection`. */
    private fun syncFromView() {
        if (!ready || isApplyingUpdate || isInHostCallback) return
        if (isComposing()) {
            // 조합 시작 범위(baseline 기준)를 한 번만 기억한다. 조합 도중에는 모델·스타일을 건드리지 않는다.
            if (!compositionStarted) {
                compositionStarted = true
                compositionRange = pendingChange.takeIf { pendingChangeCount == 1 }
            }
            pendingChange = null
            pendingChangeCount = 0
            return
        }
        val current = text.toString()
        if (current != baselineText) {
            reconcile(current)
        } else {
            clearPendingChange()
            if (renderAfterComposition && !awaitingHostState) render(state.selection ?: currentSelection())
        }
        syncSelection()
    }

    private fun reconcile(current: String) {
        val change = compositionRange?.let { DocumentEdits.anchored(baselineText, current, it) }
            ?: pendingChange?.takeIf { pendingChangeCount == 1 }?.let { DocumentEdits.anchored(baselineText, current, it) }
            ?: DocumentEdits.diff(baselineText, current)
        clearPendingChange()
        baselineText = current
        val fallbackSelection = lastCommittedSelection
        val result = callHost { onReplaceText(change.range, change.replacement) }
        val selection = result ?: state.selection.takeIf { stateChangedInCallback } ?: fallbackSelection
        if (stateChangedInCallback || !hostUpdatesAfterCallback) {
            // 모델이 편집을 그대로 받았든 무시·변형했든 최신 상태로 다시 그려 화면 == 모델 문서를 지킨다.
            awaitingHostState = false
            render(selection)
        } else {
            awaitingHostState = true
            applySelection(DocumentEdits.clamped(selection, current.length))
        }
    }

    private fun syncSelection() {
        val selection = currentSelection()
        if (selection == lastReportedSelection) return
        var reported = selection
        if (!awaitingHostState) {
            val editingIds = equationBlockIds(state.blocks, selection)
            val editingInline = MarkdownStyler.inlineMathRanges(state.blocks, selection, state.parsesDollarMath)
            if (editingIds != lastEditingEquationIds || editingInline != lastEditingInlineMathRanges) {
                render(selection)
                reported = currentSelection()
            }
        }
        lastCommittedSelection = reported
        lastReportedSelection = reported
        updateAccessory(reported)
        onSelectionChange(reported)
    }

    private inline fun <T> callHost(block: () -> T): T {
        isInHostCallback = true
        stateChangedInCallback = false
        try {
            return block()
        } finally {
            isInHostCallback = false
        }
    }

    private fun clearPendingChange() {
        pendingChange = null
        pendingChangeCount = 0
        compositionStarted = false
        compositionRange = null
    }

    private fun isComposing(): Boolean {
        val editable = text ?: return false
        return BaseInputConnection.getComposingSpanStart(editable) != -1
    }

    private fun currentSelection(): EditorRange {
        val start = selectionStart
        val end = selectionEnd
        if (start < 0 || end < 0) return EditorRange(0, 0)
        return EditorRange(min(start, end), abs(end - start))
    }

    // MARK: - 모델 → 화면

    private fun renderKey(selection: EditorRange): RenderKey = RenderKey(
        blocks = state.blocks,
        editingEquationIds = equationBlockIds(state.blocks, selection),
        editingInlineMathRanges = MarkdownStyler.inlineMathRanges(state.blocks, selection, state.parsesDollarMath),
        parsesDollarMath = state.parsesDollarMath,
        theme = state.theme,
        alignment = state.alignment,
        isDark = isDark,
        contentWidthPx = contentWidthPx(),
    )

    /**
     * 최신 상태로 문서를 그린다. iOS `applyDocumentStyle`. 텍스트가 같으면 span만 바꿔 IME 상태를 지키고,
     * 다르면 Editable 전체를 교체한다. 수식을 편집 중이면 선택을 문자 경계로 맞춘다.
     * ponytail: iOS처럼 편집마다 문서 전체를 다시 스타일링한다(블록 수에 비례). 긴 문서에서 느리면 블록 단위 span diff로 바꾼다.
     */
    private fun render(requestedSelection: EditorRange) {
        if (isComposing()) {
            renderAfterComposition = true
            return
        }
        val blocks = state.blocks
        val requested = DocumentEdits.clamped(requestedSelection, documentLength(blocks))
        val key = renderKey(requested)
        val selection = sourceAlignedSelection(requested, blocks, key.editingEquationIds, key.editingInlineMathRanges)
        val missingMath = ArrayList<MathRenderKey>()
        val styled = MarkdownStyler.styledDocument(
            context = context,
            blocks = blocks,
            editingEquationIds = key.editingEquationIds,
            parsesDollarMath = state.parsesDollarMath,
            theme = state.theme,
            alignment = state.alignment,
            selection = selection,
            isDark = isDark,
            contentWidthPx = key.contentWidthPx,
            mathImage = { mathKey -> MathRenderService.shared.cachedImage(mathKey) ?: null.also { missingMath += mathKey } },
        )
        isApplyingUpdate = true
        beginBatchEdit()
        try {
            replaceContent(styled)
            applyTypingDefaults()
            setSelection(selection.location, selection.end)
        } finally {
            endBatchEdit()
            isApplyingUpdate = false
        }
        baselineText = styled.toString()
        clearPendingChange()
        renderAfterComposition = false
        lastRenderKey = key
        lastEditingEquationIds = key.editingEquationIds
        lastEditingInlineMathRanges = key.editingInlineMathRanges
        lastCommittedSelection = selection
        lastReportedSelection = selection
        invalidate()
        requestMath(missingMath)
    }

    private fun replaceContent(styled: SpannableStringBuilder) {
        val editable = text
        for (span in appliedSpans) editable.removeSpan(span)
        // 드래그·IME가 넣은 외부 서식은 모델에 없으므로 지운다. 맞춤법 제안(SuggestionSpan)은 남긴다.
        for (span in editable.getSpans(0, editable.length, Any::class.java)) {
            if (span is ParagraphStyle || span is CharacterStyle && span !is SuggestionSpan) editable.removeSpan(span)
        }
        val spans = styled.getSpans(0, styled.length, Any::class.java)
        if (editable.toString() == styled.toString()) {
            for (span in spans) editable.setSpan(span, styled.getSpanStart(span), styled.getSpanEnd(span), styled.getSpanFlags(span))
        } else {
            editable.replace(0, editable.length, styled)
        }
        appliedSpans = spans
        hasDisplayMath = spans.any { it is DisplayMathSpan }
    }

    /** 끝 빈 문단과 조합 중인 글자가 쓰는 EditText 기본 모양 = 본문 글꼴·색 (iOS `typingAttributes`의 문단 기본값). */
    private fun applyTypingDefaults() {
        val theme = state.theme
        val sizePx = theme.bodyFont.textSizePx(context)
        val color = theme.resolvedTextColor(isDark)
        val key = Triple(sizePx, color, theme.bodyFont)
        if (key != typingKey) {
            typingKey = key
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
            typeface = theme.bodyFont.resolveTypeface()
            setTextColor(color)
        }
        chipFill = theme.inlineCodeBackground.resolve(isDark)
        chipBorder = theme.inlineCodeBorder.resolve(isDark)
        chipTextBounds = TextPaint().apply {
            typeface = theme.codeFont.resolveTypeface()
            textSize = theme.codeFont.textSizePx(context)
        }.fontMetricsInt
    }

    private fun applySelection(selection: EditorRange) {
        isApplyingUpdate = true
        try {
            setSelection(selection.location, selection.end)
        } finally {
            isApplyingUpdate = false
        }
        lastCommittedSelection = selection
        lastReportedSelection = selection
    }

    private fun contentWidthPx(): Int = if (width > 0) max(width - totalPaddingLeft - totalPaddingRight, 0) else 0

    private fun scheduleRestyle() {
        if (restyleScheduled) return
        restyleScheduled = true
        mainHandler.post(restyleRunnable)
    }

    /** 수식 raster 도착·크기·설정 변경 뒤 다시 그린다. 처리 중인 사용자 편집이 있으면 그 처리의 렌더에 맡긴다. */
    private fun restyleIfIdle() {
        if (!ready || isInHostCallback || awaitingHostState || syncScheduled || isComposing()) return
        if (text.toString() != baselineText) return
        render(currentSelection())
    }

    private fun requestMath(keys: List<MathRenderKey>) {
        val scope = scope ?: return
        for (key in keys) {
            if (!requestedMath.add(key)) continue
            scope.launch {
                val image = MathRenderService.shared.render(key)
                if (image != null) {
                    requestedMath.remove(key)
                    scheduleRestyle()
                }
            }
        }
    }

    private fun updateAccessory(selection: EditorRange?) {
        val accessory = inputAccessory ?: return
        val blocks = state.blocks
        val block = selection?.let { blockAt(blocks, it.location) } ?: blocks.firstOrNull()
        accessory.update(block?.kind ?: EditorBlockKind.Paragraph, state.canUndo, state.canRedo)
    }

    /** 호스트 콜백을 끊는다. Compose `onRelease`에서 부른다. */
    internal fun releaseHost() {
        onReplaceText = { _, _ -> null }
        onSelectionChange = {}
        onToolbarAction = { _, _ -> }
        onReplaceDocumentBlocks = null
        inputAccessory = null
    }

    // MARK: - View 수명·그리기

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // detach 동안 요청하지 못한 수식을 다시 찾는다.
        scheduleRestyle()
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        requestedMath.clear()
        imeEditDepth = 0
        super.onDetachedFromWindow()
    }

    /** 다크 모드·글꼴 배율 변화. iOS trait change. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isDark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        typingKey = null
        restyleIfIdle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 블록 수식 축소 폭이 바뀐다.
        if (w != oldw && hasDisplayMath) scheduleRestyle()
    }

    override fun onDraw(canvas: Canvas) {
        chipPainter.draw(canvas, this, chipFill, chipBorder, chipTextBounds)
        super.onDraw(canvas)
    }

    override fun getFreezesText(): Boolean = false

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // iOS accessibilityLabel "문서 편집기". 본문 낭독을 가리지 않도록 contentDescription 대신 hint로 단다.
        if (info.hintText.isNullOrEmpty()) info.hintText = ACCESSIBILITY_HINT
    }

    // MARK: - IME

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        imeEditDepth = 0
        return ReconcilingInputConnection(connection)
    }

    /**
     * IME 호출이 끝날 때마다(batch 바깥에서) 동기화한다. `commitText`는 조합 문자열과 같은 글자를 확정하면 텍스트가 바뀌지 않아
     * TextWatcher가 울리지 않으므로 이 경로가 조합 종료를 알아챈다.
     */
    private inner class ReconcilingInputConnection(target: InputConnection) : InputConnectionWrapper(target, true) {
        override fun beginBatchEdit(): Boolean {
            imeEditDepth += 1
            return super.beginBatchEdit()
        }

        override fun endBatchEdit(): Boolean {
            val result = super.endBatchEdit()
            leave()
            return result
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = edit { super.commitText(text, newCursorPosition) }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            edit { super.setComposingText(text, newCursorPosition) }

        override fun setComposingRegion(start: Int, end: Int): Boolean = edit { super.setComposingRegion(start, end) }

        override fun finishComposingText(): Boolean = edit { super.finishComposingText() }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            edit { super.deleteSurroundingText(beforeLength, afterLength) }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            edit { super.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }

        override fun setSelection(start: Int, end: Int): Boolean = edit { super.setSelection(start, end) }

        override fun sendKeyEvent(event: KeyEvent?): Boolean = edit { super.sendKeyEvent(event) }

        override fun performContextMenuAction(id: Int): Boolean = edit { super.performContextMenuAction(id) }

        private inline fun edit(block: () -> Boolean): Boolean {
            imeEditDepth += 1
            try {
                return block()
            } finally {
                leave()
            }
        }

        private fun leave() {
            imeEditDepth = max(imeEditDepth - 1, 0)
            if (imeEditDepth == 0) syncNow()
        }
    }

    // MARK: - 단축키·메뉴·클립보드

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_Z && event.hasModifiers(KeyEvent.META_CTRL_ON)) {
            performToolbarAction(EditorToolbarAction.Undo)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_Z && event.hasModifiers(KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)) {
            performToolbarAction(EditorToolbarAction.Redo)
            return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        when (id) {
            android.R.id.undo -> {
                performToolbarAction(EditorToolbarAction.Undo)
                return true
            }
            android.R.id.redo -> {
                performToolbarAction(EditorToolbarAction.Redo)
                return true
            }
            android.R.id.copy -> {
                syncNow()
                val copiesWholeDocument = currentSelection() == EditorRange(0, text.length)
                val handled = super.onTextContextMenuItem(id)
                if (copiesWholeDocument) copyBlockDocument()
                return handled
            }
            android.R.id.paste -> {
                syncNow()
                if (pasteBlockDocument()) return true
                // 외부 span이 들어오지 않도록 항상 일반 텍스트로 붙여넣는다.
                return super.onTextContextMenuItem(android.R.id.pasteAsPlainText)
            }
        }
        return super.onTextContextMenuItem(id)
    }

    /** 전체 선택 복사: 일반 텍스트 = 앱이 준 Markdown, 가능하면 블록 payload도 싣는다. iOS `BlockDocumentUITextView.copy`. */
    private fun copyBlockDocument() {
        val markdown = state.sourceMarkdown ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val payload = BlockDocumentPasteboardPayload.encode(state.blocks)
        val mimeTypes = if (payload == null) {
            arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)
        } else {
            arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN, BLOCK_DOCUMENT_MIME_TYPE)
        }
        val description = ClipDescription(CLIP_LABEL, mimeTypes)
        if (payload != null) {
            description.extras = PersistableBundle().apply { putString(BLOCK_DOCUMENT_EXTRA, payload.decodeToString()) }
        }
        clipboard.setPrimaryClip(ClipData(description, ClipData.Item(markdown)))
    }

    /** 블록 payload가 있고 앱이 받아들이면 true. iOS `BlockDocumentUITextView.paste` + `Coordinator.replaceDocumentBlocks`. */
    private fun pasteBlockDocument(): Boolean {
        val handler = onReplaceDocumentBlocks ?: return false
        if (isComposing()) return false
        val description = context.getSystemService(ClipboardManager::class.java)?.primaryClipDescription ?: return false
        if (!description.hasMimeType(BLOCK_DOCUMENT_MIME_TYPE)) return false
        val json = description.extras?.getString(BLOCK_DOCUMENT_EXTRA) ?: return false
        val blocks = BlockDocumentPasteboardPayload.decode(json.encodeToByteArray()) ?: return false
        clearPendingChange()
        val range = currentSelection()
        val result = callHost { handler(range, blocks) } ?: return false
        if (stateChangedInCallback) {
            awaitingHostState = false
            render(result)
        }
        return true
    }

    companion object {
        /** 블록 payload를 실은 클립의 MIME 형식. iOS pasteboard type `com.richmarkdown.block-document`. */
        const val BLOCK_DOCUMENT_MIME_TYPE = "application/vnd.richmarkdown.block-document+json"

        /** `ClipDescription.extras`에서 payload JSON 문자열을 담는 키. */
        const val BLOCK_DOCUMENT_EXTRA = "io.github.jimmyjung.richmarkdown.block-document"

        /** 테스트·UI 자동화가 찾는 뷰 tag. iOS `accessibilityIdentifier` "blockDocumentTextView". */
        const val VIEW_TAG = "blockDocumentTextView"

        private const val CLIP_LABEL = "RichMarkdown document"
        private const val ACCESSIBILITY_HINT = "문서 편집기"
        private const val COMPOSING_ANNOUNCEMENT = "한글 입력을 완료한 후 편집 도구를 사용하세요"
    }
}
