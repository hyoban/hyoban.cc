import { renderIcon } from './icons.ts'
import { t, type Messages } from './messages.ts'
import { getIntroCovers, getTripMeta, MOSAIC_CLASS, MOSAIC_IMAGE_CLASS } from './player/intro.ts'
import type { TripData } from './player/types.ts'

// The markup the player drives, as strings: a site renders it into its containers
// on the server (so a trip page opens on its intro card before any script has run),
// or the client renders it when a container comes empty. The page's own layout, such
// as where the panel sits, is the site's.

export type MusicTrack = { src: string; title: string }

/** Who made the video, on the presentation stage's corner. */
export type Brand = { image?: string | undefined; name: string }

/** The trip panel's parts: the progress bar, the card and its controls. */
export function renderTripPanel({
  messages,
  music = [],
  trip,
}: {
  messages: Messages
  /** The tracks a video may be set to, in the setup form. */
  music?: readonly MusicTrack[] | undefined
  /** The trip the page opens on, to show its intro card at once. */
  trip?: TripData | undefined
}) {
  const button = (attributes: string, icon: Parameters<typeof renderIcon>[0]) =>
    `<button class="trip-map-panel__button" type="button" ${attributes}>${renderIcon(icon)}</button>`
  const intro = trip
    ? `<p class="trip-map-card__meta">${escapeHtml(getTripMeta(trip, messages))}</p>` +
      `<div class="${MOSAIC_CLASS}" aria-hidden="true">${getIntroCovers(trip)
        .map(
          (cover, index) =>
            `<img class="${MOSAIC_IMAGE_CLASS}" src="${escapeHtml(cover.thumbnail)}" alt="" decoding="async" fetchpriority="${index < 3 ? 'high' : 'low'}">`,
        )
        .join('')}</div>`
    : ''

  return (
    `<ol class="trip-map-panel__progress" data-trip-map-progress aria-label="${escapeHtml(messages.progress)}"></ol>` +
    `<header class="trip-map-panel__header" data-trip-map-header>` +
    `<p class="trip-map-panel__eyebrow" data-trip-map-eyebrow></p>` +
    `<h2 class="trip-map-panel__title" id="trip-map-panel-title" data-trip-map-title>${escapeHtml(trip?.title ?? '')}</h2>` +
    `</header>` +
    `<div class="trip-map-panel__body" data-trip-map-body>${intro}</div>` +
    `<p class="trip-map-sr-only" data-trip-map-announcer aria-live="polite"></p>` +
    `<footer class="trip-map-panel__footer">` +
    `<div class="trip-map-panel__controls" data-trip-map-controls>` +
    button(
      `data-trip-map-previous aria-label="${escapeHtml(messages.previous)}" aria-keyshortcuts="ArrowLeft" title="${escapeHtml(t(messages, 'withKey', { action: messages.previous, key: '←' }))}"`,
      'chevron-left',
    ) +
    button(
      `data-trip-map-play aria-label="${escapeHtml(messages.pause)}" aria-keyshortcuts="Space"`,
      'pause',
    ) +
    button(
      `data-trip-map-next aria-label="${escapeHtml(messages.next)}" aria-keyshortcuts="ArrowRight" title="${escapeHtml(t(messages, 'withKey', { action: messages.next, key: '→' }))}"`,
      'chevron-right',
    ) +
    `</div>` +
    `<div class="trip-map-panel__actions" data-trip-map-actions>` +
    `<button class="trip-map-panel__button trip-map-panel__speed" type="button" data-trip-map-speed aria-label="${escapeHtml(t(messages, 'playbackSpeed', { speed: 1 }))}" aria-keyshortcuts="- =">1×</button>` +
    button(
      `data-trip-map-flyover aria-label="${escapeHtml(messages.flyover)}" aria-pressed="false" aria-keyshortcuts="V" title="${escapeHtml(t(messages, 'withKey', { action: messages.flyover, key: 'V' }))}"`,
      'mountain',
    ) +
    button(
      `data-trip-map-music aria-label="${escapeHtml(messages.music)}" aria-pressed="false" aria-keyshortcuts="M" title="${escapeHtml(t(messages, 'withKey', { action: messages.music, key: 'M' }))}"`,
      'volume-x',
    ) +
    button(
      `data-trip-map-present aria-label="${escapeHtml(messages.present)}" aria-keyshortcuts="F" title="${escapeHtml(t(messages, 'withKey', { action: messages.present, key: 'F' }))}"`,
      'tv',
    ) +
    button(
      `data-trip-map-export aria-label="${escapeHtml(messages.exportVideo)}" aria-keyshortcuts="R" title="${escapeHtml(t(messages, 'withKey', { action: messages.exportVideo, key: 'R' }))}" hidden`,
      'clapperboard',
    ) +
    button(
      `data-trip-map-close aria-label="${escapeHtml(messages.closePlayback)}" aria-keyshortcuts="Escape" title="${escapeHtml(t(messages, 'withKey', { action: messages.closePlayback, key: messages.keyEscape }))}"`,
      'x',
    ) +
    `</div>` +
    `<div class="trip-map-setup" data-trip-map-setup role="group" aria-labelledby="trip-map-setup-title" hidden>` +
    `<p class="trip-map-setup__title" id="trip-map-setup-title" data-trip-map-setup-title>${escapeHtml(messages.present)}</p>` +
    `<div class="trip-map-setup__row">` +
    `<span class="trip-map-setup__label" id="trip-map-setup-ratio">${escapeHtml(messages.ratio)}</span>` +
    `<div class="trip-map-setup__segments" role="radiogroup" aria-labelledby="trip-map-setup-ratio">` +
    `<button type="button" role="radio" aria-checked="true" data-trip-map-setup-ratio="portrait">${escapeHtml(messages.portrait)}</button>` +
    `<button type="button" role="radio" aria-checked="false" data-trip-map-setup-ratio="landscape">${escapeHtml(messages.landscape)}</button>` +
    `</div></div>` +
    `<label class="trip-map-setup__check" data-trip-map-setup-safe-area-row>` +
    `<input type="checkbox" data-trip-map-setup-safe-area checked><span>${escapeHtml(messages.safeArea)}</span>` +
    `</label>` +
    `<label class="trip-map-setup__row">` +
    `<span class="trip-map-setup__label">${escapeHtml(messages.music)}</span>` +
    `<select class="trip-map-setup__select" data-trip-map-setup-music>` +
    music
      .map(
        (track) => `<option value="${escapeHtml(track.src)}">${escapeHtml(track.title)}</option>`,
      )
      .join('') +
    `<option value="">${escapeHtml(messages.noMusic)}</option>` +
    `</select></label>` +
    `<div class="trip-map-setup__actions">` +
    `<button type="button" data-trip-map-setup-cancel>${escapeHtml(messages.cancel)}</button>` +
    `<button type="button" data-trip-map-setup-start>${escapeHtml(messages.presentStart)}</button>` +
    `</div></div>` +
    `</footer>`
  )
}

/** The presentation stage's parts: the heading, the photo cards' column and the exit button. */
export function renderTripStage({
  brand,
  messages,
}: {
  brand?: Brand | undefined
  messages: Messages
}) {
  const brandMarkup = brand
    ? `<p class="trip-map-stage__brand">${brand.image ? `<img src="${escapeHtml(brand.image)}" alt="" width="24" height="24">` : ''}${escapeHtml(brand.name)}</p>`
    : ''

  return (
    `<header class="trip-map-stage__header" data-trip-map-stage-header>` +
    `<p class="trip-map-stage__eyebrow" data-trip-map-stage-eyebrow></p>` +
    `<p class="trip-map-stage__title" data-trip-map-stage-title></p>` +
    `</header>` +
    brandMarkup +
    `<div class="trip-map-stage__photos" data-trip-map-stage-photos></div>` +
    `<div class="trip-map-stage__safe" data-trip-map-stage-safe aria-hidden="true"></div>` +
    `<div class="trip-map-stage__center" data-trip-map-stage-center></div>` +
    `<div class="trip-map-stage__progress" data-trip-map-stage-progress></div>` +
    `<button class="trip-map-stage__music" type="button" data-trip-map-stage-music aria-label="${escapeHtml(messages.music)}" aria-pressed="false" aria-keyshortcuts="M" title="${escapeHtml(t(messages, 'withKey', { action: messages.music, key: 'M' }))}">${renderIcon('volume-x')}</button>` +
    `<button class="trip-map-stage__close" type="button" data-trip-map-stage-close aria-label="${escapeHtml(messages.exitPresentation)}">${renderIcon('x')}</button>`
  )
}

/** The shortcut panel's content, for a `<dialog>`: the player's keys, grouped. */
export function renderShortcuts(messages: Messages) {
  const groups: [title: string, keys: [string[], string][]][] = [
    [
      messages.shortcutsPlayback,
      [
        [[messages.keySpace], messages.shortcutPlay],
        [['←', '→'], messages.shortcutCards],
        [[messages.keyShift, '←', '→'], messages.shortcutDays],
        [[messages.keyHome, messages.keyEnd], messages.shortcutEnds],
        [['↑', '↓'], messages.shortcutPhotos],
        [['V'], messages.shortcutFlyover],
      ],
    ],
    [
      messages.shortcutsSpeedSound,
      [
        [['-', '='], messages.shortcutSpeed],
        [['M'], messages.shortcutMusic],
      ],
    ],
    [
      messages.shortcutsPresenting,
      [
        [['F'], messages.shortcutFullscreen],
        [['R'], messages.shortcutExport],
        [[messages.keyEscape], messages.shortcutEscape],
        [['?'], messages.shortcutHelp],
      ],
    ],
  ]

  return (
    `<header class="trip-map-shortcuts__header">` +
    `<h2 class="trip-map-shortcuts__title" id="trip-map-shortcuts-title">${escapeHtml(messages.shortcuts)}</h2>` +
    `<form method="dialog"><button class="trip-map-panel__button" aria-label="${escapeHtml(messages.close)}">${renderIcon('x')}</button></form>` +
    `</header>` +
    groups
      .map(
        ([title, keys]) =>
          `<section><h3>${escapeHtml(title)}</h3><dl>${keys
            .map(
              ([names, action]) =>
                `<div><dt>${names.map((name) => `<kbd>${escapeHtml(name)}</kbd>`).join('')}</dt><dd>${escapeHtml(action)}</dd></div>`,
            )
            .join('')}</dl></section>`,
      )
      .join('')
  )
}

/** JSON for a `<script type="application/json">`, with no way to close the tag early. */
export function toScriptJson(value: unknown) {
  return JSON.stringify(value).replaceAll('<', '\\u003c')
}

/** Text safe in HTML, inside an element or a double-quoted attribute. */
export function escapeHtml(text: string) {
  return text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}
