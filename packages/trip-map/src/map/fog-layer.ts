import type { ExpressionSpecification, FilterSpecification, Map as MapboxMap } from 'mapbox-gl'
import type { FogOfWar } from '../fog-of-war.ts'
import { t, type Messages } from '../messages.ts'
import { readFogPreference, writeFogPreference } from '../player/preferences.ts'
import type { Theme } from '../theme.ts'

// The fog of war over the map, behind a toggle: every city not been to under a veil.
// Turned on, the fog closes in from the edges of the screen to its middle, and each city
// been to flares gold as it passes; turned off, it clears from the middle outwards. Each
// region is reached when the sweep is as far from the screen's middle as the region's
// `cx`, `cy` is, the sweep starting at the farthest region in sight and ending at the
// nearest, so all of it is seen. The regions and which of them are lit are fetched the
// first time it's shown; it goes while a trip plays, as the markers do. Left on, it
// sweeps in as the map opens.

const SOURCE = 'trip-map-fog'
const FILL = 'trip-map-fog'
// The cities been to, flaring as the fog reaches them.
const GLOW = 'trip-map-fog-glow'
const GLOW_COLOR = '#f59e0b'
const GLOW_PEAK = 0.8
// The basemap's water again, over the fog: the sea, lakes and rivers stay clear, and the
// fog meets the land where the basemap's coast is rather than the cities' rougher one.
const WATER = 'trip-map-fog-water'
// The veil: the page's ink over the light basemap; over the dark one, which the ink
// would lighten, black, so the cities been to are the ones that stand out.
const VEIL = { dark: { color: '#000', opacity: 0.62 }, light: { opacity: 0.5 } }
// How long the sweep takes, closing in and clearing.
const SWEEP = { in: 1400, out: 900 }
// How wide the sweep's soft edge is, as a share of the way across what's in sight: wide,
// so neighbouring regions are only a little apart and the edge reads as a gradient
// rather than steps.
const EDGE = 0.8

export function createFogLayer({
  areaElement,
  button,
  getMap,
  getTheme,
  isDark,
  messages,
  onError,
  reducedMotion,
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
  reducedMotion: MediaQueryList
  /** The regions, GeoJSON features with an `id`, `cx` and `cy` each. */
  regionsUrl: string
  signal: AbortSignal
  /** `FogOfWar` as JSON: which regions are lit. */
  url: string
}) {
  let fog:
    | { centres: RegionCentre[]; lit: FogOfWar; regions: GeoJSON.FeatureCollection }
    | undefined
  let loading: Promise<unknown> | undefined
  let on = Boolean(button) && readFogPreference()
  let hidden = false
  let sweepFrame: number | undefined
  // Set while the fog is fetched for a toggle.
  let switching = false
  // On when the page opens, the fog sweeps in the first time it's shown, as for a toggle.
  let opening = on

  signal.addEventListener('abort', stopSweep)

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
        fog = { centres: regions.features.map(getRegionCentre), lit, regions }
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

  function getVeil() {
    return isDark() ? VEIL.dark : { ...VEIL.light, color: getTheme().ink }
  }

  /** The fog's layers on the map, once: clear, to be swept or set from. */
  function addLayers(map: MapboxMap, lit: readonly string[]) {
    if (map.getSource(SOURCE)) {
      return
    }

    const beforeId = getBeforeId(map)
    const isLit: ExpressionSpecification = ['in', ['get', 'id'], ['literal', lit]]

    map.addSource(SOURCE, { data: fog!.regions, type: 'geojson' })

    // Without antialiasing, the cities and the world's tiles meet without a seam.
    for (const [id, filter] of [
      [FILL, ['!', isLit]],
      [GLOW, isLit],
    ] as const) {
      map.addLayer(
        {
          filter: filter as FilterSpecification,
          id,
          layout: { visibility: 'none' },
          paint: {
            'fill-antialias': false,
            'fill-opacity': 0,
            'fill-opacity-transition': { duration: 0 },
          },
          source: SOURCE,
          type: 'fill',
        },
        beforeId,
      )
    }

    map.setPaintProperty(GLOW, 'fill-color', GLOW_COLOR)

    // Drawn just as the basemap's, so left on the map whether the fog shows or not.
    const water = map
      .getStyle()
      ?.layers?.find((layer) => layer.type === 'fill' && layer['source-layer'] === 'water')

    if (water) {
      map.addLayer({ ...water, id: WATER }, beforeId)
    }
  }

  /** The fog as it rests: shown or not, with no glow. */
  function settle(map: MapboxMap) {
    const shown = on && !hidden
    const veil = getVeil()

    map.setPaintProperty(FILL, 'fill-color', veil.color)
    map.setPaintProperty(FILL, 'fill-opacity', shown ? veil.opacity : 0)
    map.setPaintProperty(GLOW, 'fill-opacity', 0)
    map.setLayoutProperty(FILL, 'visibility', shown ? 'visible' : 'none')
    map.setLayoutProperty(GLOW, 'visibility', 'none')
  }

  function stopSweep() {
    if (sweepFrame !== undefined) {
      cancelAnimationFrame(sweepFrame)
      sweepFrame = undefined
    }

    setSweeping(false)
  }

  /** The toggle says it's busy while the fog sweeps, and is let be until it's done. */
  function setSweeping(sweeping: boolean) {
    if (button) {
      button.ariaBusy = String(sweeping)
    }
  }

  /**
   * Sweeps the fog in, from the screen's edges to its middle, each city been to flaring
   * as it's reached; or out, from the middle to the edges.
   */
  function sweep(map: MapboxMap, entering: boolean) {
    stopSweep()

    const center = map.getCenter()
    const bounds = map.getBounds()
    const squash = Math.cos((center.lat * Math.PI) / 180)
    // How far from the screen's middle the regions in sight are, nearest and farthest: those
    // whose middles are on screen, as those whose middles are off it mostly are too.
    const inSight = fog!.centres
      .filter(
        ({ cx, cy }) =>
          !bounds ||
          (cx <= bounds.getEast() &&
            cx >= bounds.getWest() &&
            cy <= bounds.getNorth() &&
            cy >= bounds.getSouth()),
      )
      .map(({ cx, cy }) => Math.hypot((cx - center.lng) * squash, cy - center.lat))
    const near = Math.min(...inSight)
    const far = Math.max(...inSight)

    if (!Number.isFinite(near) || far - near < 1e-6) {
      settle(map)
      return
    }

    const edge = (far - near) * EDGE
    // Held within what's in sight, so a region whose middle is off screen goes with the
    // farthest in sight rather than ahead of the sweep or after it.
    const distance: ExpressionSpecification = [
      'max',
      near,
      [
        'min',
        far,
        [
          'sqrt',
          [
            '+',
            ['^', ['*', ['-', ['coalesce', ['get', 'cx'], center.lng], center.lng], squash], 2],
            ['^', ['-', ['coalesce', ['get', 'cy'], center.lat], center.lat], 2],
          ],
        ],
      ],
    ]
    const veil = getVeil()
    const duration = entering ? SWEEP.in : SWEEP.out
    // Closing in, the farthest region in sight is reached at once and the nearest's glow
    // fades last; clearing, the nearest clears at once and the farthest last.
    const end = far - near + edge * (entering ? 2 : 1)
    const started = performance.now()

    map.setPaintProperty(FILL, 'fill-color', veil.color)
    map.setLayoutProperty(FILL, 'visibility', 'visible')
    map.setLayoutProperty(GLOW, 'visibility', entering ? 'visible' : 'none')

    const frame = (now: number) => {
      const progress = Math.min(1, (now - started) / duration)
      // Closing in, moving from the first frame and slowing as it lands, the edges it starts
      // at being thin; clearing, from the screen's broad middle, at an even pace, which clears
      // the screen at about the rate the fog closes in, where easing out burst it open.
      const reach = end * (entering ? easeOutSine(progress) : progress)
      // How far past each region the sweep is, in edges: 0 as it arrives, 1 once it's through.
      const past: ExpressionSpecification = entering
        ? ['/', ['-', reach, ['-', far, distance]], edge]
        : ['/', ['-', reach, ['-', distance, near]], edge]
      const through = smoothstep(['min', 1, ['max', 0, past]])

      map.setPaintProperty(
        FILL,
        'fill-opacity',
        entering ? ['*', veil.opacity, through] : ['*', veil.opacity, ['-', 1, through]],
      )

      if (entering) {
        // Flaring as the fog arrives and fading once it's through, softly at both ends.
        map.setPaintProperty(GLOW, 'fill-opacity', [
          '*',
          GLOW_PEAK,
          smoothstep(['max', 0, ['-', 1, ['abs', ['-', past, 1]]]]),
        ])
      }

      if (progress < 1) {
        sweepFrame = requestAnimationFrame(frame)
        return
      }

      sweepFrame = undefined
      setSweeping(false)
      settle(map)
    }

    setSweeping(true)
    sweepFrame = requestAnimationFrame(frame)
  }

  /** Puts the fog on the map: once it loads, each time its style does, and on each toggle, swept when `animate`. */
  function sync(animate = false) {
    const map = getMap()

    if (!map || !fog) {
      return
    }

    if (!map.getSource(SOURCE) && !(on && !hidden)) {
      return
    }

    addLayers(map, fog.lit.lit)

    if (opening && on && !hidden) {
      opening = false
      animate = true
    }

    if (animate && !hidden && !reducedMotion.matches) {
      sweep(map, on)
    } else {
      stopSweep()
      settle(map)
    }
  }

  function render(animate = false) {
    if (button) {
      button.ariaPressed = String(on)
    }

    if (areaElement) {
      areaElement.hidden = !on || hidden || !fog
      areaElement.textContent = fog
        ? t(messages, 'fogLit', { cities: fog.lit.cities, provinces: fog.lit.provinces })
        : ''
    }

    sync(animate)
  }

  async function setOn(next: boolean, animate: boolean) {
    on = next

    if (animate) {
      opening = false
    }

    writeFogPreference(on)

    if (on && !fog) {
      switching = true
      render()
      await load()
      switching = false
    }

    render(animate)
  }

  // A toggle mid-sweep would cut it off, and one while the fog first loads would race it;
  // either is let finish first.
  button?.addEventListener(
    'click',
    () => {
      if (sweepFrame === undefined && !switching) {
        void setOn(!on, true)
      }
    },
    { signal },
  )

  if (on) {
    void setOn(true, false)
  } else {
    render()
  }

  return {
    /** Once the map loads, and again each time its style does. */
    add: () => sync(),
    /** Away while a trip plays. */
    setHidden(next: boolean) {
      hidden = next
      render()
    },
  }
}

function easeOutSine(progress: number) {
  return Math.sin((progress * Math.PI) / 2)
}

/** A region's middle, `cx` and `cy`, as the boundaries' script wrote it. */
type RegionCentre = { cx: number; cy: number }

function getRegionCentre(feature: GeoJSON.Feature): RegionCentre {
  return { cx: Number(feature.properties?.cx), cy: Number(feature.properties?.cy) }
}

/** `value`, from 0 to 1, eased in and out: 3v² − 2v³. */
function smoothstep(value: ExpressionSpecification): ExpressionSpecification {
  return [
    'let',
    'v',
    value,
    ['*', ['*', ['var', 'v'], ['var', 'v']], ['-', 3, ['*', 2, ['var', 'v']]]],
  ]
}
