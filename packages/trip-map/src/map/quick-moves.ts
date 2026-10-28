import type { Map as MapboxMap, PaddingOptions } from 'mapbox-gl'
import type { MapElements } from '../elements.ts'
import type { Messages } from '../messages.ts'
import { getMapPitch } from './basemap.ts'
import type { MapCamera } from './map-camera.ts'
import { createUserDot, getUserPosition } from './user-location.ts'

// The toolbar's quick moves: back to the view the map opened on, every place framed facing
// north, and to where the viewer is, marked with a dot. Each is out of the way while it
// would do nothing.

export function createQuickMoves({
  camera,
  elements,
  getClearPadding,
  getMap,
  isPlaying,
  messages,
  onError,
  signal,
}: {
  camera: MapCamera
  elements: MapElements
  /** Room kept on the map for what's open on its side. */
  getClearPadding: () => Required<PaddingOptions>
  /** The map, once it has loaded. */
  getMap: () => MapboxMap | undefined
  /** Whether a trip's camera has the map. */
  isPlaying: () => boolean
  messages: Messages
  onError: (cause: unknown) => void
  signal: AbortSignal
}) {
  const { locateButton, resetViewButton } = elements
  const locateLabel = locateButton?.title ?? ''
  const canLocate = 'geolocation' in navigator
  let userDot: ReturnType<typeof createUserDot> | undefined
  // Whether the map is still where locating put it, untouched since.
  let atUser = false

  /**
   * Each quick move is out of the way while it would do nothing: the reset while every
   * place is framed facing north, as the map opens, and the locate button while the map
   * is where it last flew to, until something moves it.
   */
  function sync() {
    const framed = camera.framedOnAll && Math.abs(getMap()?.getBearing() ?? 0) < 0.5

    setToolHidden(resetViewButton, framed)
    setToolHidden(locateButton, !canLocate || atUser)
  }

  /** Hides a toolbar button, passing its focus on to the settings, where the keyboard was. */
  function setToolHidden(button: HTMLButtonElement | null, hidden: boolean) {
    if (!button || button.hidden === hidden) {
      return
    }

    const focused = button === document.activeElement
    button.hidden = hidden

    if (focused) {
      elements.settingsOpeners[0]?.focus()
    }
  }

  /** Flies the map to where the viewer is, asking the browser, and marks it with a dot. */
  async function locateUser() {
    if (!getMap() || !locateButton || locateButton.ariaBusy === 'true') {
      return
    }

    locateButton.ariaBusy = 'true'

    try {
      const { coords } = await getUserPosition()
      const map = getMap()

      if (signal.aborted || !map || isPlaying()) {
        return
      }

      const center: [number, number] = [coords.longitude, coords.latitude]
      const zoom = Math.max(map.getZoom(), 12)

      userDot ??= createUserDot(map, messages.userLocation)
      userDot.set(center)
      locateButton.title = locateLabel
      camera.moveByHand()
      atUser = true
      map.easeTo(
        {
          center,
          duration: camera.getAnimationDuration(),
          // In the middle of what's clear of whatever is open on the map's side.
          padding: getClearPadding(),
          pitch: camera.pitchedByHand ? map.getPitch() : getMapPitch(map, zoom),
          zoom,
        },
        // Told apart from every other move, which takes the map off the viewer.
        { tripMapLocate: true },
      )
    } catch (cause) {
      // Refused, or not found: the button says so until it's pressed again.
      locateButton.title = messages.locateFailed
      onError(cause)
    } finally {
      locateButton.ariaBusy = null
    }
  }

  // Back to the view the map opened on: every place shown framed, facing north.
  resetViewButton?.addEventListener(
    'click',
    () => {
      if (getMap() && !isPlaying()) {
        camera.fitAll({ upright: true })
      }
    },
    { signal },
  )

  sync()

  locateButton?.addEventListener('click', () => void locateUser(), { signal })

  return {
    /** Follows the map's moves; after the camera's own handlers, which a move by hand changes. */
    attach(map: MapboxMap) {
      map.on('movestart', (event) => {
        if (!(event as { tripMapLocate?: boolean }).tripMapLocate) {
          atUser = false
        }

        sync()
      })
      map.on('moveend', sync)
      map.on('wheel', () => {
        atUser = false
        sync()
      })
    },
  }
}
