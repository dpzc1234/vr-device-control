package com.vrdevicecontrol.headset.scene

import android.view.Choreographer
import android.view.Surface
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.SceneLayer
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Equirect180ShapeOptions
import com.meta.spatial.toolkit.Equirect360ShapeOptions
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Visible
import com.vrdevicecontrol.headset.R
import com.vrdevicecontrol.protocol.AssetDescriptor
import com.vrdevicecontrol.protocol.EyeOrder
import com.vrdevicecontrol.protocol.PresentationDescriptor
import com.vrdevicecontrol.protocol.Projection
import com.vrdevicecontrol.protocol.StereoLayout
import kotlin.math.abs

/** A hidden video panel created for one preparation. */
class VideoHandle internal constructor(val panelId: Int, internal val entity: Entity)

/**
 * Standby/video visibility, projection and stereo configuration. Scene construction is separate
 * from playback commands: the engine asks for a surface, the session decides when to reveal it.
 */
class ImmersiveScene(private val activity: AppSystemActivity) {
    private var statusPanel: Entity? = null
    private var video: VideoHandle? = null
    private var nextPanelId = 0x0100_0000

    // Crossfade between passthrough and video: passthrough stays on underneath while the video
    // layer's alpha animates, and is only switched off once the video is fully opaque.
    private val choreographer = Choreographer.getInstance()
    private var fade: Fade? = null
    private var videoAlpha = 0f
    private var videoVisible = false
    private var videoLayer: SceneLayer? = null
    private var destroyWhenHidden = false

    fun onSceneReady() {
        activity.scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        // Standby shows the real surroundings so wearers can see where they are going.
        activity.scene.enablePassthrough(true)
        activity.scene.setLightingEnvironment(
            ambientColor = Vector3(1.2f),
            sunColor = Vector3(0f),
            sunDirection = -Vector3(1f, 3f, 2f),
        )
        statusPanel = Entity.create(
            listOf(
                Panel(R.id.status_panel),
                // Compact and slightly below eye level so it doesn't block the passthrough view.
                Transform(Pose(Vector3(0f, 1.3f, 1.6f))),
                Visible(true),
            ),
        )
    }

    /**
     * Aligns app space with the rider's current physical position and heading. The operator calls
     * this while the rider is facing the ride direction. `LOCAL_FLOOR` preserves floor height;
     * setting a zero view origin maps the current tracked pose to app-forward at the app origin.
     * This changes only this application's scene and does not alter Horizon OS guardian data.
     */
    fun recenterToCurrentPose() {
        activity.scene.setViewOrigin(0f, 0f, 0f, 0f)
        activity.scene.resetSessionStartPose()
    }

    /** Null when the presentation is not explicitly supported; unusual layouts are never guessed. */
    fun unsupportedReason(presentation: PresentationDescriptor): String? = when {
        presentation.eyeOrder == EyeOrder.RIGHT_FIRST && presentation.stereo != StereoLayout.MONO ->
            "Right-eye-first stereo is not supported yet"
        else -> null
    }

    /**
     * Registers a direct-to-surface media panel for [presentation] and creates it hidden behind
     * standby. [onSurface] is invoked once the compositor provides the surface.
     */
    fun createVideo(asset: AssetDescriptor, presentation: PresentationDescriptor, onSurface: (Surface) -> Unit): VideoHandle {
        destroyVideoNow()
        val id = nextPanelId++
        val spherical = presentation.projection != Projection.FLAT
        activity.registerPanel(
            VideoSurfacePanelRegistration(
                id,
                surfaceConsumer = { _, surface ->
                    setVideoAlpha(0f) // Never show an opaque first frame; reveal fades in.
                    onSurface(surface)
                },
                settingsCreator = {
                    MediaPanelSettings(
                        shape = when (presentation.projection) {
                            Projection.FLAT -> flatShape(asset, presentation)
                            Projection.EQUIRECT_180 -> Equirect180ShapeOptions(radius = 50f)
                            Projection.EQUIRECT_360 -> Equirect360ShapeOptions(radius = 300f)
                        },
                        display = PixelDisplayOptions(width = asset.width.coerceAtLeast(16), height = asset.height.coerceAtLeast(16)),
                        rendering = MediaPanelRenderOptions(
                            stereoMode = stereoMode(presentation.stereo),
                            zIndex = if (spherical) -1 else 0,
                        ),
                        // Transparent style so the compositor blends the layer over passthrough.
                        style = PanelStyleOptions(themeResourceId = R.style.PanelTheme),
                    )
                },
            ),
        )
        val pose = if (spherical) {
            Pose(Vector3(0f), Quaternion(0f, presentation.initialYawDegrees, 0f))
        } else {
            Pose(Vector3(0f, 1.5f, 3f))
        }
        val entity = Entity.create(listOf(Panel(id), Transform(pose), Visible(false)))
        return VideoHandle(id, entity).also { video = it }
    }

    /**
     * Reveal video: it becomes visible at the call (playback timing is unchanged) and fades in over
     * passthrough during [FADE_MS]; passthrough switches off once the video is fully opaque.
     */
    fun showVideo() {
        val handle = video ?: return
        destroyWhenHidden = false
        statusPanel?.setComponent(Visible(false))
        setVideoAlpha(videoAlpha)
        handle.entity.setComponent(Visible(true))
        videoVisible = true
        animateVideoAlpha(1f) { activity.scene.enablePassthrough(false) }
    }

    /**
     * Standby, paused and finished all show passthrough with the status card, never an opaque void.
     * Passthrough comes back immediately underneath and the video fades out over it.
     */
    fun showStandby() {
        statusPanel?.setComponent(Visible(true))
        activity.scene.enablePassthrough(true)
        if (!videoVisible) return
        animateVideoAlpha(0f) {
            video?.entity?.setComponent(Visible(false))
            videoVisible = false
            if (destroyWhenHidden) destroyVideoNow()
        }
    }

    /** Releases the video panel, after any fade-out in progress has finished. */
    fun destroyVideo() {
        if (videoVisible && fade != null) destroyWhenHidden = true else destroyVideoNow()
    }

    private fun destroyVideoNow() {
        fade?.cancel()
        fade = null
        video?.entity?.destroy()
        video = null
        videoLayer = null
        videoAlpha = 0f
        videoVisible = false
        destroyWhenHidden = false
    }

    // --- Fade --------------------------------------------------------------------------------------

    private fun animateVideoAlpha(target: Float, onEnd: () -> Unit) {
        fade?.cancel()
        val distance = abs(target - videoAlpha)
        if (distance < 0.001f) {
            setVideoAlpha(target)
            fade = null
            onEnd()
            return
        }
        // A partial fade (e.g. resume during a fade-out) takes proportionally less time.
        fade = Fade(videoAlpha, target, (FADE_MS * distance).toLong().coerceAtLeast(1), onEnd).also { it.start() }
    }

    private fun setVideoAlpha(alpha: Float) {
        videoAlpha = alpha
        layer()?.setColorScaleBias(Vector4(1f, 1f, 1f, alpha), Vector4(0f))
    }

    private fun layer(): SceneLayer? {
        videoLayer?.let { return it }
        val entity = video?.entity ?: return null
        val sceneObject = runCatching {
            activity.systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity)?.getNow(null)
        }.getOrNull()
        return (sceneObject as? PanelSceneObject)?.getLayer()?.also { videoLayer = it }
    }

    private inner class Fade(
        private val from: Float,
        private val to: Float,
        private val durationMs: Long,
        private val onEnd: () -> Unit,
    ) : Choreographer.FrameCallback {
        private val startNs = System.nanoTime()
        private var cancelled = false

        fun start() = choreographer.postFrameCallback(this)

        fun cancel() {
            cancelled = true
            choreographer.removeFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (cancelled) return
            val t = ((System.nanoTime() - startNs) / 1_000_000f / durationMs).coerceIn(0f, 1f)
            val eased = t * t * (3 - 2 * t) // smoothstep
            setVideoAlpha(from + (to - from) * eased)
            if (t >= 1f) {
                fade = null
                onEnd()
            } else {
                choreographer.postFrameCallback(this)
            }
        }
    }

    private fun flatShape(asset: AssetDescriptor, presentation: PresentationDescriptor): QuadShapeOptions {
        val eyeAspect = when {
            presentation.aspectRatio > 0f -> presentation.aspectRatio
            asset.width <= 0 || asset.height <= 0 -> 16f / 9f
            presentation.stereo == StereoLayout.SIDE_BY_SIDE -> (asset.width / 2f) / asset.height
            presentation.stereo == StereoLayout.TOP_BOTTOM -> asset.width / (asset.height / 2f)
            else -> asset.width.toFloat() / asset.height
        }
        val width = presentation.flatWidthMeters
        return QuadShapeOptions(width = width, height = width / eyeAspect)
    }

    private fun stereoMode(layout: StereoLayout) = when (layout) {
        StereoLayout.MONO -> StereoMode.None
        StereoLayout.SIDE_BY_SIDE -> StereoMode.LeftRight
        StereoLayout.TOP_BOTTOM -> StereoMode.UpDown
    }

    private companion object {
        /** Passthrough ↔ video crossfade duration. */
        const val FADE_MS = 2_000L
    }
}
