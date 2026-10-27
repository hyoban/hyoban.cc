import {
  Box3,
  BufferAttribute,
  BufferGeometry,
  CanvasTexture,
  Color,
  CubeCamera,
  Group,
  HalfFloatType,
  LinearMipmapLinearFilter,
  MathUtils,
  Mesh,
  Object3D,
  MeshBasicMaterial,
  MeshPhysicalMaterial,
  NeutralToneMapping,
  PMREMGenerator,
  PerspectiveCamera,
  PlaneGeometry,
  SRGBColorSpace,
  Scene,
  Sprite,
  Texture,
  Timer,
  Vector2,
  Vector3,
  WebGLCubeRenderTarget,
  WebGLRenderer,
} from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { MeshoptDecoder } from 'three/examples/jsm/libs/meshopt_decoder.module.js'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js'
import { createGlow, createLightLayer, findLamps, setLamps } from './car-model-lamps.ts'
import { createCarSound, mountSoundToggle } from './car-model-sound.ts'
import {
  createFloor,
  createSpeedometer,
  createTunnel,
  reflectStreaks,
  smoothstep,
} from './car-model-speed.ts'
import { createDrift } from './car-model-drift.ts'
import { createOdometer, createRoutes, readCarYear } from './car-model-year.ts'

// The car standing on its own shadow, under a studio's light, never turning on its own. It
// can be swung round by dragging sideways, a drag coasting to rest, but not zoomed or tipped over, and a vertical drag still scrolls the
// page. It draws nothing while out of sight.
//
// The first time it comes into view it drifts in with the scroll: it rolls forward as the
// page moves on and back as it moves back, its wheels rolling as it goes, sliding in with its
// tail out, smoking and marking the ground, and caught straight to come to a stop once its
// frame is just past the middle of the screen, its front wheels left turned. There its nose dips as it brakes and
// settles back; after that the scroll leaves it be. Under reduced motion, or when it's
// already in view by the time it has loaded, it's simply there.
//
// Any press sets its wheels rolling and draws the camera in, letting it back out once it's
// let go. A drag begun straight away only turns it in its place; held a moment first, it
// speeds off, after Xiaomi SU7's showroom at gamemcu.com/su7, and from then on a drag swings
// it round as it goes: the page goes dark around it and the car stands on a glossy black
// floor, its wheels spin up, the camera closes in as the view widens so the car keeps about
// its size while the perspective stretches, and it shakes a little; dragged up or down, the
// view tilts with it; once it's going, light streaks rush past through a tunnel, glancing
// off its paint and the floor. Let go, the streaks fall away first and the rest eases back
// after them. A press the page takes back to scroll ends it at once. The canvas covers the
// screen for as long as that lasts, the car gliding to its middle and back to its place as
// it goes, its speed and how far it's drifting shown at the top of the screen. Dragged across
// slowly once it's going, the view swings round it; fast, it drifts instead, the view held
// still, its tail out, its body leaning and its front wheels turned back against the slide,
// its rear tyres smoking and marking the floor, springing back straight once the drag slows.
// Not under reduced motion. The streaks, the floor and the speed are in src/car-model-speed.ts,
// the smoke and the marks in src/car-model-drift.ts.
//
// Its lamps are off by day and on in the dark: when the page is dark, and as it goes dark
// around the car speeding off. A click, let go before the car could speed off and without
// moving, switches them off, or on by day, until the page turns dark or light. They're in
// src/car-model-lamps.ts.
//
// A button at its corner turns its sound on, and its music with it, off until then. They're in
// src/car-model-sound.ts.
//
// In a year review it stands on the year's driving, and drives through the year a day at a
// time as it speeds off. As the page scrolls through the year (src/components/YearDriving.astro)
// it drives on with it, its wheels rolling and its ground sliding back under it. They're in src/car-model-year.ts.

/** How far above the car the camera looks down, from the horizon. */
const ELEVATION = (14 * Math.PI) / 180
const FOV = 26
/** Where the camera starts round the car: the front three-quarter view, the way the car is
 * usually photographed. */
const START_AZIMUTH = Math.PI / 5

/** The run in, as a share of the car's length. */
const DRIVE_IN = 0.8
/** Where on the screen, from the top, the frame's top is when the car sets off, and its
 * middle is when it stops: just past the screen's middle. */
const START_AT = 0.9
const STOP_AT = 0.42
/** Driving in, how far its tail is out, sliding in, and how far along the run in it starts to
 * be caught straight again. */
const ENTRY_YAW = (32 * Math.PI) / 180
const ENTRY_CATCH = 0.45
/** How fast, in its lengths a second, it has to be carried in to slide its furthest, and the
 * seconds that's smoothed over, since the scroll moves it in steps. */
const ENTRY_SLIDE_SPEED = 0.4
const ROLLED_FOLLOW = 0.15
/** How far its front wheels are left turned against the slide once it has stopped, and the
 * seconds they take to straighten once it drives on. */
const PARK_STEER = (22 * Math.PI) / 180
const PARK_STEER_FOLLOW = 0.3
/** How far the nose dips at the stop, and how the springs settle after it. */
const DIP = (1.6 * Math.PI) / 180
const SETTLE_DECAY_MS = 180
const SETTLE_PERIOD_MS = 560

/** How long a press must stay put before the car speeds off, how far it may wander in that
 * time and until the speed has come up this far, before it's taken as a drag that only
 * turns the car instead, and how fast the wheels roll for any press. */
const HOLD_MS = 150
const HOLD_SLOP = 6
const ENGAGE = 0.5
const ROLL = 3
/** In a year review, how many radians its wheels turn driving through the whole year, the
 * fastest they turn as it does, in radians a second, and how far its ground slides back
 * under it meanwhile, in its lengths. */
const YEAR_ROLL = 60
const CRUISE_ROLL = 8
const YEAR_SLIDE = 0.8
/** At speed, how fast a drag across has to be, in radians a second as it would swing the view
 * round, for the car to start drifting rather than the view turning, and to drift its
 * furthest; how far its tail swings out then; how far its body leans out with it and its
 * front wheels turn back against it, past the way it's going so it's plain to see, as shares
 * of that; how long the drag's speed is smoothed over, in seconds; how stiff and damped the
 * spring is that brings it back straight, a little past and back; and how near straight, in
 * radians, it's taken to be back, the view let turn again. */
const DRIFT_FROM = 5
const DRIFT_FULL = 12
const DRIFT_YAW = (30 * Math.PI) / 180
const DRIFT_LEAN = 0.12
const COUNTER_STEER = 1.3
const SLIP_FOLLOW = 0.08
const DRIFT_STIFFNESS = 60
const DRIFT_DAMPING = 9
const DRIFT_SETTLED = 0.01
/** The longest step, in seconds, the springs take at once, so a stalled frame doesn't fling
 * them. */
const MAX_STEP = 0.05
/** How much nearer the camera comes for as long as the car's pressed, drawing back once it's
 * let go, as the SU7's does, so that even a click is felt. */
const PUSH_IN = 0.12
/** Seconds the speed takes to come up, and to fall away once it's let go. */
const SPEED_UP = 0.7
const SLOW_DOWN = 0.45
/** How fast the streaks follow the speed up, and how much faster they fall away. */
const RUSH_UP = 2
const RUSH_DOWN = 6
/** How many degrees the view widens at full speed, and how much farther back the camera
 * stays than would keep the car its size, so its nearer side doesn't loom out of the frame. */
const SPEED_FOV = 12
const HOLD_BACK = 0.04
/** How far above the car the camera looks down at full speed, and how low and high a
 * vertical drag may then tilt it. */
const SPEED_ELEVATION = (3 * Math.PI) / 180
const TILT_LOW = 0
const TILT_HIGH = (20 * Math.PI) / 180
/** How far the camera shakes at full speed, in pixels. */
const SHAKE = 2.5
/** How dark the page goes at full speed, and how much of the studio's light the car loses
 * by dark, the page's or at speed: most, so it's its lamps that shine, but its paint's colour
 * still plainly seen. */
const NIGHT = 0.92
const VEIL = 0.8
/** How brightly the paint and glass mirror the streaks at full speed. */
const GLINT = 2
/** How much darker the glass is by day, tinted as a car's is, so what's behind it doesn't
 * show through as white. */
const DAY_TINT = 0.6
/** How much darker the glass is by dark, no longer seen through. */
const DARK_TINT = 0.92
/** How much less of the studio's light the glass mirrors by dark than the rest of the car
 * does, near mirror as it is. */
const GLASS_GLARE = 0.8
/** How rough the paint is at full speed, polished from its satin. */
const POLISH = 0.08
/** Seconds the lamps take to come on, or go off. */
const LAMPS_FADE = 0.12

/** easeOutCubic: the scroll's distance laid out so the car slows as it nears its stop. */
const easeOut = cubicBezier(0.33, 1, 0.68, 1)
const easeInOut = cubicBezier(0.77, 0, 0.175, 1)

// The models are kept in the page's own cache once fetched: the full one is more than some
// browsers' HTTP caches will hold, so would come down again on every visit. A changed model
// gets a new name, so what's kept never goes stale, and what the page no longer names is let
// go. Without the cache (outside a secure context), they're just fetched.
const MODEL_CACHE = 'car-models'

async function openModelCache() {
  return 'caches' in globalThis ? caches.open(MODEL_CACHE).catch(() => undefined) : undefined
}

async function fetchModel(src: string) {
  const cache = await openModelCache()
  const kept = await cache?.match(src)
  if (kept) return kept.arrayBuffer()
  const response = await fetch(src)
  if (!response.ok) throw new Error(`${src}: ${response.status}`)
  await cache?.put(src, response.clone()).catch(() => {})
  return response.arrayBuffer()
}

/** Whether the model at `src` is kept already, so it can be drawn without fetching it. */
async function isModelKept(src: string) {
  return Boolean(await (await openModelCache())?.match(src))
}

async function forgetModelsBut(sources: string[]) {
  const cache = await openModelCache()
  for (const request of (await cache?.keys()) ?? []) {
    if (!sources.includes(request.url)) void cache?.delete(request)
  }
}

/** The model's nodes holding each axle's pair of wheels. */
const WHEELS = ['front-wheels', 'rear-wheels']
/** How far in from a wheel's outer edge its tyre meets the ground, as a share of its radius. */
const TREAD_IN = 0.3

/**
 * A car model, stood on the ground with its middle over the origin, and its size; its
 * wheels, each turning about its axle on the model's x axis, the rear pair together and each
 * front one on its own, steering about its middle, with the wheels' radius and where the rear
 * tyres meet the ground; and its lamps, with its headlamps' bounds.
 */
async function loadCar(loader: GLTFLoader, src: string) {
  const car = (await loader.parseAsync(await fetchModel(src), '')).scene
  const box = new Box3().setFromObject(car)
  const center = box.getCenter(new Vector3())
  car.position.sub(new Vector3(center.x, box.min.y, center.z))
  car.updateMatrixWorld(true)
  let radius = 1
  const axles: Group[] = []
  const steers: Group[] = []
  const tyres: { x: number; z: number }[] = []
  const turnAbout = (wheels: Object3D, middle: Vector3, parent: Object3D) => {
    const axle = new Group()
    parent.add(axle)
    parent.updateWorldMatrix(true, false)
    axle.position.copy(parent.worldToLocal(middle.clone()))
    axle.attach(wheels)
    axles.push(axle)
  }
  for (const name of WHEELS) {
    const wheels = car.getObjectByName(name)
    if (!wheels) continue
    const bounds = new Box3().setFromObject(wheels)
    radius = (bounds.max.y - bounds.min.y) / 2
    const middle = bounds.getCenter(new Vector3())
    if (name === 'rear-wheels') {
      turnAbout(wheels, middle, car)
      const across = bounds.max.x - radius * TREAD_IN
      tyres.push({ x: across, z: middle.z }, { x: -across, z: middle.z })
      continue
    }
    // Each front wheel cut out of the pair onto a steering knuckle of its own; the pair's
    // vertices stay with the halves.
    for (const { cut, bounds: half } of splitBySide(wheels)) {
      const hub = half.getCenter(new Vector3())
      const steer = new Group()
      car.add(steer)
      steer.position.copy(car.worldToLocal(hub.clone()))
      steers.push(steer)
      turnAbout(cut, hub, steer)
    }
    wheels.removeFromParent()
  }
  const size = box.getSize(new Vector3())
  return { car, size, wheels: { axles, steers, radius, tyres }, ...findLamps(car, size) }
}

/**
 * Two copies of `root`, beside it, each of its meshes cut down to the triangles on one side
 * of the car's middle, right then left, with the bounds of what's left on each. The copies
 * share the meshes' vertices, each with an index of its own, so the wheels take no more room
 * than before; each vertex is placed once.
 */
function splitBySide(root: Object3D) {
  root.updateMatrixWorld(true)
  const sides = [1, -1].map(() => {
    const cut = root.clone()
    root.parent!.add(cut)
    const meshes: Mesh[] = []
    cut.traverse((object) => {
      if (object instanceof Mesh) meshes.push(object)
    })
    return { cut, meshes, bounds: new Box3() }
  })
  const point = new Vector3()
  let at = 0
  root.traverse((object) => {
    if (!(object instanceof Mesh)) return
    const geometry = object.geometry as BufferGeometry
    const position = geometry.getAttribute('position')
    const placed = new Float32Array(position.count * 3)
    for (let vertex = 0; vertex < position.count; vertex++) {
      point
        .fromBufferAttribute(position, vertex)
        .applyMatrix4(object.matrixWorld)
        .toArray(placed, vertex * 3)
    }
    const index =
      geometry.index?.array ?? Uint32Array.from({ length: position.count }, (_, vertex) => vertex)
    const kept = sides.map(() => ({ index: new Uint32Array(index.length), length: 0 }))
    for (let first = 0; first < index.length; first += 3) {
      const across =
        placed[index[first]! * 3]! + placed[index[first + 1]! * 3]! + placed[index[first + 2]! * 3]!
      const side = across > 0 ? 0 : 1
      const { bounds } = sides[side]!
      const into = kept[side]!
      for (let corner = first; corner < first + 3; corner++) {
        const vertex = index[corner]!
        into.index[into.length++] = vertex
        bounds.expandByPoint(point.fromArray(placed, vertex * 3))
      }
    }
    for (const [side, { meshes }] of sides.entries()) {
      const cut = new BufferGeometry()
      for (const [name, attribute] of Object.entries(geometry.attributes)) {
        cut.setAttribute(name, attribute)
      }
      cut.setIndex(new BufferAttribute(kept[side]!.index.subarray(0, kept[side]!.length), 1))
      meshes[at]!.geometry = cut
    }
    at++
  })
  return sides
}

/** A glass material's own colour, how see-through it is and how much it mirrors. */
type Glass = { color: Color; transmission: number; mirror: number }

/**
 * The see-through glass in `root`. It's lent the scene's `environment` as its own, or how
 * much it mirrors would be the scene's, whatever it's set to.
 */
function findGlass(root: Object3D, environment: Texture) {
  const glass = new Map<MeshPhysicalMaterial, Glass>()
  root.traverse((object) => {
    if (!(object instanceof Mesh)) return
    for (const material of [object.material].flat()) {
      if (material instanceof MeshPhysicalMaterial && material.transmission > 0) {
        material.envMap = environment
        glass.set(material, {
          color: material.color.clone(),
          transmission: material.transmission,
          mirror: material.envMapIntensity,
        })
      }
    }
  })
  return glass
}

/** Let go of everything a part of the scene holds on the GPU. */
function release(root: Object3D) {
  root.traverse((object) => {
    // Every halo shares one glow, kept for the next model.
    if (object instanceof Sprite) return object.material.dispose()
    if (!(object instanceof Mesh)) return
    object.geometry.dispose()
    for (const material of [object.material].flat()) {
      for (const value of Object.values(material)) {
        // The glass's environment is the scene's, lent to it, not its own to let go.
        if (value instanceof Texture && value !== material.envMap) value.dispose()
      }
      material.dispose()
    }
  })
}

/**
 * Draw the car in the frame from the light model at `src`, and once it has settled, swap in
 * the full one at `fullSrc`. One that was kept from an earlier visit is drawn straight away
 * instead, with no light one first.
 */
export async function mountCarModel(frame: HTMLElement, src: string, fullSrc?: string) {
  const canvas = document.createElement('canvas')
  const renderer = new WebGLRenderer({ canvas, alpha: true, antialias: true })
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2))
  renderer.outputColorSpace = SRGBColorSpace
  renderer.toneMapping = NeutralToneMapping

  const scene = new Scene()
  const pmrem = new PMREMGenerator(renderer)
  const room = new RoomEnvironment()
  const environment = pmrem.fromScene(room, 0.04).texture
  scene.environment = environment
  pmrem.dispose()
  room.dispose()
  // The streaks as they're seen from the car's middle, for it to mirror at speed.
  const reflections = new WebGLCubeRenderTarget(256, {
    type: HalfFloatType,
    generateMipmaps: true,
    minFilter: LinearMipmapLinearFilter,
  })
  const glint = { value: 0 }

  const loader = new GLTFLoader().setMeshoptDecoder(MeshoptDecoder)
  void forgetModelsBut([src, fullSrc].filter((source) => source !== undefined))
  const fullKept = fullSrc !== undefined && (await isModelKept(fullSrc))
  const { car, size, wheels, lamps, headlamps } = await loadCar(loader, fullKept ? fullSrc : src)
  reflectStreaks(car, reflections.texture, glint)
  // The body pitches about the ground under its middle; the shadow and the lamps' light on
  // the ground only follow along.
  const body = new Group().add(car)
  const shadow = createShadow(size.x, size.z)
  const glow = createGlow(size, headlamps)
  scene.add(body, shadow, glow.tail, glow.ahead)
  const lightLayer = createLightLayer(glow.ahead, scene, renderer.getPixelRatio())
  const dark = matchMedia('(prefers-color-scheme: dark)')
  const year = readCarYear(frame)
  const routes = createRoutes(year?.floor, size, renderer)
  if (routes) scene.add(routes.ground)
  const odometer = year && createOdometer(year)
  let lit = dark.matches ? 1 : 0
  const light = (level: number) => {
    lit = level
    setLamps(current.lamps, level)
    glow.set(level)
  }

  // Close enough that side on the car spans its frame, and far enough that it still fits
  // its canvas however it's turned.
  const camera = new PerspectiveCamera(FOV, 2, 0.01, 400)
  const reach = Math.hypot(size.x, size.z) / 2
  // Aimed below its middle, lifting it in the canvas, so that nose on, its bumper nearer and
  // lower in perspective, it still clears the figures under the frame.
  const target = new Vector3(0, size.y * 0.32, 0)
  // The canvas spills over the text around the frame, but only the frame takes a drag, so
  // what's under the overflow can still be read, selected and clicked.
  const controls = new OrbitControls(camera, frame)
  controls.target.copy(target)
  controls.enableZoom = false
  controls.enablePan = false
  controls.enableDamping = true
  controls.minPolarAngle = controls.maxPolarAngle = Math.PI / 2 - ELEVATION
  frame.style.touchAction = 'pan-y'
  const reduced = matchMedia('(prefers-reduced-motion: reduce)')
  frame.append(canvas)
  // The still keeps the canvas's place in the page while the canvas is out covering the
  // screen, so the car is laid out from it.
  const home = frame.querySelector<HTMLElement>('.car-model__still') ?? canvas
  let speeding = false
  let distance = 0

  const place = () => {
    const width = home.clientWidth
    const height = home.clientHeight
    if (!speeding) renderer.setSize(width, height, false)
    camera.aspect = width / height
    const halfVertical = (FOV * Math.PI) / 360
    const halfHorizontal = Math.atan(Math.tan(halfVertical) * camera.aspect)
    // Side on, its length across the frame's width; turned, its diagonal across the canvas,
    // the nearer end looming larger in perspective; and its height within the canvas.
    const sideOn = (((size.z / 2) * (width / frame.clientWidth)) / Math.tan(halfHorizontal)) * 1.06
    const turned = (reach * 1.15) / Math.tan(halfHorizontal)
    const upright = (size.y * 0.78) / Math.tan(halfVertical)
    distance = Math.max(sideOn, turned, upright)
    const azimuth = controls.getAzimuthalAngle() || START_AZIMUTH
    camera.position.setFromSphericalCoords(distance, Math.PI / 2 - ELEVATION, azimuth).add(target)
    camera.updateProjectionMatrix()
    controls.update()
  }
  place()
  const resizeObserver = new ResizeObserver(place)
  resizeObserver.observe(frame)
  resizeObserver.observe(home)

  // Driving in with the scroll only makes sense while it hasn't begun to.
  const rect = frame.getBoundingClientRect()
  let stage: 'scrolling' | 'settling' | 'parked' | 'still' = reduced.matches
    ? 'still'
    : rect.bottom > 0 && rect.top < innerHeight * START_AT
      ? 'parked'
      : 'scrolling'
  let stageStart = 0
  // How much of its slide is left as it drives in, 1 tail out to 0 caught straight.
  let entering = 0
  // How far its front wheels are left turned once it has stopped, as a driver leaves them after
  // a slide, as a share of the most; straightened once it drives on, speeding off or through
  // the year.
  let parked = stage === 'scrolling' ? 0 : 1
  const run = size.z * DRIVE_IN

  /** How far along the run in the scroll has it. */
  const scrolled = () => {
    const { top, height } = frame.getBoundingClientRect()
    const start = innerHeight * START_AT
    const stop = innerHeight * STOP_AT - height / 2
    return Math.min(Math.max((start - top) / (start - stop), 0), 1)
  }

  const pose = (now: number) => {
    entering = 0
    if (stage === 'scrolling') {
      const progress = scrolled()
      body.position.z = -run * (1 - easeOut(progress))
      // Sliding in tail out, and caught straight again before the stop.
      entering = 1 - smoothstep(ENTRY_CATCH, 1, progress)
      parked = 1 - entering
      // The nose goes down as it brakes into the stop.
      body.rotation.x = DIP * easeInOut(progress)
      if (progress === 1) {
        stage = 'settling'
        stageStart = now
        sound.brake()
      }
    } else if (stage === 'settling') {
      // Stopped, it springs back up.
      const elapsed = now - stageStart
      body.position.z = 0
      body.rotation.x =
        DIP *
        Math.exp(-elapsed / SETTLE_DECAY_MS) *
        Math.cos((elapsed / SETTLE_PERIOD_MS) * 2 * Math.PI)
      if (elapsed > SETTLE_DECAY_MS * 6) {
        body.rotation.x = 0
        stage = 'parked'
      }
    }
    shadow.position.z = glow.tail.position.z = glow.ahead.position.z = body.position.z
    // The ground stays put, drawing in as the car drives onto it, and holds still on the screen
    // as it's swung round.
    routes?.set({
      shown: 1 + body.position.z / run,
      slid: (cruised - 0.5) * YEAR_SLIDE * size.z,
      speed,
      lit,
      dark: dark.matches,
      z: body.position.z,
      turn: controls.getAzimuthalAngle() - START_AZIMUTH,
    })
    // Its wheels turned as far as it has rolled, without slipping, and spun up at speed.
    for (const axle of current.wheels.axles) {
      axle.rotation.x = (body.position.z + run) / current.wheels.radius + spin + cruised * YEAR_ROLL
    }
  }

  // Once it's settled, the full model takes the light one's place, the swap between two
  // frames, the car keeping its place and its turn.
  let current = { car, wheels, lamps }
  let upgrading = fullKept || !fullSrc
  let released = false
  const upgrade = () => {
    upgrading = true
    loadCar(loader, fullSrc!)
      .then((full) => {
        if (released) return release(full.car)
        body.remove(current.car)
        release(current.car)
        reflectStreaks(full.car, reflections.texture, glint)
        setLamps(full.lamps, lit)
        glass = findGlass(full.car, environment)
        darkness = -1
        body.add(full.car)
        current = full
      })
      .catch(() => {})
  }

  // At speed the studio's light falls to a tenth, and the car mirrors the streaks besides,
  // as a cube camera in its middle sees them.
  const floorSize = reach * 14
  const tunnel = createTunnel(camera.position.distanceTo(target), size, floorSize / 2)
  const cubeCamera = new CubeCamera(0.05, 400, reflections)
  cubeCamera.position.y = size.y * 0.4
  const streakScene = new Scene().add(tunnel.reflection)
  const floor = createFloor(floorSize)
  scene.add(tunnel.streaks, floor.mesh)
  const drift = createDrift(size, wheels.tyres)
  scene.add(drift.group)
  const speedometer = createSpeedometer()

  const sound = createCarSound()
  const unmountToggle = mountSoundToggle(frame)

  let held = false
  let pressedAt = { x: 0, y: 0, time: 0 }
  // At speed, how far across the press has been dragged since the last frame, and from where.
  let dragged = 0
  let draggedFrom = 0
  let turning = false
  let speed = 0
  let roll = 0
  let rush = 0
  // How far through the year the page has scrolled (src/components/YearDriving.astro), or the
  // middle of its ground where it isn't played; how far the car has driven through it, and how
  // fast it's going there, out of its fastest.
  const played = () => Number(frame.dataset.played ?? 0.5)
  let cruised = played()
  let cruising = 0
  let spin = 0
  const letGo = () => {
    held = false
  }
  const press = (event: PointerEvent) => {
    if (!event.isPrimary || event.button !== 0) return
    pressedAt = { x: event.clientX, y: event.clientY, time: performance.now() }
    draggedFrom = event.clientX
    if (reduced.matches) return
    held = true
    sound.press()
    turning = false
  }
  // A press that soon moves is a drag to turn the car, and leaves it in its place; once it's
  // well under way, a drag only swings it round as it goes.
  const wander = (event: PointerEvent) => {
    if (held && speed >= ENGAGE) dragged += event.clientX - draggedFrom
    draggedFrom = event.clientX
    if (!held || turning || speed >= ENGAGE) return
    const moved = Math.hypot(event.clientX - pressedAt.x, event.clientY - pressedAt.y)
    if (moved > HOLD_SLOP) turning = true
  }
  // A swipe up or down the car is the page's to scroll, which it takes back from the car;
  // the car stops at once rather than flashing past.
  const takenBack = () => {
    held = false
    speed = rush = 0
  }
  // A long press on a phone would otherwise bring up its menu.
  const holdMenu = (event: Event) => {
    if (held) event.preventDefault()
  }
  // The lamps switched against the page's light by a click, until it turns dark or light.
  let switched = false
  const flip = (event: MouseEvent) => {
    const moved = Math.hypot(event.clientX - pressedAt.x, event.clientY - pressedAt.y)
    if (performance.now() - pressedAt.time >= HOLD_MS || moved > HOLD_SLOP) return
    switched = !switched
    sound.lamps(dark.matches !== switched)
  }
  const unswitch = () => {
    switched = false
    routes?.retheme()
  }
  frame.addEventListener('pointerdown', press)
  frame.addEventListener('click', flip)
  dark.addEventListener('change', unswitch)
  frame.addEventListener('pointermove', wander)
  frame.addEventListener('pointerup', letGo)
  frame.addEventListener('pointercancel', takenBack)
  frame.addEventListener('contextmenu', holdMenu)
  addEventListener('blur', letGo)

  // How far above the car the camera looks at speed, and the angle it was last set to, from
  // which a drag's tilt is told.
  let tilt = SPEED_ELEVATION
  let setPolar = 0

  // By dark, the page's or round the car at speed, the studio's light falls away and the car
  // is lit by its lamps. Its glass, which on a see-through canvas shows what's behind it over
  // half-white, is tinted by day, and by dark turns darker still and stops being seen through.
  let glass = findGlass(car, environment)
  let darkness = -1
  const darken = (level: number) => {
    if (level === darkness) return
    darkness = level
    scene.environmentIntensity = 1 - VEIL * level
    const tint = DAY_TINT + (DARK_TINT - DAY_TINT) * level
    for (const [material, { color, transmission, mirror }] of glass) {
      material.color.copy(color).multiplyScalar(1 - tint)
      material.transmission = transmission * (1 - level)
      material.envMapIntensity = mirror * (1 - VEIL * level) * (1 - GLASS_GLARE * level)
    }
  }
  // The paint, pale and satin, is polished up so the streaks show in it sharply.
  const paint = new Map<MeshPhysicalMaterial, number>()

  /** Out over the whole screen, or back in its place in the page. */
  const setSpeeding = (on: boolean) => {
    speeding = on
    canvas.classList.toggle('car-model-speeding', on)
    if (on) {
      document.body.append(canvas)
      // Up and down, too, while it speeds.
      controls.minPolarAngle = Math.PI / 2 - TILT_HIGH
      controls.maxPolarAngle = Math.PI / 2 - TILT_LOW
      setPolar = controls.getPolarAngle()
      body.traverse((object) => {
        if (!(object instanceof Mesh)) return
        for (const material of [object.material].flat()) {
          if (
            material instanceof MeshPhysicalMaterial &&
            !glass.has(material) &&
            material.metalness < 0.5 &&
            material.roughness > 0.2 &&
            material.roughness < 0.45 &&
            material.color.getHSL({ h: 0, s: 0, l: 0 }).l > 0.5
          ) {
            paint.set(material, material.roughness)
          }
        }
      })
    } else {
      for (const [material, roughness] of paint) material.roughness = roughness
      paint.clear()
      frame.append(canvas)
      controls.minPolarAngle = controls.maxPolarAngle = Math.PI / 2 - ELEVATION
      tilt = SPEED_ELEVATION
      camera.fov = FOV
      renderer.setClearColor(0x000000, 0)
      glint.value = 0
      place()
    }
  }

  /** The view at speed: widened with the camera drawn in to match, drawn across the screen
   * with the car gliding from where it stood in the page to the middle of it, room all
   * round it for the streaks, and shaken. */
  const drawn = new Vector2()
  const frameSpeed = (time: number) => {
    const width = canvas.clientWidth
    const height = canvas.clientHeight
    renderer.getSize(drawn)
    if (drawn.x !== width || drawn.y !== height) {
      renderer.setSize(width, height, false)
      floor.fit(renderer.getDrawingBufferSize(drawn))
    }
    const eased = easeInOut(speed)
    camera.fov = FOV + SPEED_FOV * eased
    const halfTan = Math.tan((camera.fov * Math.PI) / 360)
    const kept = Math.tan((FOV * Math.PI) / 360) / halfTan
    // Lowered nearly level, as the SU7's is, so the streaks rush in from all round rather than
    // all from above, and raised or lowered from there by as much as it's been dragged since.
    tilt = Math.min(Math.max(tilt + setPolar - controls.getPolarAngle(), TILT_LOW), TILT_HIGH)
    const elevation = ELEVATION + (tilt - ELEVATION) * eased
    setPolar = Math.PI / 2 - elevation
    camera.position
      .setFromSphericalCoords(
        distance * kept * (1 + HOLD_BACK * eased) * (1 - PUSH_IN * roll),
        Math.PI / 2 - elevation,
        controls.getAzimuthalAngle(),
      )
      .add(target)
    const stood = home.getBoundingClientRect()
    const left = stood.left + ((width - stood.width) / 2 - stood.left) * eased
    // Aimed low on the car, the view is set lower still while it's near level, so the car's
    // body sits in the middle; looking down on it, its roof does that itself.
    const level = 1 - Math.max(tilt - SPEED_ELEVATION, 0) / (TILT_HIGH - SPEED_ELEVATION)
    const lower = stood.height * 0.15 * level
    const top = stood.top + ((height - stood.height) / 2 + lower - stood.top) * eased
    const halfHeight = camera.near * halfTan
    const perPixel = (2 * halfHeight) / stood.height
    const shake = SHAKE * speed * perPixel
    const x = -halfHeight * camera.aspect - left * perPixel + wave(time) * shake
    const y = halfHeight + top * perPixel + wave(time + 5000) * shake
    camera.projectionMatrix.makePerspective(
      x,
      x + width * perPixel,
      y,
      y - height * perPixel,
      camera.near,
      camera.far,
    )
    camera.projectionMatrixInverse.copy(camera.projectionMatrix).invert()
  }

  const drive = (time: number, delta: number) => {
    const going = held && !turning && performance.now() - pressedAt.time > HOLD_MS
    speed += ((going ? 1 : 0) - speed) * (1 - Math.exp(-delta / (going ? SPEED_UP : SLOW_DOWN)))
    if (!going && speed < 0.002) speed = 0
    // Any press sets the wheels rolling and draws the camera in, a drag that only turns the
    // car as well.
    roll += ((held ? 1 : 0) - roll) * (1 - Math.exp(-delta / (held ? SPEED_UP : SLOW_DOWN)))
    rush += (speed - rush) * (1 - Math.exp(-delta * (speed > rush ? RUSH_UP : RUSH_DOWN)))
    if (speed > 0 && !speeding) setSpeeding(true)
    if (speed === 0 && speeding) setSpeeding(false)
    // Turned only a few degrees a frame, well short of the gap between spokes, so the rims
    // roll rather than strobe.
    spin += Math.max(roll * ROLL, speed * 5) * delta
    // The streaks only come once it's going.
    tunnel.advance(speed, smoothstep(0.15, 0.8, rush), delta)
    odometer?.advance(speed, delta)
    floor.mesh.visible = speeding
    floor.opacity = speed
    // Driven on through the year as it's scrolled, coasting after it no faster than the rims
    // can roll without strobing; in the middle of its ground where the year isn't played.
    const year = played()
    const step = Math.min(Math.abs(year - cruised), (CRUISE_ROLL * delta) / YEAR_ROLL)
    cruising = delta > 0 ? step / delta / (CRUISE_ROLL / YEAR_ROLL) : 0
    cruised += Math.sign(year - cruised) * step
    // A shiver through the body at speed, and a little as it cruises.
    body.position.y = Math.max(speed, cruising * 0.4) * size.y * 0.003 * Math.sin(time / 23)
    // On in the dark and off by day, the other way round once switched by a click, and on as
    // the page goes dark round it at speed.
    const wanted = Math.max(dark.matches !== switched ? 1 : 0, speed)
    if (wanted !== lit) {
      const eased = lit + (wanted - lit) * (1 - Math.exp(-delta / LAMPS_FADE))
      light(reduced.matches || Math.abs(wanted - eased) < 0.002 ? wanted : eased)
    }
    darken(Math.max(dark.matches ? 1 : 0, speed))
    if (!speeding) return
    renderer.setClearColor(0x000000, NIGHT * speed)
    for (const [material, roughness] of paint) {
      material.roughness = roughness + (POLISH - roughness) * speed
    }
    glint.value = GLINT * speed
    cubeCamera.update(renderer, streakScene)
  }

  let visible = true
  let frameId = 0
  let azimuth = controls.getAzimuthalAngle()
  // Dragged across fast at speed, it drifts: its tail swings out, its body leans out and its
  // front wheels turn back against the slide, its rear tyres smoking and marking the floor,
  // the view held still the while, springing back straight once the drag slows. How fast it's
  // dragged, smoothed; how far its tail is out, and how fast it's going there; whether the view
  // is held; and how fast it's rolling in as it drives in.
  let slip = 0
  let yaw = 0
  let yawSpeed = 0
  let viewHeld = false
  let entryZ = body.position.z
  let rolled = 0
  const skid = (delta: number) => {
    const step = Math.min(delta, MAX_STEP)
    const perSecond = delta > 0 ? 1 / delta : 0
    slip = MathUtils.damp(
      slip,
      ((2 * Math.PI * dragged) / frame.clientHeight) * perSecond,
      1 / SLIP_FOLLOW,
      step,
    )
    dragged = 0
    const swung = speed >= ENGAGE ? smoothstep(DRIFT_FROM, DRIFT_FULL, Math.abs(slip)) * speed : 0
    yawSpeed +=
      ((Math.sign(slip) * DRIFT_YAW * swung - yaw) * DRIFT_STIFFNESS - yawSpeed * DRIFT_DAMPING) *
      step
    yaw += yawSpeed * step
    const drifting = swung > 0 || Math.abs(yaw) > DRIFT_SETTLED
    if (drifting !== viewHeld) {
      viewHeld = drifting
      const azimuth = controls.getAzimuthalAngle()
      controls.minAzimuthAngle = drifting ? azimuth : -Infinity
      controls.maxAzimuthAngle = drifting ? azimuth : Infinity
    }
    // Driving in, it slides as far as its tail is out and as fast as the scroll carries it.
    rolled = MathUtils.damp(
      rolled,
      Math.abs(body.position.z - entryZ) * perSecond,
      1 / ROLLED_FOLLOW,
      step,
    )
    entryZ = body.position.z
    const sliding = entering * smoothstep(0, size.z * ENTRY_SLIDE_SPEED, rolled)
    const turned = yaw + ENTRY_YAW * entering
    body.rotation.y = glow.tail.rotation.y = glow.ahead.rotation.y = turned
    shadow.rotation.z = turned
    body.rotation.z = DRIFT_LEAN * turned
    if (speed > 0 || cruising > 0) parked = MathUtils.damp(parked, 0, 1 / PARK_STEER_FOLLOW, step)
    for (const steer of current.wheels.steers) {
      steer.rotation.y = -COUNTER_STEER * turned - PARK_STEER * parked
    }
    const slide = Math.max(swung, sliding)
    drift.update({
      slide,
      yaw: turned,
      z: body.position.z,
      speed,
      pale: darkness > 0.5,
      framed: !speeding,
      delta,
      eye: camera.position,
    })
    speedometer.update(speed, turned)
    return slide
  }
  const timer = new Timer()
  const tick = (time: number) => {
    timer.update(time)
    const delta = timer.getDelta()
    drive(time, delta)
    pose(time)
    if (!upgrading && (stage === 'parked' || stage === 'still')) upgrade()
    controls.update(delta)
    // How fast it's swung round, the long way round from one side to the other taken short.
    const turned = controls.getAzimuthalAngle()
    const swept = ((turned - azimuth + 3 * Math.PI) % (2 * Math.PI)) - Math.PI
    azimuth = turned
    const slide = skid(delta)
    sound.update({
      speed,
      rush,
      roll: Math.max(roll, cruising),
      turn: delta > 0 ? Math.abs(swept) / delta : 0,
      azimuth: turned,
      drift: slide,
    })
    if (speeding) {
      frameSpeed(time)
      tunnel.follow(camera.position)
    } else {
      camera.position
        .sub(target)
        .setLength(distance * (1 - PUSH_IN * roll))
        .add(target)
    }
    // Only by dark, and not while the car's canvas covers the screen.
    if (dark.matches && !speeding && lit > 0) {
      lightLayer.cast(camera, canvas.getBoundingClientRect())
    } else {
      lightLayer.withdraw()
    }
    renderer.render(scene, camera)
    frameId = visible && !document.hidden ? requestAnimationFrame(tick) : 0
  }
  const resume = () => {
    sound.setAudible(visible && !document.hidden)
    if (!frameId && visible && !document.hidden) frameId = requestAnimationFrame(tick)
  }
  // A callback can carry more than one change for the frame; the last is how it is now.
  const sightObserver = new IntersectionObserver((entries) => {
    visible = entries.at(-1)!.isIntersecting
    if (!visible) lightLayer.withdraw()
    resume()
  })
  sightObserver.observe(frame)
  document.addEventListener('visibilitychange', resume)
  const followMotion = () => {
    if (reduced.matches) {
      letGo()
      stage = 'still'
      body.position.z = 0
      body.rotation.x = 0
    } else if (stage === 'still') {
      stage = 'parked'
    }
    pose(performance.now())
  }
  reduced.addEventListener('change', followMotion)

  light(lit)
  darken(dark.matches ? 1 : 0)
  pose(performance.now())
  controls.update()
  renderer.render(scene, camera)
  frame.classList.add('is-ready')
  frameId = requestAnimationFrame(tick)

  return () => {
    cancelAnimationFrame(frameId)
    resizeObserver.disconnect()
    sightObserver.disconnect()
    document.removeEventListener('visibilitychange', resume)
    reduced.removeEventListener('change', followMotion)
    letGo()
    frame.removeEventListener('pointerdown', press)
    frame.removeEventListener('pointermove', wander)
    frame.removeEventListener('pointerup', letGo)
    frame.removeEventListener('pointercancel', takenBack)
    frame.removeEventListener('contextmenu', holdMenu)
    frame.removeEventListener('click', flip)
    dark.removeEventListener('change', unswitch)
    removeEventListener('blur', letGo)
    sound.dispose()
    odometer?.dispose()
    drift.dispose()
    speedometer.dispose()
    unmountToggle()
    controls.dispose()
    released = true
    floor.mesh.dispose()
    lightLayer.dispose()
    release(scene)
    release(streakScene)
    reflections.dispose()
    environment.dispose()
    renderer.dispose()
    canvas.remove()
    frame.style.touchAction = ''
  }
}

/** A sum of out-of-step waves, wandering without repeating, for the camera to shake by. */
function wave(at: number) {
  return Math.sin(at * 0.013) + 0.5 * Math.sin(at * 0.029 + 1.3) + 0.25 * Math.sin(at * 0.061 + 2.1)
}

/** A soft dark oval under the car, for it to stand on, no bigger than its footprint, so that
 * nose on it doesn't spread out over the text below. */
function createShadow(width: number, length: number) {
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = 128
  const context = canvas.getContext('2d')!
  const gradient = context.createRadialGradient(64, 64, 0, 64, 64, 64)
  gradient.addColorStop(0, 'rgb(0 0 0 / 0.42)')
  gradient.addColorStop(0.6, 'rgb(0 0 0 / 0.26)')
  gradient.addColorStop(1, 'rgb(0 0 0 / 0)')
  context.fillStyle = gradient
  context.fillRect(0, 0, 128, 128)
  const material = new MeshBasicMaterial({
    map: new CanvasTexture(canvas),
    transparent: true,
    depthWrite: false,
  })
  const shadow = new Mesh(new PlaneGeometry(width * 1.1, length * 1.02), material)
  shadow.rotation.x = -Math.PI / 2
  shadow.position.y = 0.001
  return shadow
}

/** A CSS cubic-bézier timing function, solved for its progress at a share of the time. */
function cubicBezier(x1: number, y1: number, x2: number, y2: number) {
  const at = (a: number, b: number, t: number) =>
    3 * a * t * (1 - t) ** 2 + 3 * b * t ** 2 * (1 - t) + t ** 3
  const slope = (a: number, b: number, t: number) =>
    3 * a * (1 - t) ** 2 + 6 * (b - a) * t * (1 - t) + 3 * (1 - b) * t ** 2
  return (x: number) => {
    if (x <= 0) return 0
    if (x >= 1) return 1
    let t = x
    for (let step = 0; step < 8; step++) {
      const error = at(x1, x2, t) - x
      const gradient = slope(x1, x2, t)
      if (Math.abs(error) < 1e-6 || gradient === 0) break
      t = Math.min(Math.max(t - error / gradient, 0), 1)
    }
    return at(y1, y2, t)
  }
}
