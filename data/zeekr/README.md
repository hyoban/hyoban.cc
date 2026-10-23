# Zeekr statistics

Repository-only vehicle statistics captured from the owner's Zeekr app. The API
backup leaves this directory alone. Schemas live in
`packages/data-spec/src/vehicle.ts`.

## Public and private data

The owner explicitly chose to publish full usage statistics, including daily
records and exact charging times. Public sources exclude account identifiers,
phone numbers, vehicle location and nickname, avatar/image URLs, report-access
links, internal screen identifiers, and vehicle-state pages.

`sources/` contains **redacted statistical projections**, not byte-identical
original phone captures. Every retained statistical value is unchanged. Hashes and
JSON Pointers in normalized records refer to these public sources. Capture metadata
marks them with `raw.redacted: true`.

Unmodified originals and reusable share links remain locally under the
Git-ignored `.private/zeekr/` directory with owner-only access. Exported workbooks,
debug dumps and former validation reports also remain in that private backup.
A fresh clone can reproduce and verify the public dataset without private files
or the phone.

## Layout

```text
data/zeekr/
├─ sources/                       redacted statistical source captures
├─ imports.json                   source files, device ids, timezone and SHA-256
├─ captures/<sha256>.json          source references and capture times
├─ months/zeekr-007gt/YYYY-MM.json  monthly records
├─ assisted-driving/zeekr-007gt/    cumulative observations
└─ model-validation.json           check counts, differences and missing coverage
```

Each month contains `report`, `days`, `charging.reported`, `charging.sessions`,
`assistedDriving` where available, and `sources`. The normalized model has no
vehicle-state snapshot or collection/period status fields. October is identified
by its month and capture timestamp.

References contain a capture SHA-256, JSON Pointer and collection method.
Each day and charging row has its own reference, so identical source rows remain
distinguishable. Timestamps without an offset are interpreted as `+08:00`.

Distances are integer metres, energy integer Wh and durations integer seconds.
`report` preserves displayed monthly values. A displayed 4.6 hours becomes
16,560 seconds with the same original rounding, not second-level precision.
Exact minute values in `share_data` remain in the statistical source and are
used for reconciliation. `reportedConsumption` records whether its basis is
`driving` or `driving_and_parked`; August 2025 includes static energy.
Missing data is omitted, never replaced with zero. Monthly report totals and
daily/session sums stay separate.

## Coverage on October 24, 2026

| Records | Coverage |
| --- | --- |
| Monthly usage | August 2025–October 2026: 15 records |
| Daily usage | 264 rows: 253 historical, 11 for October |
| Charging | January–October 2026: 94 rows |
| Monthly assisted driving | July, August and September 2026 |
| Cumulative assisted driving | One observation, captured October 24 |

October covers the app's October 1–24 view and can change after later uploads.
Its 3,516 km, 593.2 kWh and 14 charging rows (634.6 kWh) reconcile exactly.
The app provided no 2025 charging details and only three monthly assistance periods.

These source discrepancies remain unchanged:

| Month | Daily energy sum | Monthly report total |
| --- | ---: | ---: |
| December 2025 | 212.6 kWh | 212.3 kWh |
| January 2026 | 139.7 kWh | 139.5 kWh |
| February 2026 | 351.1 kWh | 350.7 kWh |

April contains two identical DC charging rows on April 12 at 08:36, each 22.6 kWh
and 9 minutes. Both are retained. The trip report and charging page also disagree
on charge counts in January, February, April, May and June 2026.

November 6, 2025 uses legacy `ecy` for 24.8 kWh while other days use dynamic/static
fields. The total is preserved without inventing a split.

## Prepare, convert and verify

Capture into the private cache with the repository's zeekr-data skill. To publish
a capture's statistical projection and register its hash:

```sh
vp run zeekr:prepare .private/zeekr/captures/<capture>.json zeekr-007gt
vp run zeekr:convert
vp run zeekr:check
```

Preparation only retains known statistical fields and screen text; unexpected text
inside retained fields causes an error. Conversion and checking refuse unredacted
sources. Use unique filenames for new captures; retain older registrations.

Conversion rebuilds normalized files deterministically with atomic per-file writes.
The latest observation replaces its section as a whole. A report-only capture does
not remove older daily or charging data. Reimporting never appends duplicate rows.
Unexpected generated files are reported before writing, not silently removed.

The read-only `zeekr:check` verifies privacy rules and hashes, Zod schemas,
historical daily values against share arrays, October values against saved
tooltips, charging totals and monthly reconciliation. Every saved record and
validation report is compared with a fresh conversion. Detailed passing checks
are computed on each run; only counts and actionable differences are saved.

```sh
vp test scripts/lib/zeekr-data.test.mjs scripts/lib/zeekr-privacy.test.mjs
python3 -m unittest discover -s .claude/skills/zeekr-data/scripts
```
