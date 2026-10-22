package com.vrdevicecontrol.headset

import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.runtime.SessionState
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.vr.VRFeature
import com.vrdevicecontrol.headset.playback.ExoPlaybackEngine
import com.vrdevicecontrol.headset.scene.ImmersiveScene
import com.vrdevicecontrol.headset.ui.HeadsetPanel

/**
 * Immersive entry point. Never autoplays on launch, reconnection or wake: it only restores the
 * standby scene and reports lifecycle signals to the session coordinator.
 */
class HeadsetActivity : AppSystemActivity() {
    private val graph get() = (application as HeadsetApplication).graph
    private var immersive: ImmersiveScene? = null

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this), ComposeFeature())

    override fun registerPanels(): List<PanelRegistration> = listOf(
        ComposeViewPanelRegistration(
            R.id.status_panel,
            composeViewCreator = { _, ctx -> ComposeView(ctx).apply { setContent { HeadsetPanel(graph) } } },
            settingsCreator = {
                UIPanelSettings(
                    // Same 960x600 dp layout, physically smaller: about 1 m wide at 1.6 m away.
                    shape = QuadShapeOptions(width = 0.96f, height = 0.6f),
                    style = PanelStyleOptions(themeResourceId = R.style.PanelTheme),
                    display = DpDisplayOptions(width = 960f, height = 600f),
                )
            },
        ),
    )

    override fun onSceneReady() {
        super.onSceneReady()
        val scene = ImmersiveScene(this).also { it.onSceneReady() }
        immersive = scene
        graph.session.attachPlayer(ExoPlaybackEngine(this, scene), scene)
    }

    override fun onSessionStateChanged(state: SessionState) {
        super.onSessionStateChanged(state)
        graph.session.onSessionPresenting(state == SessionState.VISIBLE || state == SessionState.FOCUSED)
    }

    override fun onVRReady() {
        super.onVRReady()
        graph.session.onVrReady(true)
    }

    override fun onVRPause() {
        graph.session.onVrReady(false)
        super.onVRPause()
    }

    override fun onHMDMounted() {
        super.onHMDMounted()
        graph.session.onMounted(true)
    }

    override fun onHMDUnmounted() {
        graph.session.onMounted(false)
        super.onHMDUnmounted()
    }

    override fun onSpatialShutdown() {
        graph.session.detachPlayer()
        immersive = null
        super.onSpatialShutdown()
    }
}
