# Hermes Pixel Assistant

A native Kotlin Android client for a personal [Hermes Agent](https://github.com/NousResearch/hermes-agent). It can be selected as Android's default digital assistant and uses the platform speech services plus Hermes' Runs API.

> **Status:** Experimental and debug-signed. It is intended for a private Tailnet deployment, not a public production service.

## What works

- Android `ROLE_ASSISTANT`, `VoiceInteractionService`, and a bottom-anchored assistant overlay
- Android/GMS `SpeechRecognizer` with live partial transcription and waveform animation
- A fallback for recognizers that emit a usable partial result followed by `ERROR_NO_MATCH`
- Hermes Runs API client: `POST /v1/runs` plus SSE consumption from `/v1/runs/{run_id}/events`
- Streaming response text and final Android `TextToSpeech` output
- A growing Material-inspired conversation sheet with left/right Hermes and user bubbles, scrollable history, text composer, and voice trigger
- Visible FIFO queue for typed follow-ups, with a per-message action to steer a live Hermes run
- Resume UX that restores the latest persistent Pixel conversation and pages older messages on scroll
- First-run configuration for Tailnet host, port, and bearer API key
- API key storage encrypted with an Android Keystore AES-GCM key
- Dismiss with a downward swipe or a tap outside the widget; an already accepted Hermes run is deliberately not cancelled
- Reinvoking the system assistant while the sheet is visible restarts voice input in place instead of resetting the conversation

## Architecture

```text
Android/GMS speech recognition
        ↓
Hermes Pixel Assistant (thin client)
        ↓ private Tailnet
Hermes API Server Runs API
        ↓
Hermes tools, memory, and configured model
        ↓
Android TextToSpeech
```

The app does not embed a server address or API key. Configure a private Hermes API Server in the launcher activity before invoking the assistant.

## Build

Requirements:

- JDK 17
- Android SDK platform 35 and build-tools 35.0.0

```bash
./gradlew :app:assembleDebug
```

The resulting APK is at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install and test

1. Install the debug APK on an Android device running API 29 or newer.
2. Open **Hermes Pixel Assistant**.
3. Allow microphone access.
4. Enter the private Tailnet hostname/IP, port, and Hermes API bearer token.
5. Select **Save and test connection**.
6. Tap **Select as default assistant** and confirm the Android role dialog.
7. Invoke Android's assistant gesture, speak a request, and wait for the streamed Hermes answer.

## Privacy and security

- The microphone transcript is sent to the configured Hermes API Server only after Android speech recognition completes.
- The API key is encrypted at rest with Android Keystore; it is not shipped in the APK or logged by the app.
- Cleartext HTTP is currently permitted so the app can reach a private Hermes endpoint over Tailscale. Transport confidentiality therefore relies on the Tailnet; do not use this configuration for a public endpoint.
- The current implementation does not request screen context.

## Next steps

- Persist active run IDs and provide resume/re-attach UX
- Android completion notifications for runs that outlive the overlay
- Explicit cancel action mapped to the Hermes Runs stop endpoint
- Voice-specific intent guidance and richer user-safe progress states
- Opt-in screen context and confirmation UI for side-effecting actions
