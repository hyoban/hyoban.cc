# Asset storage

Images and videos are stored in the Cloudflare R2 bucket configured in
`apps/web/src/data/asset-config.json` and served from `https://image.hyoban.cc`. Objects are
immutable: a changed file is uploaded under a new key, never over an old one.

| Prefix                                                           | For                                         |
| ---------------------------------------------------------------- | ------------------------------------------- |
| `moments/<moment uid>/<media uid>[-960w\|-video\|-poster].<ext>` | Moment media, all lowercase (`getMediaKey`) |
| `posts/<post-slug>/<semantic-file>`                              | article media                               |
| `site/<file>`                                                    | global site assets                          |

Upload an article or site asset without adding it to the repository:

```sh
vp run assets:upload --key posts/my-article/architecture-diagram.webp --file /absolute/path/architecture-diagram.webp
```

It refuses to overwrite an existing key unless its ETag and size match the local file, and
sets the MIME type and `Cache-Control: public, max-age=31536000, immutable`.

## Share cards

An article or Markdown page can put a photo on its share card with an absolute image URL in
its frontmatter; the first image in the article is never chosen automatically:

```yaml
ogImage: https://image.hyoban.cc/posts/my-article/cover.jpg
ogDescription: A short summary that fits beside the photo.
```

`ogDescription` shortens only the text drawn on the card; search, Open Graph and RSS keep
`description`.

## Moment media

The create-moment skill optimizes images into 480, 960 and 1920 pixel variants, transcodes
videos with a poster, uploads and verifies them, and records each media item's R2 key, size,
MIME type, ETag, dimensions and variants in its `published` block.

Moments the Android app publishes keep their media as uploaded (`original: true`, no
variants or poster). The site asks Cloudflare Images for resized photos
(`/cdn-cgi/image/width=…,format=auto,onerror=redirect/<key>`) and Media Transformations
for a video's first frame as its poster (`/cdn-cgi/media/mode=frame,time=0s,width=…,format=jpg/<key>`),
which needs the video's width and height on its media item and a source under 100 MB,
ideally H.264 MP4. Transformations must be enabled on the `hyoban.cc` zone; past the free
plan's 5,000 a month a new size falls back to the original.

```sh
vp run moments:audit            # every Moment and its media; --remote checks each R2 object
vp run moments:variants         # missing responsive variants for existing images
vp run moments:review-hidden    # a report on hidden records under .artifacts, changing nothing
vp run moments:gc               # an R2 cleanup manifest under .artifacts, deleting nothing
```

`moments:audit --changed-from <git-ref>` requires non-empty alt text on changed visible
Moments. `moments:gc` keeps a 30-day grace period; deletion is a separate step, which
reloads the references and the inventory and refuses anything that changed since:

```sh
node scripts/gc-moment-assets.mjs --apply /absolute/path/to/manifest.json
```

## The car model

The car on the year review's driving section, and in an article paragraph that is only its
still, is the "Zeekr 7GT 2026" model by Zifir3d, bought on Superhive (royalty free),
repainted and exported as glTF with transmissive glass, its axles as the nodes
`front-wheels` and `rear-wheels`, and its lamp strips as the glowing materials. It comes in
two cuts: `site/zeekr-007gt-9.glb`, simplified with 512px WebP textures, loads first and
drives a trip's flyover; `site/zeekr-007gt-9-full.glb`, only meshopt-quantized, replaces it
once the car settles. The page keeps both in Cache Storage, since the full one exceeds some
browsers' HTTP caches. Its still is `site/zeekr-007gt-7.webp`. The purchase and export
script stay under `.private/3d/zeekr-007gt`; a changed model goes up under the next number.
