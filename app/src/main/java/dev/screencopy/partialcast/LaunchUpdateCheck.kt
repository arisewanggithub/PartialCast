package dev.screencopy.partialcast

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Retains one metadata check across configuration recreation, never its Activity. */
internal class LaunchUpdateCheck(
    private val source: UpdateSource,
    private val installedVersion: String,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
) {
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var closed = false
    private var listener: (() -> Unit)? = null
    var available: ReleaseUpdate? = null
        private set
    var handled = false
        private set

    fun attach(changed: () -> Unit) { listener = changed }
    fun detach() { listener = null }
    fun start() {
        if (started || closed) return
        started = true
        worker.execute {
            val result = try { source.check(installedVersion) }
            catch (e: Exception) {
                Log.w("PartialCast", "Launch update metadata check failed; no prompt shown", e)
                null
            }
            main.post {
                if (!closed) {
                    available = result
                    listener?.invoke()
                }
            }
        }
        worker.shutdown()
    }
    fun dismiss() { handled = true }
    fun close() {
        closed = true
        detach()
        source.cancel()
        worker.shutdownNow()
    }
}
