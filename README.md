# Lumen Player

A native Android music-player starter built with Jetpack Compose and Material 3. The visual direction follows the provided references: album-led now playing, expressive color, a sound studio/equalizer, and easy access to the music library.

## Included

- Choose a folder with Android's Storage Access Framework. The system picker exposes eligible internal storage and SD-card locations; the app keeps read access and scans audio files recursively.
- Local playback and queue controls using AndroidX Media3 ExoPlayer.
- Embedded cover art and an accent color sampled from each track's cover.
- A five-band equalizer backed by Android's audio effects where the device supports it.
- Dark appearance and a selectable accent palette, plus library and queue views.

## Open and run

Open this folder in Android Studio with JDK 17 and Android SDK 36 installed. Let Gradle sync, then run the `app` configuration on an Android device or emulator. The first folder selection is handled by Android's system picker; select a music folder on internal storage or the SD card.

## Build an APK with GitHub Actions

The workflow at `.github/workflows/android-apk.yml` runs on pushes and pull requests, and can also be started manually from the repository's **Actions** tab with **Build Android APK → Run workflow**. It uses JDK 17, Gradle 8.13, and Android SDK 36, builds `assembleDebug`, and uploads an artifact named `lumen-debug-apk`.

To download it, open the completed workflow run on GitHub and select `lumen-debug-apk` in the **Artifacts** section. The artifact contains the installable debug APK and is kept for 14 days. This workflow builds an unsigned debug APK; release signing is not configured.

## Notes

This is a starter project, not a published or signed APK. Audio effects are device dependent. The equalizer UI uses the platform audio session effect, while bass boost and spatial effects may depend on the device's audio stack. Chromecast receiver support is not configured in this first build; the reference's cast affordance can be wired to a Cast receiver once you have its application ID.
