package com.vrdevicecontrol.controller.access

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persistent key/value storage for access secrets. Android implementation: private SharedPreferences. */
interface AccessStore {
    fun getString(key: String): String?
    fun getLong(key: String): Long
    fun edit(values: Map<String, Any?>)
}

data class AccessState(
    /** True when content management (library editing) is available. */
    val developer: Boolean,
    /** False on a fresh install until the developer chooses a PIN. */
    val pinSet: Boolean,
    /** Remaining lockout after too many wrong attempts, in ms (0 when not locked out). */
    val lockoutRemainingMs: Long = 0,
)

sealed interface UnlockResult {
    data object Success : UnlockResult
    data class Wrong(val attemptsBeforeLockout: Int) : UnlockResult
    data class LockedOut(val remainingMs: Long) : UnlockResult
}

/**
 * PIN-protected developer mode. Customer mode hides library editing so customers cannot add their
 * own content. This is a UI lock, not tamper-proofing: clearing the app's data returns it to a fresh
 * install, which opens in developer mode (an accepted gap).
 *
 * PIN and recovery code are stored only as salted PBKDF2 hashes. Wrong attempts (PIN or recovery
 * code) share one counter; from the fifth, each failure locks the prompt for 30 s, doubling each time.
 * The lockout uses wall-clock time so it survives app restarts and reboots.
 */
class DeveloperAccess(
    private val store: AccessStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = 120_000,
    private val idleTimeoutMs: Long = 15 * 60 * 1000L,
) {
    private var lastActivityMs = nowMs()

    // A process start always begins locked if a PIN exists; a fresh install starts in developer mode.
    private val _state = MutableStateFlow(AccessState(developer = !pinSet(), pinSet = pinSet()))
    val state: StateFlow<AccessState> = _state.asStateFlow()

    val isDeveloper: Boolean get() = _state.value.developer

    private fun pinSet() = store.getString(KEY_PIN_HASH) != null

    /** Call on any user interaction; resets the inactivity timer. */
    fun touch() {
        lastActivityMs = nowMs()
    }

    /** Call periodically: applies the inactivity auto-lock and refreshes the lockout countdown. */
    @Synchronized
    fun tick() {
        val s = _state.value
        if (s.developer && s.pinSet && nowMs() - lastActivityMs > idleTimeoutMs) {
            _state.value = s.copy(developer = false)
            return
        }
        val remaining = lockoutRemaining()
        if (remaining != s.lockoutRemainingMs) _state.value = s.copy(lockoutRemainingMs = remaining)
    }

    @Synchronized
    fun lock() {
        if (pinSet()) _state.value = _state.value.copy(developer = false)
    }

    @Synchronized
    fun unlock(pin: String): UnlockResult {
        lockoutRemaining().takeIf { it > 0 }?.let { return UnlockResult.LockedOut(it) }
        if (!pinSet()) return UnlockResult.Success.also { setDeveloper() }
        return if (verify(pin.trim(), KEY_PIN_HASH, KEY_PIN_SALT)) {
            succeed()
            UnlockResult.Success
        } else {
            fail()
        }
    }

    /**
     * Sets or changes the PIN. Only possible in developer mode. Returns a new one-time recovery code
     * that must be shown to the developer once; any previous recovery code stops working.
     */
    @Synchronized
    fun setPin(pin: String): String {
        check(_state.value.developer) { "Developer mode required to set the PIN" }
        require(isValidPin(pin)) { "PIN must be 6 to 8 digits" }
        val recovery = newRecoveryCode()
        val pinSalt = salt()
        val recoverySalt = salt()
        store.edit(
            mapOf(
                KEY_PIN_HASH to hash(pin, pinSalt),
                KEY_PIN_SALT to pinSalt.hex(),
                KEY_RECOVERY_HASH to hash(normalize(recovery), recoverySalt),
                KEY_RECOVERY_SALT to recoverySalt.hex(),
            ),
        )
        lastActivityMs = nowMs()
        _state.value = _state.value.copy(pinSet = true, developer = true)
        return recovery
    }

    /**
     * Resets a forgotten PIN using the recovery code. On success the app is unlocked, the new PIN is
     * stored and a fresh recovery code is returned (the used code is spent).
     */
    @Synchronized
    fun recover(recoveryCode: String, newPin: String): Pair<UnlockResult, String?> {
        require(isValidPin(newPin)) { "PIN must be 6 to 8 digits" }
        lockoutRemaining().takeIf { it > 0 }?.let { return UnlockResult.LockedOut(it) to null }
        if (!verify(normalize(recoveryCode), KEY_RECOVERY_HASH, KEY_RECOVERY_SALT)) return fail() to null
        succeed()
        return UnlockResult.Success to setPin(newPin)
    }

    // --- Internals ------------------------------------------------------------------------------

    private fun setDeveloper() {
        lastActivityMs = nowMs()
        _state.value = _state.value.copy(developer = true)
    }

    private fun succeed() {
        store.edit(mapOf(KEY_FAILURES to 0L, KEY_LOCKED_UNTIL to 0L))
        _state.value = _state.value.copy(lockoutRemainingMs = 0)
        setDeveloper()
    }

    private fun fail(): UnlockResult {
        val failures = store.getLong(KEY_FAILURES) + 1
        val updates = mutableMapOf<String, Any?>(KEY_FAILURES to failures)
        val result = if (failures >= FREE_ATTEMPTS) {
            val lockMs = BASE_LOCKOUT_MS shl (failures - FREE_ATTEMPTS).toInt().coerceAtMost(10)
            updates[KEY_LOCKED_UNTIL] = nowMs() + lockMs
            UnlockResult.LockedOut(lockMs)
        } else {
            UnlockResult.Wrong((FREE_ATTEMPTS - failures).toInt())
        }
        store.edit(updates)
        _state.value = _state.value.copy(lockoutRemainingMs = lockoutRemaining())
        return result
    }

    private fun lockoutRemaining(): Long = (store.getLong(KEY_LOCKED_UNTIL) - nowMs()).coerceAtLeast(0)

    private fun verify(secret: String, hashKey: String, saltKey: String): Boolean {
        val expected = store.getString(hashKey) ?: return false
        val salt = store.getString(saltKey)?.unhex() ?: return false
        return MessageDigest.isEqual(hash(secret, salt).toByteArray(), expected.toByteArray())
    }

    private fun hash(secret: String, salt: ByteArray): String {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.hex()
        } finally {
            spec.clearPassword()
        }
    }

    private fun salt() = ByteArray(16).also(random::nextBytes)

    private fun newRecoveryCode(): String =
        (1..RECOVERY_LENGTH).map { RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)] }
            .chunked(4).joinToString("-") { it.joinToString("") }

    companion object {
        const val FREE_ATTEMPTS = 5L
        const val BASE_LOCKOUT_MS = 30_000L
        private const val RECOVERY_LENGTH = 16
        /** No 0/O, 1/I/L, so the code is easy to read back from paper. */
        private const val RECOVERY_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

        private const val KEY_PIN_HASH = "pinHash"
        private const val KEY_PIN_SALT = "pinSalt"
        private const val KEY_RECOVERY_HASH = "recoveryHash"
        private const val KEY_RECOVERY_SALT = "recoverySalt"
        private const val KEY_FAILURES = "failedAttempts"
        private const val KEY_LOCKED_UNTIL = "lockedUntilMs"

        fun isValidPin(pin: String) = pin.length in 6..8 && pin.all { it in '0'..'9' }

        fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
