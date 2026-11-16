package app.shortcam

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import app.shortcam.engine.Engine

/** Binds the rear camera's preview stream to the engine's input surface. */
class CameraFeed(
    private val activity: ComponentActivity,
    private val engine: Engine,
    private val streamSize: Size,
) {
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var stopped = false

    // Keeps the camera's idea of "up" in step with the screen, including a straight
    // flip from one landscape to the other, which causes no configuration change.
    private val displays = activity.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val rotation = activity.display?.rotation ?: return
            preview?.let { if (it.targetRotation != rotation) it.targetRotation = rotation }
        }
    }

    fun start() {
        val executor = ContextCompat.getMainExecutor(activity)
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (stopped) return@addListener
            val provider = future.get().also { provider = it }

            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                streamSize,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .build()

            preview.setSurfaceProvider(executor) { request ->
                request.setTransformationInfoListener(executor) { info ->
                    // When the camera writes its orientation into the surface, the
                    // SurfaceTexture transform already makes the image upright for the
                    // device's natural orientation, leaving only the display rotation.
                    val displayDegrees = (activity.display?.rotation ?: 0) * 90
                    val extra = if (info.hasCameraTransform()) -displayDegrees else info.rotationDegrees
                    android.util.Log.i(
                        "Short",
                        "camera ${request.resolution} front=$frontLens rotation=${info.rotationDegrees} " +
                            "cameraTransform=${info.hasCameraTransform()} mirrored=${info.isMirroring} " +
                            "display=$displayDegrees",
                    )
                    engine.setInputTransform(info.rotationDegrees % 180 != 0, extra)
                }
                engine.provideInputSurface(request.resolution.width, request.resolution.height) { surface ->
                    request.provideSurface(surface, executor) { surface.release() }
                }
            }

            this.preview = preview
            bind()
            displays.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        }, executor)
    }

    /** True while the camera facing the user is selected. */
    var frontLens = false
        private set

    /**
     * Switches between the rear camera and the one facing the user. Returns false,
     * changing nothing, if the phone lacks the camera asked for.
     */
    fun useFrontLens(front: Boolean): Boolean {
        val provider = provider ?: return false
        if (!provider.hasCamera(selector(front))) return false
        frontLens = front
        bind()
        return true
    }

    private fun selector(front: Boolean) =
        if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private var camera: androidx.camera.core.Camera? = null
    private var zoom = 1f

    /**
     * Zooms to [factor] times the widest view. The phone's own lens does as much of it
     * as it can, which keeps the picture sharp; the engine magnifies the rest.
     */
    fun setZoom(factor: Float) {
        zoom = factor.coerceAtLeast(1f)
        val camera = camera
        val reach = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
        val byLens = zoom.coerceAtMost(reach.coerceAtLeast(1f))
        camera?.cameraControl?.setZoomRatio(byLens)
        engine.setZoom(zoom / byLens)
    }

    private fun bind() {
        val provider = provider ?: return
        val preview = preview ?: return
        provider.unbindAll()
        camera = provider.bindToLifecycle(activity, selector(frontLens), preview)
        setZoom(zoom)
    }

    fun stop() {
        stopped = true
        displays.unregisterDisplayListener(displayListener)
        preview = null
        camera = null
        provider?.unbindAll()
    }
}
