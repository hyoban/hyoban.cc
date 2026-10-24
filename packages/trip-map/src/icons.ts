// The icons the map and the player draw, as inline SVG: Lucide's (https://lucide.dev,
// ISC licence), so the page needs no icon font or Tailwind plugin.

const ICONS = {
  camera:
    '<path d="M13.997 4a2 2 0 0 1 1.76 1.05l.486.9A2 2 0 0 0 18.003 7H20a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h1.997a2 2 0 0 0 1.759-1.048l.489-.904A2 2 0 0 1 10.004 4z"/><circle cx="12" cy="13" r="3"/>',
  'car-front':
    '<path d="m21 8-2 2-1.5-3.7A2 2 0 0 0 15.646 5H8.4a2 2 0 0 0-1.903 1.257L5 10 3 8m4 6h.01M17 14h.01"/><rect width="18" height="8" x="3" y="10" rx="2"/><path d="M5 18v2m14-2v2"/>',
  cloud: '<path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9"/>',
  'cloud-drizzle':
    '<path d="M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242M8 19v1m0-6v1m8 4v1m0-6v1m-4 6v1m0-6v1"/>',
  'cloud-fog':
    '<path d="M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242M16 17H7m10 4H9"/>',
  'cloud-lightning':
    '<path d="M6 16.326A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 .5 8.973"/><path d="m13 12-3 5h4l-3 5"/>',
  'cloud-rain':
    '<path d="M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242M16 14v6m-8-6v6m4-4v6"/>',
  'chevron-left': '<path d="m15 18-6-6 6-6"/>',
  'chevron-right': '<path d="m9 18 6-6-6-6"/>',
  clapperboard:
    '<path d="m12.296 3.464 3.02 3.956M20.2 6 3 11l-.9-2.4c-.3-1.1.3-2.2 1.3-2.5l13.5-4c1.1-.3 2.2.3 2.5 1.3zM3 11h18v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zm3.18-5.724 3.1 3.899"/>',
  'cloud-sun':
    '<path d="M12 2v2m-7.07.93 1.41 1.41M20 12h2m-2.93-7.07-1.41 1.41m-1.713 6.31a4 4 0 0 0-5.925-4.128M13 22H7a5 5 0 1 1 4.9-6H13a3 3 0 0 1 0 6"/>',
  'plug-zap':
    '<path d="M6.3 20.3a2.4 2.4 0 0 0 3.4 0L12 18l-6-6l-2.3 2.3a2.4 2.4 0 0 0 0 3.4ZM2 22l3-3m2.5-5.5L10 11m.5 5.5L13 14m5-11l-4 4h6l-4 4"/>',
  pause:
    '<rect width="5" height="18" x="14" y="3" rx="1"/><rect width="5" height="18" x="5" y="3" rx="1"/>',
  plane:
    '<path d="M17.8 19.2 16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9.1-1.1.5l-.3.5c-.2.5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3.4.8.5 1.3.3l.5-.2c.4-.3.6-.7.5-1.2"/>',
  play: '<path d="M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z"/>',
  'rotate-ccw': '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/>',
  ship: '<path d="M12 2v2m0 5.189V13m7-1V6a2 2 0 0 0-2-2H7a2 2 0 0 0-2 2v6"/><path d="M19.38 19A11.6 11.6 0 0 0 21 13l-8.188-3.639a2 2 0 0 0-1.624 0L3 13.001a11.6 11.6 0 0 0 2.81 7.76"/><path d="M2 20c.6.5 1.2 1 2.5 1c2.5 0 2.5-2 5-2c1.3 0 1.9.5 2.5 1s1.2 1 2.5 1c2.5 0 2.5-2 5-2c1.3 0 1.9.5 2.5 1"/>',
  snowflake:
    '<path d="m10 20-1.25-2.5L6 18m4-14L8.75 6.5 6 6m8 14 1.25-2.5L18 18M14 4l1.25 2.5L18 6"/><path d="m17 21-3-6h-4m7-12-3 6 1.5 3M2 12h6.5L10 9m10 1-1.5 2 1.5 2"/><path d="M22 12h-6.5L14 15M4 10l1.5 2L4 14m3 7 3-6-1.5-3M7 3l3 6h4"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M4.93 4.93l1.41 1.41m11.32 11.32 1.41 1.41M2 12h2m16 0h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41"/>',
  tv: '<path d="m17 2-5 5-5-5"/><rect width="20" height="15" x="2" y="7" rx="2"/>',
  'train-front':
    '<path d="M8 3.1V7a4 4 0 0 0 8 0V3.1M9 15l-1-1m7 1 1-1"/><path d="M9 19c-2.8 0-5-2.2-5-5v-4a8 8 0 0 1 16 0v4c0 2.8-2.2 5-5 5Zm-1 0-2 3m10-3 2 3"/>',
  'volume-2':
    '<path d="M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298zM16 9a5 5 0 0 1 0 6m3.364 3.364a9 9 0 0 0 0-12.728"/>',
  'volume-x':
    '<path d="M11 4.702a.7.7 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.7.7 0 0 0 11 19.298zm5.5 9.798 5-5m-5 0 5 5"/>',
  x: '<path d="M18 6 6 18M6 6l12 12"/>',
} as const

export type IconName = keyof typeof ICONS

/** The icons a day's weather may have (see `describeWeather`). */
export type WeatherIcon = Extract<
  IconName,
  | 'cloud'
  | 'cloud-drizzle'
  | 'cloud-fog'
  | 'cloud-lightning'
  | 'cloud-rain'
  | 'cloud-sun'
  | 'snowflake'
  | 'sun'
>

export const ICON_CLASS = 'trip-map-icon'

/** The icon's strokes, for an `<svg viewBox="0 0 24 24">`; it takes the text colour. */
export function getIconBody(name: IconName) {
  return `<g fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${ICONS[name]}</g>`
}

/** The icon as markup, for a page rendered on the server. Decorative: hidden from screen readers. */
export function renderIcon(name: IconName) {
  return `<svg class="${ICON_CLASS}" viewBox="0 0 24 24" aria-hidden="true" data-icon="${name}">${getIconBody(name)}</svg>`
}
