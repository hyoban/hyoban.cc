# Asset storage

Images and videos are stored in the Cloudflare R2 bucket configured in
`src/data/asset-config.json`. Production files are served from
`https://image.hyoban.cc`.

Use these object key prefixes:

- `moments/<moment-id>/<file>` for Moment media
- `posts/<post-slug>/<semantic-file>` for article media
- `site/<file>` for global site assets

Upload a new article or site asset without adding it to the repository:

```sh
pnpm assets:upload --key posts/my-article/architecture-diagram.webp --file /absolute/path/architecture-diagram.webp
```

The command refuses to overwrite an existing key unless its ETag and size match
the local file. Uploaded objects use their correct MIME type and
`Cache-Control: public, max-age=31536000, immutable`.

The `create-moment` skill handles image optimization, upload, verification, and
dimension metadata automatically. Moment Markdown keeps the media filename while
`src/data/moment-media.generated.json` keeps the width and height used for stable
page layout.

`pnpm assets:migrate` is a dry run for any legacy media accidentally placed in
the old local directories. Add `--upload` to upload and verify it. Add
`--remove-local` only together with `--upload` to remove files after every object
has been verified.
