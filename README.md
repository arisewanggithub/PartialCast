# PartialCast

**Share part or all of your screen**

Independent Android 11+ app (`dev.screencopy.partialcast`) for **local GPU
projection** to a public secondary presentation display. It is not ScrcpySend
or ScrcpyRecv and does not interact with either app.

## Use

1. Connect a display that Android exposes as a public presentation target.
   Choose it on Home. Targets can be wired, wireless, simulated or internal;
   the app does not promise that every target is HDMI. Some phones, adapters
   and USB cables never expose a usable secondary display.
2. Optionally choose **Edit crop**. The only saved/configurable parameters are
   **width, height, X and Y**, in pixels of the phone's **natural/default
   orientation**, not the current rotated orientation.
   All four zero means the whole phone. Otherwise width/height must be positive,
   X/Y nonnegative and the crop origin inside the phone. Edges are clipped
   before rotation. The editor previews current clipped dimensions but saves
   the requested values unchanged. Invalid/noninteger/overflow/outside input
   stays in the editor with an error; it is never silently corrected.
   A failed disk commit restores the previous effective crop before reporting
   failure, so a failed Save does not change the next Play.
   Home refreshes on return and observes shared crop changes, including when
   another Home window is simultaneously visible.
   The padded editor uses compact labeled rows and scrolls on short/landscape
   screens or with the keyboard open; Cancel/Save remain outside the scrolling form.
3. Press **Play**, optionally allow normal notification permission, then approve
   Android screen-capture consent. Every Play requires fresh consent.
4. **Stop** in Home or the foreground notification. Home/app switching does not
   itself stop an existing session. Back or destruction/recreation of the
   Activity that actually owns the session,
   task removal, output removal/surface loss/window dismissal, screen off/lock,
   projection revocation or service destruction ends the session.
   Reconnection never starts capture.

The fullscreen output follows actual source orientation with aspect-fit black
bars, never stretching or forced landscape. Full-resolution capture is used;
unsupported GPU texture/output limits fail visibly rather than reducing quality.
Frame callbacks are coalesced, scheduling waits at least approximately 16 ms
between submissions, and EGL swaps synchronize to the output. Reported swap
rate is **not** source FPS, latency, a thermal limit or a performance guarantee.

No audio is captured. Android's ordinary system/HDMI audio routing is unchanged;
there is **no guarantee of audio forwarding**.

## Privacy and platform limits

See [PRIVACY.md](PRIVACY.md) and [DESIGN.md](DESIGN.md). There is no Internet,
microphone, installer, overlay, privileged capture or root permission. No
encoder/decoder, network receiver, bitmap readback, analytics, bundled updater
or capture files are included. Only the four crop integers are stored, with
app backup disabled. The app cannot enforce exclusivity against another app.

Android 14+ always requests the default phone display with public
`MediaProjectionConfig`. Actual content-size callbacks are validated against
live phone bounds. Detectable OEM app/window substitution is rejected, including
whole-screen mode. **Equal-size substitutions cannot be reliably distinguished
by public callbacks.** Protected content may be blank. OEM window, foreground
service and lock-screen policies still apply: foreground capture is not an
overlay/background-window exemption.
Queued resize reports are reconciled with current phone geometry before source
rejection. While dimensions disagree the output is black/paused; a persistent
mismatch fails after a 1.5-second settling interval on the render thread.
Output surface changes repaint the acquired texture even when the phone content
is static; they do not require a new consent or virtual display.
Crop-only rotations with unchanged capture dimensions retain the acquired or
pending frame; a static final 180-degree frame does not depend on another update.

## Build and validation

Pinned Gradle 9.8.1 (SHA-256 in wrapper properties), AGP 9.4.1 built-in Kotlin,
SDK/target 37 (Android 17), Build Tools 37.0.0, min SDK 30, Java/JVM 17 under JDK 21.
No NDK/CMake/Node is required.

PowerShell (set your own SDK/JDK paths):

```powershell
$env:JAVA_HOME='C:\path\to\jdk-21'
$env:ANDROID_HOME='C:\path\to\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --console=plain
```

Release assembly enables R8 and resource shrinking and is **unsigned**.
Debug builds use Android's ordinary local development signing. No signing
material is stored in this repository. Do not distribute the unsigned release
as an installable APK.

Distribution can use GitHub rather than Play Store. Publication is a separate
authorized step: choose licensing, privately manage a stable release key, sign
and verify the optimized APK, and publish that APK and notices. Updates must
use the same package/key and increasing versionCode. PartialCast contains no
installer/updater; GitHub-distributed updates are user-managed through Android.
Nothing here automatically satisfies Play Console Data Safety or foreground
service declarations/demo requirements if Play is reconsidered later.

The JVM suites exercise geometry, crop storage, display eligibility, real
Activity/service/Presentation lifecycles and an injected GPU boundary. SDK 33
fixtures use paused loopers, legacy graphics and Conscrypt disabled; consent is
simulated, not real capture. The stopped-Activity rotation test uses
Robolectric's display/window-context configuration fixtures. These tests do
**not** validate actual EGL pixels, HDMI latency, OEM window policies, thermal
behavior or long-run stability.

Platform test APKs are compiled only. These results do not include connected
capture tests or emulator acceptance. Separately authorized app deployment/UI
checks do not validate the HDMI/EGL capture pipeline.

Local Windows ARM64 SDK 37 validation: **64 JVM tests passed, zero failures/errors**;
`lintDebug` passed with zero errors (25 non-blocking lint warnings).
All five tasks in the command above passed, including optimized unsigned release
assembly. SDK 33 reproduces frozen portrait Activity metrics while the fresh
provider rotates; live source queries are additionally tested on SDK 30/34.
`aapt2` checks verify min/target 30/37, the distinct package, one exported
launcher Activity, one non-exported mediaProjection service, and only the three
foreground-service/notification permissions. R8/resource-shrink reports and
APK resources/notices are retained; no native libraries or bundled executables
are present. `apksigner verify` accepts debug and rejects unsigned release as
expected. These are build/JVM checks, **not hardware acceptance**.

### Android 17 compatibility

The app uses public Handler/Looper APIs, not MessageQueue reflection or static
final-field mutation. Android 17's local-network permission and background-audio
restrictions do not require new permissions: PartialCast has no app-managed
network or audio APIs. Consent/foreground-service startup still requires the
resumed control Activity, without new background-launch privileges. Modern
Back callbacks explicitly Stop and finish Home instead of relying on Android's
root-Activity default Back behavior; Android 11/12 use the same legacy policy.
Home/app switching remains nonterminal.

Android 17 memory limits and OEM window policies can terminate the process.
The app does not exempt itself or silently downscale. Hardware testing on
Android 17, including background continuation, Back, lock and full-resolution
GPU resource usage, remains required; SDK 37 assembly and older-SDK JVM
fixtures do not simulate Android 17's runtime or establish stability.

## Manual HDMI acceptance (only after explicit installation/capture authorization)

- Confirm unsupported adapters show the useful empty state; choose the intended
  local display, not a feedback source.
- Deny consent; Stop/unplug during consent; send an old notification Stop after
  a later Play. No late callback may start or stop a replacement session.
- Display an asymmetric labeled corner/grid image yourself. With control Home
  **stopped/backgrounded**, rotate phone portrait -> landscape -> portrait,
  then test all four rotations with nonzero crop offsets and clipped edges.
  Confirm external landscape output stays landscape, capture buffer follows
  phone dimensions, crop stays in natural coordinates, aspect is correct,
  and there is no new consent or virtual-display creation in that session.
  Lifecycle logs expose only dimensions/rotation/stop reasons, never pixels.
- Return via launcher/notification without restart. Back, close/recreate the
  Activity, remove task, lock/screen off, revoke capture, disconnect/reconnect,
  dismiss output and lose its surface. Each terminal event must stop safely;
  replug remains idle. Android 14+ content resize must not permit wrong crops.
- Repeated Play/Stop and cleanup failure: fresh capture stays blocked through
  cleanup; an unconfirmed/stalled cleanup asks for an **app process restart**.
  This app does not stop or manipulate other casting apps.
- Check secure-content blanking and normal system audio independently. Measure
  real pixels, swaps, latency, heat and stability on the exact hardware before
  making any performance claims.

Prior art and dependency notices: [THIRD_PARTY.md](THIRD_PARTY.md).
