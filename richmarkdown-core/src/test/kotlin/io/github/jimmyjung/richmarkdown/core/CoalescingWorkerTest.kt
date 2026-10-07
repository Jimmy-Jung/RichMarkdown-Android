// Author: JunyoungJung
// Date: 2026-09-15

package io.github.jimmyjung.richmarkdown.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 비동기 계약 테스트 (iOS DEVELOPMENT.md §8 대응): latest-wins, 동시 실행 1, out-of-order 방지.
 * iOS `CoalescingWorkerTests`의 이식이다.
 */
class CoalescingWorkerTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun exceptionDropsPendingAndLiveScopeCanRestart() = runTest {
        val errors = ArrayList<Throwable>()
        val lifetime = SupervisorJob()
        val scope = CoroutineScope(lifetime + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> errors.add(e) })
        val gate = CompletableDeferred<Unit>()
        val executed = ArrayList<Int>()
        val worker = CoalescingWorker<Int>(scope) { value ->
            executed.add(value)
            if (value == 1) {
                gate.await()
                error("제어된 작업 실패")
            }
        }
        try {
            worker.submit(1)
            runCurrent()
            worker.submit(2)
            gate.complete(Unit)
            runCurrent()
            worker.awaitIdle()
            assertFalse(worker.hasOutstandingWork)
            assertEquals(1, errors.size)
            worker.submit(3)
            worker.awaitIdle()
            assertEquals(listOf(1, 3), executed)
        } finally {
            lifetime.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationBeforeDrainStartsReleasesIdle() = runTest {
        val lifetime = SupervisorJob()
        val scope = CoroutineScope(lifetime + StandardTestDispatcher(testScheduler))
        var calls = 0
        val worker = CoalescingWorker<Int>(scope) { calls += 1 }
        worker.submit(1)
        lifetime.cancel()
        runCurrent()
        assertFalse(worker.hasOutstandingWork, "시작 전 취소도 pending과 idle을 정리한다")
        worker.awaitIdle()
        assertEquals(0, calls)
        worker.submit(2)
        runCurrent()
        assertFalse(worker.hasOutstandingWork, "취소된 scope에 다시 제출해도 idle을 붙잡지 않는다")
        assertEquals(0, calls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationDuringDrainDropsPending() = runTest {
        val lifetime = SupervisorJob()
        val scope = CoroutineScope(lifetime + StandardTestDispatcher(testScheduler))
        val executed = ArrayList<Int>()
        val worker = CoalescingWorker<Int>(scope) { value ->
            executed.add(value)
            awaitCancellation()
        }
        worker.submit(1)
        runCurrent()
        worker.submit(2)
        lifetime.cancel()
        runCurrent()
        worker.awaitIdle()
        assertFalse(worker.hasOutstandingWork)
        assertEquals(listOf(1), executed)
    }

    /** 실행 순서와 최대 동시 실행 수를 기록한다. 실제 스레드 테스트도 쓰므로 스레드 안전하게 둔다. */
    private class Recorder {
        val executed: MutableList<Int> = Collections.synchronizedList(ArrayList())
        private val inFlight = AtomicInteger(0)
        private val maxSeen = AtomicInteger(0)
        val maxConcurrent: Int get() = maxSeen.get()

        fun begin(value: Int) {
            val now = inFlight.incrementAndGet()
            maxSeen.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            executed.add(value)
        }

        fun end() {
            inFlight.decrementAndGet()
        }
    }

    @Test
    fun latestWinsAndSingleConcurrency() = runTest {
        val recorder = Recorder()
        val worker = CoalescingWorker<Int>(backgroundScope) { value ->
            recorder.begin(value)
            delay(5)
            recorder.end()
        }

        for (i in 1..100) worker.submit(i)
        worker.awaitIdle()

        val executed = recorder.executed.toList()
        assertEquals(1, recorder.maxConcurrent, "동시 실행은 최대 1")
        assertEquals(100, executed.last(), "마지막 실행은 최신 제출 (latest wins)")
        assertTrue(executed.size < 100, "coalescing으로 제출보다 훨씬 적게 실행된다")
        assertEquals(executed.sorted(), executed, "실행 순서가 제출 순서를 위반하지 않는다")
        assertFalse(worker.hasOutstandingWork)
    }

    @Test
    fun submitAfterIdleRunsAgain() = runTest {
        val recorder = Recorder()
        val worker = CoalescingWorker<Int>(backgroundScope) { value ->
            recorder.begin(value)
            recorder.end()
        }

        worker.submit(1)
        assertTrue(worker.hasOutstandingWork, "제출 직후에는 outstanding")
        worker.awaitIdle()
        assertFalse(worker.hasOutstandingWork, "drain이 끝나면 idle")

        worker.submit(2)
        worker.awaitIdle()
        assertEquals(listOf(1, 2), recorder.executed.toList())
    }

    @Test
    fun lowerGenerationCannotReplaceNewerPendingInput() = runTest {
        val recorder = Recorder()
        val worker = CoalescingWorker<Int>(backgroundScope) { value ->
            recorder.begin(value)
            delay(5)
            recorder.end()
        }

        worker.submit(1, generation = 1)
        worker.submit(3, generation = 3)
        worker.submit(2, generation = 2) // 늦게 도착한 과거 generation
        worker.awaitIdle()

        val executed = recorder.executed.toList()
        assertEquals(3, executed.last())
        assertFalse(2 in executed)
    }

    @Test
    fun lowerGenerationIsIgnoredWhileAnotherInputIsRunning() = runTest {
        val recorder = Recorder()
        val worker = CoalescingWorker<Int>(backgroundScope) { value ->
            recorder.begin(value)
            delay(5)
            recorder.end()
        }

        worker.submit(1, generation = 1)
        yield() // 1이 실행을 시작할 때까지 양보한다.
        worker.submit(3, generation = 3)
        worker.submit(2, generation = 2)
        worker.awaitIdle()

        assertEquals(listOf(1, 3), recorder.executed.toList())
        assertEquals(1, recorder.maxConcurrent)
    }

    @Test
    fun concurrentSubmitsFromRealThreadsStayOrdered() = runTest {
        val recorder = Recorder()
        val worker = CoalescingWorker<Int>(backgroundScope) { value ->
            recorder.begin(value)
            recorder.end()
        }

        withContext(Dispatchers.Default) {
            coroutineScope {
                (1..200).map { i -> async { worker.submit(i, generation = i) } }.awaitAll()
            }
        }
        worker.awaitIdle()

        val executed = recorder.executed.toList()
        assertTrue(executed.isNotEmpty())
        assertEquals(200, executed.last(), "최대 generation이 마지막에 실행된다")
        assertEquals(executed.sorted(), executed, "generation 판정이 lock 안에서 일어나므로 단조 증가한다")
        assertEquals(1, recorder.maxConcurrent)
        assertFalse(worker.hasOutstandingWork)
    }
}
