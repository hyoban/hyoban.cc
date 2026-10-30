# Design

hyoban.cc is a quiet, text-first personal site built with Astro and plain CSS: each component styles itself in a scoped `<style>`, over a small reset and the shared tokens in [index.css](src/styles/index.css). The design principle is "one size, one column, almost no decoration." The year review, calendar and map are the deliberate exceptions.

## Layout

- **One column for everything.** Home, posts, `/media` and `/use` all sit in a centered `40rem` column with a `1.75rem` line height. The shared wrapper is [SitePage.astro](src/components/SitePage.astro).
- **A quiet header.** [SiteHeader.astro](src/components/SiteHeader.astro) shows the avatar, name and tagline, linking home, beside a row of small text links (日历, 旅程, 影视, 回顾, Use, 搜索); the current one sits on a soft pill. The footer is a row of social icons.
- **Posts are stripped back.** A post shows only the title, the date and the prose. On screens 64rem and wider, the "返回" (back) link moves into the left margin, `4rem` from the text ([[...slug].astro](src/pages/[...slug].astro)).
- **Posts are grouped by year.** On the home page, each year label sits in a narrow `3.5rem` column that stays pinned while its posts scroll, and leads to that year's review when there is one. Each row is title plus date.
- **Search floats over the page.** ⌘K, `/` or the header's 搜索 opens it ([SearchDialog.astro](src/components/SearchDialog.astro)), a panel near the top over a dimmed page, like a command palette: a field, then a list where the arrow keys move and Enter opens. It matches the Moments' words, places and photo alt text and the posts in the browser, from `/search.json`. Before a query it offers a hand-picked row of hot words ([search-index.ts](src/search-index.ts)), each with how many it finds, set like `/media`'s filters. A result is a row: a photo it found (or the Moment's first, or an icon), the words around the match, and its date and place; a match is marked with the `border` gray, not a color. The query is the page's `?q=`, so an address with one opens it, and Back from a result reopens it.

## Year review

`/<year>/` ([[year].astro](src/pages/[year].astro), [Review.astro](src/components/Review.astro); numbers from [year-review.ts](src/year-review.ts), drawings from [year-map.ts](src/year-map.ts)) and `/life/` for all years together. It breaks out to `56rem` and lets drawings, not sentences, carry the year. `/life/` keeps only what adds up across the years: the counts, a row per year linking to its review, the footprint, the menu and the driving.

- **Opening.** The year set huge, its numerals cut out of a collage of the year's photos, over a row of counts.
- **Order: time, place, car.** The wall of every day (a tile per day, a photo where there is one, trip days joined by a band; turned on its side on a phone), and a few days worth going back to; then the footprint map with the cities written in most, the trips as cards of their cover photo under their route, and the food menu; then the driving, and last the devices bought that year.
- **The car.** The driving section opens on a 3D model of the car ([car-model.ts](src/car-model.ts), fetched ahead once the page is idle and drawn only as it nears the screen, with a still in its place until then). Once well into sight it drifts in at its own pace and parks with a double flash of its lamps, then holds while the year's driving plays through a day at a time. It can be dragged round, and a held press speeds it off into a showroom-style speed mode after GameMCU's SU7 showroom, with optional sound made in Web Audio ([car-model-sound.ts](src/car-model-sound.ts)). A post paragraph that is only the car's still (`site/zeekr-007gt-7.webp`) becomes the same model.
- **Motion plays once.** The counts roll up, each section rises in the first time it's seen and its parts follow in turn. None of it runs under reduced motion, and without the script nothing waits hidden.

## Calendar and map

They use the full width: a rounded grid with photo thumbnails, a side drawer swiped away like a phone's sheet ([@hyoban/drawer](../../packages/drawer)) and a pill-shaped home button ([CalendarBackLink.astro](src/components/CalendarBackLink.astro)). Switching between them uses view transitions.

- **Calendar.** A month is a grid or a list ([CalendarAgenda.astro](src/components/CalendarAgenda.astro)): newest day first along a thin line, the date in a narrow rail on the left. Phones always show the list; on a desktop a small switch picks one and remembers it.
- **Map.** Every trip from [data/collections](../../data/collections) is a thin route. The header's 旅程 opens the map with the trips listed in its drawer (`/trip/`, `/trip/<year>/`), and `/trip/<id>/` with one picked. Clicking one replays it stop by stop: the rest fades, the route draws in `tx`, and a card slides in for each stop.
- **A Moment's own address.** `/m/<uid>/` opens its day in the calendar with that Moment marked, under its own title and share card.

## Typography

- **One font size.** Headings, including prose `h1`–`h6`, are `1em` and set apart only by `font-weight: 500`. Size isn't used to build hierarchy; only the header's links and small captions step down.
- **Default system fonts and weights.** No web fonts are loaded. Monospace appears only for the calendar's and map's numbers, dates and times.
- **Careful text rhythm.** `text-wrap: pretty` on paragraphs and `balance` on headings, `text-autospace: normal` between Chinese and Latin, `tabular-nums` on dates, and links that don't break mid-word.

## Color

- **A small set of neutral colors.** Four surface colors (`bg`, `border`, `inline`, `hover`) and three text levels (`tx`, `tx-2`, `tx-3`), all warm off-white/off-black grays ([index.css](src/styles/index.css)).
- **No accent color.** Links are told apart by a thin `tx-3` underline that turns full-strength on hover. The active filter on `/media` is marked by text color alone.
- **Dark mode follows the system.** `prefers-color-scheme`, no toggle; Shiki's Vitesse light/dark themes follow it.

## Details

- **Small icons inline with text.** Lucide and Simple Icons, as CSS masks in [icons.css](src/styles/icons.css), at `0.85em` before link text.
- **Light framing on code, tables and images.** A 1px border, a `0.625rem` radius and the `inline` background. A horizontal rule is drawn as `· · ·`.
- **Small, quick motion.** Page changes fade over 200ms, colors change over 140ms, thumbnails scale up 2.5% on hover. What a page change carries along moves instead of fading: a year's label grows into its review's huge year ([year-transition.ts](src/year-transition.ts)), a day's tile on the wall opens into that day in the calendar, and a calendar month slides in the way time goes ([calendar-month-transition.ts](src/calendar-month-transition.ts)). All of it respects reduced motion.
- **Chinese-first.** Copy, ARIA labels and date formats are written for Chinese readers.
