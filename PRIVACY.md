# PartialCast privacy

PartialCast uses Android's ordinary MediaProjection screen-sharing permission
only after explicit Play and consent. Screen content, including personal
notifications, is sent on-device through GPU surfaces to the chosen local
presentation display. People/devices observing that display can see the shared
content. A presentation target can be wireless; Android/device display routing
is outside PartialCast's control.

The app does not save, encode, upload, log, analyze or transmit capture through
an app-managed network. Internet permission is used solely for anonymous
GitHub release metadata; there is no microphone permission, analytics,
advertising SDK, remote receiver, captured audio or playback loopback.
GPU buffers necessarily hold transient screen content during an owned session;
Stop releases owned display, projection, surfaces, texture and EGL resources.
Protected/secure content may be blank; protection is not bypassed.

Only crop width, height, X and Y are saved in app-private preferences.
Display choice, consent tokens and sessions are not persisted. App backup is
disabled. Local diagnostic logs contain lifecycle reasons and geometry/rate
metadata, not screen content or persistent device identifiers.

Home/app switching can continue an already-consented session through a
mediaProjection foreground service and its existing Activity-owned Presentation.
Use Stop in Home or the notification. If notification permission is denied,
Android's foreground-task controls remain available; no system settings are
changed to grant permission. Back or destruction/recreation of the actual
session-owning Activity, task removal,
display/surface/window loss, screen off/lock, projection revocation and service
destruction terminate capture. No automatic reconnect or new background consent
is attempted. OEMs may stop/dismiss capture despite the foreground service.

No captured audio is forwarded. Normal Android audio routing may or may not
route audio over the connected display interface.

A fresh Home launch checks the public GitHub latest stable release API once.
GitHub receives ordinary HTTPS request metadata (including IP address) and a
generic updater User-Agent, not screen content, saved crop, credentials, phone
identifiers or analytics. Only newer versions prompt while capture is idle.
Failures/private releases remain silent in the UI and are logged locally.
Upgrade opens the release page in the user's browser; PartialCast does not
download, store or install APKs and requests no installer permission.

This document describes this app's behavior, not an automatically completed
Play Store Data Safety/foreground-service submission. GitHub distribution does
not remove the need for accurate notices, stable signing and user consent.
