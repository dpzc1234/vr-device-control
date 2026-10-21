package com.vrdevicecontrol.headset.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The controller this headset is paired with. The address is only a hint; discovery re-resolves it. */
data class Pairing(
    val controllerId: String,
    val controllerName: String,
    val hostHint: String,
    val portHint: Int,
    val secret: String,
)

/**
 * Persistent installation identity and pairing material. Generated UUIDs, never hardware serials or
 * IP addresses. Excluded from backup (see data_extraction_rules.xml) so a restored copy cannot
 * impersonate this headset.
 */
class HeadsetIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val secretBox = SecretBox("vrdc-pairing")

    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString(KEY_DEVICE_ID, it).apply()
    }

    /** New for every process: invalidates readiness and schedules from a previous process. */
    val processInstanceId: String = UUID.randomUUID().toString()

    val defaultName: String = "Quest ${deviceId.takeLast(4).uppercase()}"

    private val _label = MutableStateFlow(prefs.getString(KEY_LABEL, null) ?: defaultName)
    /** Operator-assigned label pushed by the controller; editable on the tablet only. */
    val label: StateFlow<String> = _label.asStateFlow()

    private val _pairing = MutableStateFlow(loadPairing())
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    fun setLabel(label: String) {
        prefs.edit().putString(KEY_LABEL, label).apply()
        _label.value = label
    }

    fun savePairing(pairing: Pairing) {
        prefs.edit()
            .putString(KEY_CONTROLLER_ID, pairing.controllerId)
            .putString(KEY_CONTROLLER_NAME, pairing.controllerName)
            .putString(KEY_HOST, pairing.hostHint)
            .putInt(KEY_PORT, pairing.portHint)
            .putString(KEY_SECRET, secretBox.seal(pairing.secret))
            .apply()
        _pairing.value = pairing
    }

    fun updateAddressHint(host: String, port: Int) {
        val current = _pairing.value ?: return
        if (current.hostHint == host && current.portHint == port) return
        prefs.edit().putString(KEY_HOST, host).putInt(KEY_PORT, port).apply()
        _pairing.value = current.copy(hostHint = host, portHint = port)
    }

    fun clearPairing() {
        prefs.edit()
            .remove(KEY_CONTROLLER_ID).remove(KEY_CONTROLLER_NAME).remove(KEY_HOST)
            .remove(KEY_PORT).remove(KEY_SECRET)
            .apply()
        _pairing.value = null
    }

    private fun loadPairing(): Pairing? {
        val controllerId = prefs.getString(KEY_CONTROLLER_ID, null) ?: return null
        val sealed = prefs.getString(KEY_SECRET, null) ?: return null
        val secret = runCatching { secretBox.open(sealed) }.getOrNull() ?: return null
        return Pairing(
            controllerId = controllerId,
            controllerName = prefs.getString(KEY_CONTROLLER_NAME, null) ?: "Controller",
            hostHint = prefs.getString(KEY_HOST, null) ?: "",
            portHint = prefs.getInt(KEY_PORT, 0),
            secret = secret,
        )
    }

    private companion object {
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_LABEL = "label"
        const val KEY_CONTROLLER_ID = "controllerId"
        const val KEY_CONTROLLER_NAME = "controllerName"
        const val KEY_HOST = "hostHint"
        const val KEY_PORT = "portHint"
        const val KEY_SECRET = "secret"
    }
}

/** AES-GCM with a non-exportable Android Keystore key. */
class SecretBox(private val alias: String) {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun open(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }
}
