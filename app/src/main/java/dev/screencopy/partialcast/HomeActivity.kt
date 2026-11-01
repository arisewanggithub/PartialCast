package dev.screencopy.partialcast

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.lang.ref.WeakReference

class HomeActivity : Activity() {
    private val state = LocalDisplayState.shared
    private var session: LocalDisplayState.Session? = null
    private var pendingConsent: Intent? = null
    private var resumed = false
    private var backCallback: OnBackInvokedCallback? = null
    private var notificationRequest: Pair<Int, LocalDisplayState.Session>? = null
    private var consentRequest: Pair<Int, LocalDisplayState.Session>? = null
    private lateinit var discovery: LocalDisplayDiscovery
    private lateinit var store: CropStore
    private var stopObservingCrop: (() -> Unit)? = null
    private lateinit var phone: PhoneGeometry
    private lateinit var status: TextView
    private lateinit var cropSummary: TextView
    private lateinit var displays: Spinner
    private lateinit var play: Button
    private lateinit var stop: Button
    private lateinit var edit: Button
    private var targets: List<LocalDisplayTarget> = emptyList()
    private val observer: () -> Unit = {
        if (targets != state.targets) {
            val selected = targets.getOrNull(displays.selectedItemPosition)?.id
            targets = state.targets
            displays.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                targets.map { "${it.name} (display ${it.id})" })
            targets.indexOfFirst { it.id == selected }.takeIf { it >= 0 }?.let(displays::setSelection)
        }
        val active = state.active
        status.text = if (active == null && targets.isEmpty())
            "No public presentation display is available. Connect a supported display/adapter. " +
                "Some phones and USB cables cannot expose a secondary display. Wireless or simulated displays may also appear.\n${state.message}"
            else state.message
        play.isEnabled = active == null && targets.isNotEmpty()
        displays.isEnabled = active == null
        edit.isEnabled = active == null
        stop.isEnabled = active != null && active.phase != LocalDisplayState.Phase.STOPPING
        active?.takeIf { it.phase == LocalDisplayState.Phase.STOPPING }
            ?.let { LocalProjectionService.stop(it, state.message) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = OnBackInvokedCallback { closeControl() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
        store = CropStore(this)
        phone = PhoneGeometry(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val padding = (20 * resources.displayMetrics.density).toInt()
        root.setPadding(padding, padding, padding, padding)
        setContentView(ScrollView(this).apply { addView(root) })
        window.decorView.setOnApplyWindowInsetsListener { _: View, insets: WindowInsets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            root.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        root.addView(TextView(this).apply { setText(R.string.app_name); textSize = 30f })
        root.addView(TextView(this).apply { setText(R.string.subtitle); textSize = 18f })
        root.addView(TextView(this).apply {
            tag = "app-version"
            text = installedVersionLabel()
            textSize = 14f
        })
        cropSummary = TextView(this).also(root::addView)
        edit = Button(this).apply { text = "Edit crop"; setOnClickListener { editCrop() } }.also(root::addView)
        root.addView(TextView(this).apply { text = "Presentation display" })
        displays = Spinner(this).also(root::addView)
        play = Button(this).apply { text = "Play"; setOnClickListener { beginPlay() } }.also(root::addView)
        stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { state.active?.let { LocalProjectionService.stop(it, "Stopped by user") } }
        }.also(root::addView)
        status = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }.also(root::addView)
        root.addView(TextView(this).apply {
            text = "Full-resolution phone capture; aspect-fit black bars. No captured audio: Android controls normal system/HDMI audio routing.\n\n" +
                "Home and app switching keep a started session running if Android keeps its window available. " +
                "Stop, Back, closing this Activity, removing the task, unplugging the display or system capture Stop ends it. " +
                "Secure content may be blank. No cross-app exclusivity is enforced."
        })
        refreshCrop()
        stopObservingCrop = store.observe { if (!isDestroyed) refreshCrop() }
        state.observe(observer)
        discovery = LocalDisplayDiscovery(this, state::updateTargets)
        discovery.start()
    }

    private fun refreshCrop() {
        try {
            cropSummary.text = store.load().options().summary()
        } catch (e: IllegalArgumentException) {
            report("Saved crop is invalid: ${e.message}. Edit it before Play.")
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionLabel(): String {
        val installed = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e("PartialCast", "Could not read installed package version", e)
            return getString(R.string.version_unavailable)
        }
        val name = installed.versionName
        if (name.isNullOrBlank() || installed.longVersionCode <= 0) {
            Log.e("PartialCast", "Installed package version metadata is missing or invalid")
            return getString(R.string.version_unavailable)
        }
        return getString(R.string.app_version, name, installed.longVersionCode)
    }

    private fun editCrop() {
        val saved = store.load()
        val unit = resources.displayMetrics.density
        val padding = (24 * unit).toInt()
        val spacing = (8 * unit).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, spacing, padding, spacing)
        }
        val scroll = ScrollView(this).apply {
            addView(root)
        }
        root.addView(TextView(this).apply {
            text = "Pixels in the phone's natural orientation. All four zero shares the whole screen. " +
                "Otherwise width/height must be positive and X/Y nonnegative. Edges are clipped."
            setPadding(0, 0, 0, spacing)
        })
        val fields = listOf("Width" to saved.width, "Height" to saved.height, "X" to saved.x, "Y" to saved.y).mapIndexed { index, (name, value) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isBaselineAligned = false
            }.also(root::addView)
            row.addView(TextView(this).apply { text = name },
                LinearLayout.LayoutParams((72 * unit).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setSingleLine(true)
                minHeight = (48 * unit).toInt()
                imeOptions = if (index == 3) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
                setText(value.toString()); setSelectAllOnFocus(true)
                contentDescription = "Crop $name"
            }.also { row.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        val error = TextView(this).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(getColor(android.R.color.holo_red_dark))
        }.also(root::addView)
        val dialog = AlertDialog.Builder(this).setTitle("Crop").setView(scroll)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    check(state.active == null) { "Stop capture before editing" }
                    val value = CropSettings.parse(fields.map { it.text.toString() })
                    val geometry = store.save(value, phone.read())
                    refreshCrop()
                    val rect = geometry.crop
                    status.text = "Saved. Current clipped output: ${rect.width} x ${rect.height}. Requested values were preserved."
                    dialog.dismiss()
                } catch (e: Exception) {
                    Log.e("PartialCast", "Crop validation/save failed", e)
                    error.text = e.message ?: "Could not save crop"
                    error.visibility = View.VISIBLE
                    scroll.post { scroll.smoothScrollTo(0, root.height) }
                }
            }
        }
        dialog.show()
    }

    internal fun beginPlay() {
        if (!resumed || state.active != null) return
        try {
            check(display?.displayId == Display.DEFAULT_DISPLAY) { "Open PartialCast on the default phone display, not the output display." }
            val crop = store.load()
            crop.validate(phone.read())
            val target = targets.getOrNull(displays.selectedItemPosition) ?: error("Choose an available presentation display")
            session = state.reserve(target.id, crop.options())
            host = WeakReference(this)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                val request = nextRequestCode()
                notificationRequest = request to checkNotNull(session)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request)
            } else requestConsent()
        } catch (e: Exception) {
            session?.takeIf(state::owns)?.let { LocalProjectionService.stop(it, "Could not request capture: ${e.message}") }
            report("Could not start: ${e.message}")
        }
    }

    private fun requestConsent() {
        val current = session ?: return
        if (!resumed || !state.owns(current) || current.phase != LocalDisplayState.Phase.CONSENT) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        val consent = if (Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
        val request = nextRequestCode()
        consentRequest = request to current
        startActivityForResult(consent, request)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val request = notificationRequest?.takeIf { it.first == requestCode } ?: return
        notificationRequest = null
        if (!state.owns(request.second) || session !== request.second) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED)
            Log.i("PartialCast", "Notification permission denied; Android foreground task controls remain available")
        notificationResultPending = true
        continueNotificationConsent()
    }
    private var notificationResultPending = false
    private fun continueNotificationConsent() {
        if (!resumed || !notificationResultPending) return
        notificationResultPending = false
        try { requestConsent() } catch (e: Exception) {
            session?.let { LocalProjectionService.stop(it, "Consent unavailable: ${e.message}") }
            report("Consent unavailable: ${e.message}")
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val request = consentRequest?.takeIf { it.first == requestCode } ?: return
        consentRequest = null
        val current = request.second
        if (session !== current) return
        if (!state.consent(current, resultCode == RESULT_OK && data != null)) return
        pendingConsent = data
        startConsentedSession()
    }
    private fun startConsentedSession() {
        if (!resumed) return
        val current = session ?: return
        val data = pendingConsent ?: return
        pendingConsent = null
        if (!state.owns(current) || current.phase != LocalDisplayState.Phase.STARTING) return
        try {
            startForegroundService(Intent(this, LocalProjectionService::class.java).setAction("start")
                .putExtra("localSession", current.token).putExtra("consent", data))
        } catch (e: Exception) {
            LocalProjectionService.stop(current, "Foreground capture service unavailable: ${e.message}")
            report("Foreground capture service unavailable: ${e.message}")
        }
    }
    override fun onResume() {
        super.onResume()
        refreshCrop()
        resumed = true
        continueNotificationConsent()
        startConsentedSession()
    }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onStop() {
        state.active?.let { Log.i("PartialCast", "host_hidden phase=${it.phase} action=keep_session") }
        super.onStop()
    }
    // API 30-32 fallback; newer versions use the registered OnBackInvokedCallback.
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = closeControl()
    private fun closeControl() {
        session?.takeIf(state::owns)?.let { LocalProjectionService.stop(it, "Stopped by Back; press Play for new consent.") }
        finish()
    }
    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33)
            backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        backCallback = null
        pendingConsent = null
        notificationResultPending = false
        notificationRequest = null
        consentRequest = null
        session?.takeIf(state::owns)?.let { LocalProjectionService.stop(it, "Control Activity closed; press Play for new consent.") }
        stopObservingCrop?.invoke()
        stopObservingCrop = null
        state.removeObserver(observer)
        discovery.stop()
        if (host.get() === this) host.clear()
        super.onDestroy()
    }
    private fun report(message: String) {
        Log.e("PartialCast", message)
        if (::status.isInitialized) status.text = message
        if (::cropSummary.isInitialized) cropSummary.text = message
    }
    companion object {
        private var requestSequence = 700
        private fun nextRequestCode(): Int {
            check(requestSequence < 65534) { "Consent request identifiers exhausted; restart the app." }
            return ++requestSequence
        }
        private var host = WeakReference<HomeActivity>(null)
        internal fun host(session: LocalDisplayState.Session): HomeActivity? =
            host.get()?.takeIf { it.session === session && it.resumed && !it.isFinishing && !it.isDestroyed }
    }
}
