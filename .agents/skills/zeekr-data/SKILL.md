---
name: zeekr-data
description: Record everything the Zeekr (极氪) app shows about the user's car — battery and range, every charging session, monthly trip reports with per-day distance, driving time and energy, and assisted-driving (NZP, ACC/LCC, parking assist) stats — by driving the app on the user's Android phone over adb and saving a JSON snapshot. Use it whenever the user asks to record, export, back up, sync, pull or check their Zeekr / 极氪 / car data, mileage, 行程统计, 用车报告, 充电记录, 能量管理 or 辅助驾驶统计, even if they don't say "skill" or name every page.
---

# Zeekr data

The Zeekr app (`com.zeekrlife.mobile`) has no export, so the scripts read its pages over
adb and write one JSON file. Most pages come from the accessibility tree
(`uiautomator dump`). Monthly trip reports come from their share links, which load the
whole month as exact JSON. The user has approved sharing each report to their own
WeChat 文件传输助手, and nowhere else.

Only **complete months** are recorded; trips and charging skip the month in progress.
Only if the user explicitly asks for it, capture the unfinished month by tapping:

```bash
python3 .agents/skills/zeekr-data/scripts/capture_current_month.py --out .private/zeekr/captures
```

It reads each daily chart tooltip and the current charging list, keeps the screen text
after every read, shares nothing to WeChat, leaves the historical archive alone, saves a
dated incomplete-month snapshot, and locks the phone on exit. Before reporting,
reconcile its daily distance, energy and charging rows with the displayed totals, keep
missing fields missing, and label the report with its displayed date range.

## Private captures and public statistics

Raw captures hold location, account metadata and report-access links. Save them only
under `.private/zeekr/captures/` (git-ignored); both capture scripts write with private
permissions. Keep `share-links.json` there too, never in the public data directory. Do
not commit private exports or logs.

Run `vp run data:export` first, then publish only the statistical projection:

```sh
vp run zeekr:prepare .private/zeekr/captures/<capture>.json zeekr-007gt
vp run zeekr:convert
vp run zeekr:check
vp run zeekr:entries --write
```

Push every file this wrote with `vp run data:push`.

- The projection keeps daily statistics and exact charging times, as the owner
  authorized, and drops identities, location, account images, report links and vehicle
  state. Private originals are not modified.
- `zeekr:entries` makes each charging session an entry with a `charge` part under
  `data/entries` (no place; uid from its start time), adding new ones and updating
  changed ones.

## Before running

- **Mac**: `adb`, `python3` (stdlib only), Node 22+ (built-in WebSocket), and Google
  Chrome at the default path (or set `CHROME`).
- **Phone**: unlocked, logged in to the Zeekr app and WeChat. The script keeps the
  screen on.
- **Side effects to tell the user**: each newly shared month adds one report card to
  文件传输助手, and the phone's clipboard is replaced by the copied link.

## Run it

1. Check `adb devices -l`; the phone usually connects over wireless debugging. If none
   is listed, ask the user to reconnect. Don't start an emulator.
2. From the project root, run in the background:

   ```bash
   python3 .agents/skills/zeekr-data/scripts/zeekr_capture.py --out .private/zeekr/captures
   ```

   By default it reads every page and every month the app keeps (about two years).
   - `--sections car,energy,charging,trips,assist` picks pages.
   - `--months N` reads only the N newest complete months, and only the trips,
     charging and assisted-driving pages (car and energy hold car state, which
     `zeekr:prepare` drops). `--sections` still overrides the pages.
   - `--no-share` sends nothing to WeChat; months without a saved link are read by
     tapping every chart bar, 4–5 minutes per month instead of seconds.
   - `--refresh-reports` rereads phone report pages. Otherwise saved reports in
     `<out>/zeekr-*.json` are reused when month and share link agree (their daily JSON
     is still refreshed from the links); a cached report from another month or link is
     rejected.
   - `--profile` prints timings; `--serial` picks a phone when several are connected.

   Progress goes to stderr; the last stdout line is the file,
   `<out>/zeekr-YYYY-MM-DD-HHMMSS.json`. It is saved atomically after each section and
   trip month, and on failure with whatever was captured. Keep snapshots and
   `share-links.json` together so a rerun resumes without reopening saved months.

   Timing: car, energy, charging and assisted driving take a few minutes together; each
   trip month about 40 s to reach its report, plus about a minute the first time it is
   shared; link fetches about 2 s per month. A first full run (about 14 months) takes
   roughly half an hour. Later runs share only the newly finished month and skip saved
   report pages, but still read the odometer and find the oldest month. `--months 1`
   records just the latest month (its trip report, charges, 自提车至今 and assisted
   driving) in a few minutes.

3. Tell the user the script will tap through the Zeekr app and WeChat, so they shouldn't
   use the phone until it finishes.

## Share links

`<out>/share-links.json` maps each month (`2026-08`) to its report link
(`https://snc-static.zeekrlife.com/minapp/wap/trip/reportMonth?key=…`). A month's key
never changes, so each month is shared once and later runs reuse the link without
touching WeChat.

For a past month without a link, the script:

1. Taps the report's share button, then 微信, handling Zeekr's 启动微信 prompt and the
   chooser between 微信 and 微信（工作）, always picking the main 微信.
2. In 选择聊天 searches `wenjianchuanshu`, taps 文件传输助手, and sends **only if** the
   confirm sheet shows 发送给： 文件传输助手 and the report card; otherwise it stops with
   an error.
3. Taps 留在微信, opens the newest card, and uses ⋯ → 复制链接. It reads the clipboard by
   pasting into the chat box, then deletes the text without sending.

`scripts/fetch_share.mjs` loads the links in headless Chrome (about 2 s per month) and
reads the JSON the page requests
(`POST /svc/journey-statistics-service/api/v2.0/open/shareQuery`). The endpoint only
accepts requests signed by the page's own script, so don't call it directly. To fetch
saved links without the phone:

```bash
node .agents/skills/zeekr-data/scripts/fetch_share.mjs <link>... > months.json
```

## What gets captured

| Key                          | Source                      | Contents                                                                                                |
| ---------------------------- | --------------------------- | ------------------------------------------------------------------------------------------------------- |
| `car`                        | 爱车 tab                    | battery %, range, status line, car name, location, cabin temp, sentry mode                              |
| `energy`                     | 能量管理                    | battery %, range, charge limit, scheduled charging window                                               |
| `charging.months[]`          | 能量管理 → 充电记录         | per complete month: count and kWh; `sessions[]` with AC/DC, kWh, start time, duration                   |
| `trips`                      | 爱车 → 行程统计             | odometer; per complete month: `summary`, `report` (用车报告 text), `daily[]`, `share_url`, `share_data` |
| `assisted_driving.periods[]` | 行程统计 → 辅助驾驶统计数据 | 自提车至今 and each month offered: NZP and ACC/LCC km and hours, rank, parking assists, days owned      |

- A `daily[]` row has `date`, `distance_km`, `duration_min`, `energy_kwh` and, when the
  report splits it, `energy_driving_kwh` and `energy_parked_kwh`.
- `share_data` is the raw month JSON, with more than the page shows: fast and slow
  charge counts, LCC/ACC use counts and km, emission reductions, average consumption
  including parked energy, and the longest drive in minutes.
- Screen-read pages keep their `raw_text`. Charging months also keep the expanded header
  and session text, including duplicate rows the app shows; don't silently deduplicate.

How far back the app goes:

- **Trip reports**: back to the car's first month (August 2025), at most two years.
- **Assisted driving**: the last three complete months, plus 自提车至今.
- **Charging records**: the app claims two years, but when first explored it showed only
  the current year.

Unreadable trip months are recorded with what was available and the gap is logged.
Never fill gaps with estimates.

`daily_from_share` is checked against August 2025 through September 2026. Energy formats
can mix within a month: November 6, 2025 uses `ecy` while the rest of that report uses
`dynamicEcY` and `staticEcY`, and August 2025 uses only `ecy`. Choose the format per day,
keeping a legacy day's total without inventing a driving/parked split. If daily energy
doesn't reconcile, inspect all three raw arrays before blaming rounding.

## Check the result before reporting

Confirm the numbers add up:

- Each trip month's `daily[]` sums to `report.distance_km`, `report.energy_kwh` (±0.1,
  since each day's parked and driving energy round separately) and
  `report.driving_energy_kwh`; its minutes ÷ 60 round to `report.duration_h`.
- Each charging month's `sessions` sum to `kwh`, and their count equals `count`.
- Each trip month's `share_data.yearMonth` matches its `month`. A mismatch means the
  wrong report was shared: delete that month from `share-links.json` and run again.

The December 2025, January 2026 and February 2026 source arrays sum to 0.3, 0.2 and
0.4 kWh more than their monthly totals. Keep both values and report the differences;
don't alter the daily rows. `vp run zeekr:check` checks each public statistical source
and daily value, reconciles the converted model, and records these discrepancies as
warnings.

Script regression tests (mixed energy formats, cached month identity, partial charging
capture recovery):

```bash
python3 -m unittest discover -s .agents/skills/zeekr-data/scripts
```

Then give the user a short summary: what was captured, how many months, the totals, and
anything missing or not reconciled.

## App quirks the script works around

- The trip page first shows `0 km` everywhere; the script waits until the odometer is
  non-zero and two dumps in a row match.
- The trip page's month picker resets to 本月 after leaving a report, so month k is k
  rows down. The assisted-driving picker keeps its last pick, so it moves one row on. A
  run stops when the same month comes back twice.
- Icon-font glyphs (Private Use Area characters) are stripped from all text.
- The tapping fallback finds each chart as an untitled `Image` node after the 行驶统计
  text: distance, duration, energy. It sweeps 32 taps across the first chart to learn the
  days, then taps those positions on the others, reading only tooltips inside the tapped
  chart (old tooltips linger on the others). Tooltips omit zero rows, which are filled as
  0; a day with no bar at all (no energy recorded) is missing from that chart.
- If the app or WeChat changes and a step breaks, take a screenshot
  (`adb exec-out screencap -p`) and dump the tree (`adb exec-out uiautomator dump /dev/tty`)
  where it fails, and fix the script against what you see. WeChat's web-view ⋯ button has
  no text, so it is tapped by position (top right).
