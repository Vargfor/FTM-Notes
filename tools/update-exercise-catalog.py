"""Rebuild the offline library from a pinned, public-domain source. No AI-generated entries.

Run: py tools/update-exercise-catalog.py [--check]
Only the Python standard library is needed. Downloads are verified before use.
"""
from pathlib import Path
from urllib.request import Request, urlopen
import argparse
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
REVISION = "f00c92c7dcf1216a928a52c3706c7ce8e2f71ed5"
SHA256 = "5bb747e3fc658f095a60dcbf6d53c96627acdcc6ffb6fffde86f7e26995d40bf"
BASE = f"https://raw.githubusercontent.com/yuhonas/free-exercise-db/{REVISION}/"
OUTPUT = ROOT / "shared/src/commonMain/composeResources/files/exercise_catalog.json"

# Reviewed equivalents of existing entries. Retain their IDs and familiar display names;
# preserve the source name as an alias. Equipment-specific variants remain separate.
EQUIVALENTS = {
    "Barbell_Bench_Press_-_Medium_Grip": "bench-press",
    "Barbell_Incline_Bench_Press_-_Medium_Grip": "incline-bench-press",
    "Decline_Barbell_Bench_Press": "decline-bench-press",
    "Close-Grip_Barbell_Bench_Press": "close-grip-bench-press",
    "Dumbbell_Flyes": "dumbbell-fly",
    "Low_Cable_Crossover": "low-cable-fly",
    "Machine_Bench_Press": "chest-press-machine",
    "Butterfly": "pec-deck",
    "Pushups": "push-up",
    "Bent_Over_Barbell_Row": "bent-over-row",
    "Seated_Cable_Rows": "seated-cable-row",
    "Pullups": "pull-up",
    "Standing_Military_Press": "overhead-press",
    "Upright_Barbell_Row": "upright-row",
    "Arnold_Dumbbell_Press": "arnold-press",
    "Side_Lateral_Raise": "lateral-raise",
    "Front_Dumbbell_Raise": "front-raise",
    "Machine_Shoulder_Military_Press": "shoulder-press-machine",
    "Barbell_Squat": "back-squat",
    "Front_Barbell_Squat": "front-squat",
    "Zercher_Squats": "zercher-squat",
    "Dumbbell_Lunges": "dumbbell-lunge",
    "Dumbbell_Step_Ups": "dumbbell-step-up",
    "Leg_Extensions": "leg-extension",
    "Barbell_Deadlift": "deadlift",
    "Lying_Leg_Curls": "lying-leg-curl",
    "Barbell_Hip_Thrust": "hip-thrust",
    "Barbell_Glute_Bridge": "glute-bridge",
    "Pull_Through": "cable-pull-through",
    "One-Legged_Cable_Kickback": "cable-kickback",
    "Standing_Calf_Raises": "standing-calf-raise",
    "Dumbbell_Bicep_Curl": "dumbbell-curl",
    "Hammer_Curls": "hammer-curl",
    "Concentration_Curls": "concentration-curl",
    "Standing_Biceps_Cable_Curl": "cable-curl",
    "Cable_Hammer_Curls_-_Rope_Attachment": "rope-hammer-curl",
    "Triceps_Pushdown_-_Rope_Attachment": "rope-pushdown",
    "Tricep_Dumbbell_Kickback": "dumbbell-kickback",
    "EZ-Bar_Skullcrusher": "skull-crusher",
    "Crunches": "crunch",
    "Elliptical_Trainer": "elliptical",
    "Bicycling": "cycling",
    "Farmers_Walk": "farmer-carry",
}


def normal(value):
    return re.sub(r"[^a-z0-9]", "", value.lower())


def generate():
    request = Request(BASE + "dist/exercises.json", headers={"User-Agent": "FTM-Notes"})
    raw = urlopen(request, timeout=60).read()
    assert hashlib.sha256(raw).hexdigest() == SHA256, "Source changed; review it before updating the pin"
    source = json.loads(raw)
    assert len(source) == 876 and len({r["id"] for r in source}) == 876
    text = (ROOT / "core/src/commonMain/kotlin/io/github/vargfor/ftmnotes/core/training/ExerciseCatalog.kt").read_text(encoding="utf-8")
    legacy = dict(re.findall(r'Ids.seeded\("exercise", "([^"]+)"\), title = "([^"]+)"', text))
    source_ids = {r["id"] for r in source}
    assert EQUIVALENTS.keys() <= source_ids, EQUIVALENTS.keys() - source_ids
    assert set(EQUIVALENTS.values()) <= legacy.keys()
    claimed = set()
    rows = []
    for row in source:
        entry = {key: row[key] for key in ("id", "name", "category", "equipment", "primaryMuscles", "instructions")}
        exact = [slug for slug, title in legacy.items() if normal(title) == normal(row["name"])]
        slug = EQUIVALENTS.get(row["id"]) or (exact[0] if len(exact) == 1 else None)
        if slug:
            assert slug not in claimed, f"Two source exercises map to {slug}; review the variants"
            claimed.add(slug)
            entry["legacySlug"] = slug
        rows.append(entry)
    snapshot = {"repository": "https://github.com/yuhonas/free-exercise-db", "revision": REVISION,
                "sha256": SHA256, "exercises": rows}
    output = json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    if parser.parse_args().check:
        assert OUTPUT.read_text(encoding="utf-8") == output, "Bundled catalog differs from the verified source"
    else:
        OUTPUT.write_text(output, encoding="utf-8", newline="\n")
        license_text = urlopen(BASE + "LICENSE.md", timeout=30).read()
        license_path = ROOT / "third-party/free-exercise-db/LICENSE.md"
        license_path.parent.mkdir(parents=True, exist_ok=True)
        license_path.write_bytes(license_text)
    print(f"Verified {len(rows)} source exercises; {len(claimed)} reuse existing IDs; {len(legacy) - len(claimed)} additional existing exercises retained.")


if __name__ == "__main__":
    generate()
