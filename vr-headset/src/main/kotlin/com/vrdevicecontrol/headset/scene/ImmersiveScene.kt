package com.vrdevicecontrol.headset.scene

import android.view.Surface
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Equirect180ShapeOptions
import com.meta.spatial.toolkit.Equirect360ShapeOptions
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Visible
import com.vrdevicecontrol.headset.R
import com.vrdevicecontrol.protocol.AssetDescriptor
import com.vrdevicecontrol.protocol.EyeOrder
import com.vrdevicecontrol.protocol.PresentationDescriptor
import com.vrdevicecontrol.protocol.Projection
import com.vrdevicecontrol.protocol.StereoLayout

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

    fun onSceneReady() {
        activity.scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        activity.scene.enablePassthrough(false)
        activity.scene.setLightingEnvironment(
            ambientColor = Vector3(1.2f),
            sunColor = Vector3(0f),
            sunDirection = -Vector3(1f, 3f, 2f),
        )
        statusPanel = Entity.create(
            listOf(
                Panel(R.id.status_panel),
                Transform(Pose(Vector3(0f, 1.45f, 2.2f))),
                Visible(true),
            ),
        )
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
        destroyVideo()
        val id = nextPanelId++
        val spherical = presentation.projection != Projection.FLAT
        activity.registerPanel(
            VideoSurfacePanelRegistration(
                id,
                surfaceConsumer = { _, surface -> onSurface(surface) },
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

    /** Reveal video and hide standby in the same frame. */
    fun showVideo() {
        video?.entity?.setComponent(Visible(true))
        statusPanel?.setComponent(Visible(false))
    }

    fun showStandby() {
        video?.entity?.setComponent(Visible(false))
        statusPanel?.setComponent(Visible(true))
    }

    fun destroyVideo() {
        video?.entity?.destroy()
        video = null
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
}
