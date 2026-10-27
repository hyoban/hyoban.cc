import mapboxgl, { type CustomLayerInterface, type Map as MapboxMap } from 'mapbox-gl'
import { getTurn, type Coordinates } from '../geo.ts'

// A car in 3D at the route's tip while flying over, in place of the car's icon: a glTF
// model, drawn into the map's own WebGL context by three.js, standing on the terrain and
// facing the way the route goes. It's drawn far larger than life, or at the zooms the
// camera flies at it would be a speck, growing and shrinking with the zoom at half the
// map's pace, within bounds.
//
// The model is placed by a matrix every frame rather than as a feature of a source, so
// moving it costs the map nothing (see route-layers.ts). three.js and the model are
// fetched only once the car is first asked to show: a page that never flies over
// doesn't download them.
//
// Its lamps, the strips across its nose and its tail, are on when the map round it is dark,
// and when the trip's clock beside it is at dusk or by night; off by day.

const LAYER_ID = 'trip-map-car'
/** How long the car is on the screen at `CAR_ZOOM`, in pixels, and its bounds at others. */
const CAR_PIXELS = 56
const CAR_ZOOM = 9
const CAR_MIN_PIXELS = 40
const CAR_MAX_PIXELS = 160

/** How long the car is on the screen at `zoom`, in pixels. */
export function getCarPixels(zoom: number) {
  const pixels = CAR_PIXELS * 2 ** ((zoom - CAR_ZOOM) / 2)
  return Math.min(Math.max(pixels, CAR_MIN_PIXELS), CAR_MAX_PIXELS)
}
/** Seconds for the car to swing round to a new heading, so a twisting road doesn't shake it. */
const TURN_SETTLE = 0.4

/** Whether playback flies over the route, and the car in 3D that drives it then, if any. */
export type Flyover = { car?: CarModel | undefined; isOn: () => boolean }

type Three = typeof import('three')
type Loaded = {
  camera: import('three').Camera
  /** The materials that glow, with how brightly they glow when lit. */
  lamps: Map<import('three').MeshStandardMaterial, number>
  /** The model's length, front to back, in its own units. */
  length: number
  model: import('three').Object3D
  RoomEnvironment: typeof import('three/examples/jsm/environments/RoomEnvironment.js').RoomEnvironment
  scene: import('three').Scene
  // Reused every frame rather than made anew.
  scratch: {
    eye: import('three').Vector4
    place: import('three').Matrix4
    scale: import('three').Vector3
    unproject: import('three').Matrix4
  }
  /** Stands the model, lying along the map, up off it. */
  standUp: import('three').Matrix4
  three: Three
}

export type CarModel = ReturnType<typeof createCarModel>

/**
 * `url` is a glTF model of a car, standing on y = 0, its nose to +z; Meshopt compression is
 * understood. `isDark` tells whether the map is lit dark.
 */
export function createCarModel(url: string, isDark: () => boolean) {
  let loading: Promise<Loaded | undefined> | undefined
  let loaded: Loaded | undefined
  let renderer: import('three').WebGLRenderer | undefined
  let added: { context: WebGL2RenderingContext; map: MapboxMap } | undefined
  let tip: { coordinates: Coordinates; heading: number } | undefined
  // Where the car is pointing now, easing toward the tip's heading.
  let heading: number | undefined
  let lastTime: number | undefined
  // The trip's clock at the tip is at dusk or by night.
  let night = false

  function load() {
    loading ??= (async () => {
      const [three, { GLTFLoader }, { MeshoptDecoder }, { RoomEnvironment }, data] =
        await Promise.all([
          import('three'),
          import('three/examples/jsm/loaders/GLTFLoader.js'),
          import('three/examples/jsm/libs/meshopt_decoder.module.js'),
          import('three/examples/jsm/environments/RoomEnvironment.js'),
          fetch(url).then((response) => response.arrayBuffer()),
        ])
      const gltf = await new GLTFLoader().setMeshoptDecoder(MeshoptDecoder).parseAsync(data, '')
      const model = gltf.scene

      const lamps = new Map<import('three').MeshStandardMaterial, number>()

      // Glass that lets the inside show through takes a pass of its own, which can't be
      // made in the map's context; at this size, tinted glass that reflects reads the same.
      model.traverse((object) => {
        if (!(object instanceof three.Mesh)) {
          return
        }

        for (const material of [object.material].flat()) {
          if (material instanceof three.MeshPhysicalMaterial && material.transmission > 0) {
            material.transmission = 0
            material.color.set(0x14171a)
            material.transparent = true
            material.opacity = 0.82
          } else if (
            material instanceof three.MeshStandardMaterial &&
            material.emissive.getHex() !== 0
          ) {
            lamps.set(material, material.emissiveIntensity)
          }
        }
      })
      const box = new three.Box3().setFromObject(model)
      const center = box.getCenter(new three.Vector3())

      // About the ground under its middle, so it turns on the spot.
      model.position.sub(new three.Vector3(center.x, box.min.y, center.z))

      const scene = new three.Scene()
      const turntable = new three.Group().add(model)

      scene.add(turntable)
      loaded = {
        camera: new three.Camera(),
        lamps,
        length: box.getSize(new three.Vector3()).z,
        model: turntable,
        RoomEnvironment,
        scene,
        scratch: {
          eye: new three.Vector4(),
          place: new three.Matrix4(),
          scale: new three.Vector3(),
          unproject: new three.Matrix4(),
        },
        standUp: new three.Matrix4().makeRotationX(Math.PI / 2),
        three,
      }
      prepare()
      return loaded
    })().catch(() => undefined)

    return loading
  }

  /** Sets three.js up on the map's context, once both the model and the layer are there. */
  function prepare() {
    if (!loaded || !added || renderer) {
      return
    }

    const { RoomEnvironment, scene, three } = loaded
    const { context, map } = added

    renderer = new three.WebGLRenderer({ antialias: true, canvas: map.getCanvas(), context })
    renderer.autoClear = false
    renderer.outputColorSpace = three.SRGBColorSpace
    renderer.toneMapping = three.NeutralToneMapping

    // Lit as in a studio, as the year review shows it, whatever the map's light.
    const pmrem = new three.PMREMGenerator(renderer)
    const room = new RoomEnvironment()

    scene.environment?.dispose()
    scene.environment = pmrem.fromScene(room, 0.04).texture
    room.dispose()
    pmrem.dispose()
    map.triggerRepaint()
  }

  const layer: CustomLayerInterface = {
    id: LAYER_ID,
    onAdd(map, gl) {
      renderer = undefined
      added = { context: gl as WebGL2RenderingContext, map }
      prepare()
    },
    onRemove() {
      renderer?.dispose()
      renderer = undefined
      added = undefined
    },
    render(gl, matrix) {
      const map = added?.map

      if (!map || !renderer || !loaded || !tip) {
        return
      }

      const now = performance.now()
      const seconds = Math.min((now - (lastTime ?? now)) / 1000, 0.1)

      lastTime = now
      heading =
        heading === undefined
          ? tip.heading
          : heading + getTurn(heading, tip.heading) * (1 - Math.exp(-seconds / TURN_SETTLE))

      const { camera, lamps, length, model, scene, scratch, standUp } = loaded
      const lit = night || isDark()

      for (const [material, intensity] of lamps) {
        material.emissiveIntensity = lit ? intensity : 0
      }

      const [longitude, latitude] = tip.coordinates
      const ground = map.queryTerrainElevation([longitude, latitude]) ?? 0
      const at = mapboxgl.MercatorCoordinate.fromLngLat([longitude, latitude], ground)
      const zoom = map.getZoom()
      const scale = getCarPixels(zoom) / (512 * 2 ** zoom * length)

      // Its nose, +z, turned from south (where the flip of y below points it) to the heading.
      model.rotation.y = Math.PI - (heading * Math.PI) / 180
      const projection = camera.projectionMatrix.fromArray(matrix).multiply(
        scratch.place
          .makeTranslation(at.x, at.y, at.z ?? 0)
          .scale(scratch.scale.set(scale, -scale, scale))
          .multiply(standUp),
      )
      // The map's eye, in the model's own space: the point the projection sends to w = 0.
      // three.js shades from the camera's place, so it's set there, and the projection takes
      // that step back out; left at the model's middle, its paint would catch the light as
      // if seen from inside it.
      const eye = scratch.eye
        .set(0, 0, 1, 0)
        .applyMatrix4(scratch.unproject.copy(projection).invert())
      camera.position.set(eye.x / eye.w, eye.y / eye.w, eye.z / eye.w)
      camera.updateMatrixWorld()
      camera.matrixWorldInverse.copy(camera.matrixWorld).invert()
      projection.multiply(camera.matrixWorld)
      camera.projectionMatrixInverse.copy(projection).invert()
      renderer.resetState()
      // The canvas as it is now: three.js only measured it when it was set up, before any
      // resize.
      renderer.setViewport(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight)
      renderer.render(scene, camera)

      // Still turning: draw again next frame though the map itself stands still.
      if (Math.abs(getTurn(heading, tip.heading)) > 0.1) {
        map.triggerRepaint()
      }
    },
    renderingMode: '3d',
    type: 'custom',
  }

  /** Adds the layer to `map`, also after its style changes and drops it. */
  function add(map: MapboxMap) {
    if (!map.getLayer(LAYER_ID)) {
      map.addLayer(layer)
    }
  }

  function remove(map: MapboxMap | undefined) {
    if (map?.getLayer(LAYER_ID)) {
      map.removeLayer(LAYER_ID)
    }

    tip = undefined
    heading = undefined
  }

  /**
   * Shows the car at `coordinates`, facing `heading` (degrees from north), or hides it. The
   * model's fetched the first time it's asked for; the map draws again once it's ready.
   */
  function set(next: { coordinates: Coordinates; heading: number } | undefined) {
    const moved =
      next?.heading !== tip?.heading ||
      next?.coordinates[0] !== tip?.coordinates[0] ||
      next?.coordinates[1] !== tip?.coordinates[1]

    if (next) {
      void load()
    } else {
      heading = undefined
    }

    tip = next

    // Drawn again only when it moved: every frame otherwise, the map could never go idle.
    if (moved && loaded && renderer) {
      added?.map.triggerRepaint()
    }
  }

  /** Lights its lamps, or not, by whether the trip's clock at the tip is at dusk or by night. */
  function setNight(next: boolean) {
    if (next !== night) {
      night = next

      if (tip && loaded && renderer) {
        added?.map.triggerRepaint()
      }
    }
  }

  /** Whether the car is on the map now. */
  function isShowing() {
    return Boolean(tip && loaded && renderer)
  }

  return { add, isShowing, remove, set, setNight }
}
