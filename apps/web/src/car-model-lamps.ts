import {
  AdditiveBlending,
  Box3,
  CanvasTexture,
  ConeGeometry,
  DoubleSide,
  Group,
  Mesh,
  MeshBasicMaterial,
  MeshStandardMaterial,
  Object3D,
  PerspectiveCamera,
  PlaneGeometry,
  SRGBColorSpace,
  Scene,
  ShaderMaterial,
  Sprite,
  SpriteMaterial,
  Vector2,
  Vector3,
  WebGLRenderer,
} from 'three'
import { smoothstep } from './car-model-speed.ts'

// The 3D car's lamps (src/car-model-client.ts): the strip across its nose and the one across
// its tail, the materials in the model that glow. Lit, they burn, soft halos spilling round
// them, and they're on high beam: two beams reaching far ahead and lighting a long stretch of
// the ground, with a red glow behind. By dark, the beams and the lit ground go on past the
// car's canvas over the page, drawn by its camera on a canvas of their own across the window,
// which lightens what's under it, the words they fall on among it.

/** How many times brighter the tail's red glows lit than the model has it. */
const TAIL_BURN = 4
/** How many halos glow along each strip, across the car. */
const HALOS = 9

/** A car's lamps: the materials that glow, with how brightly they burn lit, and their halos,
 * and how lit they are. */
export type Lamps = {
  materials: Map<MeshStandardMaterial, number>
  halos: Sprite[]
  level: { value: number }
}

/**
 * The lamps of a `car` of `size`, stood with its nose to +z, its halos added to it; and the
 * bounds of its headlamps, the white ones, across its nose.
 */
export function findLamps(car: Object3D, size: Vector3) {
  const lamps: Lamps = { materials: new Map(), halos: [], level: { value: 0 } }
  const headlamps = new Box3()
  // The strips' halos are added once the car's been walked, not to it while it's walked.
  const strips: { mesh: Mesh; front: boolean }[] = []
  car.traverse((object) => {
    if (!(object instanceof Mesh)) return
    for (const material of [object.material].flat()) {
      if (material instanceof MeshStandardMaterial && material.emissive.getHex() !== 0) {
        const { r, g, b } = material.emissive
        const front = Math.min(r, g, b) > 0.5
        // The tail's red, dull as the model has it, burns as brightly as a lit lamp does.
        lamps.materials.set(material, material.emissiveIntensity * (front ? 1 : TAIL_BURN))
        if (front) headlamps.expandByObject(object)
        strips.push({ mesh: object, front })
      }
    }
  })
  for (const { mesh, front } of strips) {
    lamps.halos.push(...createHalos(car, mesh, front, size, lamps.level))
  }
  return { lamps, headlamps }
}

/** Light the car's `lamps` to `level`, from off at 0 to as bright as they burn. */
export function setLamps(lamps: Lamps, level: number) {
  lamps.level.value = level
  for (const [material, intensity] of lamps.materials) {
    material.emissiveIntensity = intensity * level
  }
  for (const halo of lamps.halos) halo.visible = level > 0
}

/**
 * Soft halos along a light strip, the `front` one or the tail's, for the light that spills
 * round a lit lamp, as lit as `level`: one glowing at its outermost point in each of a few
 * slices across the car of `size`, a little out from its surface. Each fades as its lamp
 * turns away, gone once it's seen from behind.
 */
function createHalos(
  car: Object3D,
  strip: Mesh,
  front: boolean,
  size: Vector3,
  level: { value: number },
) {
  const position = strip.geometry.getAttribute('position')
  const point = new Vector3()
  const slices: (Vector3 | undefined)[] = Array.from({ length: HALOS })
  const outward = front ? 1 : -1
  for (let index = 0; index < position.count; index++) {
    car.worldToLocal(point.fromBufferAttribute(position, index).applyMatrix4(strip.matrixWorld))
    const slice = Math.floor(((point.x / size.x + 0.5) * HALOS) % HALOS)
    const outermost = slices[slice]
    if (!outermost || point.z * outward > outermost.z * outward) slices[slice] = point.clone()
  }
  const glow = haloTexture()
  const facing = new Vector3()
  const seen = new Vector3()
  return slices.flatMap((at) => {
    if (!at) return []
    const halo = new Sprite(
      new SpriteMaterial({
        map: glow,
        color: front ? 0xfff6e8 : 0xff2a18,
        blending: AdditiveBlending,
        depthWrite: false,
        transparent: true,
      }),
    )
    halo.position.copy(at).add(new Vector3(0, 0, outward * size.z * 0.012))
    halo.scale.set(size.x * 0.3, size.y * 0.2, 1)
    halo.visible = false
    halo.onBeforeRender = (_renderer, _scene, camera) => {
      facing.set(0, 0, outward).transformDirection(car.matrixWorld)
      seen.setFromMatrixPosition(camera.matrixWorld).sub(halo.getWorldPosition(point)).normalize()
      halo.material.opacity =
        (front ? 1 : 1.2) * level.value * smoothstep(-0.15, 0.45, facing.dot(seen))
    }
    car.add(halo)
    return [halo]
  })
}

let glowTexture: CanvasTexture | undefined

/** A soft round glow, white, fading out from its middle, shared by every halo. */
function haloTexture() {
  if (glowTexture) return glowTexture
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = 64
  const context = canvas.getContext('2d')!
  const gradient = context.createRadialGradient(32, 32, 0, 32, 32, 32)
  gradient.addColorStop(0, 'rgb(255 255 255 / 1)')
  gradient.addColorStop(0.2, 'rgb(255 255 255 / 0.5)')
  gradient.addColorStop(0.5, 'rgb(255 255 255 / 0.12)')
  gradient.addColorStop(1, 'rgb(255 255 255 / 0)')
  context.fillStyle = gradient
  context.fillRect(0, 0, 64, 64)
  glowTexture = new CanvasTexture(canvas)
  return glowTexture
}

/**
 * The lamps' light, for a car of `size`, nose to +z, its `headlamps` across its nose: the
 * light `ahead` of it on high beam, two beams reaching far through the air and a long stretch
 * of light on the ground under them, and the red glow on the ground at its `tail`; `set` to
 * how lit they are.
 */
export function createGlow(size: Vector3, headlamps: Box3) {
  const { x: width, z: length } = size
  // How far in from the edges of the canvas it's drawn on the light fades out, as a share of
  // its shorter side, so wherever the car's turned, the canvas never cuts it off in a line.
  const screen = { value: new Vector2() }
  const fade = { value: 0 }
  const fadeAtEdges = /* glsl */ `
    vec2 glowEdge = min(gl_FragCoord.xy, glowScreen - gl_FragCoord.xy);
    gl_FragColor.a *= smoothstep(0.0, glowFade, min(glowEdge.x, glowEdge.y));`
  // An oval, brightest at `at` (a share of the way down the canvas, whose top is laid to -z)
  // and gone by the canvas's edges, `reach` times as long as it's wide, fading in from the
  // edge the car's at, `near`, so the light starts at the bumper and never under the car.
  const spot = (rgb: string, alpha: number, at: number, reach: number, near: 'top' | 'bottom') => {
    const canvas = document.createElement('canvas')
    canvas.width = canvas.height = 128
    const context = canvas.getContext('2d')!
    context.translate(64, 128 * at)
    context.scale(1, reach)
    const gradient = context.createRadialGradient(0, 0, 0, 0, 0, 64)
    gradient.addColorStop(0, `rgb(${rgb} / ${alpha})`)
    gradient.addColorStop(0.4, `rgb(${rgb} / ${alpha * 0.45})`)
    gradient.addColorStop(1, `rgb(${rgb} / 0)`)
    context.fillStyle = gradient
    context.fillRect(-64, -128, 128, 256)
    context.setTransform(1, 0, 0, 1, 0, 0)
    context.globalCompositeOperation = 'destination-in'
    const [from, to] = near === 'top' ? [0, 128] : [128, 0]
    const fadeIn = context.createLinearGradient(0, from, 0, to)
    fadeIn.addColorStop(0, 'rgb(0 0 0 / 0)')
    fadeIn.addColorStop(0.15, 'rgb(0 0 0 / 1)')
    context.fillStyle = fadeIn
    context.fillRect(0, 0, 128, 128)
    const material = new MeshBasicMaterial({
      map: new CanvasTexture(canvas),
      transparent: true,
      depthWrite: false,
      opacity: 0,
    })
    material.onBeforeCompile = (shader) => {
      shader.uniforms.glowScreen = screen
      shader.uniforms.glowFade = fade
      shader.fragmentShader = shader.fragmentShader
        .replace(
          'void main() {',
          'uniform vec2 glowScreen;\nuniform float glowFade;\nvoid main() {',
        )
        .replace('#include <opaque_fragment>', `#include <opaque_fragment>${fadeAtEdges}`)
    }
    return material
  }
  // Each from the car's end outwards, clear of its wheels: ahead from the nose at 0.5 of its
  // length from its middle to 4, brightest a little past it; behind from the tail at 0.5 to
  // 0.8, brightest just past it.
  const ground = new Mesh(
    new PlaneGeometry(width * 2, length * 3.5),
    spot('255 248 236', 0.42, 0.035, 1.93, 'top'),
  )
  ground.position.z = length * 2.25
  const behind = new Mesh(
    new PlaneGeometry(width * 1.1, length * 0.3),
    spot('255 36 24', 0.32, 0.82, 1.64, 'bottom'),
  )
  behind.position.z = -length * 0.65
  for (const mesh of [ground, behind]) {
    mesh.rotation.x = -Math.PI / 2
    mesh.position.y = 0.002
  }

  // A cone of light from each end of the headlamps, dipped a little, lit through the air:
  // brightest at the lamp and fading out along its length, and soft at its rim, where it's
  // seen edge on.
  const reach = length * 3.5
  const cone = new ConeGeometry(width, reach, 48, 1, true)
    .rotateX(-Math.PI / 2)
    .translate(0, 0, reach / 2)
  const brightness = { value: 0 }
  const beam = new ShaderMaterial({
    uniforms: {
      glowScreen: screen,
      glowFade: fade,
      opacity: brightness,
      reach: { value: reach },
    },
    vertexShader: /* glsl */ `
      uniform float reach;
      varying float vAlong;
      varying vec3 vNormal;
      varying vec3 vView;
      void main() {
        vAlong = position.z / reach;
        vec4 view = modelViewMatrix * vec4(position, 1.0);
        vView = -view.xyz;
        vNormal = normalMatrix * normal;
        gl_Position = projectionMatrix * view;
      }
    `,
    fragmentShader: /* glsl */ `
      uniform vec2 glowScreen;
      uniform float glowFade;
      uniform float opacity;
      varying float vAlong;
      varying vec3 vNormal;
      varying vec3 vView;
      void main() {
        float facing = abs(dot(normalize(vNormal), normalize(vView)));
        float along = pow(1.0 - vAlong, 2.2) * smoothstep(0.02, 0.2, vAlong);
        gl_FragColor = vec4(1.0, 0.97, 0.92, 0.4 * opacity * along * facing * facing);
        ${fadeAtEdges}
      }
    `,
    transparent: true,
    depthWrite: false,
    side: DoubleSide,
    blending: AdditiveBlending,
  })
  const lamp = headlamps.isEmpty()
    ? new Vector3(0, size.y * 0.4, length / 2)
    : new Vector3(
        (headlamps.min.x + headlamps.max.x) / 2,
        headlamps.min.y + (headlamps.max.y - headlamps.min.y) * 0.85,
        headlamps.max.z,
      )
  const spread = headlamps.isEmpty() ? width * 0.35 : (headlamps.max.x - headlamps.min.x) * 0.4
  const beams = [-1, 1].map((side) => {
    const mesh = new Mesh(cone, beam)
    mesh.position.set(lamp.x + side * spread, lamp.y, lamp.z)
    mesh.rotation.x = (1.5 * Math.PI) / 180
    return mesh
  })

  const tail = new Group().add(behind)
  const ahead = new Group().add(ground, ...beams)
  for (const mesh of [ground, behind, ...beams]) {
    mesh.onBeforeRender = (renderer) => {
      renderer.getDrawingBufferSize(screen.value)
      fade.value = Math.min(screen.value.x, screen.value.y) * 0.2
    }
  }
  return {
    tail,
    ahead,
    set(level: number) {
      tail.visible = ahead.visible = level > 0
      ground.material.opacity = behind.material.opacity = brightness.value = level
    },
  }
}

/**
 * The window-wide canvas the light `ahead` of the car is drawn on by dark, as the car's
 * camera would see it were its canvas as big as the window, at `pixelRatio`; away from it,
 * the light is drawn with the rest of the car's `scene`. The canvas lightens what's under it.
 */
export function createLightLayer(ahead: Group, scene: Scene, pixelRatio: number) {
  const canvas = document.createElement('canvas')
  canvas.className = 'car-model-light'
  canvas.setAttribute('aria-hidden', 'true')
  const renderer = new WebGLRenderer({ canvas, alpha: true, antialias: true })
  renderer.setPixelRatio(pixelRatio)
  renderer.outputColorSpace = SRGBColorSpace
  const lightScene = new Scene()
  const lightCamera = new PerspectiveCamera()
  const drawn = new Vector2()
  const looking = new Vector3()
  let on = false
  const shift = (cast: boolean) => {
    if (cast === on) return
    on = cast
    ;(on ? lightScene : scene).add(ahead)
    if (on) document.body.append(canvas)
    else canvas.remove()
  }
  return {
    /** Draw the light across the window as `camera` sees it in the car's canvas at `box`. */
    cast(camera: PerspectiveCamera, box: DOMRect) {
      shift(true)
      const width = canvas.clientWidth
      const height = canvas.clientHeight
      renderer.getSize(drawn)
      if (drawn.x !== width || drawn.y !== height) renderer.setSize(width, height, false)
      lightCamera.copy(camera)
      lightCamera.setViewOffset(box.width, box.height, -box.left, -box.top, width, height)
      lightCamera.updateProjectionMatrix()
      // Turned away, the beams would be drawn across the car, which this canvas can't hide
      // them behind, so they fade out as the nose turns from the camera.
      const facing = -camera.getWorldDirection(looking).z
      canvas.style.opacity = String(smoothstep(-0.45, -0.05, facing))
      renderer.render(lightScene, lightCamera)
    },
    /** Hand the light back to the car's own canvas. */
    withdraw() {
      shift(false)
    },
    dispose() {
      shift(false)
      renderer.dispose()
    },
  }
}
