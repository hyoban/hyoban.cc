# Design

hyoban.cc is a quiet, text-first personal site built with Astro and plain CSS. Each
component styles itself in a scoped `<style>`, over a small reset and the shared tokens in
[index.css](src/styles/index.css). The principle is "one size, one column, almost no
decoration." The year review, calendar and map are the deliberate exceptions.

## Layout

- **One column.** Home, posts, `/media` and `/use` sit in a centered `40rem` column with a
  `1.75rem` line height ([SitePage.astro](src/components/SitePage.astro)).
- **A quiet header** ([SiteHeader.astro](src/components/SiteHeader.astro)): avatar, name and
  tagline linking home, beside small text links (日历, 旅程, 影音, 回顾, Use, 搜索). The
  current one sits on a soft pill. The footer is a row of social icons.
- **Stripped-back posts.** Only the title, date and prose. At 64rem and wider, "返回" moves
  into the left margin, `4rem` from the text ([[...slug].astro](src/pages/[...slug].astro)).
- **Posts grouped by year.** On the home page each year label sits in a pinned `3.5rem`
  column and links to that year's review when there is one. Each row is title plus date.
- **Search as a command palette** ([SearchDialog.astro](src/components/SearchDialog.astro)).
  ⌘K, `/` or 搜索 opens a panel near the top over a dimmed page. It searches Moments'
  words, places and photo alt text, and posts, in the browser from `/search.json`. With no
  query it shows hand-picked hot words with their counts ([search-index.ts](src/search-index.ts)),
  styled like `/media`'s filters. A match is marked with the `border` gray, not a color.
  The query is the page's `?q=`, so a link can open it and Back from a result reopens it.

## Year review

`/<year>/` ([[year].astro](src/pages/[year].astro), [Review.astro](src/components/Review.astro);
numbers from [year-review.ts](src/year-review.ts), drawings from [year-map.ts](src/year-map.ts))
and `/life/` for all years. It breaks out to `56rem` and lets drawings, not sentences, carry
the year. `/life/` keeps only what adds up across years: the counts, a row per year linking
to its review, the footprint, the menu and the driving.

- **Opening.** The year set huge, its numerals cut out of a collage of the year's photos,
  over a row of counts.
- **Order: time, place, car.** The wall of days and a few days worth revisiting; the
  footprint map, trip cards and food menu; then the driving and the devices bought that
  year.
- **The car** ([car-model.ts](src/car-model.ts)). A 3D model opens the driving section. It
  is fetched once the page is idle and drawn only near the screen, with a still until then.
  It can be dragged, and a held press starts the speed mode, with optional Web Audio sound
  ([car-model-sound.ts](src/car-model-sound.ts)). A post paragraph that is only the car's
  still (`site/zeekr-007gt-7.webp`) becomes the same model.
- **Motion plays once.** Counts roll up and sections rise in the first time they're seen.
  None of it runs under reduced motion, and without the script nothing waits hidden.

## Calendar and map

Both use the full width: a rounded grid with photo thumbnails, a side drawer swiped away
like a phone's sheet ([@hyoban/drawer](../../packages/drawer)) and a pill-shaped home button
([CalendarBackLink.astro](src/components/CalendarBackLink.astro)). Switching between them
uses view transitions.

- **Calendar.** A month is a grid on wide screens and a list under 56rem
  ([CalendarAgenda.astro](src/components/CalendarAgenda.astro)), newest day first. Under a
  day's Moments, after a hairline, come its charges and driving, in the same lines as a
  trip's. A day with only those is listed too.
- **Map.** Drawn like the Mac app's MapKit map.
  - Each trip is a route in its year's colour, dotted where it flies or sails.
  - Each place is its newest photo framed in white with its year's dot, or else a balloon in
    its year's colour with its kind's icon and name. Pins stay on their spot; crowded places
    pile up with the busiest on top, and a thick crowd becomes a counted stack. The open
    place's pin lifts off, leaving a dot, with its name in bold below.
  - 旅程 opens the map with trips listed in the drawer (`/trip/`, `/trip/<year>/`). A trip's
    own address is its year and pinyin title, such as `/trip/2026/xuzhou-zhoumo-xing/` for
    徐州周末行 ([trip-slugs.ts](src/data/trip-slugs.ts)). It is recomputed each build, so it
    follows the title, and earlier addresses redirect to it.
- **A trip reads as a conversation** ([TripChat.astro](src/components/TripChat.astro)), as
  in the app. Its figures include what its public bills spent, less refunds.
  - Each day opens with its date in a capsule.
  - Moments and public check-ins are bubbles from the right on the `hover` gray, with place
    and time faint after the last line. Bills and charges paid at that exact time sit at
    the bubble's foot.
  - Other bills and charges gather between them in an outlined bubble from the left. A
    charge and a bill paid at its exact time share one line, with the energy faint after the
    bill's category. A bill shows what it cost after refunds, with the amount paid struck
    through before it.
  - A day the car moved ends with 用车: driving time, energy and distance.
  - A planned trip says 计划 before its days in the list. A planned record has a dashed
    outline instead of its fill and tail, fainter words and 计划 first in its foot. A locked
    one has a solid outline and a lock before 计划; a locked check-in has a lock before its
    time.
  - Beside a Moment's bubble are actions to play the trip from there and to share it.
    Playback replays it stop by stop: the rest fades, the route draws in `tx`, and a card
    slides in for each stop.
  - The trip's share card ([trip-card.ts](src/trip-card.ts)) draws it as the map does: a
    light map on the right with the route in its year's colour, stops dotted, flights and
    ferries dashed, and a few stops' photos framed in white. The name, dates, days, distance
    and record count are on the left as the map fades out.
- **A Moment's own address.** `/m/<uid>/` opens its day in the calendar with that Moment
  marked, under its own title and share card.

## Typography

- **One font size.** Headings, including prose `h1`–`h6`, are `1em`, set apart only by
  `font-weight: 500`. Only the header's links and small captions step down.
- **System fonts and weights.** No web fonts. Monospace only for the calendar's and map's
  numbers, dates and times.
- **Careful rhythm.** `text-wrap: pretty` on paragraphs and `balance` on headings,
  `text-autospace: normal` between Chinese and Latin, `tabular-nums` on dates, and links
  that don't break mid-word.

## Color

- **A few neutrals.** Four surface colors (`bg`, `border`, `inline`, `hover`) and three
  text levels (`tx`, `tx-2`, `tx-3`), all warm off-white/off-black grays
  ([index.css](src/styles/index.css)).
- **No accent color.** Links have a thin `tx-3` underline that turns full-strength on hover.
  The active filter on `/media` is marked by text color alone.
- **Dark mode follows the system.** `prefers-color-scheme`, no toggle; Shiki's Vitesse
  light/dark themes follow it.

## Details

- **Small inline icons.** Lucide and Simple Icons as CSS masks in
  [icons.css](src/styles/icons.css), at `0.85em` before link text.
- **Light framing on code, tables and images.** A 1px border, a `0.625rem` radius and the
  `inline` background. A horizontal rule is drawn as `· · ·`.
- **Small, quick motion.** Page changes fade over 200ms, colors change over 140ms, and
  thumbnails scale up 2.5% on hover. Elements carried across a page change move instead of
  fading: a year label grows into its review's huge year
  ([year-transition.ts](src/year-transition.ts)), a day's tile opens into that day in the
  calendar, and a calendar month slides in the direction of time
  ([calendar-month-transition.ts](src/calendar-month-transition.ts)). All of it respects
  reduced motion.
- **Chinese-first.** Copy, ARIA labels and date formats are written for Chinese readers.
