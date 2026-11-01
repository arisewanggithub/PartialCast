package dev.screencopy.partialcast

import java.util.concurrent.CopyOnWriteArrayList

internal data class LocalDisplayTarget(val id: Int, val name: String)

/** In-process ownership stays reserved until GPU and window cleanup completes. */
internal class LocalDisplayState {
    enum class Phase { CONSENT, STARTING, PLAYING, STOPPING }
    class Session internal constructor(val token: Long, val target: LocalDisplayTarget, val options: LocalCaptureOptions) {
        var phase = Phase.CONSENT
            internal set
        var cleanupFailed = false
            internal set
        var cleanupPending = false
            internal set
    }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var nextToken = (java.security.SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1)
    var targets: List<LocalDisplayTarget> = emptyList()
        private set
    var active: Session? = null
        private set
    var message = "Choose a presentation display, then Play."
        private set
    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun removeObserver(listener: () -> Unit) { listeners.remove(listener) }
    private fun publish() { listeners.forEach { it() } }
    fun updateTargets(value: List<LocalDisplayTarget>) {
        targets = value.distinctBy { it.id }
        active?.takeIf { session -> targets.none { it.id == session.target.id } }?.let {
            stop(it, "Presentation display disconnected. Reconnect, then press Play.")
        }
        publish()
    }
    fun reserve(id: Int, options: LocalCaptureOptions): Session {
        check(active == null) { "Wait for local output cleanup" }
        val target = targets.singleOrNull { it.id == id }
            ?: error("Presentation display is no longer available")
        val session = Session(nextToken, target, options)
        nextToken = if (nextToken == Long.MAX_VALUE) 1 else nextToken + 1
        active = session; message = "Waiting for screen capture consent"
        publish()
        return session
    }
    fun owns(session: Session) = active === session
    fun consent(session: Session, granted: Boolean): Boolean {
        if (!owns(session) || session.phase != Phase.CONSENT) return false
        if (!granted) {
            finish(session, "Screen capture permission denied. Press Play to try again.")
            return false
        }
        session.phase = Phase.STARTING; message = "Starting local presentation"
        publish()
        return true
    }
    fun playing(session: Session, detail: String) {
        if (!owns(session) || session.phase !in listOf(Phase.STARTING, Phase.PLAYING)) return
        session.phase = Phase.PLAYING; message = detail; publish()
    }
    fun detail(session: Session, value: String) {
        if (!owns(session) || session.phase !in listOf(Phase.STARTING, Phase.PLAYING)) return
        message = value; publish()
    }
    fun stop(session: Session, reason: String = "Stopped by user"): Boolean {
        if (!owns(session) || session.phase == Phase.STOPPING) return false
        session.phase = Phase.STOPPING; message = reason; publish()
        return true
    }
    fun finish(session: Session, reason: String = message, cleanupComplete: Boolean = true) {
        if (!owns(session) || session.cleanupFailed) return
        if (!cleanupComplete) {
            session.cleanupFailed = true
            session.phase = Phase.STOPPING
            message = "$reason; cleanup is unconfirmed. Restart the app before another Play."
            publish()
            return
        }
        active = null; message = reason
        publish()
    }
    companion object { val shared = LocalDisplayState() }
}
