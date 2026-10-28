import {
  CanvasTexture,
  Color,
  Group,
  MathUtils,
  Mesh,
  MeshBasicMaterial,
  PlaneGeometry,
  Vector2,
  Vector3,
  type WebGLRenderer,
} from 'three'
import type { CarYear } from './car-model.ts'

// A year review's car stands on its year (src/car-model.ts): the legs it drove on the year's
// trips drawn faintly on the ground round it, in the page's ink as the footprint's routes are,
// the north behind it, fading out round the car, and drawing in as it drives in. The map
// holds still as the car is swung round on it. By dark they all but go out, but for where its
// lamps fall: warm in the beams ahead, which sweep across them as it's swung round, and red
// in the glow behind. Speeding off, they turn pale over the dark. As the page plays through
// the year (src/components/YearDriving.astro), they slide back under the car.

/** The ground drawn on, as a share of the car's diagonal, and its texture's size. */
const SPREAD = 3.6
const TEXTURE = 1024
/** How much of the canvas's height is the room kept under the car's frame, which the ground
 * fades out across rather than being cut off at the canvas's edge; and its sides do the same. */
const ROOM = 0.2 / 1.75
/** How wide the routes are on the texture, and how strongly they show by day and at speed. */
const LINE = 3
const INK = 0.18
const SPEED_INK = 0.45
/** By dark, how much of their ink the routes keep out of the lamps' light, and how many times
 * it they show at the brightest of the beams. */
const DIM = 0.3
const BOOST = 4
/** The ink at speed, over the dark: the dark page's own. */
const PALE = '#eeeeec'

/** The year a car's frame stands on, if it's in a year review. */
export function readCarYear(frame: HTMLElement) {
  const data = frame.dataset.year
  return data ? (JSON.parse(data) as CarYear) : undefined
}

/** How the routes are shown: as far as the car has driven in, at `speed`, with its lamps `lit`
 * as far as they are and the page as `dark`; the car's body at `z` and turned by `turn` from
 * where it started, the way the camera has gone round it. */
type RouteState = {
  shown: number
  /** How far the ground has slid back under the car as it drives through the year. */
  slid: number
  speed: number
  lit: number
  dark: boolean
  z: number
  turn: number
}

/** The angle `angle` brought within half a turn of nought. */
export function wrapAngle(angle: number) {
  return MathUtils.euclideanModulo(angle + Math.PI, 2 * Math.PI) - Math.PI
}

/** The year's routes on the ground round a car of `size`; none without any. */
export function createRoutes(floor: CarYear['floor'], size: Vector3, renderer: WebGLRenderer) {
  if (!floor) return undefined
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = TEXTURE
  const context = canvas.getContext('2d')!
  const scale = TEXTURE / floor.width
  context.translate(0, ((floor.width - floor.height) / 2) * scale)
  context.scale(scale, scale)
  context.strokeStyle = '#fff'
  context.lineWidth = LINE / scale
  context.lineCap = context.lineJoin = 'round'
  context.stroke(new Path2D(floor.d))

  const texture = new CanvasTexture(canvas)
  texture.anisotropy = renderer.capabilities.getMaxAnisotropy()
  const material = new MeshBasicMaterial({
    map: texture,
    transparent: true,
    depthWrite: false,
    toneMapped: false,
  })
  // Faded out round the car, so the ground has no edge wherever on it the car is, and towards
  // the canvas's edges, too, in the page, which the ground would run past.
  const spread = { value: 0 }
  const edges = { value: new Vector2() }
  // The car's half width, its length and where its body is along its way; and how lit its
  // lamps are, and how far the routes are shown only by them.
  const car = { value: new Vector3() }
  const lamps = { value: new Vector2() }
  material.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, { spread, edges, car, lamps })
    shader.vertexShader = shader.vertexShader
      .replace('void main() {', 'varying vec2 vGround;\nvoid main() {')
      .replace(
        '#include <project_vertex>',
        '#include <project_vertex>\nvGround = (modelMatrix * vec4(transformed, 1.0)).xz;',
      )
    shader.fragmentShader = shader.fragmentShader
      .replace(
        'void main() {',
        'uniform float spread;\nuniform vec2 edges;\nuniform vec3 car;\nuniform vec2 lamps;\nvarying vec2 vGround;\nvoid main() {',
      )
      .replace(
        '#include <opaque_fragment>',
        `diffuseColor.a *= 1.0 - smoothstep(0.5 * spread, spread, length(vGround));
        vec2 border = min(vMapUv, 1.0 - vMapUv);
        diffuseColor.a *= smoothstep(0.0, 0.04, min(border.x, border.y));
        if (edges.x > 0.0) {
          diffuseColor.a *= smoothstep(0.0, edges.x, gl_FragCoord.y)
            * smoothstep(0.0, edges.x, gl_FragCoord.x)
            * smoothstep(0.0, edges.x, edges.y - gl_FragCoord.x);
        }
        if (lamps.y > 0.0) {
          // The beams' light on the ground, from the nose out to about four lengths ahead,
          // widening as it goes, as src/car-model-lamps.ts lights it; and the tail's red glow.
          float ahead = (vGround.y - car.z - car.y * 0.5) / (car.y * 3.5);
          float across = abs(vGround.x) / (car.x * (1.0 + 1.4 * max(ahead, 0.0)));
          float beam = smoothstep(0.0, 0.05, ahead) * (1.0 - smoothstep(0.2, 1.0, ahead))
            * (1.0 - smoothstep(0.55, 1.0, across));
          float behind = (car.z - car.y * 0.5 - vGround.y) / (car.y * 0.7);
          float tail = smoothstep(0.0, 0.05, behind) * (1.0 - smoothstep(0.1, 1.0, behind))
            * (1.0 - smoothstep(0.6, 1.1, abs(vGround.x) / car.x));
          float warm = lamps.x * beam;
          float red = lamps.x * tail;
          diffuseColor.a *= mix(1.0, ${DIM.toFixed(2)} + ${BOOST.toFixed(1)} * (warm + 0.7 * red), lamps.y);
          // Coloured as soon as they're lit at all, before they're lit brightly.
          diffuseColor.rgb = mix(diffuseColor.rgb, vec3(1.0, 0.93, 0.82), lamps.y * min(warm * 3.0, 1.0));
          diffuseColor.rgb = mix(diffuseColor.rgb, vec3(1.0, 0.06, 0.04), lamps.y * min(red * 3.0, 1.0));
        }
        #include <opaque_fragment>`,
      )
  }
  const drawn = new Vector2()
  const side = Math.hypot(size.x, size.z) * SPREAD
  spread.value = side / 2
  const mesh = new Mesh(new PlaneGeometry(side, side), material)
  mesh.rotation.x = -Math.PI / 2
  mesh.position.y = 0.0005
  // Over the glossy floor at speed, under the car's shadow.
  mesh.renderOrder = -0.5
  // Turned with the camera, so the map holds still on the screen as the car's swung round.
  const ground = new Group().add(mesh)
  const ink = new Color()
  const pale = new Color(PALE)
  const readInk = () =>
    ink.setStyle(getComputedStyle(document.documentElement).getPropertyValue('--color-tx').trim())
  readInk()

  return {
    ground,
    /** In the page's ink, or pale at speed, and by dark only where the lamps fall. */
    set({ shown, slid, speed, lit, dark, z, turn }: RouteState) {
      renderer.getDrawingBufferSize(drawn)
      // Not while the canvas covers the screen.
      edges.value.set(speed > 0 ? 0 : drawn.y * ROOM, drawn.x)
      material.color.copy(ink).lerp(pale, speed)
      material.opacity = shown * (INK + (SPEED_INK - INK) * speed)
      car.value.set(size.x / 2, size.z, z)
      lamps.value.set(lit, dark ? 1 - speed : 0)
      // Back along the car's way, whichever way the ground is turned.
      ground.rotation.y = turn
      mesh.position.x = slid * Math.sin(turn)
      mesh.position.z = -slid * Math.cos(turn)
    },
    /** Read the page's ink again once it turns dark or light. */
    retheme: readInk,
  }
}
