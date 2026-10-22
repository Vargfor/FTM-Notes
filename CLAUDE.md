# FTM Notes — project guide

One app for **F**ood (recipes, shopping lists), **T**raining (workouts, plans, progression) and
**M**isc notes. Kotlin Multiplatform (Android + iOS) with Compose Multiplatform UI.
Full product/technical plan and phase roadmap: [`docs/PLAN.md`](docs/PLAN.md).

## Commands (Windows dev machine)

The JDK on PATH is Java 8; run Gradle with a modern JDK, e.g. in Git Bash:
`export JAVA_HOME="/c/Program Files/JetBrains/IntelliJ IDEA 2026.2.0.1/jbr"` (the daemon itself
uses the Azul 21 toolchain from `gradle/gradle-daemon-jvm.properties`).

| What | Command |
|---|---|
| Domain tests (fast) | `./gradlew :core:jvmTest` |
| Data/UI-logic tests | `./gradlew :shared:desktopTest :shared:testAndroidHostTest` |
| Android debug APK | `./gradlew :androidApp:assembleDebug` (install: `:androidApp:installDebug`) |
| Desktop dev app + Hot Reload | `./gradlew :shared:hotRunDesktop` |
| Single composable + Hot Reload | `./gradlew :shared:hotDevDesktop --className=… --funName=…` |
| iOS | Can't build on Windows — CI (`.github/workflows/ci.yml`) runs iOS tests on macOS |

Debug builds install as `io.github.vargfor.ftmnotes.debug` next to release `io.github.vargfor.ftmnotes`.
Emulators available locally: `Pixel_10`, `Medium_Tablet`.

## Architecture

- `:core` — pure Kotlin (android, jvm, iOS). Models, the FTM document format, domain engines,
  importers, sync logic. **No Compose, no Room, no platform APIs.** Keep logic here so it is
  testable in seconds.
- `:shared` — Compose UI, Room 3 database, Koin DI, Navigation 3. Targets android, iOS and a
  dev-only `jvm("desktop")` (Hot Reload + headless tests; never shipped).
- `:androidApp` / `iosApp` — thin platform shells.

Packages in `:shared`: `navigation` (routes, per-tab back stacks, shell), `designsystem`
(theme, components), `data` (Room + repository), `di`, `feature/<area>`.

## Conventions

- **One canonical format.** Every stored item is an `FtmDocument` (see `core/model`). The same JSON
  is used for the DB payload, import/export, backups, sync and the AI schema. Write documents only
  through `DocumentRepository.save()` — it maintains denormalised columns, hashes and the HLC.
- **Lenient in, strict out.** `FtmReader` accepts messy input (AI output, other apps) and reports
  `ReadIssue`s with JSON paths; `FtmFormat.writer` produces canonical output.
- **Everything optional.** New helpers/automation get a feature flag with a sensible default.
- **Display transforms never mutate data** (unit conversion, scaling).
- **No data-loss paths:** soft delete (`deleted_at`), undo for destructive actions.
- **Strings** go in `shared/src/commonMain/composeResources/values/strings.xml` — no hard-coded
  user-facing text. **Icons** are Material Symbols (Rounded) vector XMLs in
  `composeResources/drawable` (`ic_<name>.xml`, filled variant `ic_<name>_filled.xml`).
- **Section accents:** wrap section screens in `SectionTheme(Section.X)`; don't hard-code colours.
- **Room:** schema JSON is exported to `shared/schemas` and committed. Once real user data
  exists (from Phase 1 on), every schema change needs a migration + migration test.
- **Navigation:** add a `Route` subclass (it must be `@Serializable`) and an `entry<>` in
  `AppShell.kt`. Use `navigator.showDetail()` for list → detail so two-pane layouts replace
  rather than stack details.
- **Tests:** engines and format logic get table-driven tests in `:core`; repository/DAO tests run
  against in-memory Room in `shared/src/desktopTest`.
