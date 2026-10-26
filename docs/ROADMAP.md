# FTM Notes — roadmap in runs

The product and technical plan is [`PLAN.md`](PLAN.md). This file breaks the remaining work into
numbered **runs** — roughly one working session each — so "the next run" always means the same thing.

Every run ends with:
- all tests green: `./gradlew :core:jvmTest :shared:desktopTest :shared:testAndroidHostTest`
- common-code iOS compatibility stays checked in CI; local check (optional while iOS is paused):
  `./gradlew :core:compileTestKotlinIosArm64 :shared:compileKotlinIosArm64`
- `./gradlew :androidApp:assembleDebug`, then an emulator walkthrough (phone, plus tablet for list-detail)
- a short report, after which Henrik commits.

## Status

| Done | What |
|---|---|
| ✅ Phase 0 | Foundation, app ID `io.github.vargfor.ftmnotes` |
| ✅ P1.0 | Spikes (editor, zip writer, iOS cross-compile), CI fixed for `master` |
| ✅ P1a step 1 (+ most of step 2) | Data-loss fixes; DB v2 (tags, search, trash/purge, import batches) |
| ✅ Runs A–D implementation | Settings, rich text/checklists, organisation, search, archive/trash, Keep preview/import/undo, complete ZIP backup/restore; tested DB v2→v3 migration |
| ✅ Runs E–F implementation | Daily folder backups, retention/status/recovery, FTM/AI import/export, camera/gallery attachments, bulk actions, sharing, shortcuts, settings search and screenshot coverage |
| ✅ Runs G–I implementation and automated exit criteria | Recipes, conversion/scaling, cook mode/timers, shopping lists, URL/Paprika imports, recipe AI prompt and schemas |
| Pending Phase 1 acceptance | Import Henrik's real Keep Takeout; verify Drive/OneDrive backup folders on his phone |

## Verification — 2026-10-26

The implementation is present in the working tree; commits remain Henrik's responsibility.
Phase 1's personal-data/provider checks below still gate claiming both phases complete and starting Training.

| Check | Result |
|---|---|
| Domain, desktop and Android host tests | 189 passing test executions; includes 252 ingredient parsing cases and conversion/scaling properties |
| Format and import coverage | Note/recipe prompts and all three content schemas validated; JSON-LD object/array/graph and Paprika fixtures pass |
| Persistence coverage | Populated v2→v3 migration, import rollback/duplicates/undo, corrupt archives, complete restore, conflicts and backup retention/failure recovery pass |
| Lifecycle and navigation | Autosave/final flush, concurrent organisation/trash, shopping undo/redo, persisted cook progress/deadlines and independent tablet detail panes pass |
| UI captures | 40 desktop PNGs: notes list/editor, recipe, cook mode and shopping, light/dark, font 1×/2×, phone/tablet. Saved under `shared/build/reports/screenshots`; uploaded by CI. These are reviewed captures with semantic checks, not pixel-golden comparisons |
| Build compatibility | Android debug assembly and core/shared iOS Arm64 compile checks pass; iOS runtime remains paused |
| Android phone emulator | Recipe create/save/reopen/scaling, cook mode, gallery and camera attachment, incoming text share, persisted local backup folder and ZIP creation verified |
| Android timer | Notification observed after backgrounding and `am kill`; real-device screen-off/Doze timing remains a manual check |
| Android tablet emulator | Notes and recipes open beside their lists; incoming share and new-checklist action verified. Tab/detail isolation has a dedicated UI regression test |
| Android local backup provider | Two ZIPs written through persisted directory access; exported photo bytes match their SHA-256 reference |
| Real Keep Takeout | Pending: no export available in the workspace or Downloads; fixture and split-archive tests pass |
| Drive/OneDrive on Henrik's phone | Pending: no physical phone/provider setup available for this run |

Default recipe display is metric with spoon measures retained. Automatic backups start after folder
selection, run daily, and retain seven successful copies. Existing shopping lists remain accessible
when multiple-list creation is disabled. No destructive migration is permitted from DB v2 or later.

## Runs

| Run | Phase / steps | Delivers |
|---|---|---|
| **A** | P1a steps 3–4 | Settings and feature flags (theme, visible tabs and start tab are real settings); Google Keep parser in `:core`, with fixtures |
| **B** | P1a steps 5–6 | The real editor (rich-text toolbar, checklists with drag, undo); Keep-style notes list (grid/list, pins, colours, labels, swipe to archive, undo) |
| C | P1a steps 7–8 | Search, Archive and Trash screens; Keep import screen (preview, undo, images) |
| D | P1a steps 9–10 | Manual backup/restore, a basic settings screen, **schema freeze** → Henrik starts using the app daily |
| E | P1b part 1 | Automatic backups (tested on Henrik's phone with Drive/OneDrive), FTM export, "Use any AI" import |
| F | P1b part 2 | Adding photos, bulk actions, share-to-app, better search, shortcuts, polish and screenshot tests |
| G–I | **Phase 2 — Food** | G: unit and conversion engine + ingredient parser (≈200 test cases). H: recipes (view, editor, scaling, US⇄metric toggle, cook mode). I: shopping lists, recipe-from-URL and Paprika import, AI recipe prompt |
| J–L | **Phase 3 — Training** | J: exercise library and templates. K: live workout logging with rest-timer notifications, history and PRs. L: multi-week plans and progression rules, Strong CSV import, AI workout prompt |
| M | **Phase 4 — Import hub** | Samsung Notes via share, txt/md/docx, Evernote, convert a note to a recipe or workout |
| N–O | **Phase 5 — Sync** | Sync engine against a fake provider, then Google Drive sync with sign-in |
| P–Q | **Phase 6 — Polish & iOS** | iOS features (sign-in, share extension, notifications), charts, accessibility, branding, store builds |

Phase 1b (runs E–F) builds on the database frozen at the end of run D, so it can't start earlier.
The full design for Phases 2–6 is already in `PLAN.md` (Food, Training, Imports, Cloud). Each phase
gets a short detailed plan, like Phase 1's, when its first run starts.

## Dev helpers added along the way

- `./gradlew :shared:devImportKeep -Pzips=a.zip,b.zip` loads a Keep Takeout export (zips or unzipped
  folders) into the desktop dev database (`~/.ftm-notes-dev`, or `FTM_DEV_DIR`). Attachments are
  skipped until run C.
