import mapboxgl from 'mapbox-gl'

// Where the viewer is: asked of the browser when they press the locate button, and
// shown as a dot on the map until the page goes.

/** The viewer's position, asking them the first time; rejects if refused or not found. */
export function getUserPosition() {
  return new Promise<GeolocationPosition>((resolve, reject) => {
    if (!('geolocation' in navigator)) {
      reject(new Error('Geolocation is not supported.'))
      return
    }

    navigator.geolocation.getCurrentPosition(resolve, reject, {
      enableHighAccuracy: true,
      maximumAge: 60_000,
      timeout: 15_000,
    })
  })
}

/** A dot at the viewer's position on `map`, put there or moved by `set`. */
export function createUserDot(map: mapboxgl.Map, label: string) {
  let marker: mapboxgl.Marker | undefined

  return {
    remove() {
      marker?.remove()
      marker = undefined
    },
    set(lngLat: [number, number]) {
      if (!marker) {
        const element = document.createElement('div')
        element.className = 'trip-map-user-location'
        element.role = 'img'
        element.ariaLabel = label
        marker = new mapboxgl.Marker({ element }).setLngLat(lngLat).addTo(map)
      } else {
        marker.setLngLat(lngLat)
      }
    },
  }
}
