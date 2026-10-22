import type {
  ExpressionSpecification,
  LayerSpecification,
  LngLatBoundsLike,
  Map as MapboxMap,
  PaddingOptions,
  SourceSpecification,
} from 'mapbox-gl'
import type { MapSettings } from './map-settings.ts'

// The map in 3D: Mapbox Standard, with terrain, buildings and landmarks of its own, lit
// for the time of day. Its basemap comes in as an import, out of reach of the package's
// layers, which go into one of its slots instead of before one of its layers.
export const STANDARD_STYLE = 'mapbox://styles/mapbox/standard'
const BASEMAP = 'basemap'
// In 3D the map leans back the further the closer it is when it frames places or a trip:
// flat over a country, where leaning only hides the far side under the horizon, and well
// back over a city's streets, where the land and the buildings stand up. By zoom.
const PITCHES: readonly (readonly [zoom: number, pitch: number])[] = [
  [3, 0],
  [6, 20],
  [9, 40],
  [13, 55],
]
const MAP_PITCH = PITCHES.at(-1)![1]
// Every look but the flat one leans, up to `MAP_PITCH`.

// The looks a viewer may pick for the map: Standard in its three colour themes, or the
// plain light and dark map, flat, which asks the least of a slow device.
export const MAP_STYLES = ['standard', 'faded', 'monochrome', 'classic'] as const

export type MapStyleId = (typeof MAP_STYLES)[number]

export function isMapStyleId(value: unknown): value is MapStyleId {
  return MAP_STYLES.includes(value as MapStyleId)
}

/**
 * The style for `settings` in a colour scheme, Standard's own settings if it's Standard,
 * and how far it leans at most.
 */
export function getBasemapStyle(settings: MapSettings, dark: boolean) {
  if (settings.style === 'classic') {
    return {
      config: undefined,
      pitch: 0,
      url: dark ? 'mapbox://styles/mapbox/dark-v11' : 'mapbox://styles/mapbox/light-v11',
    }
  }

  return {
    config: { [BASEMAP]: getStandardConfig(settings, dark) },
    pitch: settings.tilt ? MAP_PITCH : 0,
    url: STANDARD_STYLE,
  }
}

/** Whether the map is lit dark: by night or at dusk, or by the colour scheme. */
export function isBasemapDark(settings: MapSettings, dark: boolean) {
  if (settings.style === 'classic' || settings.light === 'auto') {
    return dark
  }

  return settings.light === 'dusk' || settings.light === 'night'
}

/** Standard's settings for `settings`: its colour theme, light and what it shows. */
function getStandardConfig(settings: MapSettings, dark: boolean) {
  const landmarks = settings.landmarks

  return {
    lightPreset: settings.light === 'auto' ? (dark ? 'night' : 'day') : settings.light,
    show3dObjects: settings.buildings,
    showLandmarkIconLabels: landmarks,
    showLandmarkIcons: landmarks,
    // Footpaths only crowd the package's places.
    showPedestrianRoads: false,
    showPointOfInterestLabels: settings.pois,
    showRoadLabels: settings.roads,
    showTransitLabels: settings.transit,
    theme: settings.style === 'standard' ? 'default' : settings.style,
  }
}

/** Changes Standard's settings on `map` to `config`'s, without building it again. */
export function setBasemapConfig(
  map: MapboxMap,
  config: ReturnType<typeof getBasemapStyle>['config'],
) {
  for (const [key, value] of Object.entries(config?.[BASEMAP] ?? {})) {
    if (map.getConfigProperty(BASEMAP, key) !== value) {
      map.setConfigProperty(BASEMAP, key, value)
    }
  }
}

// How far each map leans at most, as its style was last set: read every frame while a
// trip plays, where reading the style itself would cost too much.
const pitches = new WeakMap<MapboxMap, number>()

export function setMapPitch(map: MapboxMap, pitch: number) {
  pitches.set(map, pitch)
}

/** How far a map that leans up to `most` leans at `zoom`. */
export function getPitchAtZoom(zoom: number, most = MAP_PITCH) {
  const above = PITCHES.findIndex(([stop]) => stop > zoom)
  const [lowZoom, lowPitch] = PITCHES[Math.max(0, above - 1)]!
  const [highZoom, highPitch] = above === -1 ? PITCHES.at(-1)! : PITCHES[above]!
  const share =
    highZoom === lowZoom ? 1 : Math.min(1, Math.max(0, (zoom - lowZoom) / (highZoom - lowZoom)))

  return ((lowPitch + (highPitch - lowPitch) * share) * most) / MAP_PITCH
}

/** How far `map` leans at `zoom` when it frames places or a trip. */
export function getMapPitch(map: MapboxMap, zoom: number) {
  return getPitchAtZoom(zoom, pitches.get(map) ?? MAP_PITCH)
}

/** How far `map` leans framing `bounds`: as it would at the zoom that frames them flat. */
export function getFramingPitch(
  map: MapboxMap,
  bounds: LngLatBoundsLike,
  options: { maxZoom?: number; padding?: number | PaddingOptions },
) {
  const flat = map.cameraForBounds(bounds, { ...options, pitch: 0 })
  return getMapPitch(map, flat?.zoom ?? map.getZoom())
}

function getBasemap(map: MapboxMap) {
  return map.getStyle()?.imports?.find((basemap) => basemap.id === BASEMAP)
}

/**
 * Where a layer goes to be over the roads and under the basemap's names: Standard's
 * middle slot, or before the first of another style's names.
 */
export function getUnderLabels(map: MapboxMap): { beforeId?: string; slot?: 'middle' } {
  if (getBasemap(map)) {
    return { slot: 'middle' }
  }

  const beforeId = map.getStyle()?.layers?.find((layer) => layer.type === 'symbol')?.id
  return beforeId ? { beforeId } : {}
}

/**
 * The basemap's water, to draw again over a layer of the package's: in another style its
 * own layer, from its own source; in Standard a copy of its layer, its settings filled in,
 * reading the same tiles from a source of the package's, `source`.
 */
export function getBasemapWater(
  map: MapboxMap,
  source: string,
):
  | { layer: Extract<LayerSpecification, { type: 'fill' }>; source?: SourceSpecification }
  | undefined {
  const basemap = getBasemap(map)
  const layers = basemap ? basemap.data?.layers : map.getStyle()?.layers
  const water = layers?.find(
    (layer): layer is Extract<LayerSpecification, { type: 'fill' }> =>
      layer.type === 'fill' && (layer.id === 'water' || layer['source-layer'] === 'water'),
  )

  if (!basemap || !water) {
    return water && { layer: water }
  }

  const from = basemap.data?.sources?.[water.source as string]
  const url = from && 'url' in from ? from.url : undefined

  if (!url) {
    return undefined
  }

  // Its settings are the basemap's, unknown outside it: each is put in as it is now.
  const config = (value: unknown): unknown => {
    if (!value || typeof value !== 'object') {
      return value
    }

    if (!Array.isArray(value)) {
      return Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, config(entry)]))
    }

    if (value[0] === 'config' && typeof value[1] === 'string') {
      return map.getConfigProperty(BASEMAP, value[1]) as unknown
    }

    return value.map(config)
  }

  const { filter, paint, ...rest } = water

  return {
    layer: {
      ...rest,
      slot: 'middle',
      source,
      ...(filter && { filter: config(filter) as ExpressionSpecification }),
      ...(paint && { paint: config(paint) as NonNullable<typeof paint> }),
    },
    source: { type: 'vector', url },
  }
}

/**
 * Hides the basemap's names but for countries' and states', for a picture of the places;
 * the returned function shows them again.
 */
export function hideBasemapLabels(map: MapboxMap, kept: RegExp) {
  if (getBasemap(map)) {
    // Standard's place names are one setting with the countries', so they stay.
    const settings = [
      'showLandmarkIconLabels',
      'showPointOfInterestLabels',
      'showRoadLabels',
      'showTransitLabels',
    ]
    const shown = settings.filter((setting) => map.getConfigProperty(BASEMAP, setting) !== false)

    for (const setting of shown) {
      map.setConfigProperty(BASEMAP, setting, false)
    }

    return () => {
      for (const setting of shown) {
        map.setConfigProperty(BASEMAP, setting, true)
      }
    }
  }

  const hidden: string[] = []

  for (const layer of map.getStyle()?.layers ?? []) {
    if (
      layer.type === 'symbol' &&
      !layer.id.startsWith('calendar') &&
      !kept.test(layer.id) &&
      map.getLayoutProperty(layer.id, 'visibility') !== 'none'
    ) {
      map.setLayoutProperty(layer.id, 'visibility', 'none')
      hidden.push(layer.id)
    }
  }

  return () => {
    for (const id of hidden) {
      map.setLayoutProperty(id, 'visibility', 'visible')
    }
  }
}
