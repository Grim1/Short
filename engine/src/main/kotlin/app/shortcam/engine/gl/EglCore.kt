package app.shortcam.engine.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/** One ES 3.0 context plus a 1x1 pbuffer so the context can be current with no window. */
internal class EglCore {
    private val display: EGLDisplay
    private val config: EGLConfig
    private val context: EGLContext
    private val pbuffer: EGLSurface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            // Lets the same config feed a MediaCodec input surface for video later.
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no matching EGL config"
        }
        config = configs[0]!!

        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${EGL14.eglGetError()}" }

        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        makeCurrent(null)
    }

    fun createWindowSurface(surface: Surface): EGLSurface? {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        return if (s == EGL14.EGL_NO_SURFACE) null else s
    }

    /** Makes [surface] current, or the offscreen pbuffer when null. */
    fun makeCurrent(surface: EGLSurface?) {
        val target = surface ?: pbuffer
        check(EGL14.eglMakeCurrent(display, target, target, context)) {
            "eglMakeCurrent failed: ${EGL14.eglGetError()}"
        }
    }

    fun swap(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    /** Timestamp for the next frame swapped to [surface], which a video encoder uses as its time. */
    fun setPresentationTime(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun destroySurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, pbuffer)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }
}
