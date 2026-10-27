import {
  Box3,
  CanvasTexture,
  Color,
  CubeCamera,
  Group,
  HalfFloatType,
  LinearMipmapLinearFilter,
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
import { createFloor, createTunnel, reflectStreaks, smoothstep } from './car-model-speed.ts'

// The car standing on its own shadow, under a studio's light, never turning on its own. It
// can be swung round by dragging sideways, a drag coasting to rest, but not zoomed or tipped over, and a vertical drag still scrolls the
// page. It draws nothing while out of sight.
//
// The first time it comes into view it drives in with the scroll: it rolls forward as the
// page moves on and back as it moves back, its wheels rolling as it goes, coming to a stop
// once its frame is just past the middle of the screen. There its nose dips as it brakes and
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
// it goes. Not under reduced motion. The streaks and the floor are in
// src/car-model-speed.ts.
//
// Its lamps are off by day and on in the dark: when the page is dark, and as it goes dark
// around the car speeding off. A click, let go before the car could speed off and without
// moving, switches them off, or on by day, until the page turns dark or light. They're in
// src/car-model-lamps.ts.

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

/** How long a press must stay put before the car speeds off, how far it may wander in that
 * time and until the speed has come up this far, before it's taken as a drag that only
 * turns the car instead, and how fast the wheels roll for any press. */
const HOLD_MS = 150
const HOLD_SLOP = 6
const ENGAGE = 0.5
const ROLL = 3
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
/** How dark the page goes, and how much of the studio's light the car loses: most, but its
 * paint's colour still plainly seen. */
const NIGHT = 0.92
const VEIL = 0.8
/** How brightly the paint and glass mirror the streaks at full speed. */
const GLINT = 2
/** How much of what's seen through the glass it holds back at full speed. */
const TINT = 0.92
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

/**
 * A car model, stood on the ground with its middle over the origin, and its size; its
 * wheels, each axle's pair on a group turning about it on the model's x axis, with the
 * wheels' radius; and its lamps, with its headlamps' bounds.
 */
async function loadCar(loader: GLTFLoader, src: string) {
  const car = (await loader.parseAsync(await fetchModel(src), '')).scene
  const box = new Box3().setFromObject(car)
  const center = box.getCenter(new Vector3())
  car.position.sub(new Vector3(center.x, box.min.y, center.z))
  car.updateMatrixWorld(true)
  let radius = 1
  const axles = WHEELS.flatMap((name) => {
    const wheels = car.getObjectByName(name)
    if (!wheels) return []
    const bounds = new Box3().setFromObject(wheels)
    radius = (bounds.max.y - bounds.min.y) / 2
    const axle = new Group()
    car.add(axle)
    axle.position.copy(car.worldToLocal(bounds.getCenter(new Vector3())))
    axle.attach(wheels)
    return [axle]
  })
  const size = box.getSize(new Vector3())
  return { car, size, wheels: { axles, radius }, ...findLamps(car, size) }
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
        if (value instanceof Texture) value.dispose()
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
    // Start from the front three-quarter view, the way the car is usually photographed.
    const azimuth = controls.getAzimuthalAngle() || Math.PI / 5
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
        stage = 'parked'
      }
    }
    shadow.position.z = glow.tail.position.z = glow.ahead.position.z = body.position.z
    // Its wheels turned as far as it has rolled, without slipping, and spun up at speed.
    for (const axle of current.wheels.axles) {
      axle.rotation.x = (body.position.z + run) / current.wheels.radius + spin
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

  let held = false
  let pressedAt = { x: 0, y: 0, time: 0 }
  let turning = false
  let speed = 0
  let roll = 0
  let rush = 0
  let spin = 0
  const letGo = () => {
    held = false
  }
  const press = (event: PointerEvent) => {
    if (!event.isPrimary || event.button !== 0) return
    pressedAt = { x: event.clientX, y: event.clientY, time: performance.now() }
    if (reduced.matches) return
    held = true
    turning = false
  }
  // A press that soon moves is a drag to turn the car, and leaves it in its place; once it's
  // well under way, a drag only swings it round as it goes.
  const wander = (event: PointerEvent) => {
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
    if (performance.now() - pressedAt.time < HOLD_MS && moved <= HOLD_SLOP) switched = !switched
  }
  const unswitch = () => {
    switched = false
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

  // Glass that's seen through shows what's behind it over half-white wherever the canvas is
  // see-through, so at night it's tinted dark, as a car's glass is, its reflections kept.
  const glass = new Map<MeshPhysicalMaterial, Color>()
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
          if (!(material instanceof MeshPhysicalMaterial)) continue
          if (material.transmission > 0) {
            glass.set(material, material.color.clone())
          } else if (
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
      for (const [material, color] of glass) material.color.copy(color)
      for (const [material, roughness] of paint) material.roughness = roughness
      glass.clear()
      paint.clear()
      frame.append(canvas)
      controls.minPolarAngle = controls.maxPolarAngle = Math.PI / 2 - ELEVATION
      tilt = SPEED_ELEVATION
      camera.fov = FOV
      renderer.setClearColor(0x000000, 0)
      scene.environmentIntensity = 1
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
    floor.mesh.visible = speeding
    floor.opacity = speed
    // A shiver through the body at speed.
    body.position.y = speed * size.y * 0.003 * Math.sin(time / 23)
    // On in the dark and off by day, the other way round once switched by a click, and on as
    // the page goes dark round it at speed.
    const wanted = Math.max(dark.matches !== switched ? 1 : 0, speed)
    if (wanted !== lit) {
      const eased = lit + (wanted - lit) * (1 - Math.exp(-delta / LAMPS_FADE))
      light(reduced.matches || Math.abs(wanted - eased) < 0.002 ? wanted : eased)
    }
    if (!speeding) return
    renderer.setClearColor(0x000000, NIGHT * speed)
    for (const [material, color] of glass) {
      material.color.copy(color).multiplyScalar(1 - TINT * speed)
    }
    for (const [material, roughness] of paint) {
      material.roughness = roughness + (POLISH - roughness) * speed
    }
    scene.environmentIntensity = 1 - VEIL * speed
    glint.value = GLINT * speed
    cubeCamera.update(renderer, streakScene)
  }

  let visible = true
  let frameId = 0
  const timer = new Timer()
  const tick = (time: number) => {
    timer.update(time)
    const delta = timer.getDelta()
    drive(time, delta)
    pose(time)
    if (!upgrading && (stage === 'parked' || stage === 'still')) upgrade()
    controls.update(delta)
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
