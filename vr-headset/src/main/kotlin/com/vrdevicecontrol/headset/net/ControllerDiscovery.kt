package com.vrdevicecontrol.headset.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.vrdevicecontrol.protocol.SERVICE_TYPE
import java.net.Inet4Address
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DiscoveredController(
    val serviceName: String,
    val controllerId: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocolMajor: Int?,
)

sealed interface DiscoveryState {
    data object Idle : DiscoveryState
    data object Searching : DiscoveryState
    data class Failed(val reason: String) : DiscoveryState
}

/**
 * NSD/mDNS discovery of `_vrctrl._tcp`. TXT records carry only controller ID, protocol major and
 * display name; discovery never authorizes anything. The multicast lock is held only while
 * searching.
 */
class ControllerDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val infoCallbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()

    private val _controllers = MutableStateFlow<List<DiscoveredController>>(emptyList())
    val controllers: StateFlow<List<DiscoveredController>> = _controllers.asStateFlow()

    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    @Synchronized
    fun start() {
        if (listener != null) return
        multicastLock = wifi.createMulticastLock("vrdc-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _state.value = DiscoveryState.Searching
            }

            override fun onServiceFound(info: NsdServiceInfo) = watch(info)

            override fun onServiceLost(info: NsdServiceInfo) {
                unwatch(info.serviceName)
                _controllers.update { list -> list.filterNot { it.serviceName == info.serviceName } }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _state.value = DiscoveryState.Idle
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed to start: $errorCode")
                _state.value = DiscoveryState.Failed("Discovery could not start (code $errorCode)")
                synchronized(this@ControllerDiscovery) { listener = null }
                releaseLock()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed to stop: $errorCode")
            }
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Synchronized
    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd.stopServiceDiscovery(l) }
        infoCallbacks.keys.toList().forEach(::unwatch)
        releaseLock()
        _state.value = DiscoveryState.Idle
    }

    /** Restart after a network change so stale results are not reused. */
    fun restart() {
        stop()
        _controllers.value = emptyList()
        start()
    }

    private fun releaseLock() {
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
    }

    @Synchronized
    private fun watch(info: NsdServiceInfo) {
        if (infoCallbacks.containsKey(info.serviceName)) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "Resolve registration failed for ${info.serviceName}: $errorCode")
            }

            override fun onServiceUpdated(resolved: NsdServiceInfo) {
                val host = resolved.hostAddresses.firstOrNull { it is Inet4Address }
                    ?: resolved.hostAddresses.firstOrNull()
                    ?: return
                val attrs = resolved.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) }
                val controllerId = attrs["cid"] ?: return
                val found = DiscoveredController(
                    serviceName = resolved.serviceName,
                    controllerId = controllerId,
                    name = attrs["name"] ?: resolved.serviceName,
                    host = host.hostAddress ?: return,
                    port = resolved.port,
                    protocolMajor = attrs["pv"]?.toIntOrNull(),
                )
                _controllers.update { list -> list.filterNot { it.serviceName == found.serviceName } + found }
            }

            override fun onServiceLost() {
                _controllers.update { list -> list.filterNot { it.serviceName == info.serviceName } }
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        infoCallbacks[info.serviceName] = callback
        runCatching { nsd.registerServiceInfoCallback(info, executor, callback) }
            .onFailure { Log.w(TAG, "Could not resolve ${info.serviceName}", it) }
    }

    @Synchronized
    private fun unwatch(serviceName: String) {
        infoCallbacks.remove(serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
    }

    private companion object {
        const val TAG = "ControllerDiscovery"
    }
}
