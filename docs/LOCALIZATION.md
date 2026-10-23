# Chinese UI localization

The app follows the Android system language. Chinese locales use `app/src/main/res/values-zh/strings.xml`; other locales retain the English fallback resources. The existing Chinese user guide is `README.zh.md`.

Only presentation code and string resources are changed. The main screen, dialer recovery screen, app label, notification channels and notifications use resources. SRTP labels are translated independently of their persisted `off` / `optional` / `mandatory` values. The built-in SIP stack label is translated independently of its `builtin` identifier.

`UiStatusText.kt` adapts legacy English status strings only when rendering screens and notifications. Those strings are also used by ADB and logs, so their producers remain unchanged. Exact matches and anchored known templates are translated; unknown diagnostics and opaque error details remain verbatim. Nested templates have a depth limit. SIP response reasons, session identifiers, low-level audio diagnostics, health keys and raw logs intentionally remain unchanged for troubleshooting.

No changes are made to SIP/RTP, call routing, audio processing, Shizuku setup, default-dialer transitions, settings storage, credential handling, component declarations or build settings.

## Validation

Run:

```sh
./gradlew :sip:test :gateway:test :app:testDebugUnitTest :app:assembleDebug
```

`UiStatusTextTest` covers both SIP registration formats, notification prefixes, call summaries, opaque failure details, unknown input and bounded recursion. The Android build requires the SDK and generated pjsua2 bindings/native libraries as described in the project guide.

Before release, compare an English and Chinese device/emulator: main screen and advanced settings; invalid ports (empty, 0, 65536) and valid ports (1, 5060, 65535); save/apply and pending-call settings; takeover on/off and dialer recovery; notifications; SIP registration and bidirectional test calls. Text expansion and physical call/audio behavior require device verification.

In the localization preparation environment, XML key/placeholder/reference checks and source boundary review passed. Gradle execution was blocked before configuration because the distribution host was unreachable. Compilation, Android unit tests, APK generation and physical-device behavior were not verified there.
