package com.vrdevicecontrol.headset

import android.app.Application
import android.os.Build
import com.vrdevicecontrol.domain.Tuning
import com.vrdevicecontrol.headset.boot.StartOnBoot
import com.vrdevicecontrol.headset.content.LocalContentRepository
import com.vrdevicecontrol.headset.identity.HeadsetIdentity
import com.vrdevicecontrol.headset.net.ControllerConnection
import com.vrdevicecontrol.headset.net.ControllerDiscovery
import com.vrdevicecontrol.headset.platform.ElapsedRealtimeClock
import com.vrdevicecontrol.headset.session.HeadsetSessionCoordinator
import com.vrdevicecontrol.headset.session.RunJournal
import com.vrdevicecontrol.protocol.Capabilities
import com.vrdevicecontrol.protocol.Hello
import com.vrdevicecontrol.protocol.ProtocolCodec
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class HeadsetApplication : Application() {
    lateinit var graph: HeadsetGraph
        private set

    override fun onCreate() {
        super.onCreate()
        StartOnBoot.syncComponent(this)
        graph = HeadsetGraph(this).also { it.start() }
    }
}

/** Explicit composition root; no DI framework. */
class HeadsetGraph(app: Application) {
    val tuning = Tuning()
    val clock = ElapsedRealtimeClock
    private val json = ProtocolCodec.DefaultJson

    /** Networking/downloads; a failure here can never cancel the session scope. */
    val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Session/playback on the main (player) looper. */
    val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val identity = HeadsetIdentity(app)
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val discovery = ControllerDiscovery(app)
    val content = LocalContentRepository(app.filesDir, http, json, tuning, networkScope)
    private val journal = RunJournal(File(app.filesDir, "run-journal.json"), json, clock, identity.processInstanceId)

    val connection: ControllerConnection = ControllerConnection(app, identity, discovery, clock, tuning, http, networkScope) {
        Hello(
            deviceId = identity.deviceId,
            deviceName = identity.label.value,
            capabilities = Capabilities(
                model = Build.MODEL,
                osVersion = "${Build.VERSION.RELEASE} (${Build.DISPLAY})",
                appVersion = BuildConfig.VERSION_NAME,
                videoDecoders = session.hardwareDecoders(),
            ),
        )
    }

    val session = HeadsetSessionCoordinator(
        context = app,
        identity = identity,
        connection = connection,
        content = content,
        journal = journal,
        clock = clock,
        tuning = tuning,
        sessionScope = sessionScope,
        networkScope = networkScope,
    )

    fun start() {
        networkScope.launch { content.reconcile() }
        session.start()
        connection.start()
    }
}
