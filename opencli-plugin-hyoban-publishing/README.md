# opencli-plugin-hyoban-publishing

Project-local [OpenCLI](https://github.com/jackwener/opencli) adapters for publishing canonical hyoban.cc Moments.

## Install

From the repository root:

```bash
opencli plugin install "file://$PWD/opencli-plugin-hyoban-publishing"
```

The local plugin is linked, so adapter changes are immediately available.

## Commands

### `telegram publish`

Validates the authenticated Telegram Web broadcast composer by default. It only submits when `--execute` is present.

```bash
opencli telegram publish "A moment" \
  --channel hyoban_travel \
  --peer -1003981320482 \
  -f json
```

The project-level `pnpm moment:publish` command invokes this adapter with `--execute` only after the complete Publication Preview succeeds.

## Verify

The verification command is intentionally a dry run:

```bash
opencli browser telegram-publish verify telegram/publish \
  --seed-args '["OpenCLI dry-run","--channel","hyoban_travel","--peer","-1003981320482"]' \
  --no-fixture \
  --trace retain-on-failure
```
