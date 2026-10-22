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
//
// The sweep goes by rings out from the screen's middle, each a layer of its own whose
// regions are set ahead, as the map comes to rest: each frame then only sets a ring's
// opacity, which Mapbox draws at once. An opacity worked out region by region would have
// it lay the regions out again every frame, falling behind, and further behind while it's
// still busy with the map's tiles, as right after the map opens.

const SOURCE = 'trip-map-fog'
const FILL = 'trip-map-fog'
// The rings read the same regions from a source of their own: setting them lays out only
// their source again, leaving the fog at rest drawn as it is meanwhile.
const RING_SOURCE = 'trip-map-fog-rings'
const RINGS = 16
const ring = (index: number) => `trip-map-fog-ring-${index}`
// The cities been to, flaring as the fog reaches them, by the same rings.
const glow = (index: number) => `trip-map-fog-glow-${index}`
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
// How long a sweep waits for its rings to be laid out before it starts anyway.
const RINGS_READY = 500

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
  // Set while the fog is fetched for a toggle, and while a sweep waits for its rings.
  let switching = false
  // On when the page opens, the fog sweeps in the first time it's shown, as for a toggle.
  let opening = on
  // The rings as last set: from where, how near and far the regions in sight were, and
  // whether Mapbox has laid them out since.
  let rings: { far: number; key: string; near: number; ready: boolean } | undefined
  // The map whose moves the rings follow.
  let watched: MapboxMap | undefined

  signal.addEventListener('abort', () => {
    stopSweep()
    watched?.off('moveend', onMoveEnd)
  })

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

  function isLit(): ExpressionSpecification {
    return ['in', ['get', 'id'], ['literal', fog!.lit.lit]]
  }

  /** The fog's layers on the map, once a style: at rest hidden, the rings clear and empty. */
  function addLayers(map: MapboxMap) {
    if (map.getSource(SOURCE)) {
      return
    }

    const beforeId = getBeforeId(map)
    const none: FilterSpecification = ['literal', false]

    map.addSource(SOURCE, { data: fog!.regions, type: 'geojson' })
    map.addSource(RING_SOURCE, { data: fog!.regions, type: 'geojson' })
    rings = undefined

    // Without antialiasing, the cities and the world's tiles meet without a seam; set at
    // once, as the sweep sets each frame's opacity itself.
    const layers: [string, string, FilterSpecification, 'visible' | 'none'][] = [
      [FILL, SOURCE, ['!', isLit()], 'none'],
      ...Array.from(
        { length: RINGS },
        (_, index) => [ring(index), RING_SOURCE, none, 'visible'] as const,
      ),
      ...Array.from(
        { length: RINGS },
        (_, index) => [glow(index), RING_SOURCE, none, 'visible'] as const,
      ),
    ] as [string, string, FilterSpecification, 'visible' | 'none'][]

    for (const [id, source, filter, visibility] of layers) {
      map.addLayer(
        {
          filter,
          id,
          layout: { visibility },
          paint: {
            'fill-antialias': false,
            'fill-opacity': 0,
            'fill-opacity-transition': { duration: 0 },
          },
          source,
          type: 'fill',
        },
        beforeId,
      )
    }

    // Drawn just as the basemap's, so left on the map whether the fog shows or not.
    const water = map
      .getStyle()
      ?.layers?.find((layer) => layer.type === 'fill' && layer['source-layer'] === 'water')

    if (water) {
      map.addLayer({ ...water, id: WATER }, beforeId)
    }

    if (watched !== map) {
      watched?.off('moveend', onMoveEnd)
      watched = map
      map.on('moveend', onMoveEnd)
    }

    prepareRings(map)
  }

  /** The map has come to rest: the rings are set out from where it is, for the next sweep. */
  function onMoveEnd() {
    const map = getMap()

    if (map && fog && map.getSource(SOURCE) && sweepFrame === undefined && !switching) {
      prepareRings(map)
    }
  }

  /**
   * Sets the rings out from the screen's middle as it is now, clear, for the next sweep to
   * start at once. Ready once Mapbox has laid them out.
   */
  function prepareRings(map: MapboxMap) {
    const center = map.getCenter()
    const key = [center.lng, center.lat, map.getZoom(), map.getBearing(), map.getPitch()].join()

    if (rings?.key === key) {
      return
    }

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
    // Zoomed in on a region or two whose middles are off screen, there's no way across to
    // sweep: every region then goes in the first ring, and the fog comes and goes evenly
    // over the whole screen, over the same time.
    const even = inSight.length < 2 || Math.max(...inSight) - Math.min(...inSight) < 1e-6
    const near = even ? 0 : Math.min(...inSight)
    const far = even ? 0 : Math.max(...inSight)
    const width = (far - near) / RINGS
    // Held within what's in sight, so a region whose middle is off screen goes with the
    // ring farthest in sight rather than ahead of the sweep or after it.
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
    const lit = isLit()

    for (let index = 0; index < RINGS; index += 1) {
      const from = index === 0 ? near - 1 : near + index * width
      const to = index === RINGS - 1 ? far + 1 : near + (index + 1) * width
      const inRing: ExpressionSpecification = even
        ? ['literal', index === 0]
        : ['all', ['>=', distance, from], ['<', distance, to]]

      map.setFilter(ring(index), ['all', ['!', lit], inRing])
      map.setFilter(glow(index), ['all', lit, inRing])
      map.setPaintProperty(ring(index), 'fill-opacity', 0)
      map.setPaintProperty(glow(index), 'fill-opacity', 0)
    }

    const prepared = { far, key, near, ready: false }
    rings = prepared
    map.once('idle', () => {
      prepared.ready = true
    })
  }

  /** The fog as it rests: shown or not, the rings clear. */
  function settle(map: MapboxMap) {
    const shown = on && !hidden
    const veil = getVeil()

    map.setPaintProperty(FILL, 'fill-color', veil.color)
    map.setPaintProperty(FILL, 'fill-opacity', shown ? veil.opacity : 0)
    map.setLayoutProperty(FILL, 'visibility', shown ? 'visible' : 'none')

    for (let index = 0; index < RINGS; index += 1) {
      map.setPaintProperty(ring(index), 'fill-opacity', 0)
      map.setPaintProperty(glow(index), 'fill-opacity', 0)
    }
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
   * as it's reached; or out, from the middle to the edges. The rings are set ahead where
   * the map allows; while they're laid out, the fog at rest stands in, and it takes over
   * again in the same frame as they go.
   */
  function sweep(map: MapboxMap, entering: boolean) {
    stopSweep()
    prepareRings(map)
    setSweeping(true)

    const prepared = rings!
    const veil = getVeil()
    const waited = performance.now()

    // Laid out under the rings, clear, to take over from them once they're through.
    map.setPaintProperty(FILL, 'fill-color', veil.color)
    map.setLayoutProperty(FILL, 'visibility', 'visible')

    for (let index = 0; index < RINGS; index += 1) {
      map.setPaintProperty(ring(index), 'fill-color', veil.color)
      map.setPaintProperty(glow(index), 'fill-color', GLOW_COLOR)
    }

    const start = () => {
      const { near, far } = prepared
      const edge = far > near ? (far - near) * EDGE : 1
      const width = (far - near) / RINGS
      const duration = entering ? SWEEP.in : SWEEP.out
      // Closing in, the farthest region in sight is reached at once and the nearest's
      // glow fades last; clearing, the nearest clears at once and the farthest last.
      const end = far - near + edge * (entering ? 2 : 1)
      const started = performance.now()

      // Clearing, the rings take over from the fog at rest in one frame.
      if (!entering) {
        for (let index = 0; index < RINGS; index += 1) {
          map.setPaintProperty(ring(index), 'fill-opacity', veil.opacity)
        }

        map.setPaintProperty(FILL, 'fill-opacity', 0)
      }

      const frame = (now: number) => {
        const progress = Math.min(1, (now - started) / duration)
        // Closing in, moving from the first frame and slowing as it lands, the edges it
        // starts at being thin; clearing, from the screen's broad middle, at an even pace,
        // which clears the screen at about the rate the fog closes in, where easing out
        // burst it open.
        const reach = end * (entering ? easeOutSine(progress) : progress)

        for (let index = 0; index < RINGS; index += 1) {
          const distance = near + (index + 0.5) * width
          // How far past the ring the sweep is, in edges: 0 as it arrives, 1 once it's through.
          const past = entering
            ? (reach - (far - distance)) / edge
            : (reach - (distance - near)) / edge
          const through = smoothstep(past)

          map.setPaintProperty(
            ring(index),
            'fill-opacity',
            veil.opacity * (entering ? through : 1 - through),
          )

          if (entering) {
            // Flaring as the fog arrives and fading once it's through, softly at both ends.
            map.setPaintProperty(
              glow(index),
              'fill-opacity',
              GLOW_PEAK * smoothstep(1 - Math.abs(past - 1)),
            )
          }
        }

        if (progress < 1) {
          sweepFrame = requestAnimationFrame(frame)
          return
        }

        sweepFrame = undefined
        setSweeping(false)
        settle(map)
      }

      sweepFrame = requestAnimationFrame(frame)
    }

    // Waits for the rings to be laid out, a frame at a time, or `RINGS_READY` at most.
    const wait = () => {
      if (prepared.ready || performance.now() - waited > RINGS_READY) {
        start()
        return
      }

      sweepFrame = requestAnimationFrame(wait)
    }

    sweepFrame = requestAnimationFrame(wait)
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

    addLayers(map)

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

/** `value`, held from 0 to 1 and eased in and out: 3v² − 2v³. */
function smoothstep(value: number) {
  const v = Math.min(1, Math.max(0, value))
  return v * v * (3 - 2 * v)
}
