# FTM Notes — roadmap in runs

The product and technical plan is [`PLAN.md`](PLAN.md). This file breaks the remaining work into
numbered **runs** — roughly one working session each — so "the next run" always means the same thing.

Every run ends with:
- all tests green: `./gradlew :core:jvmTest :shared:desktopTest :shared:testAndroidHostTest`
- the iOS compile check: `./gradlew :core:compileTestKotlinIosArm64 :shared:compileKotlinIosArm64`
- `./gradlew :androidApp:assembleDebug`, then an emulator walkthrough (phone, plus tablet for list-detail)
- a short report, after which Henrik commits.

## Status

| Done | What |
|---|---|
| ✅ Phase 0 | Foundation, app ID `io.github.vargfor.ftmnotes` |
| ✅ P1.0 | Spikes (editor, zip writer, iOS cross-compile), CI fixed for `master` |
| ✅ P1a step 1 (+ most of step 2) | Data-loss fixes; DB v2 (tags, search, trash/purge, import batches) |

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
