# Cloudflare R2 backup

This branch mirrors every object in the `blog-images` Cloudflare R2 bucket.
Binary objects live under `r2/` using their original object keys and are stored
by Git LFS. `r2-manifest.json` records the snapshot inventory, byte sizes, MIME
types, ETags, modification timestamps, and an inventory digest.

Refresh the snapshot from a checkout with project dependencies installed:

```sh
node scripts/backup-r2-to-lfs.mjs
```

When running from a separate worktree without `node_modules`, point the script
at a project checkout that has Wrangler installed:

```sh
node scripts/backup-r2-to-lfs.mjs --tool-root /absolute/path/to/hyoban.cc
```

The script is resumable. It verifies existing files, downloads changed or
missing objects, verifies every file against its R2 byte size and MD5 ETag, and
compares the bucket inventory before and after downloading. It writes the
manifest only when the bucket stayed unchanged.
