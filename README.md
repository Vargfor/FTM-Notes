# FTM Notes

**F**ood · **T**raining · **M**isc — one notes app instead of three.

- **Training:** browse 909 categorized exercises offline, log sets, reps and weight, and build routines and multi-week plans.
- **Food:** recipes with one-tap US ⇄ metric conversion, portion scaling, cook mode and shopping lists.
- **Notes:** everything else, with simple formatting and checklists.

Every helper is optional and customisable. Your data stays on your device, with optional backup and
sync to your own cloud storage. You can import from Google Keep, Samsung Notes (via share),
Markdown, recipe websites and other apps, plus an AI-friendly schema so any AI assistant can
produce importable notes.

> Status: Phase 1 notes/imports/backups, Phase 2 Food and Phase 3 Training are implemented for Android.
> Automated tests and emulator checks pass; a real Keep Takeout import and backup-provider checks
> on Henrik's phone remain pending. See [`docs/ROADMAP.md`](docs/ROADMAP.md) for verification status.

## Import schemas for every section

Create and edit notes, recipes, exercises and routines directly in the app. No AI or account is needed.
Open **Import** to open an FTM file or paste content. **Training → Import a program** imports
linked routines and exercises together. An optional AI assistant section offers prompts for every type.

The [Training and schema guide](docs/TRAINING.md) links all seven schemas and AI prompts.
The [six-week example program](schemas/ftm/v1/example_training_program.ftm.json) is ready to import.
Use item export for portable content and ZIP backups for complete recovery, including a live workout.

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
