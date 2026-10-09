# VoiceControl Assistant

VoiceControl Assistant is an Android/Kotlin app foundation for voice input,
owner-verification extensions, accessible UI automation, an optional floating
overlay, Gemini text/code generation, safe automation-plan previews, and local
automation history.

## Build in GitHub Codespaces

Install an Android SDK with Android platform 35 and build-tools, and use JDK 17
or 21. If a newer default JDK is on PATH, the project wrapper prefers a
compatible JDK 17/21 installation automatically. Then, from the repository
root, run:

```sh
./gradlew clean
./gradlew test
./gradlew assembleDebug
```

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. No Gemini API key is required at
build time. In Codespaces, download that APK from the Explorer or use the
workspace's supported file-download mechanism.

## Install the APK

Transfer `app-debug.apk` to an Android device, open it, and approve Android's
install-from-this-source prompt if shown. For a connected device with ADB
available, install the debug build with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Enable permissions and start the assistant

Open **Permissions** in the app:

1. Grant microphone access when prompted.
2. Open Android Accessibility settings and explicitly enable **VoiceControl
   Assistant**.
3. Grant permission to display over other apps.
4. Return to Home and start the assistant only when you want to use the floating
   voice controls. Stop it when finished.

The overlay is compact, does not take focus, and allows touches outside its
window to pass through. It appears only while the foreground assistant is
running; stopping the service removes it and leaves normal phone interaction
unaffected.

## Configure Gemini in the app

Open **Settings → Gemini AI**, enter your own API key, and choose **Save API
Key**. The key is masked while entering and is encrypted with an AES-GCM key
held in Android Keystore; only encrypted data is persisted in app-private
preferences. Clear the key from Settings at any time. The app has no embedded
key and does not require one to compile or run its non-Gemini features.

Gemini can answer questions, generate text and code, or return a validated
automation-plan preview. Plans are previews only: model output is never
executed directly.

## Voice verification

The **Voice Authentication** screen reports the current model status. No
on-device speaker-embedding model or enrollment flow is included yet, so owner
voice verification is **not configured** and automation fails closed. Speech
recognition transcribes speech; it is not speaker authentication. Do not treat
the current speech recognizer as proof of identity.

## Example voice commands

When a real owner-verification implementation is installed and reports a
verified owner, the voice parser recognizes examples such as:

- “Click New Repository”
- “Type VoiceControl in Repository name”
- “Scroll down”
- “Open Chrome”
- “Confirm” or “Cancel” for a pending important action
- “Ask AI how this works” or “Generate code for a Kotlin greeting”

Until owner verification is configured, recognized commands are not executed.
Entering text into password, passcode, verification-code, token, or other
sensitive fields is disallowed.

## Automation history

The History screen shows a bounded list of recent command categories, action
types, timestamps, and outcome categories. Typed text is never saved; command
labels are redacted for credential-like values. History can be cleared in the
app.

## Security and GitHub automation limitations

- Automation requires both owner authentication and an enabled, connected
  Accessibility Service.
- Important actions require a separate, owner-verified confirmation. Destructive
  actions and system-settings changes are not automated.
- If CAPTCHA, 2FA, passwords, Cloudflare, or another security control is
  detected, automation stops with **Manual verification required.** The app
  never attempts to bypass those controls.
- The allowlist does not include shell, ADB, root, arbitrary intents, file
  deletion, password entry, or system-settings modification.
- GitHub or other website workflows are limited to visible UI controls and
  require human handling for authentication, security checks, and any sensitive
  or irreversible operation. There is no special GitHub privilege or
  authentication bypass.
- API keys are entered by the user at runtime and are never written to source,
  Gradle configuration, BuildConfig, or the manifest. Requests use HTTPS; the
  app does not log API keys, request headers, passwords, tokens, or biometric
  embeddings.

The app is an extensible foundation, not a substitute for Android permission
prompts, a properly trained speaker-verification model, or human review.
