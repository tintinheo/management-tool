# AudioHub

Ad-free music + podcast player for Android (built for Xiaomi 14T / HyperOS).

- **Music:** plays DRM-free files (MP3, FLAC, M4A, OGG, OPUS, WAV) from `/Music/AudioHub/`.
- **Podcasts:** subscribe through the Apple Podcasts catalog or via RSS/OPML; episodes download to `/Podcasts/AudioHub/` *(phase 4–5)*.

## Stack
Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer + MediaSessionService, Room, DataStore, WorkManager, OkHttp, Coil.
AGP 9.5 · Gradle 9.8.2 · Kotlin 2.4.21 · compileSdk 37 · targetSdk 36 · minSdk 29.

## Build
1. Open this folder in Android Studio and let Gradle sync (downloads Gradle and the dependencies).
2. Run the `app` configuration on a device, or use `gradlew assembleDebug`.
3. Unit tests: `gradlew test`.

## Install on the phone
1. Xiaomi: Settings → About phone → tap *OS version* 7×; then Additional settings → Developer options → enable **USB debugging** and **Install via USB**.
2. Connect over USB and click Run in Android Studio.
3. Copy music from your PC into `Internal storage/Music/AudioHub/`.

## Layout
```
app/src/main/java/com/audiohub/
  AppContainer.kt        hand-wired dependencies
  data/                  Room DB, MediaStore scanner, settings, folders
  playback/              PlaybackService (Media3), PlayerConnection (MediaController)
  system/                runtime permissions, HyperOS background settings shortcuts
  ui/                    Compose screens (onboarding, music, now playing, settings…)
```
