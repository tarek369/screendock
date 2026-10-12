package com.cressu.screendock.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ApprovalGateTest {

    @Test fun `disabled setting admits without prompt`() {
        val g = ApprovalGate(200)
        assertTrue(g.request("d1", "Mac", false, emptySet(), { error("no prompt") }, {}))
    }

    @Test fun `remembered device is admitted without prompt`() {
        val g = ApprovalGate(200)
        assertTrue(g.request("d1", "Mac", true, setOf("d1"), { error("no prompt") }, {}))
    }

    @Test fun `timeout denies and clears prompt`() {
        val g = ApprovalGate(100)
        val events = CopyOnWriteArrayList<String?>()
        assertFalse(g.request("d1", "Mac", true, emptySet(), { events += it }, {}))
        assertEquals(listOf("Mac", null), events)
    }

    @Test fun `allow answer admits and remembers`() {
        val g = ApprovalGate(5_000)
        val prompted = CountDownLatch(1)
        val remembered = CopyOnWriteArrayList<String>()
        val result = AtomicBoolean(false)
        val t = Thread {
            result.set(g.request("d9", "iPhone", true, emptySet(), { if (it != null) prompted.countDown() }, { remembered += it }))
        }
        t.start()
        assertTrue(prompted.await(2, TimeUnit.SECONDS))
        g.answer(true)
        t.join(2_000)
        assertTrue(result.get())
        assertEquals(listOf("d9"), remembered.toList())
    }

    @Test fun `deny answer refuses and does not remember`() {
        val g = ApprovalGate(5_000)
        val prompted = CountDownLatch(1)
        val remembered = CopyOnWriteArrayList<String>()
        val result = AtomicBoolean(true)
        val t = Thread {
            result.set(g.request("d9", "iPhone", true, emptySet(), { if (it != null) prompted.countDown() }, { remembered += it }))
        }
        t.start()
        prompted.await(2, TimeUnit.SECONDS)
        g.answer(false)
        t.join(2_000)
        assertFalse(result.get())
        assertTrue(remembered.isEmpty())
    }

    @Test fun `needsPrompt rule`() {
        assertTrue(ApprovalGate.needsPrompt(true, "x", emptySet()))
        assertFalse(ApprovalGate.needsPrompt(true, "x", setOf("x")))
        assertFalse(ApprovalGate.needsPrompt(false, "x", emptySet()))
        assertTrue(ApprovalGate.needsPrompt(true, "", setOf("")))
    }
}
