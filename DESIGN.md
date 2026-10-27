# Design

hyoban.cc is a quiet, text-first personal site built with Astro and plain CSS: each component styles itself in a scoped `<style>`, over a small reset and the shared tokens in [index.css](src/styles/index.css). The design principle is "one size, one column, almost no decoration."

## Layout

- **One column for everything.** Home, posts, `/media` and `/use` all sit in a centered `40rem` column with a `1.75rem` line height. The shared wrapper is [SitePage.astro](src/components/SitePage.astro).
- **Identity header instead of a nav bar.** [SiteHeader.astro](src/components/SiteHeader.astro) shows just the avatar, name and bio, and links back home. There's no menu. Other sections are linked from sentences in the home intro.
- **Posts are stripped back.** A post shows only the title, the date and the prose. On screens 64rem and wider, the "返回" (back) link moves out into the left margin, `4rem` from the text, so the reading column stays clean ([[...slug].astro](src/pages/[...slug].astro)).
- **Posts are grouped by year.** On the home page, each year label sits in a narrow `3.5rem` column that stays pinned while you scroll its posts. Each row is title plus date, with a soft background on hover instead of an underline.
- **A year in review is the other exception.** `/<year>/` ([[year].astro](src/pages/[year].astro); numbers from [year-review.ts](src/year-review.ts), drawings from [year-map.ts](src/year-map.ts)) opens on the year set huge, its numerals cut out of a collage of each month's photos, over a row of counts each marked with its change on the year before (to the same day while the year runs, as `×4.3` past double), and with no sentences explaining what's drawn, then breaks out to `56rem` for two drawings made at build time: the footprint, an SVG of the cities in view as faint outlines with those lit filled by how much was written there, the trips' routes drawing themselves in (flights as thin arcs); and every day as a tile on a months × days wall, a photo where there is one, trip days joined by a band behind them, turned on its side on a phone. Under the map, the cities written in most as bars and the cities first reached under their months (by the first Moment written in each); below, the trips as cards of their cover photo dimmed under the shape of their route in white; the driving opening on the car itself, a 3D model up beside the section's heading, drawn larger than its place so it spills over the heading and the figures beneath, that the first time it's seen drives in with the scroll, rolling forward as the page moves on and back as it moves back, stopping just past the screen's middle with a dip of its nose, then turns slowly on a soft shadow that a sideways drag swings round (it never zooms, and is simply there, still, under reduced motion), over a still of it until three.js loads as it nears the screen, a lighter cut of it first and the full one swapped in once it settles, unless the connection is saving data or slow; and a few days worth going back to as cards like the trips': one photo for a single Moment, the busiest day's photos in a grid, a strip for each day of the longest run; the devices bought that year as small bordered cards, each leading to its own post if there is one, else to /use; and last, a 24-hour clock face of petals with the night half shaded. Its motion plays once and then holds still: the year rises in and its counts roll up as it opens, and each section rises in the first time it scrolls into view, its parts following in turn (the city bars growing, the months wiping in, the wall's tiles popping up a day at a time, the routes drawing, the clock's petals growing out); none of it under reduced motion, and without the script nothing waits hidden. The home page's year labels and the header's 回顾 link lead to it.
- **The calendar and map are the exception.** They use the full width: a rounded grid with photo thumbnails, a side drawer swiped away like a phone's sheet ([@hyoban/drawer](packages/drawer)), and a pill-shaped home button ([CalendarBackLink.astro](src/components/CalendarBackLink.astro)). Switching between calendar and map uses view transitions. A month can also be read as a list ([CalendarAgenda.astro](src/components/CalendarAgenda.astro)): newest day first along a thin line with a dot per day, the date pinned in a narrow rail on its left (above the Moments on a phone). Phones always show the list; on a desktop a small grid/list switch beside the mode switch picks one and remembers it, and a day's drawer always opens over the grid. On the map, every trip from [data/trips](data/trips) is drawn as a thin route; clicking one replays it stop by stop: the other routes and the markers fade, the route draws in `tx`, and a card in the drawer's style slides in for each stop.

## Typography

- **One font size.** Headings, including prose `h1`–`h6`, are `1em` and set apart only by `font-weight: 500`. Size isn't used to build hierarchy.
- **Default system fonts and weights.** No web fonts are loaded. Monospace appears only for calendar numbers and dates.
- **Careful text rhythm.** `text-wrap: pretty` is on paragraphs and `balance` on headings. `text-autospace: normal` adds space between Chinese and Latin text automatically. Dates use `tabular-nums`, and links don't break mid-word (`whitespace-nowrap`).

## Color

- **A small set of neutral colors.** The palette is four surface colors (`bg`, `border`, `inline`, `hover`) and three text levels (`tx`, `tx-2`, `tx-3`), all warm off-white/off-black grays ([index.css](src/styles/index.css)).
- **No accent color.** Links are told apart by a thin `tx-3` underline that turns full-strength on hover. The active filter on `/media` is marked by text color alone.
- **Dark mode follows the system.** It uses `prefers-color-scheme`, with no toggle, and the code highlighting (Shiki's Vitesse light/dark themes) follows the same setting.

## Details

- **Small icons inline with text.** Lucide and Simple Icons, as CSS masks in [icons.css](src/styles/icons.css), sit before link text at `0.85em`, rather than as separate buttons.
- **Light framing on code, tables and images.** They get a 1px border, a `0.625rem` radius and the `inline` background color. A horizontal rule is drawn as `· · ·`.
- **Small, quick motion.** Page changes fade over 200ms, colors change over 140ms, and thumbnails scale up 2.5% on hover. All of it, including view transitions, respects reduced-motion.
- **Chinese-first.** Copy, ARIA labels and date formats are written for Chinese readers.

## Summary

The design is held together by restraint. One column, one font size, gray text in three levels, and underlines instead of color. That keeps the writing in front, and only the calendar and map get a richer interface.
