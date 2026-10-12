# Design

hyoban.cc is a quiet, text-first personal site built with Astro and Tailwind v4. The design principle is "one size, one column, almost no decoration."

## Layout

- **One column for everything.** Home, posts, `/media` and `/use` all sit in a centered `max-w-[40rem]` column with `leading-7`. The shared wrapper is [SitePage.astro](src/components/SitePage.astro).
- **Identity header instead of a nav bar.** [SiteHeader.astro](src/components/SiteHeader.astro) shows just the avatar, name and bio, and links back home. There's no menu. Other sections are linked from sentences in the home intro.
- **Posts are stripped back.** A post shows only the title, the date and the prose. On screens 64rem and wider, the "返回" (back) link moves out into the left margin (`right-full mr-16`), so the reading column stays clean ([[...slug].astro](src/pages/[...slug].astro)).
- **Posts are grouped by year.** On the home page, each year label sits in a narrow `3.5rem` column that stays pinned while you scroll its posts. Each row is title plus date, with a soft background on hover instead of an underline.
- **The calendar and map are the exception.** They use the full width: a rounded grid with photo thumbnails, a side drawer, and a pill-shaped home button ([CalendarBackLink.astro](src/components/CalendarBackLink.astro)). Switching between calendar and map uses view transitions. On the map, every trip from [data/trips](data/trips) is drawn as a thin route; clicking one replays it stop by stop: the other routes and the markers fade, the route draws in `tx`, and a card in the drawer's style slides in for each stop.

## Typography

- **One font size.** Headings, including prose `h1`–`h6`, are `1em` and set apart only by `font-weight: 500`. Size isn't used to build hierarchy.
- **Default system fonts and weights.** No web fonts are loaded. Monospace appears only for calendar numbers and dates.
- **Careful text rhythm.** `text-wrap: pretty` is on paragraphs and `balance` on headings. `text-autospace: normal` adds space between Chinese and Latin text automatically. Dates use `tabular-nums`, and links don't break mid-word (`whitespace-nowrap`).

## Color

- **A small set of neutral colors.** Tailwind's default palette is removed (`--color-*: initial`). What's left is four surface colors (`bg`, `border`, `inline`, `hover`) and three text levels (`tx`, `tx-2`, `tx-3`), all warm off-white/off-black grays ([index.css](src/styles/index.css)).
- **No accent color.** Links are told apart by a thin `tx-3` underline that turns full-strength on hover. The active filter on `/media` is marked by text color alone.
- **Dark mode follows the system.** It uses `prefers-color-scheme`, with no toggle, and the code highlighting (Shiki's Vitesse light/dark themes) follows the same setting.

## Details

- **Small icons inline with text.** Lucide and Simple Icons (via `tailwindcss-icons`) sit before link text at `0.85em`, rather than as separate buttons.
- **Light framing on code, tables and images.** They get a 1px border, a `0.625rem` radius and the `inline` background color. A horizontal rule is drawn as `· · ·`.
- **Small, quick motion.** Page changes fade over 200ms, colors change over 140ms, and thumbnails scale up 2.5% on hover. All of it, including view transitions, respects reduced-motion.
- **Chinese-first.** Copy, ARIA labels and date formats are written for Chinese readers.

## Summary

The design is held together by restraint. One column, one font size, gray text in three levels, and underlines instead of color. That keeps the writing in front, and only the calendar and map get a richer interface.
