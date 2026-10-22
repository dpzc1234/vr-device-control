package com.vrdevicecontrol.controller.access

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeveloperAccessTest {
    private class MemoryStore : AccessStore {
        val values = mutableMapOf<String, Any?>()
        override fun getString(key: String) = values[key] as String?
        override fun getLong(key: String) = values[key] as Long? ?: 0L
        override fun edit(values: Map<String, Any?>) {
            values.forEach { (k, v) -> if (v == null) this.values.remove(k) else this.values[k] = v }
        }
    }

    private val store = MemoryStore()
    private var now = 1_000_000L
    private fun access() = DeveloperAccess(store, nowMs = { now }, iterations = 1_000, idleTimeoutMs = 15 * 60_000L)

    @Test
    fun `fresh install starts in developer mode without a PIN`() {
        val a = access()
        assertTrue(a.state.value.developer)
        assertFalse(a.state.value.pinSet)
    }

    @Test
    fun `setting a PIN returns a recovery code and a restart starts locked`() {
        val recovery = access().setPin("123456")
        assertTrue(Regex("[A-Z2-9]{4}(-[A-Z2-9]{4}){3}").matches(recovery))
        assertFalse(store.values.values.any { it == "123456" || it == recovery }, "secrets are stored hashed only")

        val restarted = access()
        assertFalse(restarted.state.value.developer)
        assertTrue(restarted.state.value.pinSet)
        assertEquals(UnlockResult.Success, restarted.unlock("123456"))
        assertTrue(restarted.isDeveloper)
    }

    @Test
    fun `PIN rules are enforced and setting it requires developer mode`() {
        val a = access()
        assertFailsWith<IllegalArgumentException> { a.setPin("12345") }
        assertFailsWith<IllegalArgumentException> { a.setPin("12ab56") }
        a.setPin("12345678")
        a.lock()
        assertFailsWith<IllegalStateException> { a.setPin("654321") }
    }

    @Test
    fun `wrong attempts lock out with doubling delays that survive restarts`() {
        access().setPin("123456")
        val a = access()
        repeat(4) { i -> assertEquals(UnlockResult.Wrong(4 - i), a.unlock("000000")) }
        assertEquals(UnlockResult.LockedOut(30_000), a.unlock("000000"))
        assertIs<UnlockResult.LockedOut>(a.unlock("123456"), "even the right PIN is refused during lockout")

        val restarted = access()
        assertIs<UnlockResult.LockedOut>(restarted.unlock("123456"))
        now += 30_001
        assertEquals(UnlockResult.LockedOut(60_000), restarted.unlock("999999"), "next failure doubles")
        now += 60_001
        assertEquals(UnlockResult.Success, restarted.unlock("123456"))
        restarted.lock()
        assertEquals(UnlockResult.Wrong(4), restarted.unlock("000000"), "success resets the counter")
    }

    @Test
    fun `inactivity auto-locks only when a PIN exists`() {
        val a = access()
        now += 60 * 60_000L
        a.tick()
        assertTrue(a.isDeveloper, "without a PIN there is nothing to lock with")

        a.setPin("123456")
        now += 10 * 60_000L
        a.touch()
        now += 10 * 60_000L
        a.tick()
        assertTrue(a.isDeveloper, "activity resets the timer")
        now += 6 * 60_000L
        a.tick()
        assertFalse(a.isDeveloper)
    }

    @Test
    fun `recovery code resets the PIN once and issues a new code`() {
        val recovery = access().setPin("123456")
        val a = access()
        val typed = recovery.lowercase().replace("-", " ")
        val (result, next) = a.recover(typed, "24681357")
        assertEquals(UnlockResult.Success, result)
        assertNotNull(next)
        assertNotEquals(recovery, next)
        assertTrue(a.isDeveloper)

        a.lock()
        assertEquals(UnlockResult.Success, a.unlock("24681357"))
        a.lock()
        val (reuse, none) = a.recover(recovery, "11112222")
        assertIs<UnlockResult.Wrong>(reuse, "a used recovery code is spent")
        assertNull(none)
    }

    @Test
    fun `wrong recovery codes count towards the same lockout`() {
        access().setPin("123456")
        val a = access()
        repeat(3) { a.unlock("000000") }
        assertIs<UnlockResult.Wrong>(a.recover("AAAA-AAAA-AAAA-AAAA", "123123").first)
        assertIs<UnlockResult.LockedOut>(a.recover("AAAA-AAAA-AAAA-AAAA", "123123").first)
    }
}
