---
name: zeekr-data
description: Record everything the Zeekr (极氪) app shows about the user's car — battery and range, charging records (every session), monthly trip reports with per-day distance, driving time and energy, and assisted-driving (NZP, ACC/LCC, parking assist) stats — by driving the app on the user's Android phone over adb and saving a JSON snapshot. Use it whenever the user asks to record, export, back up, sync, pull or check their Zeekr / 极氪 / car data, mileage, 行程统计, 用车报告, 充电记录, 能量管理 or 辅助驾驶统计, even if they don't say "skill" or name every page.
---

# Zeekr data

The Zeekr app (`com.zeekrlife.mobile`) has no export, so this skill reads the
pages over adb from the user's phone and writes them to one JSON file. Most pages are
read from the accessibility tree (`uiautomator dump`). Monthly trip reports come
from their share links: the user has approved sharing each report to their own
WeChat 文件传输助手, and nowhere else. A link's page loads the whole month as JSON,
which is fast and exact.

Only **complete months** are recorded. The month in progress is skipped for trips
and charging, because its numbers will still change.

When the user explicitly requests the unfinished month, use the separate tapping
capture below. It saves a dated, incomplete-month snapshot without changing the
historical archive or sharing anything to WeChat:

```bash
python3 .claude/skills/zeekr-data/scripts/capture_current_month.py --out .private/zeekr/captures
```

This reads each daily chart tooltip and the current charging list, preserving the
screen text after every read. It locks the phone on exit. Reconcile the daily
distance, energy and charging rows with the displayed totals before reporting;
keep missing fields missing and label the report with its displayed date range.

## Private captures and public statistics

Raw phone captures contain location, account metadata and report-access links.
Always save them under `.private/zeekr/captures/`, which Git ignores. Both Python
capture scripts use private file permissions. Keep `share-links.json` there for
cache reuse; never put it in the public data directory.

Only publish the statistical projection created by:

```sh
vp run zeekr:prepare .private/zeekr/captures/<capture>.json zeekr-007gt
vp run zeekr:convert
vp run zeekr:check
```

This retains daily statistics and exact charging times, as authorized by the owner,
and removes identities, location, account images, report links and vehicle state.
Original private files are not modified. Do not commit private exports or logs.

## Before running

- **On the Mac**: `adb`, `python3` (stdlib only), Node 22 or later (for its built-in
  WebSocket), and Google Chrome at the default path (or set `CHROME`).
- **On the phone**: unlocked, logged in to both the Zeekr app and WeChat. The
  script keeps the screen on while it runs.
- **Side effects the user should know about**:
  - Each newly shared month adds one report card to 文件传输助手.
  - The phone's clipboard is replaced by the copied link.

## Run it

1. Check that the phone is connected with `adb devices -l`. It usually connects over
   wireless debugging. If no device is listed, ask the user to reconnect; don't start an emulator.
2. From the project root, run the capture in the background:

   ```bash
   python3 .claude/skills/zeekr-data/scripts/zeekr_capture.py --out .private/zeekr/captures
   ```

   - `--sections car,energy,charging,trips,assist` captures only some pages.
   - `--months N` limits trips to the N newest complete months. The app keeps about two years.
   - `--no-share` sends nothing to WeChat. Months without a saved link are then
     read by tapping every chart bar, which takes 4–5 minutes per month instead of seconds.
   - `--profile` prints where the time went. `--serial` picks a phone when more than one is connected.
   - Saved screen reports in `<out>/zeekr-*.json` are reused when their month and
     share link agree. Their daily JSON is still refreshed from the share links.
     `--refresh-reports` forces the phone pages to be read again.

   Progress goes to stderr. The last line of stdout is the written file,
   `<out>/zeekr-YYYY-MM-DD-HHMMSS.json`. The file is saved atomically after each
   section and trip month, and even if a step fails, with whatever was captured.
   Keep snapshots and `share-links.json` together to resume without reopening
   months already saved. A cached report from another month or link is rejected.

   How long a run takes:
   - Car, energy, charging and assisted driving take a few minutes together.
   - Each trip month takes about 40 s to reach its report, plus about a minute
     the first time it is shared.
   - Fetching data from the links takes about 2 s per month.
   - A first full run (about 14 months) takes roughly half an hour.
   - Later runs share only the newly finished month and skip saved monthly report
     pages. They still read the current odometer and discover the oldest month.
     Use `--months 1` to record just the latest month.
3. Tell the user that the phone will be tapped through the Zeekr app and WeChat
   while this runs, so they shouldn't use it until the capture is done.

## Share links

`<out>/share-links.json` maps each month (`2026-08`) to its report link
(`https://snc-static.zeekrlife.com/minapp/wap/trip/reportMonth?key=…`).
A month's link never changes; sharing the same month again returns the same key.
So each month is shared once, and later runs reuse the saved link without
touching WeChat. Keep this file next to the snapshots.

For a past month without a link, the script does the following:

1. It taps the report's share button, then 微信. It handles Zeekr's 启动微信 prompt
   and the phone's chooser between 微信 and 微信（工作）, always picking the main 微信.
2. In 选择聊天 it searches `wenjianchuanshu`, taps 文件传输助手, and sends **only if**
   the confirm sheet shows 发送给： 文件传输助手 and the report card.
   Otherwise it stops with an error instead of sending.
3. It taps 留在微信, opens the newest card, and uses ⋯ → 复制链接. To read the
   clipboard it pastes into the chat box, then deletes the text without sending.

`scripts/fetch_share.mjs` opens the links in headless Chrome and reads the JSON
the page requests (`POST /svc/journey-statistics-service/api/v2.0/open/shareQuery`).
The endpoint only accepts requests signed by the page's own script, so the
page has to be loaded; don't call the endpoint directly. It takes about 2 s per month.
To fetch saved links without the phone, run:

```bash
node .claude/skills/zeekr-data/scripts/fetch_share.mjs <link>... > months.json
```

## What gets captured

| Key | Source | Contents |
|---|---|---|
| `car` | 爱车 tab | battery %, range, status line, car name, location, cabin temp, sentry mode |
| `energy` | 能量管理 | battery %, range, charge limit, scheduled charging window |
| `charging.months[]` | 能量管理 → 充电记录 | per complete month: count and kWh; `sessions[]` with AC/DC, kWh, start time, duration |
| `trips` | 爱车 → 行程统计 | odometer; per complete month: `summary`, `report` (用车报告 text), `daily[]`, `share_url`, `share_data` |
| `assisted_driving.periods[]` | 行程统计 → 辅助驾驶统计数据 | 自提车至今 and each month offered: NZP and ACC/LCC km and hours, rank, parking assists, days owned |

Each `daily[]` row has `date`, `distance_km`, `duration_min`, `energy_kwh`, and,
when the report splits it, `energy_driving_kwh` and `energy_parked_kwh`.
`share_data` is the raw month JSON and holds more than the page shows: fast and
slow charge counts, LCC/ACC use counts and km, emission reductions, average
consumption including parked energy, and the longest drive in minutes.
Pages read from the screen also keep their `raw_text`.
Charging months keep the expanded header and session text too, including any
duplicate rows shown by the app; do not silently deduplicate them.

The app limits how far back the data goes:
- **Trip reports**: back to the first month the car was used (August 2025),
  for at most two years.
- **Assisted driving**: only the last three complete months, plus 自提车至今.
- **Charging records**: the app says it keeps two years, but when first explored
  the list only showed the current year.

Trip months that can't be read are recorded with what was available, and the
gap is logged. Don't fill gaps by estimating values.

`daily_from_share` has been checked against August 2025 through September 2026.
Energy formats can be mixed within a month: November 6, 2025 uses `ecy`, while
the other days in that report use `dynamicEcY` and `staticEcY`. Choose the format
per day, preserving a legacy day's total without inventing a driving/parked split.
August 2025 uses only `ecy`. If daily energy does not reconcile, inspect all three
raw arrays before attributing it to rounding.

## Check the result before reporting

Read the JSON and confirm that the numbers add up:

- For each trip month, `daily[]` should sum to `report.distance_km`, `report.energy_kwh`
  (give or take 0.1, since each day's parked and driving energy are rounded separately)
  and `report.driving_energy_kwh`, and the minutes ÷ 60 should round to `report.duration_h`.
- For each charging month, `sessions` should sum to `kwh`, and the session count
  should equal `count`.

The December 2025, January 2026 and February 2026 source arrays sum to 0.3, 0.2
and 0.4 kWh more than their monthly totals. Keep both source values and report the
differences; do not alter the daily rows. `vp run zeekr:check` checks each public statistical source and daily value,
reconciles the converted model, and records these source discrepancies as warnings.

Script regression tests, including mixed energy formats, cached month identity
and partial charging capture recovery:

```bash
python3 -m unittest discover -s .claude/skills/zeekr-data/scripts
```

Each trip month's `share_data.yearMonth` must match its `month`. A mismatch means
the wrong report was shared; delete that month's entry in `share-links.json` and
run again. Then give the user a short summary: what was captured, how many
months, the totals, and anything that didn't reconcile or was missing.

## Things the app does that the script works around

- The trip page first shows `0 km` everywhere, then fills in. The script waits
  until the odometer is non-zero and two dumps in a row match.
- The trip page's month picker resets to 本月 after leaving a report, so month k
  is k rows down. The assisted-driving picker keeps its last pick, so it moves one
  row on. A run stops when the same month comes back twice.
- Icon-font glyphs (Private Use Area characters) are stripped from all text.
- The tapping fallback finds each chart as an untitled `Image` node after the
  行驶统计 text: distance, duration, energy. It sweeps 32 taps across the first chart to
  learn the days, then taps those positions on the others. It only reads tooltip
  nodes inside the tapped chart, because old tooltips stay on the other charts.
  Tooltips leave out zero rows, which are filled in as 0. A day with no bar at all
  (no energy recorded) is missing from that chart.
- If the app or WeChat changes and a step stops working, take a screenshot
  (`adb exec-out screencap -p`) and dump the tree
  (`adb exec-out uiautomator dump /dev/tty`) where it fails, and fix the script
  against what you see. The ⋯ button in WeChat's web view is tapped by position
  (top right), since it has no text.
