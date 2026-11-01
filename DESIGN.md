# Local-only projection design

```text
Home display selection + validated four-integer crop store
  -> in-process session reservation / explicit default-phone consent
  -> mediaProjection foreground service (only while host resumed at startup)
  -> one virtual display -> SurfaceTexture / external-OES texture
  -> crop coordinates composed with each SurfaceTexture transform
  -> aspect-fit OpenGL ES / EGL swap -> fullscreen Presentation SurfaceView
```

`LocalDisplayState` uses positive random-seeded generation tokens and object
identity. Pending consent and cleanup are exclusive **within this process only**.
Request codes are unique for the process lifetime, so a late old consent cannot
authorize a new Play even when Android returns identical consent Intent actions.
Notification Stop carries its session token. Late display/surface/frame/status/
cleanup callbacks cannot revive a retired owner. Cleanup failure or its ten-second
watchdog holds the gate until process restart, even if cleanup later succeeds.
An explicit pending-cleanup marker survives service destruction/weak-host removal,
so a later Home Stop cannot release ownership ahead of render-thread completion.
The service is not sticky and never restores saved consent.

The service owns the existing Presentation but its window uses the visible
Home Activity context. All Presentation/view/lifecycle work runs on main.
Home stopping does not dismiss it. Back/destruction/recreation is explicitly
terminal: stale Activity windows are not retained/reparented. Returning to the
same Activity brings control back without a new consent/display. Creating a
window still requires a resumed host; the foreground service does not grant
overlay permissions. Platform window loss is terminal.
Only explicit Play registers that Activity as the host. Additional Home
instances can explicitly Stop, but do not adopt an existing session or retire
another owner's Presentation on Back/destruction.

Projection callbacks, virtual-display creation/resize/rebind/release, input
surfaces/textures and EGL/GL operations are serialized on an owned HandlerThread.
Cleanup atomically closes admission before queuing releases, collects individual
release failures, calls quitSafely and reports to main. No UI join or render-thread
self-join is used. A projection obtained before renderer ownership, but abandoned
by startup failure, is stopped on a dedicated cleanup thread.

## Rotation correction

The experimental sender queried `Activity.maximumWindowMetrics` from a
backgrounded control Activity. Its stale portrait dimensions could leave the
capture virtual display portrait despite landscape phone/output displays.

`PhoneGeometry` starts from the **application** DEFAULT-display manager and
creates a fresh public display/window context (API 31+) for each geometry read;
size, rotation and density are taken from that context, never hidden Activity
metrics or the physical display mode. API 30 uses fresh display-context public
logical real metrics. Fresh window metrics respect logical display overrides.
Display callbacks plus a half-second render-thread poll handle background
changes/missed notifications on older Android. Polling is not a frame allocation.
The public KeyguardManager state is also checked during source queries, so lock
is terminal even if a screen-off broadcast is missed.

On Android 14+, actual captured-content resize is authoritative; incompatible
default-phone dimensions are rejected. Display updates still remap same-size
rotations (including 180 degrees). Resize changes the existing VD dimensions
and SurfaceTexture default buffer size, rebinds the same input surface, and
recomputes crop/viewport. There is only one createVirtualDisplay call per
renderer/session; the callback is registered before it. Public default-display
consent is requested for both whole-screen and cropped sharing. Same-size OEM
app substitutions remain unidentifiable, not claimed solved.
Resize reconciliation runs behind already-queued content callbacks, so an old
rotation report does not retire a newer valid orientation. Mismatches pause
rendering to black and are retried every 100 ms with a 1.5-second settling bound
before an explicit unsupported-source error. Cleanup removes both reconciliation
and retry callbacks. Drawing distinguishes pending new frames from cached images:
output resize can repaint the cached texture without consuming a new frame,
while source/buffer-dimension changes invalidate the old image. Crop-only changes
retain acquired and pending frames, update coordinates/viewport, and do not
resize/rebind an unchanged capture buffer (including 180-degree rotation).
Black-only swaps do
not mark startup as Playing.
After an output resize, dimensions are also checked after the first EGL swap:
implementations that apply native-window resizing during that swap get another
cached repaint with the corrected viewport, even without a new capture frame.

Crop persistence validates before writing. Since SharedPreferences.commit()
updates its memory before returning a disk failure, failed writes roll back the
four previous values before returning an error; an unconfirmed disk rollback is
also logged. Activity and store fixtures cover failed-save/next-Play behavior.
Home observes the four shared preference keys and refreshes on resume, keeping
returning and simultaneously visible controls consistent with the next Play.
Observers are removed at Activity destruction and ignore late notifications.

Capture geometry clips in natural coordinates with overflow-safe arithmetic,
rotates the rectangle, and preserves a full-resolution source buffer (one pixel
is never turned into a subpixel crop). Invalid fractional/overflow editor input
is rejected. GPU texture and viewport limits are explicit errors. Texture matrices
are read on every updated frame. EGL queries actual output-surface dimensions
and recomputes centered black-bar viewport as needed. Geometry/coordinate buffers
are reused between size changes; there is no ImageReader/CPU pixel readback.

## Evidence boundaries

The app compiles/targets Android 17 (API 37), retaining min SDK 30. The reviewed
[targeted changes](https://developer.android.com/about/versions/17/behavior-changes-17)
and [all-app changes](https://developer.android.com/about/versions/17/behavior-changes-all)
do not introduce LAN/audio permissions into the GPU pipeline. The separately
requested launch update check uses Internet only for public GitHub metadata.
Handler/Looper calls stay public and no static final fields are mutated.
Consent is foreground-only; notification return does not start new capture.
An explicit registered Back callback on API 33+ (legacy onBackPressed below)
retires the session and finishes Home, while Home/app switching keeps it alive.
Android 17 process memory limits are not disabled or bypassed.
Actual Android 17 runtime behavior is not covered by the older-SDK JVM fixtures.

## Update prompt boundary

`GitHubUpdateSource` follows the sender/receiver prior art's bounded anonymous
metadata HTTP requests and validates the exact repository/tag/asset paths.
It does not import their downloader, installer, protocol or credentials.
Stable semantic versions are compared numerically; versioned and legacy asset
names work. Redirects, oversized/incomplete metadata and failed/private
responses are rejected; rate-limit cooldowns do not retry on resume.
`LaunchUpdateCheck` owns a one-shot background worker and retains check/handled
state across configuration changes without retaining an Activity. Destruction
detaches UI and cancels non-retained work. Home presents results only foreground
and idle; Later/Back dismisses for the launch. Upgrade opens the validated
release page through a browser, never stops or restarts projection.

The SDK 33 fixture reproduces stale portrait maximumWindowMetrics using a real
stopped Activity with frozen configuration, while the fresh provider follows
portrait -> landscape -> portrait and all four rotations. SDK 30/34 fixtures
also verify live provider dimensions/rotation; their window shadows do not
reproduce that same frozen-Activity behavior.

JVM geometry tests and stopped-Activity live DEFAULT-display fixtures cover all
crop rotations, portrait/landscape buffer expectations and letterboxing.
Real Activity and service fixtures exercise deferred foreground start, background
continuation/return, request generation, dismissal, task/service cleanup and
cleanup failure. Service fixtures inject the GPU boundary and simulate ordinary
consent: they do not create a real MediaProjection or verify rendered pixels.
Actual one-VD rotation, Android 14+ callbacks, lock behavior and OEM HDMI output
need the explicitly authorized manual checklist in [README.md](README.md).

Public API rationale:
- [Android MediaProjection guide](https://developer.android.com/media/grow/media-projection)
  (default-display opt-out, one-use consent, existing display/content resize).
- [Android 13 Display implementation](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/core/java/android/view/Display.java)
  (Activity/resource max-bounds and fixed-rotation adjustments explain why
  an Activity-associated size/rotation source is inappropriate when stale).
