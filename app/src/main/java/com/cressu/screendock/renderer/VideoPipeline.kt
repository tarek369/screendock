package com.cressu.screendock.renderer

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "AirplayVideo"

// EGL_RECORDABLE_ANDROID from the EGL_ANDROID_recordable extension (0x3142) is
// not exposed as a constant in EGL14 on every API level we target; declare it
// locally so eglChooseConfig can require a recordable surface (lets MediaCodec
// consume it on some chipsets).
private const val EGL_RECORDABLE_ANDROID = 0x3142

/**
 * Decouples [MediaCodec][android.media.MediaCodec] decode from the display
 * [Surface] by owning the codec's *output* [SurfaceTexture] as a GLES external
 * (`GL_OES_EGL_image_external`) texture on a dedicated EGL thread, then blitting
 * each new frame onto whichever display [Surface] is currently bound.
 *
 * **Why the indirection**: the codec's input surface is stable across Activity
 * `Surface` lifecycle changes (rotation, PiP, fullscreen toggle), so the codec
 * never has to restart when only the display changes (DESIGN §7.1.3).
 *
 * **Threading**: EGL, [SurfaceTexture.updateTexImage] and the blit all run on
 * the dedicated [HandlerThread] named `"VideoPipeline"`. Public fields are
 * `@Volatile` so the decode thread may read [inputSurface] / [inputSurfaceTexture]
 * safely without holding the pipeline thread's lock.
 *
 * **Robustness** (NFR-6): every EGL/GLES call is guarded so a bad/invalid
 * display `Surface` can never crash the GL thread; failures are logged under
 * tag `"AirplayVideo"` and the pipeline keeps running, ready for the next
 * [setDisplaySurface].
 */
class VideoPipeline {

    private val thread = HandlerThread("VideoPipeline").apply { start() }
    private val handler = Handler(thread.looper)

    // ---- EGL state (pipeline thread only) ----------------------------------
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var displayEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // ---- GLES state (pipeline thread only) ---------------------------------
    private var textureId: Int = 0
    private var program: Int = 0
    private var aPositionLoc: Int = 0
    private var aTexCoordLoc: Int = 0
    private var uTexMatrixLoc: Int = 0
    private var uTextureLoc: Int = 0
    private val texMatrix = FloatArray(16)
    // Reused vertex buffers — allocated once, mutated in place each draw.
    private val quadVertices = FloatArray(8)   // per-instance (rewritten each draw)
    private val quadBuffer: FloatBuffer = newFloatBuffer(quadVertices)
    private val coordBuffer: FloatBuffer = newFloatBuffer(QUAD_TEXCOORDS)

    // ---- output surface (reassigned on the pipeline thread) ----------------
    @Volatile private var displaySurface: Surface? = null
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0

    // ---- input surface (created once in [start]; published via @Volatile) --
    @Volatile private var _inputSurfaceTexture: SurfaceTexture? = null
    @Volatile private var _inputSurface: Surface? = null

    @Volatile private var started: Boolean = false
    @Volatile private var released: Boolean = false

    // Draw-request dedup so a burst of frames does not flood the handler.
    @Volatile private var drawPending: Boolean = false

    /** The [SurfaceTexture] MediaCodec decodes into. `null` until [start] completes. */
    val inputSurfaceTexture: SurfaceTexture? get() = _inputSurfaceTexture

    /** A [Surface] wrapping [inputSurfaceTexture], for [MediaCodec.configure][android.media.MediaCodec.configure]. */
    val inputSurface: Surface? get() = _inputSurface

    /**
     * Source video aspect ratio (width / height). Set from the decoded format so
     * the blit can letterbox/pillarbox. `0f` means "unknown → fit-fill" until the
     * codec reports its size.
     */
    @Volatile var videoAspect: Float = 0f
        set(value) {
            field = value
            if (!released && started) requestDrawLocked()
        }

    /** How the picture is mapped to the display; takes effect on the next draw (redraws immediately). */
    @Volatile var scaleMode: ScaleMode = ScaleMode.FIT
        set(value) {
            field = value
            if (!released && started) requestDrawLocked()
        }

    /**
     * Start the EGL thread and create the input [SurfaceTexture]/[Surface].
     *
     * Idempotent: a no-op if already started or already released. Blocks the
     * caller until EGL + GLES init finishes so that [inputSurface] is non-null
     * on return (callers configure MediaCodec against it immediately).
     */
    fun start() {
        if (started || released) return
        val latch = CountDownLatch(1)
        handler.post {
            try {
                initEglLocked()
                initGlLocked()
                initSurfaceTextureLocked()
                started = true
            } catch (t: Throwable) {
                Log.e(TAG, "VideoPipeline start failed", t)
            } finally {
                latch.countDown()
            }
        }
        try {
            if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "VideoPipeline start timed out after ${START_TIMEOUT_MS}ms")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * (Re)target the display [Surface]. Pass `null` to blank. Does NOT restart
     * the codec — that is the point of this class. Work is posted to the
     * pipeline thread; this method returns immediately.
     */
    fun setDisplaySurface(surface: Surface?) {
        if (released) return
        handler.post { bindDisplayLocked(surface) }
    }

    /** Stop the EGL thread and release every GL/EGL/SurfaceTexture resource. */
    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            try {
                releaseLocked()
            } catch (t: Throwable) {
                Log.e(TAG, "VideoPipeline release failed", t)
            } finally {
                latch.countDown()
            }
        }
        try {
            if (!latch.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "VideoPipeline release timed out after ${RELEASE_TIMEOUT_MS}ms")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread.quitSafely()
    }

    // ---- pipeline-thread implementation ------------------------------------

    private fun initEglLocked() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        if (!EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 0)) {
            throw RuntimeException("eglInitialize failed")
        }
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] == 0
        ) {
            // Some drivers expose no window config with 8-bit alpha + recordable;
            // degrade to a plain RGB565 window config rather than losing video.
            Log.w(TAG, "strict eglChooseConfig found 0 configs; retrying relaxed")
            val relaxedAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 5,
                EGL14.EGL_GREEN_SIZE, 6,
                EGL14.EGL_BLUE_SIZE, 5,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(eglDisplay, relaxedAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] == 0
            ) {
                throw RuntimeException("eglChooseConfig failed")
            }
        }
        val cfg = configs[0] ?: throw RuntimeException("null EGLConfig")
        eglConfig = cfg
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE,
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(eglContext !== EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        // Make the context current with no draw/read surface so that
        // updateTexImage() keeps working before any display surface binds.
        if (!EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                eglContext,
            )
        ) {
            throw RuntimeException("eglMakeCurrent(no surface) failed")
        }
    }

    private fun initGlLocked() {
        // External OES texture the SurfaceTexture will update into.
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR.toFloat(),
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat(),
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )

        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER.trimIndent())
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER.trimIndent())
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        check(linkStatus[0] == GLES20.GL_TRUE) {
            "Program link failed: ${GLES20.glGetProgramInfoLog(program)}"
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        // Identity until the first frame supplies a real transform.
        Matrix.setIdentityM(texMatrix, 0)
    }

    private fun initSurfaceTextureLocked() {
        val st = SurfaceTexture(textureId)
        // Listener runs on this pipeline thread (handler), so updateTexImage
        // executes on the EGL thread that owns the texture.
        st.setOnFrameAvailableListener(
            SurfaceTexture.OnFrameAvailableListener { onFrameAvailableLocked() },
            handler,
        )
        _inputSurfaceTexture = st
        _inputSurface = Surface(st)
    }

    private fun onFrameAvailableLocked() {
        if (released) return
        val st = _inputSurfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        } catch (t: Throwable) {
            Log.e(TAG, "updateTexImage failed", t)
            return
        }
        requestDrawLocked()
    }

    private fun requestDrawLocked() {
        if (drawPending) return
        drawPending = true
        handler.post {
            drawPending = false
            drawFrameLocked()
        }
    }

    private fun bindDisplayLocked(surface: Surface?) {
        // Tear down any previous window surface first (make it non-current
        // before destroying — a surface can't be destroyed while current).
        if (displayEglSurface !== EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                eglContext,
            )
            EGL14.eglDestroySurface(eglDisplay, displayEglSurface)
            displayEglSurface = EGL14.EGL_NO_SURFACE
        }
        // We do not own the caller's Surface; never release displaySurface itself.
        displaySurface = surface
        if (surface == null) {
            // Keep the context current with no surface so updateTexImage survives.
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                eglContext,
            )
            return
        }
        if (!surface.isValid) {
            Log.w(TAG, "setDisplaySurface: surface is invalid, blanking")
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                eglContext,
            )
            return
        }
        val cfg = eglConfig
        if (cfg == null) {
            Log.e(TAG, "setDisplaySurface: no EGLConfig (start not run?)")
            return
        }
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val es = EGL14.eglCreateWindowSurface(eglDisplay, cfg, surface, surfaceAttribs, 0)
        if (es == null || es === EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed")
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                eglContext,
            )
            return
        }
        displayEglSurface = es
        if (!EGL14.eglMakeCurrent(eglDisplay, displayEglSurface, displayEglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent(window) failed")
            EGL14.eglDestroySurface(eglDisplay, displayEglSurface)
            displayEglSurface = EGL14.EGL_NO_SURFACE
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                eglContext,
            )
            return
        }
        queryDisplaySizeLocked()
        Log.i(TAG, "display surface bound (${displayWidth}x${displayHeight})")
        // Repaint the last frame when a new display binds.
        drawFrameLocked()
    }

    private fun queryDisplaySizeLocked() {
        if (displayEglSurface === EGL14.EGL_NO_SURFACE) return
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, displayEglSurface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(eglDisplay, displayEglSurface, EGL14.EGL_HEIGHT, h, 0)
        displayWidth = w[0]
        displayHeight = h[0]
    }

    private fun drawFrameLocked() {
        if (released) return
        if (displayEglSurface === EGL14.EGL_NO_SURFACE) return
        if (eglContext === EGL14.EGL_NO_CONTEXT) return
        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, displayEglSurface, displayEglSurface, eglContext)) {
                Log.e(TAG, "draw: eglMakeCurrent failed")
                return
            }
            // Re-query each draw so rotation / SurfaceView resize is picked up.
            queryDisplaySizeLocked()
            val w = displayWidth
            val h = displayHeight
            if (w <= 0 || h <= 0) return

            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTextureLoc, 0)
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

            // Clip space is [-1,1]^2; extents come from the pure ScaleMode math.
            val ext = VideoGeometry.quadExtents(scaleMode, videoAspect, w, h)
            val halfW = ext.halfW
            val halfH = ext.halfH
            quadVertices[0] = -halfW; quadVertices[1] = -halfH
            quadVertices[2] = halfW;  quadVertices[3] = -halfH
            quadVertices[4] = -halfW; quadVertices[5] = halfH
            quadVertices[6] = halfW;  quadVertices[7] = halfH
            quadBuffer.clear()
            quadBuffer.put(quadVertices)
            quadBuffer.position(0)
            coordBuffer.clear()
            coordBuffer.put(QUAD_TEXCOORDS)
            coordBuffer.position(0)

            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, coordBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)

            EGL14.eglSwapBuffers(eglDisplay, displayEglSurface)
        } catch (t: Throwable) {
            Log.e(TAG, "drawFrame failed", t)
        }
    }

    private fun releaseLocked() {
        if (displayEglSurface !== EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                eglContext,
            )
            EGL14.eglDestroySurface(eglDisplay, displayEglSurface)
            displayEglSurface = EGL14.EGL_NO_SURFACE
        }
        // Context is still current (with no surface) — delete GL objects now.
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        runCatching { _inputSurfaceTexture?.release() }
            .onFailure { Log.e(TAG, "SurfaceTexture.release failed", it) }
        runCatching { _inputSurface?.release() }
            .onFailure { Log.e(TAG, "inputSurface.release failed", it) }
        _inputSurfaceTexture = null
        _inputSurface = null
        if (eglContext !== EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        displaySurface = null
        started = false
    }

    private fun compileShader(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Shader compile failed: ${GLES20.glGetShaderInfoLog(s)}"
        }
        return s
    }

    private fun newFloatBuffer(src: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(src.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(src)
        fb.position(0)
        return fb
    }

    private companion object {
        private const val START_TIMEOUT_MS = 5_000L
        private const val RELEASE_TIMEOUT_MS = 3_000L
        private val QUAD_TEXCOORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                gl_Position = aPosition;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
