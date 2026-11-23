# Asset storage

Images and videos are stored in the R2 bucket set in `site.config.json`'s `assets` and
served from its origin, `https://image.hyoban.cc`. Objects are immutable: a changed file
gets a new key, never overwrites an old one. The one exception is blanking a location in
place (see AGENTS.md).

| Prefix                                                           | For                                         |
| ---------------------------------------------------------------- | ------------------------------------------- |
| `moments/<moment uid>/<media uid>[-960w\|-video\|-poster].<ext>` | Moment media, all lowercase (`getMediaKey`) |
| `posts/<post-slug>/<semantic-file>`                              | article media                               |
| `site/<file>`                                                    | global site assets                          |

Upload an article or site asset without adding it to the repository:

```sh
vp run assets:upload --key posts/my-article/architecture-diagram.webp --file /absolute/path/architecture-diagram.webp
```

It refuses to overwrite an existing key unless its ETag and size match the local file. It
sets the MIME type and `Cache-Control: public, max-age=31536000, immutable`.

## Share cards

An article or Markdown page can put a photo on its share card with an absolute image URL in
its frontmatter. The article's first image is never picked automatically.

```yaml
ogImage: https://image.hyoban.cc/posts/my-article/cover.jpg
ogDescription: A short summary that fits beside the photo.
```

`ogDescription` only shortens the text on the card; search, Open Graph and RSS keep
`description`.

## Xiaohongshu cards

`vp run posts:cards <slug>` lays an article out as 3:4 Xiaohongshu cards, 1242×1656 PNGs in
`.private/post-cards/<slug>/`. The cover uses the title, `ogDescription`, `description` and
`ogImage` (else the first image). It renders in a local headless Chrome; pass another with
`--chrome` or `CHROME_PATH`.

## Moment media

The create-moment skill makes 480, 960 and 1920 pixel image variants, transcodes videos
with a poster, uploads and verifies them, and records each item's R2 key, size, MIME type,
ETag, dimensions and variants in its `published` block. Its sharp and ffmpeg steps keep
none of the source's metadata.

The Android app uploads media as-is (`original: true`, no variants or poster), except for
location. Before uploading it blanks the GPS in a photo's EXIF and XMP and a video's
location boxes, in place so nothing else changes (`@hyoban/data-spec/location`, and
`Location.kt` in the app's shared code). The API rejects an image that still has a
location.

The site resizes through Cloudflare:

- Photos via Images: `/cdn-cgi/image/width=…,format=auto,onerror=redirect/<key>`.
- A video's poster is its first frame via Media Transformations:
  `/cdn-cgi/media/mode=frame,time=0s,width=…,format=jpg/<key>`. This needs the video's
  width and height on its media item and a source under 100 MB, ideally H.264 MP4.

Transformations must be enabled on the `hyoban.cc` zone. Past the free plan's 5,000 a
month, a new size falls back to the original.

```sh
vp run moments:audit            # every Moment and its media; --remote checks each R2 object
vp run moments:variants         # missing responsive variants for existing images
vp run moments:review-hidden    # a report on hidden records under .artifacts, changing nothing
vp run moments:gc               # an R2 cleanup manifest under .artifacts, deleting nothing
```

These read Moments from `data/`, so run `vp run data:export` first. `moments:audit` warns
about visible media with empty alt text.

`moments:gc` counts every `moments/` key named anywhere in `data/` as referenced, including
private media and records without words. It refuses an export more than a day old and keeps
a 30-day grace period. Deleting is a separate step: it reloads the references and the
inventory, refuses anything that changed since, and retries a failed delete up to five
times.

```sh
node scripts/gc-moment-assets.mjs --apply /absolute/path/to/manifest.json
```

## The car model

The car in the year review's driving section, and in a post paragraph that is only its
still, is the "Zeekr 7GT 2026" model by Zifir3d, bought on Superhive (royalty free). It was
repainted and exported as glTF with transmissive glass, its axles as the nodes
`front-wheels` and `rear-wheels`, and its lamp strips as glowing materials.

- `site/zeekr-007gt-9.glb`: simplified, with 512px WebP textures. It loads first and drives
  a trip's flyover.
- `site/zeekr-007gt-9-full.glb`: only meshopt-quantized. It replaces the first once the car
  settles.
- `site/zeekr-007gt-7.webp`: the still.

The page keeps both models in Cache Storage, since the full one exceeds some browsers' HTTP
caches. The purchase and export script stay under `.private/3d/zeekr-007gt`. A changed
model is uploaded under the next number.
