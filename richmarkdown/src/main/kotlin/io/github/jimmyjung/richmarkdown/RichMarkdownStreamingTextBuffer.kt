// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown

import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 토큰 도착 속도와 화면 갱신 속도를 분리하는 latest-wins 버퍼
 * (iOS `RichMarkdownStreamingOptions.swift`의 `RichMarkdownStreamingTextBuffer`).
 *
 * 렌더러는 누적 전체 문자열을 받으므로 호출자가 갱신 빈도를 합쳐야 한다. 간격 안에 도착한 갱신은
 * 마지막 값만 남기고, 간격이 끝나면 trailing 게시 1회로 흘려 보낸다 — 마지막 조각이 다음 조각까지
 * 화면에 못 오르는 일이 없다. iOS `@MainActor`와 같이 main 스레드에서만 호출한다.
 *
 * @param scope main dispatcher 기반 scope. trailing 게시 코루틴을 여기서 띄운다.
 */
class RichMarkdownStreamingTextBuffer internal constructor(
    private val scope: CoroutineScope,
    val interval: Duration,
    initialText: String,
    private val timeSource: TimeSource,
) {
    constructor(
        scope: CoroutineScope,
        interval: Duration = 100.milliseconds,
        initialText: String = "",
    ) : this(scope, interval, initialText, TimeSource.Monotonic)

    private val _text = MutableStateFlow(initialText)
    val text: StateFlow<String> = _text.asStateFlow()

    private var pending: String? = null
    private var lastPublished: TimeMark? = null
    private var trailing: Job? = null

    /** 누적 전체 문자열을 넘긴다. 첫 호출과 간격 경과 뒤 호출은 즉시 게시, 그 사이는 trailing 예약. */
    @MainThread
    fun submit(latest: String) {
        pending = latest
        val last = lastPublished
        if (last == null) {
            publishPending()
            return
        }
        val elapsed = last.elapsedNow()
        if (elapsed >= interval) {
            publishPending()
            return
        }
        if (trailing != null) return
        trailing = scope.launch {
            delay(interval - elapsed)
            trailing = null
            publishPending()
        }
    }

    /** 델타를 이어 붙인다. 아직 게시되지 않은 pending이 있으면 그 뒤에 붙는다. */
    @MainThread
    fun append(delta: String) = submit((pending ?: _text.value) + delta)

    /** 스트림 종료·오류 시 남은 pending을 즉시 게시한다. */
    @MainThread
    fun flush() {
        cancelTrailing()
        publishPending()
    }

    /** 새 스트림 시작. pending과 간격 상태를 버리고 [text]를 즉시 바꾼다. */
    @MainThread
    fun reset(text: String = "") {
        cancelTrailing()
        pending = null
        lastPublished = null
        _text.value = text
    }

    private fun publishPending() {
        val next = pending ?: return
        pending = null
        _text.value = next
        lastPublished = timeSource.markNow()
    }

    private fun cancelTrailing() {
        trailing?.cancel()
        trailing = null
    }
}
