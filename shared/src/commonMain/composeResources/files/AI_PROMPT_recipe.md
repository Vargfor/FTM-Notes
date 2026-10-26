Produce importable recipes for FTM Notes. Return only JSON, without commentary.
Use {"ftm":1,"documents":[...]}. Every recipe has type "recipe" and a title.
Omit optional fields you do not need. Do not invent IDs, timestamps, attachment hashes, nutrition, or app settings.
Preserve original units and amounts; the app converts and scales them for display.
Quantities are a number or {"min":number,"max":number}. Use numeric JSON values: 0.5 for a half, never "1/2".
Set scalable:false for salt to taste, garnish, and other fixed amounts. Preserve unclear ingredients in raw and omit uncertain quantity/unit fields.
Canonical units include g, kg, mg, oz, lb, ml, dl, l, us_cup, uk_cup, metric_cup, us_tbsp, us_tsp, metric_tbsp, metric_tsp, piece, can, clove, slice.
For packages, quantity is the package count, unit is its type, and packageQuantity/packageUnit describe one package.
Keep cooking times fixed. timerSeconds is a positive integer; prep/cook/total minutes are nonnegative integers.
Nutrition maps string labels to string values including units. Rating is an integer from 0 to 5.

Compact type:
type Recipe = {
  type: "recipe"; title: string; description?: string;
  servings?: {amount?: number; label?: string};
  prepMinutes?: number; cookMinutes?: number; totalMinutes?: number;
  ingredientGroups?: {name?: string; items?: Ingredient[]}[];
  steps?: {text?: string; timerSeconds?: number; section?: string}[];
  notes?: string; tags?: string[]; sourceUrl?: string; sourceName?: string;
  nutrition?: Record<string,string>; rating?: number;
};
type Ingredient = {
  raw?: string; name?: string; quantity?: number | {min:number; max?:number};
  unit?: string; note?: string; optional?: boolean; scalable?: boolean;
  category?: string; packageQuantity?: number | {min:number; max?:number}; packageUnit?: string;
};

Example 1:
```json
{"ftm":1,"documents":[{"type":"recipe","title":"Tomato soup","servings":{"amount":4,"label":"bowls"},"prepMinutes":10,"cookMinutes":20,"totalMinutes":30,"ingredientGroups":[{"items":[{"raw":"2 (400 g) cans tomatoes","name":"tomatoes","quantity":2,"unit":"can","packageQuantity":400,"packageUnit":"g"},{"raw":"500 ml stock","name":"stock","quantity":500,"unit":"ml"},{"raw":"salt to taste","name":"salt","scalable":false}]}],"steps":[{"text":"Simmer for 20 minutes.","timerSeconds":1200}],"tags":["Soup"]}]}
```

Example 2:
```json
{"ftm":1,"documents":[{"type":"recipe","title":"Pancakes","servings":{"amount":2,"label":"portions"},"ingredientGroups":[{"name":"Batter","items":[{"raw":"1 1/2 cups flour","name":"flour","quantity":1.5,"unit":"us_cup"},{"raw":"2-3 eggs","name":"eggs","quantity":{"min":2,"max":3},"unit":"piece"},{"raw":"berries, optional","name":"berries","optional":true,"scalable":false}]}],"steps":[{"text":"Mix the batter."},{"text":"Cook each side for 2 minutes.","timerSeconds":120}],"nutrition":{"Energy per portion":"350 kcal"}}]}
```

Full JSON Schema: https://github.com/Vargfor/FTM-Notes/blob/master/schemas/ftm/v1/recipe.schema.json

INPUT:
