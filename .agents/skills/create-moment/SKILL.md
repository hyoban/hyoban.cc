---
name: create-moment
description: Create a hyoban.cc Moment from exact user text and images. Use when adding or recording a Moment through conversation.
---

# Create Moment

Create one Moment in `src/content/moments`. Never publish it externally.

1. Inspect `src/moment-content.ts`, `src/data/locations.ts`, and a recent Moment.
2. Preserve the user's text exactly, including errors, punctuation, paragraphs, and line breaks.
3. Use the current Asia/Singapore time for `publishedAt`. Add `occurredOn` only when clear. If the text names a specific place, use that place instead of its city; reuse or add it in `src/data/locations.ts` and verify new coordinates. Use `YYYY/MM/DD-HHmm-short-slug` for the ID.
4. Process images in order from the repository root:

```bash
node .agents/skills/create-moment/scripts/prepare-images.mjs --repo <repo> --target <moment-dir> -- <images...>
```

5. Inspect each image and write concise English alt text.
6. Create `index.md` with `apply_patch`, ordering fields as `publishedAt`, `occurredOn`, `location`, `media`. Omit unused optional fields and `hidden`.
7. Compare the saved body with the user's text, then run `pnpm test`, `pnpm check`, and `pnpm build`.

Only perform Git operations when requested. Commit directly on `main`; do not create a branch.
