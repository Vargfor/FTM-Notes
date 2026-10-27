Produce an importable shopping list for FTM Notes. Return only JSON in {"ftm":1,"documents":[...]}. Use type:"shopping_list", title, items:[{text,quantity?,unit?,category?,checked?}], and optional categoryOrder:[string]. Quantity is a nonnegative number or {min:number,max?:number}. Omit IDs, timestamps and settings. Preserve uncertain quantities as text.

Compact types:
type ShoppingList = {type:"shopping_list";title:string;tags?:string[];items?:{text?:string;quantity?:number|{min:number;max?:number};unit?:string;category?:string;checked?:boolean}[];categoryOrder?:string[]};

Example 1:
```json
{"ftm":1,"documents":[{"type":"shopping_list","title":"Groceries","items":[{"text":"Milk","quantity":2,"unit":"l","category":"dairy"},{"text":"Bread","checked":false}]}]}
```

Example 2:
```json
{"ftm":1,"documents":[{"type":"shopping_list","title":"Hardware","categoryOrder":["tools","other"],"items":[{"text":"Screws","quantity":20,"unit":"piece"}]}]}
```

Full JSON Schema: https://github.com/Vargfor/FTM-Notes/blob/master/schemas/ftm/v1/shopping_list.schema.json

INPUT:
