// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.demo

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.jimmyjung.richmarkdown.LatexDollarMathOptions
import io.github.jimmyjung.richmarkdown.RichMarkdownCodeBlockOptions
import io.github.jimmyjung.richmarkdown.RichMarkdownStreamingOptions
import io.github.jimmyjung.richmarkdown.RichMarkdownStreamingTextBuffer
import io.github.jimmyjung.richmarkdown.RichMarkdownView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * View 채팅 (iOS `UIKitChatDemo`). RecyclerView 셀 안에 [RichMarkdownView]를 직접 둔다.
 *
 * iOS 데모의 메시지 ID별 뷰 캐시(`AssistantMessageViewCache`)는 옮기지 않았다 — Android는 스트리밍 append에서
 * 이전 렌더를 유지하고 완성 답변은 ParseCache가 재파싱을 막으므로, 셀 재사용 시 `markdown`만 다시 넣는다.
 * 첫 진입은 대화의 시작을 보여주고, 재생 중에는 마지막 버블을 따라간다.
 */
class ViewChatActivity : AppCompatActivity() {

    /** 어댑터 행. 스트리밍 여부를 diff에 넣어 스트림 종료 시 마지막 셀이 다시 바인딩된다. */
    data class Row(
        val message: ChatMessage,
        val streaming: Boolean,
        val parsesDollarMath: Boolean,
        val showsCaseLabels: Boolean,
    )

    private val buffer by lazy { RichMarkdownStreamingTextBuffer(lifecycleScope) }
    private lateinit var adapter: ChatAdapter
    private lateinit var recycler: RecyclerView
    private var replayJob: Job? = null
    private var replayText: String? = null
    private var isStreaming = false
    private var parsesDollarMath = false
    private var showsCaseLabels = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parsesDollarMath = savedInstanceState?.getBoolean("parsesDollarMath") ?: false
        showsCaseLabels = savedInstanceState?.getBoolean("showsCaseLabels") ?: true
        adapter = ChatAdapter(demoCodeBlocks(this))
        adapter.onContentSizeChange = {
            // 스트리밍 중 수식 hydration·다이어그램 렌더로 마지막 셀이 커지면 바닥을 유지한다.
            if (isStreaming) recycler.scrollToPosition(adapter.itemCount - 1)
        }
        setContentView(buildLayout())
        lifecycleScope.launch {
            buffer.text.collect { text ->
                if (replayText != null) {
                    replayText = text
                    publish()
                }
            }
        }
        publish()
        if (intent.getBooleanExtra(EXTRA_AUTOPLAY, false)) replay()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isNight()) 0xFF000000.toInt() else 0xFFF2F2F7.toInt())
        }
        // 시스템 바 아래로 콘텐츠가 깔리지 않게 한다 (targetSdk 35+ edge-to-edge).
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val header = Toolbar(this).apply {
            title = "AI 챗봇"
            setTitleTextColor(if (isNight()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
            navigationIcon = DrawerArrowDrawable(context).apply {
                progress = 1f
                color = if (root.isNight()) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
            }
            navigationContentDescription = "뒤로 가기"
            setNavigationOnClickListener { finish() }
            menu.add("$ 수식").apply {
                isCheckable = true
                isChecked = parsesDollarMath
                setOnMenuItemClickListener {
                    parsesDollarMath = !parsesDollarMath
                    isChecked = parsesDollarMath
                    publish()
                    true
                }
            }
            menu.add("케이스 라벨").apply {
                isCheckable = true
                isChecked = showsCaseLabels
                setOnMenuItemClickListener {
                    showsCaseLabels = !showsCaseLabels
                    isChecked = showsCaseLabels
                    publish()
                    true
                }
            }
            menu.add(R.string.action_replay).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener {
                    replay()
                    true
                }
            }
        }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ViewChatActivity.adapter
            // 기본 change 애니메이션은 갱신마다 새 ViewHolder를 만들어 이전 홀더와 교차 페이드한다 — 스트리밍 중
            // 같은 셀이 겹쳐 그려지고 RichMarkdownView의 append 증분 렌더도 버려진다. 같은 홀더를 제자리에서 다시 바인딩한다.
            itemAnimator = null
            clipToPadding = false
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        // iOS `DemoLayout.readableWidth` 720pt: 넓은 화면에서는 폭을 멈추고 가운데 둔다.
        val readableWidth = minOf(resources.displayMetrics.widthPixels, dp(720))
        root.addView(
            FrameLayout(this).apply {
                addView(
                    recycler,
                    FrameLayout.LayoutParams(readableWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL),
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    /** fixture + (재생 중이면) 질문·스트리밍 답변을 한 리스트로 게시한다. */
    private fun publish(scrollToEnd: Boolean = false) {
        fun row(message: ChatMessage, streaming: Boolean = false) =
            Row(message, streaming, parsesDollarMath, showsCaseLabels)
        val rows = SampleMarkdown.conversation.map { row(it) }.toMutableList()
        replayText?.let { text ->
            rows += row(ChatMessage(REPLAY_QUESTION_ID, ChatMessage.Role.User, REPLAY_QUESTION))
            rows += row(ChatMessage(REPLAY_ANSWER_ID, ChatMessage.Role.Assistant, text, "스트리밍 재생"), streaming = isStreaming)
        }
        // submitList의 diff는 비동기다. 커밋 콜백에서 itemCount를 읽어야 새 행까지 스크롤된다.
        adapter.submitList(rows) { if (scrollToEnd) recycler.scrollToPosition(adapter.itemCount - 1) }
    }

    private fun replay() {
        replayJob?.cancel()
        replayJob = lifecycleScope.launch {
            buffer.reset()
            replayText = ""
            isStreaming = true
            publish(scrollToEnd = true)
            try {
                for (chunk in SampleMarkdown.graphemeChunks(SampleMarkdown.streamingAnswer)) {
                    buffer.append(chunk)
                    delay(REPLAY_CHUNK_DELAY_MS)
                }
                buffer.flush()
            } finally {
                isStreaming = false
                publish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("parsesDollarMath", parsesDollarMath)
        outState.putBoolean("showsCaseLabels", showsCaseLabels)
        super.onSaveInstanceState(outState)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val EXTRA_AUTOPLAY = "autoplay"
        const val REPLAY_QUESTION_ID = 1_000L
        const val REPLAY_ANSWER_ID = 1_001L
    }
}

private class ChatAdapter(
    private val codeBlocks: RichMarkdownCodeBlockOptions,
) : ListAdapter<ViewChatActivity.Row, RecyclerView.ViewHolder>(Diff) {

    /** 어느 셀이든 렌더 높이가 바뀌면 호출된다. */
    var onContentSizeChange: (() -> Unit)? = null

    override fun getItemViewType(position: Int): Int = getItem(position).message.role.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == ChatMessage.Role.User.ordinal) {
            UserHolder(parent.context)
        } else {
            AssistantHolder(parent.context, codeBlocks) { onContentSizeChange?.invoke() }
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        when (holder) {
            is UserHolder -> holder.bind(row.message.text)
            is AssistantHolder -> holder.bind(row)
        }
    }

    private object Diff : DiffUtil.ItemCallback<ViewChatActivity.Row>() {
        override fun areItemsTheSame(a: ViewChatActivity.Row, b: ViewChatActivity.Row) = a.message.id == b.message.id
        override fun areContentsTheSame(a: ViewChatActivity.Row, b: ViewChatActivity.Row) = a == b
    }
}

private fun View.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

private fun View.bubble(color: Int): GradientDrawable = GradientDrawable().apply {
    cornerRadius = 18f * resources.displayMetrics.density
    setColor(color)
}

private class UserHolder(context: Context) : RecyclerView.ViewHolder(
    FrameLayout(context).apply {
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (16 * resources.displayMetrics.density).toInt() }
    },
) {
    private val text = TextView(context).apply {
        val d = resources.displayMetrics.density
        setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
        background = bubble(if (isNight()) 0x260A84FF else 0x26007AFF)
        setTextIsSelectable(true)
    }

    init {
        (itemView as FrameLayout).addView(
            text,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END)
                .apply { marginStart = (40 * itemView.resources.displayMetrics.density).toInt() },
        )
    }

    fun bind(message: String) {
        text.text = message
    }
}

/**
 * 답변 셀. [RichMarkdownView]는 ViewHolder 수명 동안 하나만 만들고 `markdown`만 바꾼다.
 *
 * `notifyItemChanged`는 필요 없다: 뷰가 블록 재구성 뒤 `requestLayout()`을 스스로 호출하고, RecyclerView는
 * 자식의 requestLayout을 받아 wrap_content 셀을 다시 측정한다. `notifyItemChanged`를 부르면 재바인딩으로
 * 스트리밍 중 깜빡임만 생긴다. `onContentSizeChange`는 자동 스크롤 같은 부가 동작에만 쓴다.
 */
private class AssistantHolder(
    context: Context,
    codeBlocks: RichMarkdownCodeBlockOptions,
    onContentSizeChange: () -> Unit,
) : RecyclerView.ViewHolder(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }) {

    private val d = context.resources.displayMetrics.density
    private val label = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        background = bubble(if (isNight()) 0xFF3A3A3C.toInt() else 0xFFE5E5EA.toInt())
    }
    private val markdownView = RichMarkdownView(context).apply {
        this.codeBlocks = codeBlocks
        this.onContentSizeChange = onContentSizeChange
    }

    init {
        val column = itemView as LinearLayout
        column.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (16 * d).toInt() }
        column.addView(
            label,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (8 * d).toInt() },
        )
        column.addView(
            FrameLayout(context).apply {
                background = bubble(if (isNight()) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt())
                setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
                addView(
                    markdownView,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    fun bind(row: ViewChatActivity.Row) {
        label.text = row.message.caseName
        label.visibility = if (row.showsCaseLabels && row.message.caseName.isNotEmpty()) View.VISIBLE else View.GONE
        markdownView.dollarMath = if (row.parsesDollarMath) LatexDollarMathOptions.Single else LatexDollarMathOptions.None
        markdownView.markdown = row.message.text
        markdownView.streaming = if (row.streaming) RichMarkdownStreamingOptions.Default else null
    }
}
