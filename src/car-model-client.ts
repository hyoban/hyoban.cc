import {
  Box3,
  CanvasTexture,
  Group,
  Mesh,
  Object3D,
  MeshBasicMaterial,
  NeutralToneMapping,
  PMREMGenerator,
  PerspectiveCamera,
  PlaneGeometry,
  SRGBColorSpace,
  Scene,
  Texture,
  Timer,
  Vector3,
  WebGLRenderer,
} from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { MeshoptDecoder } from 'three/examples/jsm/libs/meshopt_decoder.module.js'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js'

// The car turning slowly on its own shadow, under a studio's light. It can be swung round
// by dragging sideways, but not zoomed or tipped over, and a vertical drag still scrolls the
// page. It draws nothing while out of sight.
//
// The first time it comes into view it drives in with the scroll: it rolls forward as the
// page moves on and back as it moves back, coming to a stop once its frame is just past
// the middle of the screen. There its nose dips as it brakes and settles back, and only then
// does it begin to turn, gathering speed; after that the scroll leaves it be. Under reduced
// motion, or when it's already in view by the time it has loaded, it's simply there,
// standing still under reduced motion.

/** Seconds for a whole turn on its own. */
const TURN_SECONDS = 40
/** How far above the car the camera looks down, from the horizon. */
const ELEVATION = (14 * Math.PI) / 180
const FOV = 26

/** The run in, as a share of the car's length. */
const DRIVE_IN = 0.8
/** Where on the screen, from the top, the frame's top is when the car sets off, and its
 * middle is when it stops: just past the screen's middle. */
const START_AT = 0.9
const STOP_AT = 0.42
/** How far the nose dips at the stop, and how the springs settle after it. */
const DIP = (1.6 * Math.PI) / 180
const SETTLE_DECAY_MS = 180
const SETTLE_PERIOD_MS = 560
/** How long the turn takes to come up to speed once it's settled. */
const RAMP_MS = 2000

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

/** A car model, stood on the ground with its middle over the origin, and its size. */
async function loadCar(loader: GLTFLoader, src: string) {
  const car = (await loader.parseAsync(await fetchModel(src), '')).scene
  const box = new Box3().setFromObject(car)
  const center = box.getCenter(new Vector3())
  car.position.sub(new Vector3(center.x, box.min.y, center.z))
  return { car, size: box.getSize(new Vector3()) }
}

/** Let go of everything a part of the scene holds on the GPU. */
function release(root: Object3D) {
  root.traverse((object) => {
    if (!(object instanceof Mesh)) return
    object.geometry.dispose()
    for (const material of [object.material].flat()) {
      for (const value of Object.values(material)) {
        if (value instanceof Texture) value.dispose()
      }
      material.dispose()
    }
  })
}

/**
 * Whether to fetch the full model after the light one: not when the reader asks to save
 * data or is on a mobile network, nor on a touch screen, whose browser may not say.
 */
function wantsFullModel() {
  const connection = (
    navigator as Navigator & {
      connection?: { saveData?: boolean; type?: string; effectiveType?: string }
    }
  ).connection
  if (connection) {
    return (
      !connection.saveData && connection.type !== 'cellular' && connection.effectiveType === '4g'
    )
  }
  return !matchMedia('(pointer: coarse)').matches
}

/**
 * Draw the car in the frame from the light model at `src`, and once it has settled, swap in
 * the full one at `fullSrc` where the connection allows. One that was kept from an earlier
 * visit is drawn straight away instead, with no light one first.
 */
export async function mountCarModel(frame: HTMLElement, src: string, fullSrc?: string) {
  const canvas = document.createElement('canvas')
  const renderer = new WebGLRenderer({ canvas, alpha: true, antialias: true })
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2))
  renderer.outputColorSpace = SRGBColorSpace
  renderer.toneMapping = NeutralToneMapping

  const scene = new Scene()
  const pmrem = new PMREMGenerator(renderer)
  const environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture
  scene.environment = environment
  pmrem.dispose()

  const loader = new GLTFLoader().setMeshoptDecoder(MeshoptDecoder)
  void forgetModelsBut([src, fullSrc].filter((source) => source !== undefined))
  const fullKept = fullSrc !== undefined && (await isModelKept(fullSrc))
  const { car, size } = await loadCar(loader, fullKept ? fullSrc : src)
  // The body pitches about the ground under its middle; the shadow only follows along.
  const body = new Group().add(car)
  const shadow = createShadow(size.x, size.z)
  scene.add(body, shadow)

  // Close enough that side on the car spans its frame, and far enough that it still fits
  // its canvas however it's turned.
  const camera = new PerspectiveCamera(FOV, 2, 0.01, 100)
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
  controls.autoRotateSpeed = 60 / TURN_SECONDS
  frame.style.touchAction = 'pan-y'
  const reduced = matchMedia('(prefers-reduced-motion: reduce)')
  frame.append(canvas)

  const place = () => {
    const width = canvas.clientWidth
    const height = canvas.clientHeight
    renderer.setSize(width, height, false)
    camera.aspect = width / height
    const halfVertical = (FOV * Math.PI) / 360
    const halfHorizontal = Math.atan(Math.tan(halfVertical) * camera.aspect)
    // Side on, its length across the frame's width; turned, its diagonal across the canvas,
    // the nearer end looming larger in perspective; and its height within the canvas.
    const sideOn = (((size.z / 2) * (width / frame.clientWidth)) / Math.tan(halfHorizontal)) * 1.06
    const turned = (reach * 1.15) / Math.tan(halfHorizontal)
    const upright = (size.y * 0.78) / Math.tan(halfVertical)
    const distance = Math.max(sideOn, turned, upright)
    // Start from the front three-quarter view, the way the car is usually photographed.
    const azimuth = controls.getAzimuthalAngle() || Math.PI / 5
    camera.position.setFromSphericalCoords(distance, Math.PI / 2 - ELEVATION, azimuth).add(target)
    camera.updateProjectionMatrix()
    controls.update()
  }
  place()
  const resizeObserver = new ResizeObserver(place)
  resizeObserver.observe(frame)
  resizeObserver.observe(canvas)

  // Driving in with the scroll only makes sense while it hasn't begun to.
  const rect = frame.getBoundingClientRect()
  let stage: 'scrolling' | 'settling' | 'turning' | 'still' = reduced.matches
    ? 'still'
    : rect.bottom > 0 && rect.top < innerHeight * START_AT
      ? 'turning'
      : 'scrolling'
  let stageStart = 0
  const run = size.z * DRIVE_IN

  /** How far along the run in the scroll has it. */
  const scrolled = () => {
    const { top, height } = frame.getBoundingClientRect()
    const start = innerHeight * START_AT
    const stop = innerHeight * STOP_AT - height / 2
    return Math.min(Math.max((start - top) / (start - stop), 0), 1)
  }

  const pose = (now: number) => {
    if (stage === 'scrolling') {
      const progress = scrolled()
      body.position.z = -run * (1 - easeOut(progress))
      // The nose goes down as it brakes into the stop.
      body.rotation.x = DIP * easeInOut(progress)
      if (progress === 1) {
        stage = 'settling'
        stageStart = now
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
        stage = 'turning'
        stageStart = now
      }
    }
    if (stage === 'turning') {
      controls.autoRotate = true
      const ramp = stageStart ? Math.min((now - stageStart) / RAMP_MS, 1) : 1
      controls.autoRotateSpeed = (60 / TURN_SECONDS) * easeInOut(ramp)
    } else {
      controls.autoRotate = false
    }
    shadow.position.z = body.position.z
  }

  // Once it's settled, the full model takes the light one's place, the swap between two
  // frames, the car keeping its place and its turn.
  let current = car
  let upgrading = fullKept || !fullSrc || !wantsFullModel()
  let released = false
  const upgrade = () => {
    upgrading = true
    loadCar(loader, fullSrc!)
      .then(({ car: full }) => {
        if (released) return release(full)
        body.remove(current)
        release(current)
        body.add(full)
        current = full
      })
      .catch(() => {})
  }

  let visible = true
  let frameId = 0
  const timer = new Timer()
  const tick = (time: number) => {
    timer.update(time)
    pose(time)
    if (!upgrading && (stage === 'turning' || stage === 'still')) upgrade()
    controls.update(timer.getDelta())
    renderer.render(scene, camera)
    frameId = visible && !document.hidden ? requestAnimationFrame(tick) : 0
  }
  const resume = () => {
    if (!frameId && visible && !document.hidden) frameId = requestAnimationFrame(tick)
  }
  // A callback can carry more than one change for the frame; the last is how it is now.
  const sightObserver = new IntersectionObserver((entries) => {
    visible = entries.at(-1)!.isIntersecting
    resume()
  })
  sightObserver.observe(frame)
  document.addEventListener('visibilitychange', resume)
  const followMotion = () => {
    if (reduced.matches) {
      stage = 'still'
      body.position.z = 0
      body.rotation.x = 0
    } else if (stage === 'still') {
      stage = 'turning'
      stageStart = performance.now()
    }
    pose(performance.now())
  }
  reduced.addEventListener('change', followMotion)

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
    controls.dispose()
    released = true
    release(scene)
    environment.dispose()
    renderer.dispose()
    canvas.remove()
    frame.style.touchAction = ''
  }
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
