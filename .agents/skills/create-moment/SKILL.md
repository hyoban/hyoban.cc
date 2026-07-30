---
name: create-moment
description: Create a hyoban.cc Moment from exact user text and media. Use when adding or recording a Moment through conversation.
---

# Create Moment

Create one Moment in `src/content/moments`. Never deploy the site or publish the
Moment to a social network. Uploading its media to the configured R2 bucket is
part of preparing the local Moment.

1. Inspect `src/moments/content.ts`, `src/data/locations.ts`, and a recent Moment.
2. Preserve the user's text exactly, including errors, punctuation, paragraphs, and line breaks.
3. Use one `occurredAt` value for when the Moment happened. Use an ISO timestamp
   with an explicit offset when the time is known, otherwise use `YYYY-MM-DD`.
   Never add publication or source fields. If the text names a specific place,
   use that place instead of its city; reuse or add it in
   `src/data/locations.ts` and verify new coordinates. Use
   `YYYY/MM/DD-NN-short-slug` for the ID, where `NN` is the next available
   two-digit order for that occurrence date.
4. Process and upload images or videos in order from the repository root:

```bash
node .agents/skills/create-moment/scripts/prepare-media.mjs --repo <repo> --target <moment-dir> -- <media...>
```

The command prepares everything before atomically creating the Moment directory.
It optimizes images and responsive variants, transcodes videos to web-compatible
MP4 with a poster, gives files semantic names from the Moment slug, uploads or
safely reuses and verifies them in the matching R2 directory, and writes the
colocated `assets.json`. It is safe to rerun after an interrupted upload and
leaves no media files in the Moment directory.

5. Inspect each source media item and replace every empty suggested alt with
   concise English alt text. Visible new Moments must never use an empty alt.
6. Create `index.md` with `apply_patch`, ordering fields as `occurredAt`,
   `location`, `media`. Omit unused optional fields and `hidden`.
7. Compare the saved body with the user's text, then run `pnpm test`, `pnpm check`, and `pnpm build`.

Only perform Git operations when requested. Commit directly on `main`; do not create a branch.
