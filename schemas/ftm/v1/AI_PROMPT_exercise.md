Produce an importable exercise for FTM Notes. Return only JSON without commentary.
Use {"ftm":1,"documents":[...]}. Include all exercises and routines needed by a complete program.
Use matching exercise/routine names for references; no IDs are required. Do not invent document IDs, timestamps, attachment hashes or settings. Optional day IDs may be short labels only when needed by overrides.
Weights are numeric kg or lb; seconds and metres are numeric; reps are a positive integer or repMin/repMax. A bodyweight set uses weight:0 for no added load. Store actual performed values separately from targets.
Keep incomplete sets completed:false. Import session history only as status:"finished" with actual ISO-8601 start/end times; never invent performance history.
Every helper is optional. Use progression kind:"none" when no progression was requested. Linear/double increments use the declared unit. Percent load kinds need a configured or estimated reference maximum in the app.

Compact types:
type ExerciseRef = {id?:string; name?:string; tracking?:Tracking; equipment?:string};
type Tracking = "weight_reps"|"bodyweight_reps"|"reps"|"duration"|"distance"|"duration_distance";
type Load = {amount?:number; unit?:"kg"|"lb"; kind?:"absolute"|"percent_1rm"|"percent_tm"|"rpe"|"rir"};
type Target = {kind?:"warmup"|"working"|"drop"|"failure"|"amrap"; reps?:number; repMin?:number; repMax?:number; load?:Load; seconds?:number; metres?:number; customValues?:Record<string,string|number|boolean>};
type Exercise = {type:"exercise"; title:string; category?:string; aliases?:string[]; muscleGroups?:string[]; equipment?:string; tracking?:Tracking; defaultRestSeconds?:number; increment?:number; unitOverride?:"kg"|"lb"; fields?:{key:string;label?:string;kind?:"number"|"text"|"boolean";unit?:string}[]; notes?:string; instructionUrl?:string};
type Routine = {type:"workout_template"; title:string; tags?:string[]; notes?:string; blocks?:{exercise:ExerciseRef; supersetGroup?:string; restSeconds?:number; notes?:string; sets?:Target[]}[]};
type Rule = {kind?:"none"|"linear"|"double"|"manual"; increment?:number; unit?:"kg"|"lb"; frequency?:"session"|"week"; missesBeforeDeload?:number; deloadPercent?:number; repMin?:number; repMax?:number; manualWeeks?:{week:number;weight?:number;reps?:number;unit?:"kg"|"lb"}[]};
type Plan = {type:"training_plan"; title:string; weeks?:number; notes?:string; days?:{id?:string;name?:string;template:{name?:string;id?:string}}[]; progression?:Rule; exerciseRules?:{exercise:ExerciseRef;rule:Rule}[]; deloadEveryWeeks?:number; deloadPercent?:number; overrides?:{week:number;dayId?:string;exercise:ExerciseRef;sets?:Target[]}[]};
type Session = {type:"workout_session"; title:string;status:"finished";startedAt:string;endedAt:string;notes?:string;bodyweight?:number;bodyweightUnit?:"kg"|"lb";exercises?:{exercise:ExerciseRef;snapshot?:{name?:string;tracking?:Tracking};notes?:string;restSeconds?:number;supersetGroup?:string;sets?:{target?:Target;kind?:"warmup"|"working"|"drop"|"failure"|"amrap";weight?:number;unit?:"kg"|"lb";reps?:number;seconds?:number;metres?:number;rpe?:number;rir?:number;completed?:boolean;customValues?:Record<string,string|number|boolean>}[]}[]};

Example 1:
```json
{"ftm":1,"documents":[{"type":"exercise","title":"Bench Press","tracking":"weight_reps","equipment":"barbell","muscleGroups":["chest"]}]}
```

Example 2:
```json
{"ftm":1,"documents":[{"type":"exercise","title":"Plank","tracking":"duration","equipment":"bodyweight","muscleGroups":["core"],"defaultRestSeconds":60}]}
```

Full JSON Schema: https://github.com/Vargfor/FTM-Notes/blob/master/schemas/ftm/v1/exercise.schema.json

INPUT:
