# Asset storage

Images and videos are stored in the Cloudflare R2 bucket configured in
`src/data/asset-config.json`. Production files are served from
`https://image.hyoban.cc`.

Use these object key prefixes:

- `moments/YYYY/MM/DD-NN-short-slug/<semantic-file>` for Moment media
- `posts/<post-slug>/<semantic-file>` for article media
- `site/<file>` for global site assets

Upload a new article or site asset without adding it to the repository:

```sh
pnpm assets:upload --key posts/my-article/architecture-diagram.webp --file /absolute/path/architecture-diagram.webp
```

The command refuses to overwrite an existing key unless its ETag and size match
the local file. Uploaded objects use their correct MIME type and
`Cache-Control: public, max-age=31536000, immutable`.

The `create-moment` skill handles image optimization, responsive variants,
video transcoding and poster generation, upload, verification, and colocated
metadata automatically. Its preparation step is resumable and creates the final
Moment directory atomically only after every remote object has been verified.
Each Moment directory contains:

- `index.md` for the exact Moment text and ordered semantic media references
- `assets.json` for byte size, MIME type, ETag, image dimensions, and responsive
  variant relationships

The R2 directory mirrors the Moment content id. Existing objects are immutable;
replace changed media with a new semantic filename instead of overwriting a key.

Audit every Moment document and colocated asset record:

```sh
pnpm moments:audit
```

Add `--remote` to verify every referenced R2 object's status, byte size, MIME
type, and ETag. Existing empty alt text is reported as an accessibility warning.
When `--changed-from <git-ref>` is used, changed visible Moments must have
non-empty alt text.

Generate missing 480, 960, and 1920 pixel responsive variants for existing
Moment images. The source object remains the lightbox original:

```sh
pnpm moments:variants
```

Review hidden records without changing or deleting them:

```sh
pnpm moments:review-hidden
```

The report is written under the ignored `.artifacts` directory and groups
hidden records into unique, hidden duplicates, visible duplicates, and empty
records.

Inventory R2 and create a cleanup manifest without deleting anything:

```sh
pnpm moments:gc
```

The dry run uses a 30-day grace period and saves a hashed manifest under
`.artifacts`. Deletion is intentionally a separate explicit command:

```sh
node scripts/gc-moment-assets.mjs --apply /absolute/path/to/manifest.json
```

Before deletion, the apply step reloads both current references and the remote
inventory, then refuses any object that became referenced or changed since the
manifest was created.

`pnpm assets:migrate` is a dry run for any legacy media accidentally placed in
the old local directories. Add `--upload` to upload and verify it. Add
`--remove-local` only together with `--upload` to remove files after every object
has been verified.
