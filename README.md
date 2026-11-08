# Digimax Transcriber — Android

Native Android app that records with the **screen locked / phone in your pocket**, transcribes **on-device**, and emails the transcript to you. The native counterpart of the web tool at **record.digimax.mu** (which can't record in the background on a phone).

Target device: **Nothing Phone (1), Nothing OS 3.2 / Android 15**. Min Android 8 (API 26), target API 35.

## What it does (v0.3.0)
1. **Background recording** — a foreground microphone service records to a 16 kHz mono `.wav` and keeps going with the screen locked / phone in your pocket.
2. **On-device transcription** — Vosk (small en model bundled in the APK, fully offline). Audio never leaves the phone.
3. **Email via your own mail app** — set a destination email once (prompted on the first transcription, saved on the phone); after each transcription, **Email** opens your phone's mail app prefilled to that address with the transcript. You tap Send.

## Private + shareable
- **No INTERNET permission.** The app itself has zero network access — recording, transcription and model all run on-device. Emailing is a hand-off to your own mail app (a separate app with its own network).
- **Shareable APK.** Because each user sets their own destination and sends from their own email, anyone can install this same APK and use it — nothing routes through a shared server.
- whisper.cpp (ggml, JNI) remains a possible accuracy upgrade over Vosk later.

## Build & install (no Mac needed)
0. **Get the speech model** (not committed — ~40 MB): run `tools/fetch-model.sh`. It downloads the Vosk small-English model into `app/src/main/assets/model/`.
1. Open the folder in **Android Studio** (Koala or newer). Let it sync — it downloads the Gradle wrapper, AGP 8.6, SDK 35.
   - CLI alternative: `gradle wrapper` once (to create `gradlew`), then `./gradlew assembleDebug`.
2. Build the debug APK: **Build → Build APK(s)**, or `./gradlew assembleDebug`.
   - APK lands at `app/build/outputs/apk/debug/app-debug.apk`.
3. Install on the phone:
   - USB: enable **Developer options → USB debugging**, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`, **or**
   - Sideload: copy the APK to the phone, tap it, allow **install unknown apps** for your file manager.
4. On first launch, grant **Microphone** and **Notifications** when asked.

## Testing stage 1 (the core question)
1. Tap **Record**.
2. **Lock the phone** and put it down / in your pocket for a few minutes. The persistent "Recording…" notification stays up.
3. Unlock, open the app, tap **Stop**.
4. A `rec-*.wav` appears under **Recordings** with a non-trivial size (≈ 32 KB/second). That proves it recorded while locked.

## Keep it alive in your pocket
Nothing OS is close to stock, but if a long recording gets cut when locked:
**Settings → Apps → Digimax Transcriber → Battery → Unrestricted.**
(Android can't give a web page this at all — this allowance is the native advantage.)

## What's intentionally NOT here
- Capturing the **other side of a call** — Android blocks call-audio capture; use speakerphone so the mic hears both sides.
- iOS — a separate build.

## Licence
MIT — see `LICENSE`.
