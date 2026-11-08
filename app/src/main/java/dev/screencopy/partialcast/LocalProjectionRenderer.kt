package dev.screencopy.partialcast

import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal interface ProjectionOutput {
    fun start(surface: Surface, width: Int, height: Int)
    fun resizeOutput(width: Int, height: Int)
    fun phoneChanged()
    fun close(done: (String?) -> Unit)
}

/** All projection, SurfaceTexture and EGL work is serialized on this owned thread. */
internal class LocalProjectionRenderer(
    private val projection: MediaProjection,
    private val options: LocalCaptureOptions,
    private val phoneSize: () -> PhoneSnapshot,
    private val failed: (String) -> Unit,
    private val progress: (String) -> Unit,
    private val createdDisplay: (Int) -> Unit
) : ProjectionOutput {
    private val thread = HandlerThread("local-projection-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val closing = AtomicBoolean(false)
    private val faulted = AtomicBoolean(false)
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var input: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var geometry: LocalGeometry? = null
    private var outputSize = VideoSize(1, 1)
    private var outputResizePending = false
    private var viewport = LocalRect(0, 0, 1, 1)
    private val content = CaptureResizeState(Build.VERSION.SDK_INT >= 34)
    private val frames = ProjectionFrameState()
    private var maxTextureSize = 0
    private val maxViewport = IntArray(2)
    private val surfaceDimension = IntArray(1)
    private val transform = FloatArray(16)
    private val positions = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val coordinates = floats(FloatArray(8))
    private val cropCoordinates = FloatArray(8)
    private var positionLocation = 0
    private var coordinateLocation = 0
    private var matrixLocation = 0
    private var lastDraw = 0L
    private var sampleStart = 0L
    private var presented = 0
    private var callbackRegistered = false
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!closing.get() && faulted.compareAndSet(false, true))
                failed("Android stopped screen capture (system Stop, lock screen or permission revocation). Press Play for new consent.")
        }
        override fun onCapturedContentResize(width: Int, height: Int) {
            guarded {
                val size = VideoSize(width, height)
                content.report(size)
                requestReconcile()
            }
        }
    }
    override fun start(surface: Surface, width: Int, height: Int) {
        handler.post {
            guarded {
                check(virtualDisplay == null && surfaceTexture == null) { "Local capture surface may only start once" }
                outputSize = VideoSize(width, height)
                initializeEgl(surface)
                val phone = phoneSize()
                resizeCapture(phone.size, phone.rotation, phone.density)
                projection.registerCallback(callback, handler)
                callbackRegistered = true
                virtualDisplay = projection.createVirtualDisplay("PartialCast capture",
                    checkNotNull(geometry).buffer.width, checkNotNull(geometry).buffer.height, phone.density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, checkNotNull(input), null, handler)
                    ?: error("Android did not create the capture virtual display")
                createdDisplay(checkNotNull(virtualDisplay).display.displayId)
                handler.postDelayed(pollPhone, 500)
            }
        }
    }
    override fun resizeOutput(width: Int, height: Int) {
        handler.post { guarded {
            require(width > 0 && height > 0) { "Output surface has invalid dimensions" }
            outputSize = VideoSize(width, height)
            outputResizePending = true
            geometry?.let { viewport = it.viewport(outputSize) }
            scheduleDraw()
        } }
    }
    override fun phoneChanged() {
        handler.post { guarded { updatePhone() } }
    }
    private var lastPhone: PhoneSnapshot? = null
    private fun updatePhone() {
        if (virtualDisplay == null) return
        if (Build.VERSION.SDK_INT >= 34) {
            requestReconcile()
            return
        }
        val phone = phoneSize()
        if (phone == lastPhone) return
        resizeCapture(phone.size, phone.rotation, phone.density)
        scheduleDraw()
    }
    private fun reconcileCapture(phone: PhoneSnapshot) {
        val wasReady = content.ready
        val wasWaiting = content.waiting
        handler.removeCallbacks(retryResize)
        if (content.reconcile(phone.size, SystemClock.uptimeMillis())) {
            val changed = lastPhone != phone
            if (changed) resizeCapture(phone.size, phone.rotation, phone.density)
            if (changed || !wasReady) scheduleDraw()
        } else if (content.waiting) {
            if (!wasWaiting) {
                progress("Waiting for default-phone capture dimensions to settle; presentation is paused.")
                scheduleDraw()
            }
            check(handler.postDelayed(retryResize, CaptureResizeState.RETRY_INTERVAL_MS)) {
                "Render thread rejected capture-size reconciliation"
            }
        }
    }
    private fun requestReconcile() {
        // Run behind already-queued resize callbacks, including after a stalled render-thread swap.
        handler.removeCallbacks(reconcileResize)
        check(handler.post(reconcileResize)) { "Render thread rejected capture-size reconciliation" }
    }
    private val reconcileResize: Runnable = Runnable { guarded { reconcileCapture(phoneSize()) } }
    private val retryResize: Runnable = Runnable { guarded { requestReconcile() } }
    private val pollPhone = object : Runnable {
        override fun run() {
            guarded { updatePhone() }
            if (!closing.get() && !faulted.get()) handler.postDelayed(this, 500)
        }
    }
    private inline fun guarded(action: () -> Unit) {
        if (closing.get() || faulted.get()) return
        try { action() }
        catch (e: Exception) {
            faulted.set(true)
            android.util.Log.e("PartialCast", "Local projection failed", e)
            failed("Local presentation failed: ${e.message ?: e.javaClass.simpleName}. Stop, check display/settings, then press Play.")
        }
    }
    private fun resizeCapture(source: VideoSize, rotation: Int, density: Int) {
        val next = LocalCaptureGeometry.calculate(source, rotation, options)
        check(next.buffer.width <= maxTextureSize && next.buffer.height <= maxTextureSize) {
            "Full-resolution capture ${next.buffer} exceeds GPU texture limit $maxTextureSize. This phone display is unsupported; quality was not reduced."
        }
        val st = checkNotNull(surfaceTexture)
        val captureChanged = frames.captureGeometryChanged(geometry, next)
        if (captureChanged && frames.takePendingFrame()) {
            // Empty the old queue before resizing, not after a final resized frame can replace it.
            st.updateTexImage()
            frames.imageDiscarded(st.timestamp)
            checkGl("drain capture before resize")
        }
        if (captureChanged || lastPhone?.density != density) {
            st.setDefaultBufferSize(next.buffer.width, next.buffer.height)
            virtualDisplay?.resize(next.buffer.width, next.buffer.height, density)
            virtualDisplay?.surface = input
        }
        geometry = next
        lastPhone = PhoneSnapshot(source, rotation, density)
        viewport = next.viewport(outputSize)
        val rect = next.crop
        next.textureCoordinates(cropCoordinates)
        coordinates.clear(); coordinates.put(cropCoordinates); coordinates.position(0)
        android.util.Log.i("PartialCast", "capture_resize source=$source rotation=$rotation buffer=${next.buffer}")
        val detail = if (frames.canPresent(content.ready)) "Repainting the acquired frame." else "Waiting for frames."
        progress("Captured phone $source; clipped crop ${rect.width} x ${rect.height}; full-resolution buffer ${next.buffer}. $detail")
    }
    private val draw: Runnable = Runnable {
        guarded {
            if (frames.beginDraw()) {
                val st = checkNotNull(surfaceTexture)
                st.updateTexImage()
                frames.imageUpdated(st.timestamp)
                st.getTransformMatrix(transform)
            }
            check(EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, surfaceDimension, 0)) { eglError("query width") }
            val width = surfaceDimension[0]
            check(EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, surfaceDimension, 0)) { eglError("query height") }
            val height = surfaceDimension[0]
            check(width in 1..maxViewport[0] && height in 1..maxViewport[1]) {
                "Output surface $width x $height exceeds GPU viewport limits"
            }
            if (outputSize.width != width || outputSize.height != height) {
                outputSize = VideoSize(width, height)
                viewport = checkNotNull(geometry).viewport(outputSize)
            }
            GLES20.glViewport(0, 0, outputSize.width, outputSize.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val presenting = frames.canPresent(content.ready)
            if (presenting) {
                GLES20.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
                GLES20.glUniformMatrix4fv(matrixLocation, 1, false, transform, 0)
                GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, positions)
                GLES20.glVertexAttribPointer(coordinateLocation, 2, GLES20.GL_FLOAT, false, 0, coordinates)
                GLES20.glEnableVertexAttribArray(positionLocation)
                GLES20.glEnableVertexAttribArray(coordinateLocation)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
            checkGl("draw")
            check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { eglError("swap buffers") }
            lastDraw = SystemClock.uptimeMillis()
            if (outputResizePending) {
                outputResizePending = false
                // Some EGL implementations apply native-window resizing only during the swap.
                check(EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, surfaceDimension, 0)) { eglError("query resized width") }
                val resizedWidth = surfaceDimension[0]
                check(EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, surfaceDimension, 0)) { eglError("query resized height") }
                if (frames.changedAfterSwap(outputSize, resizedWidth, surfaceDimension[0])) scheduleDraw()
            }
            if (!presenting) return@guarded
            if (sampleStart == 0L)
                progress("Playing: first frame presented; collecting presentation rate (not source FPS/latency).")
            if (sampleStart == 0L) sampleStart = lastDraw
            presented++
            val elapsed = lastDraw - sampleStart
            if (elapsed >= 2000) {
                val current = checkNotNull(geometry)
                progress("Playing: content ${current.source}, capture buffer ${current.buffer}; " +
                    "presented ${(presented * 1000.0 / elapsed).toInt()} FPS over ${elapsed} ms (not source FPS/latency).")
                presented = 0; sampleStart = lastDraw
            }
        }
    }
    private fun scheduleDraw() {
        if (!frames.queueDraw()) return
        check(handler.postDelayed(draw, (lastDraw + 16L - SystemClock.uptimeMillis()).coerceAtLeast(0))) {
            "Render thread rejected presentation redraw"
        }
    }
    private fun initializeEgl(output: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { eglError("get display") }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) { eglError("initialize") }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0) {
            eglError("choose window config")
        }
        val config = checkNotNull(configs[0])
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { eglError("create context") }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { eglError("create output surface") }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { eglError("make current") }
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, count, 0)
        maxTextureSize = count[0]
        GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, maxViewport, 0)
        check(EGL14.eglSwapInterval(eglDisplay, 1)) { eglError("set swap interval") }
        GLES20.glGenTextures(1, count, 0)
        texture = count[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = GLES20.glCreateProgram()
        val vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec2 position; attribute vec2 coordinate; uniform mat4 transform; varying vec2 uv;" +
                "void main(){ gl_Position=vec4(position,0.0,1.0); uv=(transform*vec4(coordinate,0.0,1.0)).xy; }")
        try {
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 uv;" +
                    "uniform samplerExternalOES image; void main(){ gl_FragColor=texture2D(image,uv); }")
            try {
                GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment)
                GLES20.glLinkProgram(program)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, count, 0)
                check(count[0] != 0) { "GPU program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
            } finally { GLES20.glDeleteShader(fragment) }
        } finally { GLES20.glDeleteShader(vertex) }
        positionLocation = GLES20.glGetAttribLocation(program, "position")
        coordinateLocation = GLES20.glGetAttribLocation(program, "coordinate")
        matrixLocation = GLES20.glGetUniformLocation(program, "transform")
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        checkGl("initialize")
        surfaceTexture = SurfaceTexture(texture).apply {
            setOnFrameAvailableListener({
                guarded {
                    frames.frameAvailable()
                    scheduleDraw()
                }
            }, handler)
        }
        input = Surface(checkNotNull(surfaceTexture))
    }
    private fun shader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        try {
            GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "GPU shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        } catch (e: Exception) { GLES20.glDeleteShader(shader); throw e }
    }
    override fun close(done: (String?) -> Unit) {
        if (!closing.compareAndSet(false, true)) return
        val queued = handler.post {
            handler.removeCallbacks(draw)
            handler.removeCallbacks(pollPhone)
            handler.removeCallbacks(reconcileResize)
            handler.removeCallbacks(retryResize)
            var failure: Exception? = null
            fun release(action: () -> Unit) {
                try { action() } catch (e: Exception) {
                    android.util.Log.e("PartialCast", "Local capture cleanup failed", e)
                    val previous = failure
                    if (previous == null) failure = e else previous.addSuppressed(e)
                }
            }
            release { virtualDisplay?.release() }; virtualDisplay = null
            release { if (callbackRegistered) projection.unregisterCallback(callback) }
            release { projection.stop() }
            release { input?.release() }; input = null
            release { surfaceTexture?.setOnFrameAvailableListener(null) }
            release { surfaceTexture?.release() }; surfaceTexture = null
            release {
                if (program != 0) GLES20.glDeleteProgram(program)
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                if (program != 0 || texture != 0) checkGl("delete resources")
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                release { check(EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) { eglError("unbind") } }
                release { if (eglSurface != EGL14.EGL_NO_SURFACE) check(EGL14.eglDestroySurface(eglDisplay, eglSurface)) { eglError("destroy surface") } }
                release { if (eglContext != EGL14.EGL_NO_CONTEXT) check(EGL14.eglDestroyContext(eglDisplay, eglContext)) { eglError("destroy context") } }
                release { check(EGL14.eglTerminate(eglDisplay)) { eglError("terminate") } }
                release { check(EGL14.eglReleaseThread()) { eglError("release thread") } }
            }
            thread.quitSafely()
            done(failure?.let { "Local GPU cleanup failed: ${it.message}. Restart the app before retrying." })
        }
        if (!queued) done("Render thread rejected cleanup; restart the app before retrying.")
    }
    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "OpenGL $operation error 0x${error.toString(16)}" }
    }
    private fun eglError(operation: String) = "EGL $operation error 0x${EGL14.eglGetError().toString(16)}"
    companion object {
        private fun floats(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    }
}
