# Zeekr statistics

Vehicle statistics captured from the owner's Zeekr app. Unlike the rest of `data/`, git
keeps this directory. Schemas live in `packages/data-spec/src/vehicle.ts`.

- The months, assisted-driving observations and captures are also in the API's D1;
  `vp run data:export` writes them back here.
- The expenses, sources and validation live only in the repository.
- Only an import changes the statistics; nothing edits them by hand, the app included. The
  expenses are edited by hand.

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
├─ expenses/zeekr-007gt.json       expense ledger exported from iCost
└─ model-validation.json           check counts, differences and missing coverage
```

Each month contains `report`, `days`, `charging.reported`, `charging.sessions`,
`assistedDriving` where available, and `sources`. The normalized model has no
vehicle-state snapshot or collection/period status fields; the current month is
identified by its month and capture timestamp.

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

## Known source discrepancies

`model-validation.json` holds the current coverage and check counts. The app provided no
2025 charging details. These differences are the app's own and are kept as they are:

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

## Expenses

`expenses/zeekr-007gt.json` is the car's spending from the iCost bookkeeping app, from the
deposit on, converted once from an export. The zeekr conversion doesn't touch it; edit it by
hand.

- Each expense has its time (`+08:00`), `amount` in yuan, an English `category`, an
  optional `subcategory`, and a note of what was bought or serviced.
- `refunds` lists what later came back of it (a refund, subsidy or cashback), each with its
  time. The refund lines themselves are not kept.
- Also left out: the workbook, income, financing (the down payment and loan are one 购车款
  line; repayments are dropped), accounts, tags, discounts, and one unexplained USD payment.
- No location: parking, toll and charging lines have no note, and no note names a shop's
  city, so payment times are the only trace.

## Prepare, convert and verify

Capture into the private cache with the repository's zeekr-data skill. To publish
a capture's statistical projection and register its hash:

```sh
vp run zeekr:prepare .private/zeekr/captures/<capture>.json zeekr-007gt
vp run zeekr:convert
vp run zeekr:check
vp run zeekr:entries --write
```

`zeekr:entries` turns each charging session into an entry with a `charge` part under
`data/entries`: no place, a uid derived from its start time, naming the car and its source.
It adds new entries and updates the rest; the API rejects any other edit of them. A charge
whose figures didn't change keeps the capture it was first read from.

The car's app sometimes lists one session twice, with the same start, energy and time. The
months keep both rows as captured; `zeekr:entries` makes one entry naming both. Two rows
with the same start and different figures stop it.

After `vp run data:export`, send the new and changed entries and the statistics with
`vp run data:push <the files>`.

Preparation only retains known statistical fields and screen text; unexpected text
inside retained fields causes an error. Conversion and checking refuse unredacted
sources. Use unique filenames for new captures; retain older registrations.

Conversion rebuilds normalized files deterministically with atomic per-file writes.
The latest observation replaces its section as a whole. A report-only capture does
not remove older daily or charging data. Reimporting never appends duplicate rows.
Unexpected generated files are reported before writing, not silently removed.

The read-only `zeekr:check` verifies privacy rules and hashes, Zod schemas,
historical daily values against share arrays, the current month against saved
tooltips, charging totals and monthly reconciliation. Every saved record and
validation report is compared with a fresh conversion. Detailed passing checks
are computed on each run; only counts and actionable differences are saved.

```sh
vp test scripts/lib/zeekr-data.test.mjs scripts/lib/zeekr-privacy.test.mjs
python3 -m unittest discover -s .agents/skills/zeekr-data/scripts
```
