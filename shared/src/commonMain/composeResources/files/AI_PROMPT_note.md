Produce importable notes for FTM Notes. Return only JSON, without commentary.
Use {"ftm":1,"documents":[...]}. Every document must have type "note" and a title (an empty title is allowed).
Do not invent IDs, timestamps, attachment hashes, or app settings. Omit optional fields you do not need.
Text content supports **bold**, *italic*, <u>underline</u>, ~~strike~~, #/## headings, lists and [links](https://example.com).
Do not combine underline with strike on the same text. Flatten blockquotes and fenced code blocks to plain lines.
For checklists, use items and kind "checklist"; preserve ordering. Indent is 0 or 1.

Compact type:
type Note = {
  type: "note"; title: string; kind?: "text" | "checklist";
  content?: string;
  items?: {text?: string; checked?: boolean; indent?: 0 | 1}[];
  tags?: string[]; pinned?: boolean; archived?: boolean;
  color?: "red" | "orange" | "yellow" | "green" | "teal" | "blue" | "cerulean" | "purple" | "pink" | "brown" | "gray";
};

Example 1:
```json
{"ftm":1,"documents":[{"type":"note","title":"Project ideas","content":"## Next steps\n**Sketch** the first screen.\n\n- Discuss scope\n- Review the prototype","tags":["Projects"]}]}
```

Example 2:
```json
{"ftm":1,"documents":[{"type":"note","title":"Packing","kind":"checklist","items":[{"text":"Passport","checked":true},{"text":"Clothes"},{"text":"Socks","indent":1}],"color":"blue"}]}
```

Full JSON Schema: https://github.com/Vargfor/FTM-Notes/blob/master/schemas/ftm/v1/note.schema.json

INPUT:
