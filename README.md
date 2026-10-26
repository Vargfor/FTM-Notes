# FTM Notes

**F**ood · **T**raining · **M**isc — one notes app instead of three.

- **Training:** log sets, reps and weight; build multi-week plans with automatic progression.
- **Food:** recipes with one-tap US ⇄ metric conversion, portion scaling, cook mode and shopping lists.
- **Notes:** everything else, with simple formatting and checklists.

Every helper is optional and customisable. Your data stays on your device, with optional backup and
sync to your own cloud storage. You can import from Google Keep, Samsung Notes (via share),
Markdown, recipe websites and other apps, plus an AI-friendly schema so any AI assistant can
produce importable notes.

> Status: Phase 1 notes/imports/backups and Phase 2 Food are implemented for Android.
> Automated tests and emulator checks pass; a real Keep Takeout import and backup-provider checks
> on Henrik's phone remain pending. See [`docs/ROADMAP.md`](docs/ROADMAP.md) for verification status.

## Project layout

| Module | Contents |
|---|---|
| [`core`](core/src) | Pure Kotlin domain: document model, FTM format reader/writer, engines |
| [`shared`](shared/src) | Compose Multiplatform UI, Room database, DI, navigation |
| [`androidApp`](androidApp) | Android application shell |
| [`iosApp`](iosApp) | iOS (SwiftUI) shell — open in Xcode on a Mac |

## Building

Requires JDK 17+ to launch Gradle (the build uses a JDK 21 toolchain automatically).

```shell
./gradlew :core:jvmTest :shared:desktopTest :shared:testAndroidHostTest   # tests
./gradlew :androidApp:assembleDebug                                      # Android APK
./gradlew :shared:hotRunDesktop                                           # desktop dev app with Hot Reload
```

On Windows use `gradlew.bat`. iOS builds need macOS: open [`iosApp`](iosApp) in Xcode, or let CI
build it.
