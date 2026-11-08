package dev.screencopy.partialcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Presentation
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import java.lang.ref.WeakReference

class LocalProjectionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val state = LocalDisplayState.shared
    private var owner: LocalDisplayState.Session? = null
    private var renderer: ProjectionOutput? = null
    private var projection: MediaProjection? = null
    private var presentation: Presentation? = null
    private var retiring = false
    private var surfaceStarted = false
    private var destroying = false
    private lateinit var discovery: LocalDisplayDiscovery
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF)
                state.active?.let { stop(it, "Phone screen turned off or locked; press Play for new consent.") }
        }
    }
    private val observer: () -> Unit = {
        owner?.takeIf { !state.owns(it) || it.phase == LocalDisplayState.Phase.STOPPING }
            ?.let { retire(it, state.message) }
    }
    override fun onCreate() {
        super.onCreate()
        running = WeakReference(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("local-presentation", "Local presentation", NotificationManager.IMPORTANCE_LOW))
        discovery = LocalDisplayDiscovery(this) {
            state.updateTargets(it)
            renderer?.phoneChanged()
        }
        discovery.start()
        state.observe(observer)
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getLongExtra("localSession", -1) ?: -1
        if (intent?.action == "stop") {
            state.active?.takeIf { it.token == token }?.let { stop(it, "Stopped by user") }
            if (owner == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != "start") {
            if (owner == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (owner != null) return START_NOT_STICKY
        val session = state.active?.takeIf { it.token == token && it.phase == LocalDisplayState.Phase.STARTING }
        if (session == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        try {
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("consent", Intent::class.java)
                else intent.getParcelableExtra<Intent>("consent")
            checkNotNull(data) { "Missing screen capture consent" }
            startForeground(2, notification(token), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            owner = session
            session.cleanupPending = true
            retiring = false
            surfaceStarted = false
            val activity = HomeActivity.host(session)
                ?: error("Local control screen is no longer visible. Press Play again.")
            check(activity.display?.displayId == android.view.Display.DEFAULT_DISPLAY) {
                "Open PartialCast on the phone's default display, not on the output display"
            }
            val target = getSystemService(DisplayManager::class.java).getDisplay(session.target.id)
            check(target != null && LocalDisplayDiscovery.eligible(target, captureDisplayId)) {
                "Presentation display disconnected while requesting consent. Reconnect and press Play."
            }
            val capture = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(android.app.Activity.RESULT_OK, data)
                ?: error("Android rejected screen capture consent")
            projection = capture
            val current = rendererFactory(capture, session.options, PhoneGeometry(applicationContext)::read,
                { reason -> main.post { if (owner === session) retire(session, reason) } },
                { detail -> main.post {
                    if (owner === session && !retiring) {
                        if (detail.startsWith("Playing:")) state.playing(session, detail)
                        else state.detail(session, detail)
                    }
                } },
                { id -> main.post { if (owner === session && !retiring) captureDisplayId = id } })
            renderer = current
            projection = null // Renderer now owns projection lifetime, including failed initialization.
            val output = object : Presentation(activity, target) {
                override fun onCreate(savedInstanceState: android.os.Bundle?) {
                    super.onCreate(savedInstanceState)
                    val view = SurfaceView(context)
                    setContentView(view)
                    window?.setBackgroundDrawableResource(android.R.color.black)
                    window?.setDecorFitsSystemWindows(false)
                    window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    window?.insetsController?.let {
                        it.hide(WindowInsets.Type.systemBars())
                        it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                    view.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            if (owner !== session || retiring || !state.owns(session)) return
                            if (!surfaceStarted) {
                                surfaceStarted = true
                                current.start(holder.surface, width, height)
                            } else current.resizeOutput(width, height)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            if (owner === session && !retiring) retire(session, "Presentation surface lost. Press Play to start a new session.")
                        }
                    })
                }
            }
            presentation = output
            output.setOnDismissListener {
                if (owner === session && !retiring) retire(session, "Presentation dismissed by Android. Press Play again.")
            }
            output.show()
            output.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            main.postDelayed({
                if (owner === session && !retiring && session.phase != LocalDisplayState.Phase.PLAYING)
                    retire(session, if (!surfaceStarted)
                        "Display did not provide a rendering surface. Check the adapter/display and press Play again."
                    else "Android did not provide a captured frame/content-size callback within 10 seconds. Check capture permission/display and press Play again.")
            }, 10_000)
        } catch (e: Exception) {
            android.util.Log.e("PartialCast", "Could not start local presentation", e)
            val reason = if (e is WindowManager.InvalidDisplayException)
                "Android cannot present on this display. Check display availability and keep the local control screen visible."
            else "Could not start local presentation: ${e.message ?: e.javaClass.simpleName}"
            owner = session
            session.cleanupPending = true
            retire(session, reason)
        }
        return START_NOT_STICKY
    }
    private fun notification(token: Long): Notification {
        val stop = PendingIntent.getService(this, 2,
            Intent(this, LocalProjectionService::class.java).setAction("stop")
                .putExtra("localSession", token).setData(android.net.Uri.parse("partialcast://stop/$token")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "local-presentation")
            .setSmallIcon(R.drawable.ic_partialcast_foreground)
            .setContentTitle("PartialCast is sharing your phone screen")
            .setContentText("Local presentation; system audio routing unchanged")
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    private fun retire(session: LocalDisplayState.Session, reason: String) {
        if (owner !== session || retiring) return
        retiring = true
        android.util.Log.i("PartialCast", "local_projection event=stop reason=$reason")
        state.stop(session, reason)
        val current = renderer
        main.postDelayed({
            if (owner === session && retiring) {
                android.util.Log.e("PartialCast", "Owned cleanup did not confirm completion within 10 seconds")
                state.finish(session, "$reason; cleanup timed out", cleanupComplete = false)
            }
        }, 10_000)
        if (current != null) current.close { failure ->
            main.post { finish(session, failure ?: reason, failure == null) }
        } else {
            val abandoned = projection
            projection = null
            if (abandoned == null) {
                finish(session, reason, true)
            } else {
                val cleanupThread = android.os.HandlerThread("partialcast-startup-cleanup").apply { start() }
                Handler(cleanupThread.looper).post {
                    var cleanupError: String? = null
                    try { abandoned.stop() } catch (e: Exception) {
                        android.util.Log.e("PartialCast", "Local projection cleanup failed", e)
                        cleanupError = "Projection cleanup failed: ${e.message}"
                    } finally { cleanupThread.quitSafely() }
                    main.post { finish(session, cleanupError ?: reason, cleanupError == null) }
                }
            }
        }
    }
    private fun finish(session: LocalDisplayState.Session, reason: String, cleanupComplete: Boolean) {
        if (owner !== session) return
        var result = reason
        var clean = cleanupComplete
        try { presentation?.dismiss() } catch (e: Exception) {
            android.util.Log.e("PartialCast", "Local Presentation dismissal failed", e)
            result = "$result; display window cleanup failed: ${e.message}"
            clean = false
        }
        presentation = null; renderer = null; captureDisplayId = -1
        owner = null
        state.finish(session, result, clean)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!destroying) stopSelf()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        state.active?.let { stop(it, "Local presentation stopped because the task was removed.") }
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        destroying = true
        discovery.stop()
        unregisterReceiver(screenOff)
        state.removeObserver(observer)
        owner?.let { retire(it, "Local presentation service destroyed. Press Play for new consent.") }
        if (running.get() === this) running.clear()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private var running = WeakReference<LocalProjectionService>(null)
        var captureDisplayId = -1
            private set
        internal fun stop(session: LocalDisplayState.Session, reason: String) {
            val service = running.get()
            if (service?.owner === session) service.retire(session, reason)
            else {
                LocalDisplayState.shared.stop(session, reason)
                if (!session.cleanupPending) LocalDisplayState.shared.finish(session, reason)
            }
        }
        internal var rendererFactory: (
            MediaProjection, LocalCaptureOptions, () -> PhoneSnapshot,
            (String) -> Unit, (String) -> Unit, (Int) -> Unit
        ) -> ProjectionOutput = ::LocalProjectionRenderer
    }
}
