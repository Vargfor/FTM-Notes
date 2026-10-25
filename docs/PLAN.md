# FTM-Notes (Food, Training, Misc) — Product & Implementation Plan

## Context

Henrik uses three separate apps (workout tracker, recipe app, notes app), and each one only does half the job. FTM-Notes replaces all three:

1. **Training**: real sets/reps/weight logging, multi-week plans with incremental progression, and optional automation.
2. **Food**: automatic US→EU conversion, portion scaling, ingredient/step checklists and a shopping list. Every feature can be turned on, off or customised.
3. **Misc**: general notes with simple formatting.

Cross-cutting goals:
- Import from Samsung Notes, Google Keep and other apps.
- An **AI-friendly schema** per note type that any AI can follow.
- **Very high UI/UX quality.**
- Optional **cloud storage**.

**Starting point:** a stock JetBrains KMP wizard template (generated 2026-10-22).
- Versions: Kotlin 2.4.20, Compose MP 1.12.1, CMP material3 1.12.0-alpha03, AGP 9.1.1 with the new `com.android.kotlin.multiplatform.library` plugin, Gradle 9.5.1, JDK 21, compileSdk 37, minSdk 24.
- Targets: android, iosArm64, iosSimulatorArm64.
- Package `com.example.ftm_notes`. Not a git repo.

**Dev machine is Windows.** Android is the main loop. All logic and UI live in common code. **iOS is paused until the Android app is complete:** the macOS CI job is disabled and comes back in P6. Until then, common code stays iOS-safe through the Linux klib cross-compile in CI.

## Guiding principles
1. **One canonical document format ("FTM format").** Every note is a typed JSON document. The same format is used for DB payloads, import/export, the AI schema, cloud sync and backups. Build it once, test it once.
2. **Local-first.** The app is fully usable offline without an account. Cloud is opt-in.
3. **Everything optional.** Every helper or automation is a typed feature flag. Settings are shown in context (for example cook-mode and session overflow menus), plus a searchable settings screen. Onboarding offers presets (Simple / Standard / Power).
4. **Pure domain engines** live in a `:core` module with no Compose or Room. That covers units, the ingredient parser, conversion, scaling, progression, importers, the format and sync merge logic. Their tests run in seconds on Windows.
5. **Conversion and scaling never change stored data.** They're display transforms; "Apply permanently" is a separate, explicit action.
6. **No data-loss paths.** Undo on every destructive action, a trash, version history on sync conflicts, and undo for whole imports.

## Architecture

```
:core     KMP (android, jvm, iosArm64, iosSimulatorArm64) — pure Kotlin, no Compose/Room
  model/        FtmDocument + payload types, Ids (wraps Uuid.generateV7), HLC
  format/       strict writer, lenient reader, validator, migrations
  food/engine   units, ingredient parser, converter, scaler, formatter, densities
  training/engine progression rules, e1RM, plates, warm-ups, PR detection
  importer/     Importer API + parsers (Keep, txt/md/docx, JSON-LD, Paprika, Strong/Hevy, ENEX)
  sync/         SyncEngine, SyncProvider API, per-type conflict resolvers
:shared   KMP + Compose (android, iosArm64, iosSimulatorArm64, jvm("desktop") dev-only)
  app/          App(), Nav3 back stacks, Koin modules
  designsystem/ theme, tokens, components
  data/         Room 3 DB, DAOs, repositories, projections, attachments store
  settings/     flag registry + synced settings doc
  feature/{notes,food,training,importer,sync,search}/  screens + ViewModels
  platform/     expect/actual: files, share, haptics, keep-awake, notifications, auth bridge
:androidApp   thin shell + share-target activity, rest-timer foreground service, WorkManager
iosApp        SwiftUI shell; later GoogleSignIn bridge + share extension
```

- **Why a dev-only `jvm("desktop")` target in `:shared`:** it gives Compose Hot Reload on Windows for fast UI iteration, plus headless `runComposeUiTest` and JVM Room tests. It is never shipped.
- **Navigation is Navigation 3 (KMP):**
  - Three top-level tabs, each with its own back stack (TopLevelBackStack pattern).
  - `ListDetailSceneStrategy` from adaptive-navigation3 handles tablet and foldable panes.
  - Routes are a `sealed interface Route : NavKey`, registered polymorphically in `SavedStateConfiguration` (required on iOS).
- **Active workout:** an app-scoped `ActiveSessionController` (a Koin singleton backed by the DB). It drives a mini-player bar above the nav bar and a rest-timer pill. Tapping either one pushes `LiveSession(id)`.

### Storage: documents plus projections (Room 3 + BundledSQLiteDriver)
- **`documents` table:**
  - `id` (UUIDv7; seeded exercises use a UUIDv5 of a slug), `type`, `title`, `plain_text`, `payload_json`, `schema_version`
  - `color`, `pinned`, `archived`
  - `created_at`, `updated_at`, `deleted_at` (doubles as trash and tombstone)
  - sync columns: `hlc`, `base_hlc`, `dirty`, `content_hash`, `remote_ref`
  - indexes on `(type, deleted_at, updated_at)` and `(dirty)`
- **Supporting tables:**
  - `tags` and `doc_tags`
  - `documents_fts` (full-text search)
  - `attachments`: sha256 content-addressed files
  - `doc_revisions`: the last N losers of conflicts, so "Restore previous version" works
  - `import_batches`: for undoing an import
  - `device_prefs`: settings that stay on this device
- **Projections** are written in the same transaction as the document and can be rebuilt from payloads at any time:
  - `set_log`: written **only when a session is finished**. Powers "previous", history, PRs and progression.
- **Settings** are a synced `settings` document, merged per key. DataStore isn't used.
- **Time:** sessions store an Instant plus local date plus time zone, so history doesn't shift when you travel.

### Document types (FTM format v1)
| type | contents |
|---|---|
| `note` | `kind: text` (Markdown subset; underline stored as `<u>`) **or** `kind: checklist` (`items[{id,text,checked,indent}]`, which maps 1:1 to Google Keep) |
| `recipe` | see Food |
| `shopping_list` | `items[{id,text,quantity?,unit?,category,checked,sourceRecipe?}]` |
| `exercise`, `workout_template`, `workout_session`, `training_plan` | see Training |
| `settings` | typed key/value map |

## Food
**Recipe model:**
- title, description
- `servings{amount,label}`, prep/cook/total minutes
- `ingredientGroups[{name?, items[Ingredient]}]`
- `steps[{text, timerSeconds?}]`
- notes, tags, source URL/name, images, optional nutrition and rating
- `Ingredient`: `{raw, quantity (number | {min,max}), unit, name, note, optional, scalable=true, category}`

**Engine** (`:core`, table-driven tests):
- **Unit registry:**
  - Volume, mass, count, length and temperature, each with a system tag (US/UK/metric) and a factor to ml or g.
  - **Multilingual aliases** (cup/c, tbsp/T/ss/msk/EL, tsp/t/ts/tsk/TL, dl, …).
- **Ingredient parser:**
  - Numbers: `1 1/2`, `½`, `1,5`, ranges `2-3` / `2 to 3`.
  - Packages like `1 (14 oz) can`, `stick of butter`, and "to taste" (non-scalable).
- **Converter policies:**
  - Target: Original / Metric (EU) / US.
  - Metric volume: ml / dl / auto.
  - Keep spoon measures, or convert them.
  - **Weights for dry goods** come from a curated, editable density list, shown with "≈".
  - Rounding: exact / kitchen-friendly / custom step.
- **Conversions inside step text:** °F→°C rounded to 5 (optional fan-oven hint), gas mark, and pan sizes in inches→cm.
- **Scaler:**
  - Servings stepper, ½×/2×/3×, and **scale-by-ingredient** ("I have 300 g chicken").
  - Units auto-normalise (48 tsp → 1 cup, 1200 ml → 1.2 l).
  - Count items round sensibly with a hint (1.5 eggs).
  - Non-scalable items stay fixed. Times aren't scaled; a small hint says so.
- **Formatter:** US mode shows fractions; metric mode shows locale-formatted decimals.

**UX:**
- **Recipe screen:**
  - hero image, then a sticky servings stepper with animated numbers
  - a segmented **Original / Metric / US** toggle (default set in settings)
  - an ingredient checklist and "Add to shopping list"
- **Cook mode:** step by step in large type, screen kept awake, tappable inline timers, step checklist.
- **Shopping list:**
  - recipe items merge with unit-aware summing
  - aisle categories in an order the user sets
  - checked items slide into an "In cart" section (with undo); clear checked
  - quick add with parsing ("2 l milk"); multiple lists optional; share as text
- **Imports shipped with Food:**
  - **Recipe from URL**: schema.org JSON-LD (single object, array or `@graph`), ISO-8601 durations, HowToStep/HowToSection.
  - **Paprika**: a zip of gzipped JSON.

## Training
**Models:**
- **`exercise`:**
  - Name, aliases, muscle groups, equipment.
  - `tracking`: weight×reps / bodyweight(+added)×reps / reps / time / distance / time+distance.
  - **Custom fields** (key, label, type, unit), plus default rest, increment and unit override.
- **`workout_template`:** blocks of `{exercise, supersetGroup?, rest?, notes?, sets[SetTarget]}`.
  - `SetTarget`: kind (warmup/working/drop/failure/AMRAP), reps (n or range), load (absolute / %1RM / %TM / RPE / RIR), duration/distance.
- **`workout_session`:**
  - `status` (in_progress/finished), start/end, plan ref (plan, week, day), bodyweight, notes, rating.
  - Exercises with `PerformedSet`: the actual values, completed, custom values and a snapshot of the target.
- **`training_plan`:**
  - N weeks, a rotation schedule (calendar scheduling comes later), day → template, per-week overrides.
  - Progression rules per exercise or a plan default, and an optional deload every N weeks.

**Progression engine:** sealed rules, all optional and per exercise.
- **v1:**
  - **Linear:** +X per session/week when all reps are hit. On a miss, repeat. After N misses, deload Y%.
  - **Double progression:** a rep range; when every set reaches the top, add load and reset to the bottom.
  - **Manual per-week table:** full control.
  - Plate-increment rounding applies to all rules.
- **Later:** percentage waves (5/3/1-style presets) and RPE-based load.

**Automation** (each item is a flag):
- Pre-fill from the last session or from the plan.
- Suggest next load/reps, accepted per set or all at once.
- Auto rest timer.
- Warm-up generator.
- Plate calculator (bar and plates configurable).
- e1RM formula choice.
- PR detection.
- kg/lb with per-exercise override.
- Bodyweight log.
- Auto-advance plan.

**Live-session UX:**
- **One-handed and gym-proof:**
  - big targets
  - a **custom numeric keypad sheet** with ±increment buttons, so the system keyboard never covers the table
- **Set row:** `[kind] [previous] [weight] [reps] [✓]`. Ticking fills the row with the section accent, fires haptics and starts the rest pill.
- **Editing:** swipe to delete (with undo); "+ Set" duplicates the previous set; drag to reorder; supersets shown bracketed.
- **Persistence:**
  - Discrete actions (tick, add or delete a set) are written immediately.
  - Typing is debounced (≤300 ms) and flushed on ON_STOP.
  - Only one `in_progress` session exists. It is restored from the DB and isn't synced until finished.
- **Rest timer:** stores an absolute end time.
  - **Android:**
    - a foreground service (type `health`) while a session is active, with a countdown chronometer notification
    - the end alert uses `setExactAndAllowWhileIdle` if exact alarms were granted, otherwise a partial wakelock
    - `POST_NOTIFICATIONS` is requested at the first session
  - **iOS:** a scheduled `UNTimeIntervalNotificationTrigger` (Time-Sensitive).
- **Finish:** a summary (duration, volume, PRs) and "update template with today's changes".
- **Library:** about 100 seeded exercises with deterministic ids, all editable.
- **Import shipped with Training:** **Strong CSV**. Auto-detect delimiter, header and unit; group rows into sessions; create missing exercises.

## Misc notes
- **Keep-style features:** grid/list toggle, pinned section, colours, tags, text and checklist notes, archive, trash with configurable auto-purge, global full-text search.
- **Editor:** compose-rich-editor (WYSIWYG ↔ Markdown).
  - A toolbar above the keyboard: B / I / U / S / H1 / H2 / bullets / numbers / link / quote / clear.
  - Undo/redo.
  - Checklists use their own reorderable list component (Reorderable lib) rather than the rich-text editor.
- **"Convert to recipe / workout"** reuses the importer parsers. Handy for old Keep notes.

## App-wide UX
- **Navigation:**
  - `NavigationSuiteScaffold` (bottom bar on phones, rail on large screens) with **Training / Food / Notes**, plus search and settings.
  - Tabs can be hidden or reordered, and the start tab is selectable. An optional "Today" dashboard sits behind a flag.
- **Theme:**
  - Material 3 using the Expressive APIs from CMP material3 1.12.0-alpha03 (pinned; alpha risk accepted, as in the template). Optional dynamic colour.
  - **Per-section accents:** Training warm orange, Food fresh green, Notes calm blue.
  - Light/dark/AMOLED. A bundled variable font with tabular numerals.
- **Motion:** shared-element list→detail transitions, animated numbers, predictive back, haptics on meaningful actions.
- **Onboarding (skippable):** pick modules, units (metric/US, kg/lb), a theme and a feature preset.
- **Accessibility:** content descriptions, ≥48dp targets, font scaling up to 200 %, contrast-checked palette.
- **Strings:** all in compose resources from day one.

## AI-friendly schema
- **Envelope:** `{"ftm": 1, "documents": [ {"type": "recipe", ...} ]}`. A bare document or an array is also accepted.
- **Made for LLMs:**
  - everything optional except `type` and `title`; no IDs needed
  - exercises are referenced **by name** and fuzzy-matched, with create-if-missing
  - units are strings with a documented alias list
  - quantities can be numbers or strings like `"1 1/2"`
  - ISO-8601 dates
- **Hand-written** JSON Schemas (draft 2020-12), one per type, go in `schemas/ftm/v1/` and are mirrored in `composeResources/files/`.
- **`AI_PROMPT_<type>.md`:** a self-contained prompt. It holds rules, a **compact TypeScript-style type spec** (LLMs follow that better than raw JSON Schema), two worked examples and an `INPUT:` slot. A link to the full schema is included.
- **Drift protection (tests):**
  - every serial name in the Kotlin models appears in the schema
  - every example passes schema validation
  - every example decodes through the lenient reader
- **Lenient reader:**
  - strips ```json fences
  - ignores unknown keys, with warnings
  - coerces types and normalises unit aliases
  - reports human-readable errors with paths
- **In-app:**
  - Import → "Use any AI" → pick a type → **Copy / Share prompt**.
  - **Paste result** validates it, lists auto-fixes, then opens the import preview.

## Imports
**Pipeline:**
1. Pick a source, or let auto-detect `sniff()` it.
2. `parse()` produces drafts and warnings.
3. **Preview & map:**
   - select or deselect drafts
   - change the type per item (recipe/workout heuristics suggest one)
   - add tags; duplicates are flagged by content hash
4. Commit in one transaction with a batch id, then a summary with **Undo import**.

| Phase | Source | Path |
|---|---|---|
| P1 | FTM JSON / AI output | Paste or file |
| P1 | **Google Keep** | Takeout `.zip` → per-note JSON:<br>• `title`, `textContent` → text note; `listContent[{text,isChecked}]` → checklist note<br>• `labels` → tags; `color`, `isPinned`/`isArchived`; `isTrashed` → skipped<br>• `*TimestampUsec` → dates; `attachments` → attachment store |
| P2 | Recipe URL, Paprika | see Food |
| P3 | Strong CSV (Hevy once real export samples exist) | see Training |
| P4 | **Samsung Notes** | Android **share target** (`ACTION_SEND` / `SEND_MULTIPLE`, text/plain), plus exported `.txt` / `.docx` files. `.sdocx` is skipped: proprietary, and the only parser is GPL. |
| P4 | Markdown / .txt bulk | Covers Apple Notes (iOS 26+ Markdown export), Obsidian, Notion |
| P4 | Evernote `.enex` | ENML → Markdown; `en-todo` → checklist |

## Cloud & backup
**Step 1 (P1): automatic zip backup to a folder the user picks.** No account, no cost.
- **Zip contents:** `manifest.json`, `documents.ftm.json` and `attachments/`.
- **Android:**
  - The folder is chosen with `ACTION_OPEN_DOCUMENT_TREE` and a persisted permission, so it can sit in Drive, OneDrive, Dropbox or local storage.
  - A daily WorkManager job keeps the last N backups.
  - Android Auto Backup (`dataExtractionRules`) is also enabled.
- **iOS:** a folder bookmark (for example in iCloud Drive), backed up on launch or backgrounding if the last backup is older than 24 h.
- **Restore** uses the same merge code as sync, so the conflict resolvers get exercised early.

**Step 2 (P5): real multi-device sync.** A provider-agnostic `SyncEngine` with **Google Drive `appDataFolder`** as the first provider.
- Why Drive: no backend to run, free, the data stays in Henrik's own Google account, and the scope is non-sensitive (`drive.appdata`).
- **Engine:**
  - **Clock and outbox:** HLC clock. The outbox is every `dirty` row, cleared with `WHERE id=? AND hlc=?`.
  - **Pull rules:** insert new documents; skip our own echoes; fast-forward when there's no local edit. If both sides changed, the type's resolver decides.
  - **Tombstones** are purged after 90 days via `purgeHorizon`. A device older than the horizon does a full reconcile.
- **Resolvers:**

  | Document type | On conflict |
  |---|---|
  | Text notes | Keep both. The loser becomes a "(conflict · device · time)" copy. |
  | Checklists and shopping lists | Union items by id, last-writer-wins per item |
  | Settings | Last-writer-wins per key |
  | Everything else | Last-writer-wins; the loser goes to `doc_revisions` |

- **Drive layout:**
  - `ftm-meta.json`
  - `d_<docId>.json`, with `appProperties{docId,type,hlc,del,hash}` so the change feed carries metadata without downloads
  - `a_<sha256>` attachments, uploaded first and immutable
- **Change detection:** the Drive **changes API** with a page token. If the token expires, fall back to `files.list`.
- **Transport:** Ktor with backoff.
- **Scheduling:**
  - **Android:** a unique WorkManager job after edits (~20 s delay, coalesced), a 12 h periodic job, and a sync whenever the app comes to the foreground.
  - **iOS:** sync whenever the app becomes active, plus a short background task.
- **Auth:**
  - **Android:** Credential Manager plus `AuthorizationClient` for the Drive scope. The OAuth client is registered per SHA-1, which is why the package rename happens in P0.
  - **iOS:** the GoogleSignIn SDK (SPM) behind a Kotlin `GoogleAuthBridge` interface implemented in Swift.
- **Later alternative:** the same `SyncProvider` interface fits a **Supabase** provider (`documents` table with row-level security, `server_seq` cursor, Storage for blobs). That's the option if web access or sharing with other people is ever wanted.

## Roadmap
Each phase ends green on its exit criteria before the next starts. Food and Training (P2/P3) can swap places.

| Phase | Scope | Exit criteria |
|---|---|---|
| **P0 Foundation** | • git init; CLAUDE.md (commands, conventions); **package rename**; minSdk 26; Kotlin 2.4.21 / CMP 1.12.2<br>• New libraries: Room 3, KSP, serialization, Nav3, Koin, adaptive, Reorderable, FileKit, rich-editor, Ktor<br>• `:core` module; desktop dev target<br>• `documents` table + DAO; FTM envelope + lenient-reader skeleton<br>• Theme/design tokens; Nav3 shell with 3 tabs and adaptive panes<br>• GitHub repo + Actions (Linux: host tests + `assembleDebug`; macOS: iOS simulator tests)<br>• 1-hour Roborazzi spike | • Local and CI builds green<br>• DAO round-trip test passes<br>• Each tab keeps its back stack when switching<br>• App runs on emulator |
| **P1 Misc** | • Text and checklist notes, grid/list, tags, pin/archive/trash, full-text search, undo<br>• Flag registry v1 + settings<br>• FTM import/export, **Keep Takeout import**, **zip backup/restore**<br>• Note AI prompt | • Format round-trip and golden tests<br>• Keep fixture tests<br>• Backup → wipe → restore test<br>• Manual import of a real Takeout |
| **P2 Food** | • Food engine; recipe CRUD, view and editor; conversion/scaling UI<br>• Cook mode; shopping lists<br>• URL and Paprika import; recipe AI prompt | • 200+ table-driven engine cases<br>• Scaling property tests<br>• Recipe screen screenshots |
| **P3 Training** | • Library, templates, live session, rest-timer service, history/PRs<br>• Plans with linear, double and manual progression<br>• Strong CSV import; workout AI prompts | • Engine tests<br>• Session survives `adb shell am kill`<br>• Rest alert with screen off (manual check) |
| **P4 Import hub** | • Share target (Samsung Notes), txt/md/docx, ENEX<br>• "Use any AI" paste flow for all types<br>• Convert note → recipe/workout | • Fixture tests<br>• Corpus of real LLM outputs (fences, aliases, numbers as strings) |
| **P5 Sync** | • SyncEngine against a fake provider first<br>• Then the Drive provider + Android auth + sync settings UI | • Simulated multi-device scenarios and a randomised test (all devices end up identical, nothing lost)<br>• Manual two-phone test |
| **P6 Polish & iOS** | • Re-enable the macOS CI job and its nightly schedule<br>• iOS actuals, GoogleSignIn bridge, share extension, notifications<br>• Charts (e1RM/volume); percentage waves and RPE<br>• Accessibility audit, baseline profiles, icon/branding, TestFlight / Play internal track | — |

## Testing strategy
- **`:core` (JVM, fast):**
  - table-driven tests for the engines
  - property tests: scaling by k then 1/k returns the original; conversion round-trips stay within the rounding step
  - golden JSON, migration fixtures and the lenient-reader corpus
  - importer fixtures, kept small and anonymised and loaded via `com.goncalossilva:resources`
- **Data:** DAO, repository and projection-rebuild tests on in-memory Room with the bundled driver on the JVM.
- **Sync:**
  - a `FakeSyncProvider` that injects failures, races and expired tokens
  - a `FakeClock`
  - 2–3 simulated devices
- **UI:** about 10 `runComposeUiTest` flows on the desktop target (log set + undo, check item + undo, servings stepper, editor toolbar).
- **Screenshots:** Roborazzi in light/dark, font scale 1×/2×, phone and tablet. If the P0 spike fails with the new KMP plugin, use desktop screenshots instead.
- **Manual checklists:** notifications, foreground service, share target, Drive auth.

## Verification (each phase)
- `.\gradlew.bat :core:jvmTest :shared:testAndroidHostTest :shared:desktopTest`
- `.\gradlew.bat :androidApp:assembleDebug` then `:androidApp:installDebug` on an emulator or device, and walk the phase's user flows.
- CI: the macOS job runs `./gradlew :shared:iosSimulatorArm64Test` and links the framework (paused; re-enabled in P6).
- Hot-reload iteration: `.\gradlew.bat :shared:desktopRun --hot` (exact task name confirmed in P0).

## Open decisions (defaults are assumed unless Henrik says otherwise)
1. **App id / package.** ✅ Decided: `io.github.vargfor.ftmnotes`. This is the Android `applicationId`, the iOS bundle id and the Kotlin package. It can't change once the app is published, and the Phase 5 Google OAuth client is registered against it.
2. **Phase order.** Default is Food (P2) before Training (P3). Swap if the workout app is the one you most want to drop.
3. **Cloud.** Default is zip backup in P1, then Google Drive sync in P5. Choose Supabase instead only if you want web access or sharing with other people.
4. **Mac/iPhone access.** Default is GitHub Actions macOS runners. On a private repo, free macOS minutes are limited (×10 multiplier), so that job runs nightly or on main only.
5. **Languages.** Default is English UI with localisation-ready strings. More languages can be added in P6; tell me which ones.

## Critical files (Phase 0)
- `gradle/libs.versions.toml`, root `build.gradle.kts`, `settings.gradle.kts` (add `:core`)
- `shared/build.gradle.kts`: KSP per target (`kspAndroid`, `kspIosArm64`, `kspIosSimulatorArm64`, `kspDesktop`), `room3 { schemaDirectory(...) }`, serialization plugin, `compose.resources { packageOfResClass }`, `jvm("desktop")`
- `core/build.gradle.kts` (new)
- `androidApp/build.gradle.kts` (applicationId, minSdk); `androidApp/src/main/AndroidManifest.xml`
- `shared/src/commonMain/kotlin/<pkg>/App.kt`: replace the template UI with the app shell; delete `Greeting*`
- `iosApp/Configuration/Config.xcconfig` (bundle id)
- `.github/workflows/ci.yml`, `CLAUDE.md` (new)
