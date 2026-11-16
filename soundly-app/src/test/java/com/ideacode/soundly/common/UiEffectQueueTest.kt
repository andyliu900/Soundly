package com.ideacode.soundly.common

import com.ideacode.soundly.common.state.UiEffect
import com.ideacode.soundly.common.state.UiEffectQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiEffectQueueTest {
    private data class Navigate(val target: Int) : UiEffect

    @Test
    fun `effects survive collector restart until explicitly acknowledged`() = runTest {
        val queue = UiEffectQueue<Navigate>()
        queue.post(Navigate(1))
        val first = queue.pending.first().single()
        assertEquals(first, queue.pending.first().single())
        queue.acknowledge(first.id)
        assertTrue(queue.pending.first().isEmpty())
    }

    @Test
    fun `acknowledging one effect preserves others including repeated values`() {
        val queue = UiEffectQueue<Navigate>()
        queue.post(Navigate(1))
        queue.post(Navigate(1))
        val entries = queue.pending.value
        queue.acknowledge(entries.first().id)
        assertEquals(listOf(entries.last()), queue.pending.value)
        queue.acknowledge(entries.first().id)
        assertEquals(listOf(entries.last()), queue.pending.value)
    }

    @Test
    fun `concurrent producers do not lose effects or duplicate ids`() = runTest {
        val queue = UiEffectQueue<Navigate>()
        (1..100).map { value -> async(Dispatchers.Default) { queue.post(Navigate(value)) } }.awaitAll()
        assertEquals(100, queue.pending.value.size)
        assertEquals(100, queue.pending.value.map { it.id }.toSet().size)
    }
}
