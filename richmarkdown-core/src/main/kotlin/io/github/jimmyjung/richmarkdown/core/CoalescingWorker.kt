// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * latest-wins coalescing 실행기 (iOS DEVELOPMENT.md §4, `CoalescingWorker` actor 대응).
 *
 * - 장수명 drain 코루틴 하나만 요청을 소비한다.
 * - 실행 중 1개 + 최신 대기 1개만 유지한다. 새 제출은 대기 요청을 교체한다.
 * - [submit]은 어느 스레드에서든 호출할 수 있다. 상태 변경은 [lock]으로 직렬화하고,
 *   [perform]은 lock 밖에서 실행한다.
 *
 * [perform]은 던지지 않는 것을 전제로 한다(렌더러는 fail-open으로 원문을 표시한다).
 * 그래도 예외·취소로 drain이 비정상 종료되면 상태를 초기화해 다음 [submit]이 막히지 않게 한다.
 */
@InternalRichMarkdownApi
public class CoalescingWorker<Input : Any>(
    private val scope: CoroutineScope,
    private val perform: suspend (Input) -> Unit,
) {
    private val lock = Any()
    private var pending: Input? = null
    private var isRunning = false
    private var latestGeneration: Int? = null

    /** `isRunning || pending != null`의 관찰 가능한 사본. [awaitIdle]용. */
    private val outstanding = MutableStateFlow(false)

    /** 입력 종료 후 idle 검증용. 실행 중이거나 대기가 남아 있으면 true. */
    public val hasOutstandingWork: Boolean
        get() = synchronized(lock) { isRunning || pending != null }

    public fun submit(input: Input) {
        val shouldStart = synchronized(lock) { enqueueLocked(input) }
        if (shouldStart) scope.launch { drain() }
    }

    /**
     * generation이 작은 늦은 제출은 최신 대기 입력을 덮을 수 없다.
     * lock 안에서 판정하므로 ingress 순서가 곧 high-water 판정 순서다.
     */
    public fun submit(input: Input, generation: Int) {
        val shouldStart = synchronized(lock) {
            val latest = latestGeneration
            if (latest != null && generation <= latest) return
            latestGeneration = generation
            enqueueLocked(input)
        }
        if (shouldStart) scope.launch { drain() }
    }

    /** 실행 중·대기 중인 작업이 모두 끝날 때까지 기다린다. */
    public suspend fun awaitIdle() {
        outstanding.first { !it }
    }

    /** lock 안에서 호출. drain을 새로 시작해야 하면 true. */
    private fun enqueueLocked(input: Input): Boolean {
        pending = input
        outstanding.value = true
        if (isRunning) return false
        isRunning = true
        return true
    }

    private suspend fun drain() {
        var completedNormally = false
        try {
            while (true) {
                val next = takePending() ?: break
                perform(next)
            }
            completedNormally = true
        } finally {
            if (!completedNormally) {
                // ponytail: 비정상 종료 시 대기 입력은 버린다. 렌더러 perform은 던지지 않고,
                // scope 취소 뒤에는 어차피 실행할 곳이 없다.
                synchronized(lock) {
                    pending = null
                    isRunning = false
                    outstanding.value = false
                }
            }
        }
    }

    /** 대기 입력을 꺼낸다. 없으면 running을 끝내고 null을 돌려준다. */
    private fun takePending(): Input? = synchronized(lock) {
        val next = pending
        pending = null
        if (next == null) {
            isRunning = false
            outstanding.value = false
        }
        next
    }
}
