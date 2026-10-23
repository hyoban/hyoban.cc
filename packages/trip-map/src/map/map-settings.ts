import { isMapStyleId, type MapStyleId } from './basemap.ts'
import { createSheet, type Sheet } from './sheet.ts'

// What a viewer may set about the map, in a sidebar of the page's: its look, how it's
// lit, what the basemap shows besides the places, whether it leans as it comes closer,
// and the globe or a flat map. Remembered in this browser.

export const MAP_LIGHTS = ['auto', 'day', 'dawn', 'dusk', 'night'] as const
export const MAP_PROJECTIONS = ['globe', 'mercator'] as const

export type MapLight = (typeof MAP_LIGHTS)[number]
export type MapProjection = (typeof MAP_PROJECTIONS)[number]

export type MapSettings = {
  /** Standard's buildings, trees and landmarks in 3D. */
  buildings: boolean
  /** Standard's landmarks as icons, with their names. */
  landmarks: boolean
  /** Standard's light: by the colour scheme (day, or night in the dark), or one time of day. */
  light: MapLight
  /** Standard's shops, restaurants and the rest. */
  pois: boolean
  projection: MapProjection
  /** The roads' names and numbers. */
  roads: boolean
  style: MapStyleId
  /** Leaning back the closer the map is, in Standard. */
  tilt: boolean
  /** Standard's stations and stops. */
  transit: boolean
}

// As plain as the flat map was: the places, roads and water named, and the land and the
// buildings in 3D.
export const DEFAULT_MAP_SETTINGS: MapSettings = {
  buildings: true,
  landmarks: false,
  light: 'auto',
  pois: false,
  projection: 'globe',
  roads: true,
  style: 'standard',
  tilt: true,
  transit: false,
}

const SWITCHES = ['buildings', 'landmarks', 'pois', 'roads', 'tilt', 'transit'] as const

const isOneOf = <T extends string>(values: readonly T[], value: unknown): value is T =>
  values.includes(value as T)

/** Settings from what was stored, each one that isn't understood left at its default. */
export function parseMapSettings(value: unknown): MapSettings {
  const settings = { ...DEFAULT_MAP_SETTINGS }

  if (!value || typeof value !== 'object') {
    return settings
  }

  const stored = value as Record<string, unknown>

  if (isMapStyleId(stored.style)) settings.style = stored.style
  if (isOneOf(MAP_LIGHTS, stored.light)) settings.light = stored.light
  if (isOneOf(MAP_PROJECTIONS, stored.projection)) settings.projection = stored.projection

  for (const key of SWITCHES) {
    if (typeof stored[key] === 'boolean') settings[key] = stored[key]
  }

  return settings
}

/** The settings as `form` has them: radios named for a setting, and a checkbox for each switch. */
export function readMapSettingsForm(form: HTMLFormElement, current: MapSettings) {
  const data = new FormData(form)
  const next: Record<string, unknown> = { ...current }

  for (const name of ['style', 'light', 'projection'] as const) {
    if (data.has(name)) next[name] = data.get(name)
  }

  // A switch out of reach is left out of the form's data, and so as it was.
  for (const key of SWITCHES) {
    const input = form.elements.namedItem(key)

    if (input instanceof HTMLInputElement && !input.matches(':disabled')) {
      next[key] = data.has(key)
    }
  }

  return parseMapSettings(next)
}

/** Shows `settings` in `form`, the settings only Standard has unavailable in another look. */
export function writeMapSettingsForm(form: HTMLFormElement, settings: MapSettings) {
  for (const element of form.elements) {
    if (!(element instanceof HTMLInputElement) || !isSettingField(element)) {
      continue
    }

    const value = settings[element.name as keyof MapSettings]

    if (element.type === 'radio') {
      element.checked = element.value === value
    } else if (element.type === 'checkbox') {
      element.checked = value === true
    }
  }

  for (const group of form.querySelectorAll<HTMLFieldSetElement>('[data-trip-map-standard]')) {
    group.disabled = settings.style === 'classic'
  }
}

/** Whether `input` is one of the settings, rather than another control beside them. */
function isSettingField(input: HTMLInputElement) {
  return input.name in DEFAULT_MAP_SETTINGS
}

/**
 * Opens the page's settings sidebar from its buttons, and calls `onChange` with the
 * settings as each one changes, at once, so the map shows it behind.
 */
export function createMapSettings({
  dialog,
  getDefaults,
  getSettings,
  narrow,
  onChange,
  openers,
  parent,
  signal,
}: {
  dialog: HTMLDialogElement | null
  /** What the reset goes back to: the defaults, or the classic map on a slow device. */
  getDefaults: () => MapSettings
  getSettings: () => MapSettings
  /** Whether the screen is a phone's, where the sidebar comes up from the bottom. */
  narrow: MediaQueryList
  onChange: (settings: MapSettings) => void
  openers: readonly HTMLElement[]
  /** The sheet open beside the map, which steps back behind the settings. */
  parent: () => Sheet | undefined
  signal: AbortSignal
}) {
  const form = dialog?.querySelector('form')

  if (!dialog || !form) {
    return
  }

  // A drawer of its own: closed by Escape, a press beside it, or a swipe.
  const sheet = createSheet(dialog, { modal: true, narrow, parent, signal })

  for (const opener of openers) {
    opener.addEventListener(
      'click',
      () => {
        writeMapSettingsForm(form, getSettings())
        sheet.open()
      },
      { signal },
    )
  }

  for (const button of dialog.querySelectorAll('[data-trip-map-settings-close]')) {
    button.addEventListener('click', () => sheet.close(), { signal })
  }

  form.addEventListener(
    'change',
    (event) => {
      // Another control in the form, as the fog's toggle, is its own.
      if (!(event.target instanceof HTMLInputElement) || !isSettingField(event.target)) {
        return
      }

      const settings = readMapSettingsForm(form, getSettings())

      writeMapSettingsForm(form, settings)
      onChange(settings)
    },
    { signal },
  )

  form.addEventListener('submit', (event) => event.preventDefault(), { signal })

  for (const button of form.querySelectorAll('[data-trip-map-settings-reset]')) {
    button.addEventListener(
      'click',
      () => {
        const defaults = getDefaults()

        writeMapSettingsForm(form, defaults)
        onChange({ ...defaults })
      },
      { signal },
    )
  }
}
