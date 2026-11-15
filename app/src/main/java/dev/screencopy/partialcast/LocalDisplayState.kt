package dev.screencopy.partialcast

import java.util.concurrent.CopyOnWriteArrayList

internal data class LocalDisplayTarget(val id: Int, val name: String, val flags: Int = 0,
    val size: VideoSize? = null, val rotation: Int = 0)

/** In-process ownership stays reserved until GPU and window cleanup completes. */
internal class LocalDisplayState {
    enum class Phase { CONSENT, STARTING, PLAYING, WAITING_DISPLAY, STOPPING }
    class Session internal constructor(val token: Long, var target: LocalDisplayTarget, val options: LocalCaptureOptions,
        val autoResume: Boolean) {
        var phase = Phase.CONSENT
            internal set
        var hasPlayed = false
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
            if (!waitForDisplay(it))
                stop(it, "Presentation display disconnected. Reconnect, then press Play.")
        }
        publish()
    }
    fun waitForDisplay(session: Session): Boolean {
        if (!owns(session) || !session.autoResume || !session.hasPlayed || session.phase == Phase.STOPPING) return false
        session.phase = Phase.WAITING_DISPLAY
        message = "Sharing is paused. Reconnect the same display to resume, or Stop."
        publish()
        return true
    }
    fun reconnectTarget(session: Session): LocalDisplayTarget? = targets.singleOrNull {
        it.name == session.target.name && it.flags == session.target.flags
    }
    fun reconnect(session: Session, target: LocalDisplayTarget): Boolean {
        if (!owns(session) || session.phase != Phase.WAITING_DISPLAY || reconnectTarget(session) != target) return false
        session.target = target
        session.phase = Phase.STARTING
        message = "Resuming local presentation"
        publish()
        return true
    }
    fun reserve(id: Int, options: LocalCaptureOptions, autoResume: Boolean = false): Session {
        check(active == null) { "Wait for local output cleanup" }
        val target = targets.singleOrNull { it.id == id }
            ?: error("Presentation display is no longer available")
        val session = Session(nextToken, target, options, autoResume)
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
        session.hasPlayed = true
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
