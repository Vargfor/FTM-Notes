# Training and portable programs

Training follows a set-table workflow inspired by Hevy: routines, previous results, large numeric
controls, completed sets, rest timers and workout history. It uses the same FTM JSON format as
Food and Misc notes.

Small info icons explain unfamiliar fields and controls. Tap one for a definition or example;
you can hide them through **Settings → Show help icons**.
RPE/RIR wording follows the resistance-training scale described in the
[Hevy help centre](https://help.hevyapp.com/hc/en-us/articles/34490600233111-RPE-vs-RIR-What-They-Mean-and-How-to-Use-Them-in-Hevy).

## Build a routine without AI

1. Open **Training**, choose **Routines**, then **New routine**.
2. Give it a name and tap **Add exercise**. Search by name, muscle or equipment, or use
   the **Activity**, **Muscle groups** and **Equipment** filters.
3. Set your repetitions and optional weight for each set, then tap **Done**.
4. Open the routine and tap **Start workout**. Previous results appear as you log your sets.

Exercise definitions have editable logging choices, rest times and notes. Less common options
are under **More exercise options**. In Training settings, plate inventory uses labelled weights
and quantities rather than a text format.

## Exercise library

The offline library contains **909 exercises**: all **876** entries in a pinned
[Free Exercise DB snapshot](https://github.com/yuhonas/free-exercise-db/tree/f00c92c7dcf1216a928a52c3706c7ce8e2f71ed5),
plus 33 existing built-ins. Seventy-nine documented equivalents retain the original app IDs and
familiar names, with the source name retained as an alias. Exercise and workout links remain stable.

Categories cover strength, cardio, stretching, jumps and explosive movements, powerlifting,
Olympic weightlifting and strongman. Primary muscles, instructions and categories come from the
source. Each sourced entry links to its exact source revision. Missing equipment is left unspecified.
Logging modes and increments are editable app defaults, not additional claims from the dataset.
Existing personal edits, archived entries and trash are preserved during library updates.

The source is public domain under the [Unlicense](../third-party/free-exercise-db/LICENSE.md).
The bundle contains text only; it needs no exercise photo downloads, remote services or new dependencies.
Run `py tools/update-exercise-catalog.py --check` to verify the bundle against its pinned, hashed source.

## Import a complete program

1. Open **Training → Import a program**.
2. Open an existing FTM file or paste imported content.
3. Review the preview. Resolve any exercise or routine choices, then import the program.
   Required routines and exercises are included automatically.
4. Open the plan and start its next workout. **Choose week and day** can change the rotation.

If you want help preparing a file, expand **Use an AI assistant (optional)**, choose the content
type and copy its prompt. AI is optional for creating, using, importing or exporting your routines.

The [example six-week program](../schemas/ftm/v1/example_training_program.ftm.json) is ready to import.
It includes two exercises, two routines, double progression and a deload override.

Imports reuse compatible catalog definitions and exact names or aliases. Ambiguous or similar
matches are shown for review. Missing named exercises become editable definitions. Conflicting
document IDs are remapped consistently throughout a bundle. Imports are transactional, and undo
keeps definitions that another workout or plan now uses.

## Schemas and AI prompts

Every content type has a strict JSON Schema and a self-contained prompt with two importable examples.
The app bundles the same files; schema drift and example round trips are tested.

| Content | JSON Schema | AI prompt |
|---|---|---|
| Misc notes and checklists | [note](../schemas/ftm/v1/note.schema.json) | [Notes](../schemas/ftm/v1/AI_PROMPT_note.md) |
| Recipes | [recipe](../schemas/ftm/v1/recipe.schema.json) | [Recipes](../schemas/ftm/v1/AI_PROMPT_recipe.md) |
| Shopping lists | [shopping_list](../schemas/ftm/v1/shopping_list.schema.json) | [Shopping lists](../schemas/ftm/v1/AI_PROMPT_shopping_list.md) |
| Exercise definitions | [exercise](../schemas/ftm/v1/exercise.schema.json) | [Exercises](../schemas/ftm/v1/AI_PROMPT_exercise.md) |
| Workout routines | [workout_template](../schemas/ftm/v1/workout_template.schema.json) | [Routines](../schemas/ftm/v1/AI_PROMPT_workout_template.md) |
| Finished workout history | [workout_session](../schemas/ftm/v1/workout_session.schema.json) | [History](../schemas/ftm/v1/AI_PROMPT_workout_session.md) |
| Complete training programs | [training_plan](../schemas/ftm/v1/training_plan.schema.json) | [Programs](../schemas/ftm/v1/AI_PROMPT_training_plan.md) |

The envelope remains version 1:

~~~json
{
  "ftm": 1,
  "documents": [
    {"type": "exercise", "id": "bench", "title": "Bench press", "tracking": "weight_reps"},
    {
      "type": "workout_template",
      "id": "upper",
      "title": "Upper A",
      "blocks": [{
        "exercise": {"id": "bench", "name": "Bench press"},
        "sets": [{"reps": 8, "load": {"amount": 40, "unit": "kg"}}]
      }]
    },
    {
      "type": "training_plan",
      "title": "Six weeks",
      "weeks": 6,
      "days": [{"id": "a", "name": "Upper A", "template": {"id": "upper", "name": "Upper A"}}]
    }
  ]
}
~~~

IDs may be omitted. Named exercise references can include equipment and tracking to disambiguate.
Explicit IDs are useful when generating a large linked program. Arrays preserve exercise, day and
set order. Loads support absolute values, percentage of 1RM or training max, RPE and RIR.
Tracking supports weight/reps, bodyweight with added weight, reps, duration, distance and
duration/distance. Canonical duration and distance are seconds and metres; mass is kg or lb.

Use workout sessions for actual completed history. Plans and routines describe future work;
their prompts do not fabricate completed sessions.

## Logging and progression

The library contains 909 editable exercise definitions. Search names and aliases or
filter activities, muscles and equipment. Each exercise can define custom number, text or boolean fields,
a rest duration, load increment and unit override.

A routine contains ordered exercises, optional superset groups, notes and set targets.
Add Set copies the preceding set. Long press a set number to reorder it; its menu also offers
move buttons, set kind, units, RPE/RIR, custom fields and deletion. Exercise handles reorder
exercises, and each exercise menu provides replacement, rest and notes. Deleted sets and other
workout edits support undo/redo.

Only one workout is active. Completion and structural changes are written immediately.
Numeric set edits are debounced by 300 ms and flushed when leaving or stopping the screen.
An active-workout bar remains available in the other sections. Finished history retains exercise
snapshots even when the catalog changes, and supports editing, routine creation or updating,
PRs and exercise progress charts.

Plans rotate through days for a chosen number of weeks. They support weekly target overrides,
linear progression, double progression, manual weekly targets and deloads. Suggestions affect
future plan targets only after acceptance, per set, per exercise or all at once. Accepted targets
apply to the exercise's next occurrence. Advancing the plan is explicit by default.

Training settings control kg/lb, previous-value prefilling, previous results across routines,
rest timers, keep awake, haptics, superset focus, progression suggestions, automatic plan advance,
warmups, plate calculations, PRs, statistics, bodyweight and e1RM formula.
Defaults are kg, prefilling/rest/suggestions enabled, and automatic advance/warmup generation disabled.
Explicit 1RM and training-max values support percentage targets. Display conversions preserve
stored targets and historical units.

## Export, backup and recovery

The detail menu exports an item with its linked routines, exercises and plan. The backup screen
exports all content, including finished workout history. Historical snapshots reconstruct a missing
exercise definition when needed. A missing optional historical plan/routine link is omitted.

Live and draft workouts are recovered through a complete ZIP backup. Backups include the active
workout, rest deadline and plan rotation. Restore preserves a workout already active on the device;
other recovered live sessions become drafts. Restored monotonic clock values are discarded and
the saved wall-clock deadline is used until a new rest starts. A repeated restore does not multiply
recovered drafts.

Android uses a foreground service with a workout chronometer and a countdown during rest,
plus resume, +15 seconds and skip actions. Notification permission is requested at the first
workout; logging remains available when denied. Optional exact-alarm access is available in Training
settings. Short rests use a bounded wake lock when exact alarms are unavailable. Alerts claim the
persisted timer token so foreground and alarm delivery cannot alert twice. The service uses
[Android's specialUse type](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
because this logger does not collect health sensor data.

Room database version 4 adds derived Training projections and the active-workout singleton.
Version 2 and 3 upgrades preserve existing documents, labels and revision history.

## Strong CSV

Choose **Import Strong CSV** in the import screen. Confirm weight and distance units when the
header does not specify them. The importer detects comma, semicolon or tab delimiters, quoted
fields, workout grouping, dates, durations and supported unit headers. It creates exercise
definitions and finished sessions, with row-level problems shown before import.

Hevy CSV import awaits real export samples. iOS runtime work remains paused; common Kotlin code
and tests continue to cross-compile for iOS.
