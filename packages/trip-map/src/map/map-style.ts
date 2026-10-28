import MapboxLanguage from '@mapbox/mapbox-gl-language'
import type { IControl, Map as MapboxMap } from 'mapbox-gl'
import { readTheme } from '../dom-theme.ts'
import { readMapSettingsPreference, writeMapSettingsPreference } from '../player/preferences.ts'
import {
  getBasemapScheme,
  getBasemapStyle,
  isBasemapDark,
  setBasemapConfig,
  setMapPitch,
  STANDARD_STYLE,
} from './basemap.ts'
import { isSlowDevice } from './device.ts'
import { DEFAULT_MAP_SETTINGS, type MapSettings } from './map-settings.ts'

// The map's look: its settings, from the page's sidebar and remembered; the style they and
// the colour scheme give it; the colour scheme the page takes from a map lit by the clock;
// and the colours of the package's own layers on it. Follows the system's colour scheme,
// and the clock into the next time of day.

export type MapStyle = ReturnType<typeof createMapStyle>

export function createMapStyle({
  getMap,
  hasSettings,
  isNarrow,
  language,
  page,
  signal,
  style: givenStyle,
}: {
  /** The map, once it has loaded. */
  getMap: () => MapboxMap | undefined
  /** Whether the page has the settings' sidebar: without it, nothing is remembered. */
  hasSettings: boolean
  isNarrow: () => boolean
  /** The language for the basemap's names in a style other than Standard; `false` for none. */
  language: string | false
  page: HTMLElement
  signal: AbortSignal
  /** The style given, if any (see `TripMapOptions.mapStyle`). */
  style: string | Readonly<{ dark: string; light: string }> | undefined
}) {
  const darkMode = window.matchMedia('(prefers-color-scheme: dark)')
  // Never set in the sidebar, the style given if there is one.
  let settings = hasSettings ? readMapSettingsPreference() : undefined
  // What the map starts with until the viewer sets it: the flat classic map on a device
  // too slow for Standard. Judged the first time it's asked for.
  let defaultSettings: MapSettings | undefined
  // The style the map was last given, to catch a theme change made while it loaded, its
  // address, and whether it was lit dark.
  let styleKey = getStyleKey()
  let styleUrl = getStyle().url
  let styleDark = isMapDark()
  // Names in the language, for a style other than Standard, which has them itself.
  let languageControl: IControl | undefined

  // A phone starts on the flat map, a globe's edge wasting most of its narrow screen.
  function getDefaultSettings() {
    return (defaultSettings ??= {
      ...DEFAULT_MAP_SETTINGS,
      ...(isSlowDevice() && { style: 'classic' }),
      ...(isNarrow() && { projection: 'mercator' }),
    })
  }

  function getSettings() {
    return settings ?? getDefaultSettings()
  }

  /**
   * The colour scheme the page takes from a map lit by the clock, marked on it as
   * `data-trip-map-scheme` for its styles to follow; none while it keeps its own.
   */
  function getPageScheme() {
    return settings || !givenStyle ? getBasemapScheme(getSettings()) : undefined
  }

  function isPageDark() {
    const scheme = getPageScheme()
    return scheme ? scheme === 'dark' : darkMode.matches
  }

  function syncPageScheme() {
    const scheme = getPageScheme()

    if (page.dataset.tripMapScheme !== scheme) {
      if (scheme) {
        page.dataset.tripMapScheme = scheme
      } else {
        delete page.dataset.tripMapScheme
      }
    }
  }

  /** The style for the settings and the colour scheme, Standard's own, and how far it leans. */
  function getStyle() {
    const dark = isPageDark()

    if (settings || !givenStyle) {
      return getBasemapStyle(getSettings(), dark)
    }

    const url =
      typeof givenStyle === 'string' ? givenStyle : dark ? givenStyle.dark : givenStyle.light

    return url === STANDARD_STYLE
      ? getBasemapStyle(DEFAULT_MAP_SETTINGS, dark)
      : { config: undefined, pitch: 0, url }
  }

  /** Whether the map is lit dark, which its own layers' colours go by. */
  function isMapDark() {
    return settings || !givenStyle ? isBasemapDark(getSettings(), isPageDark()) : isPageDark()
  }

  /**
   * The page's colours for the package's layers on the map, ink and paper the other way
   * round on a map lit dark under a light page, or lit by day under a dark one.
   */
  function getMapTheme() {
    const theme = readTheme(page)

    return isMapDark() === isPageDark() ? theme : { ...theme, ink: theme.paper, paper: theme.ink }
  }

  /** The style with its settings: Standard is one style by day and by night. */
  function getStyleKey() {
    const { config, url } = getStyle()
    return `${url} ${JSON.stringify(config ?? {})}`
  }

  /** Remembers the style the map is given now, as it's made with it or given it anew. */
  function markApplied() {
    styleKey = getStyleKey()
    styleUrl = getStyle().url
    styleDark = isMapDark()
  }

  /** Gives the map the style for the look and colour scheme, its layers and the package's again. */
  function setStyle(map: MapboxMap) {
    const { config, pitch, url } = getStyle()

    markApplied()
    setMapPitch(map, pitch)
    syncLanguage(map, !config)
    // Built again from scratch: Standard and another style have too little in common to
    // be changed from one to the other.
    map.setStyle(url, {
      diff: false,
      ...(config && { config }),
    } as Partial<SetStyleOptions> as SetStyleOptions)
  }

  /** The plugin puts the names in the language, in a style that doesn't do it itself. */
  function syncLanguage(map: MapboxMap, needed: boolean) {
    if (language && needed && !languageControl) {
      languageControl = new MapboxLanguage({
        defaultLanguage: language,
      }) as unknown as IControl
      map.addControl(languageControl)
    } else if (!needed && languageControl) {
      map.removeControl(languageControl)
      languageControl = undefined
    }
  }

  /**
   * Gives the map its style anew for a change of settings or of the time of day: another
   * style built again, Standard's own settings changed in place.
   */
  function restyle(map: MapboxMap) {
    const { config, pitch, url } = getStyle()

    // The package's layers take their colours as they're added: lit the other way, they're
    // added again with the style.
    if (url !== styleUrl || isMapDark() !== styleDark) {
      setStyle(map)
    } else if (config) {
      setBasemapConfig(map, config)
      setMapPitch(map, pitch)
      styleKey = getStyleKey()
    }
  }

  darkMode.addEventListener(
    'change',
    () => {
      const map = getMap()

      // A page dark or light by the clock stays as it is.
      if (map && !getPageScheme()) {
        setStyle(map)
      }
    },
    { signal },
  )

  // A map lit by the clock, and the page with it, follow it into the next time of day.
  syncPageScheme()
  const clock = setInterval(() => {
    if (getSettings().light !== 'time') {
      return
    }

    syncPageScheme()

    const map = getMap()

    if (map && getStyleKey() !== styleKey) {
      restyle(map)
    }
  }, 60_000)
  signal.addEventListener('abort', () => clearInterval(clock))

  return {
    getDefaultSettings,
    getMapTheme,
    getSettings,
    getStyle,
    isMapDark,
    /** Whether the theme or the clock changed the style since the map was given it. */
    get isStale() {
      return getStyleKey() !== styleKey
    },
    markApplied,
    restyle,
    /** Takes the settings the viewer set, and remembers them. */
    setSettings(next: MapSettings) {
      settings = next
      writeMapSettingsPreference(next)
      syncPageScheme()
    },
    setStyle,
    syncLanguage,
  }
}

type SetStyleOptions = NonNullable<Parameters<MapboxMap['setStyle']>[1]>

/** The basemap language for a locale: Chinese by script, anything else by its language. */
export function getMapLanguage(locale: string) {
  const [language = 'en', ...rest] = locale.split('-')
  const tags = new Set(rest.map((tag) => tag.toLowerCase()))

  if (language.toLowerCase() === 'zh') {
    return tags.has('hant') || tags.has('tw') || tags.has('hk') || tags.has('mo')
      ? 'zh-Hant'
      : 'zh-Hans'
  }

  return language.toLowerCase()
}
