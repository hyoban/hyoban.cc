# Asset storage

Images and videos are stored in the Cloudflare R2 bucket configured in
`src/data/asset-config.json`. Production files are served from
`https://image.hyoban.cc`.

An article or Markdown page can opt into a photo on its share card with an
absolute image URL in its frontmatter:

```yaml
ogImage: https://image.hyoban.cc/posts/my-article/cover.jpg
ogDescription: A short summary that fits beside the photo.
```

Choose a photo that still works when cropped beside the title. Without `ogImage`,
the card keeps its text layout; the first image in the article is never chosen
automatically. Optional `ogDescription` shortens just the text drawn on the card;
without it the card uses `description`. Search, Open Graph metadata and RSS keep
the full `description`.

Use these object key prefixes:

- `moments/<moment uid>/<media uid>[-960w|-video|-poster].<ext>` for Moment media, all lowercase (`getMediaKey`)
- `posts/<post-slug>/<semantic-file>` for article media
- `site/<file>` for global site assets

The car on the year review's driving section, also drawn in an article by a paragraph
that is only its still, is the "Zeekr 7GT 2026" model by
Zifir3d, bought on Superhive (royalty free), repainted in the car's colour and
exported as glTF with its glass made transmissive and each axle's pair of wheels kept
as its own node, `front-wheels` and `rear-wheels`, for the page to roll. Its lamps are the
materials that glow, the strip across its nose and the one across its tail, which the
page and the trip's flyover switch off by day. It comes in two
cuts: `site/zeekr-007gt-9.glb`, simplified with 512px WebP textures, loads first and also
drives a trip's route while its playback flies over; `site/zeekr-007gt-9-full.glb`,
only meshopt-quantized, takes its place once the car has settled. The page keeps both
in its Cache Storage once fetched, since the
full one is more than some browsers' HTTP caches hold, and draws a kept full one straight
away, with no light one first. Its still is
`site/zeekr-007gt-7.webp`. The purchase, the
export script and its outputs are kept out of the repository under
`.private/3d/zeekr-007gt`; a changed model and its still are uploaded under the
next number, since objects are immutable.

Upload a new article or site asset without adding it to the repository:

```sh
pnpm assets:upload --key posts/my-article/architecture-diagram.webp --file /absolute/path/architecture-diagram.webp
```

The command refuses to overwrite an existing key unless its ETag and size match
the local file. Uploaded objects use their correct MIME type and
`Cache-Control: public, max-age=31536000, immutable`.

The `create-moment` skill handles image optimization, responsive variants,
video transcoding and poster generation, upload, verification, and the Moment's
record automatically. Its preparation step is resumable and writes the record
under `data/moments` only after every remote object has been verified. Each
media item of a record carries its R2 key, byte size, MIME type, ETag, image
dimensions and responsive variants in its `published` block (see
`packages/data-spec`).

Moments the recording app publishes through the API keep their photos as
uploaded instead: the image's `published` block has `original: true` and no
variants, and the site asks Cloudflare Images for 480, 960 and 1920 pixel
copies in the browser's best format
(`/cdn-cgi/image/width=…,format=auto,onerror=redirect/<key>` on the image
origin). Their videos are kept as uploaded too, marked `original: true` with no
poster, and the site takes the first frame from Media Transformations as the
poster (`/cdn-cgi/media/mode=frame,time=0s,width=…,format=jpg/<key>`); that
needs the video's width and height on its media item, and a source under
100 MB, ideally MP4 with H.264. Transformations must be enabled for the `hyoban.cc` zone; the free
plan's 5,000 unique transformations a month cover them, and past that a new
size falls back to the original.

The R2 directory is the Moment's uid and each file its media item's, so nothing moves
when a Moment changes day, place or words. Existing objects are immutable; a changed
photo is a new media item with a new uid, never an overwritten key.

Audit every Moment record and its published media:

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
