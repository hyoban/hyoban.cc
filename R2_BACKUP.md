# Cloudflare R2 backup

This branch mirrors every object in the `blog-images` Cloudflare R2 bucket.
Binary objects live under `r2/` using their original object keys and are stored
by Git LFS. `r2-manifest.json` records the snapshot inventory, byte sizes, MIME
types, ETags, modification timestamps, and an inventory digest.

Refresh the snapshot from a checkout with project dependencies installed:

```sh
node scripts/backup-r2-to-lfs.mjs
```

The `R2 Backup` GitHub Actions workflow runs this command after every commit
to `main` and can also be started manually. It uses the repository secrets
`R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY`; the configured Cloudflare
credential has Object Read only access to this bucket.

When running from a separate worktree without `node_modules`, point the script
at a project checkout that has Wrangler installed:

```sh
node scripts/backup-r2-to-lfs.mjs --tool-root /absolute/path/to/hyoban.cc
```

The script is resumable and incremental. It reuses unchanged manifest entries
and Git LFS pointers, downloads changed or missing objects, removes paths that
no longer exist in R2, and verifies downloaded files against their R2 byte size
and ETag. It compares the bucket inventory before and after downloading, and
does not rewrite the manifest when the snapshot is already current.
