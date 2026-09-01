# Hermes Pixel Assistant POC

A deliberately minimal native Android proof of concept for the public Android `ROLE_ASSISTANT` / `VoiceInteractionService` interface.

## Behavior

Once selected as Android's default digital assistant, invoking it from the Power button or assist gesture shows an overlay and speaks exactly:

```text
OK
```

It does **not** record audio, inspect the screen, call a network endpoint, retain conversations, or access Hermes yet.

## Install and test

1. Build the debug APK:
   ```bash
   ./gradlew :app:assembleDebug
   ```
2. Install `app/build/outputs/apk/debug/app-debug.apk` on the Pixel.
3. Open **Hermes Assistant POC** and tap **Als Standard-Assistent auswählen**.
4. Confirm the Android role dialog, then invoke the digital assistant by holding the power button or using the configured assist gesture.
5. Expected result: an `OK` overlay and spoken `OK`.

## Intended next increments

1. Push-to-talk audio + VAD.
2. Private STT endpoint over Tailscale.
3. Hermes API Server streaming client.
4. TTS endpoint and safe confirmation UI.
5. Optional on-device microWakeWord.

## Security posture

This POC has no network permission and no data path outside the device. Adding a Hermes connection later must use a device-bound credential, Tailscale-only transport, explicit screen-context consent, and an approval flow for privileged actions.
