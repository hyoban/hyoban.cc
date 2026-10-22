import type { FilterSpecification, Map as MapboxMap } from 'mapbox-gl'
import type { FogOfWar } from '../fog-of-war.ts'
import { t, type Messages } from '../messages.ts'
import { readFogPreference, writeFogPreference } from '../player/preferences.ts'
import type { Theme } from '../theme.ts'

// The fog of war over the map, behind a toggle: every city not been to under a veil. The regions and which of them are lit are
// fetched the first time it's shown; it goes while a trip plays, as the markers do.

const SOURCE = 'trip-map-fog'
const FILL = 'trip-map-fog'
// The basemap's water again, over the fog: the sea, lakes and rivers stay clear, and the
// fog meets the land where the basemap's coast is rather than the cities' rougher one.
const WATER = 'trip-map-fog-water'
// The veil: the page's ink over the light basemap; over the dark one, which the ink
// would lighten, black, so the cities been to are the ones that stand out.
const VEIL = { dark: { color: '#000', opacity: 0.62 }, light: { opacity: 0.5 } }

export function createFogLayer({
  areaElement,
  button,
  getMap,
  getTheme,
  isDark,
  messages,
  onError,
  regionsUrl,
  signal,
  url,
}: {
  /** Where the cities lit are counted while the fog shows. */
  areaElement: HTMLElement | null
  /** The toggle; without one there is no fog. */
  button: HTMLButtonElement | null
  /** The map once it has loaded, its style with it. */
  getMap: () => MapboxMap | undefined
  getTheme: () => Theme
  isDark: () => boolean
  messages: Messages
  onError: (cause: unknown) => void
  /** The regions, GeoJSON features with an `id` each. */
  regionsUrl: string
  signal: AbortSignal
  /** `FogOfWar` as JSON: which regions are lit. */
  url: string
}) {
  let fog: { lit: FogOfWar; regions: GeoJSON.FeatureCollection } | undefined
  let loading: Promise<unknown> | undefined
  let on = Boolean(button) && readFogPreference()
  let hidden = false

  function fetchJson<T>(from: string) {
    return fetch(from, { signal }).then((response) => {
      if (!response.ok) {
        throw new Error(`${response.status} ${response.statusText}`)
      }

      return response.json() as Promise<T>
    })
  }

  function load() {
    loading ??= Promise.all([
      fetchJson<FogOfWar>(url),
      fetchJson<GeoJSON.FeatureCollection>(regionsUrl),
    ])
      .then(([lit, regions]) => {
        fog = { lit, regions }
      })
      .catch((cause: unknown) => {
        loading = undefined

        if (!signal.aborted) {
          onError(cause)
        }
      })

    return loading
  }

  /** The first of the package's own layers, which the fog goes under. */
  function getBeforeId(map: MapboxMap) {
    return map
      .getStyle()
      ?.layers?.find((layer) => layer.id.startsWith('trip-map-') && !layer.id.startsWith(SOURCE))
      ?.id
  }

  /** Puts the fog on the map as it should be: once it loads, each time its style does, and on each toggle. */
  function sync() {
    const map = getMap()

    if (!map || !fog) {
      return
    }

    const visibility = on && !hidden ? 'visible' : 'none'
    const { color, opacity } = isDark() ? VEIL.dark : { ...VEIL.light, color: getTheme().ink }

    if (!map.getSource(SOURCE)) {
      const beforeId = getBeforeId(map)
      const filter: FilterSpecification = ['!', ['in', ['get', 'id'], ['literal', fog.lit.lit]]]

      map.addSource(SOURCE, { data: fog.regions, type: 'geojson' })
      // Without antialiasing, the cities and the world's tiles meet without a seam.
      map.addLayer(
        { filter, id: FILL, paint: { 'fill-antialias': false }, source: SOURCE, type: 'fill' },
        beforeId,
      )

      const water = map
        .getStyle()
        ?.layers?.find((layer) => layer.type === 'fill' && layer['source-layer'] === 'water')

      if (water) {
        map.addLayer({ ...water, id: WATER }, beforeId)
      }
    }

    map.setPaintProperty(FILL, 'fill-color', color)
    map.setPaintProperty(FILL, 'fill-opacity', opacity)

    for (const id of [FILL, WATER]) {
      if (map.getLayer(id)) {
        map.setLayoutProperty(id, 'visibility', visibility)
      }
    }
  }

  function render() {
    if (button) {
      button.ariaPressed = String(on)
    }

    if (areaElement) {
      areaElement.hidden = !on || hidden || !fog
      areaElement.textContent = fog
        ? t(messages, 'fogLit', { cities: fog.lit.cities, provinces: fog.lit.provinces })
        : ''
    }

    sync()
  }

  async function setOn(next: boolean) {
    on = next
    writeFogPreference(on)
    render()

    if (on && !fog) {
      await load()
      render()
    }
  }

  button?.addEventListener('click', () => void setOn(!on), { signal })

  if (on) {
    void setOn(true)
  } else {
    render()
  }

  return {
    /** Once the map loads, and again each time its style does. */
    add: sync,
    /** Away while a trip plays. */
    setHidden(next: boolean) {
      hidden = next
      render()
    },
  }
}
