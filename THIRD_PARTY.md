# Origins and notices

PartialCast's geometry, discovery, reservation and GPU/service helpers are
adapted from the author's public ScrcpySend experimental local presentation
implementation, based on public main
`226b1c533bb1f2381ba09d7a03ccb0a7eae5517f`. This is a separate app and checkout.
No networking/privileged/native code, keys, private data or Git history was copied.
The source worktree's existing modifications were read, not changed.

Runtime code uses the Android platform and AGP's built-in Kotlin standard library 2.2.10
(JetBrains, Apache-2.0). JetBrains annotations 13.0 are Apache-2.0. The complete
Apache-2.0 text is bundled at `app/src/main/assets/licenses/Apache-2.0.txt`;
Kotlin upstream copyright: Copyright JetBrains s.r.o. and Kotlin
Programming Language contributors. Upstream: https://github.com/JetBrains/kotlin.
No unrelated sender dependencies are bundled.

Gradle wrapper 9.8.1 is checksum-pinned and retains its upstream script headers.
Gradle is Apache-2.0, Copyright 2015 the original author or authors.
https://github.com/gradle/gradle. The Apache-2.0 text above also covers the wrapper.
AGP build tooling is Apache-2.0 and is not bundled into the app.

Test-only dependencies (not shipped in the main APK):
- JUnit 4.13.2: EPL-1.0, https://junit.org/junit4/
- Robolectric 4.16.1: MIT, https://github.com/robolectric/robolectric
- AndroidX test runner 1.6.2 / ext JUnit 1.2.1: Apache-2.0,
  https://developer.android.com/jetpack/androidx/releases/test

No project-wide license for publication is selected by these dependency notices.
The owner should explicitly choose one before public source distribution.
