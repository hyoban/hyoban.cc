import {
  CanvasTexture,
  Color,
  Group,
  Mesh,
  MeshBasicMaterial,
  PlaneGeometry,
  Vector2,
  Vector3,
  type WebGLRenderer,
} from 'three'
import { formatCarDate, formatCarSum, getCarDate, sumCarDays, type CarYear } from './car-model.ts'

// A year review's car stands on its year (src/car-model.ts): the legs it drove on the year's
// trips drawn faintly on the ground round it, in the page's ink as the footprint's routes are,
// the north behind it, fading out towards the edges, and drawing in as it drives in. The map
// holds still as the car is swung round on it. By dark they all but go out, but for where its
// lamps fall: warm in the beams ahead, which sweep across them as it's swung round, and red
// in the glow behind. Speeding off, they turn pale over the dark, and under it the car drives
// through the year a day at a time: the date, what it had driven, charged and spent by then,
// and the year's driving graph lighting up to it, held where it got to when let go and gone
// once the car is back.

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
/** Seconds held at full speed to drive through the whole year. */
const COUNT = 6

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
  context.setTransform(1, 0, 0, 1, 0, 0)
  // Faded out from the middle, so the ground has no edge.
  const half = TEXTURE / 2
  const fade = context.createRadialGradient(half, half, 0, half, half, half)
  fade.addColorStop(0.5, '#000')
  fade.addColorStop(1, 'transparent')
  context.globalCompositeOperation = 'destination-in'
  context.fillStyle = fade
  context.fillRect(0, 0, TEXTURE, TEXTURE)

  const texture = new CanvasTexture(canvas)
  texture.anisotropy = renderer.capabilities.getMaxAnisotropy()
  const material = new MeshBasicMaterial({
    map: texture,
    transparent: true,
    depthWrite: false,
    toneMapped: false,
  })
  // Faded out towards the canvas's edges, too, in the page, which the ground would run past.
  const edges = { value: new Vector2() }
  // The car's half width, its length and where its body is along its way; and how lit its
  // lamps are, and how far the routes are shown only by them.
  const car = { value: new Vector3() }
  const lamps = { value: new Vector2() }
  material.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, { edges, car, lamps })
    shader.vertexShader = shader.vertexShader
      .replace('void main() {', 'varying vec2 vGround;\nvoid main() {')
      .replace(
        '#include <project_vertex>',
        '#include <project_vertex>\nvGround = (modelMatrix * vec4(transformed, 1.0)).xz;',
      )
    shader.fragmentShader = shader.fragmentShader
      .replace(
        'void main() {',
        'uniform vec2 edges;\nuniform vec3 car;\nuniform vec2 lamps;\nvarying vec2 vGround;\nvoid main() {',
      )
      .replace(
        '#include <opaque_fragment>',
        `if (edges.x > 0.0) {
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
      ground.rotation.y = turn
      // Back along the car's way, whichever way the ground is turned.
      mesh.position.x = slid * Math.sin(turn)
      mesh.position.z = -slid * Math.cos(turn)
    },
    /** Read the page's ink again once it turns dark or light. */
    retheme: readInk,
  }
}

/** The year driven through over the screen while the car speeds: the day it has got to, and
 * how far it had driven, how much it had charged and spent by then, with the year's driving
 * graph lighting up behind it; the year's caption over the totals once it's through. */
export function createOdometer(year: CarYear) {
  const element = document.createElement('div')
  element.className = 'car-model-odometer'
  element.ariaHidden = 'true'
  const caption = document.createElement('span')
  const figure = document.createElement('strong')
  const value = document.createTextNode('')
  const unit = document.createElement('small')
  unit.textContent = 'km'
  figure.append(value, unit)
  const more = document.createElement('span')
  more.className = 'car-model-odometer__more'
  const charged = document.createElement('span')
  const spent = document.createElement('span')
  more.append(charged, spent)
  // The graph as the page draws it, a column a week, Monday at the top.
  const graph = document.createElement('span')
  graph.className = 'car-model-odometer__days'
  const offset = (new Date(`${year.start}T00:00Z`).getUTCDay() + 6) % 7
  graph.style.setProperty('--weeks', String(Math.ceil((offset + year.days.length) / 7)))
  graph.style.setProperty('--offset', String(offset + 1))
  const cells = year.levels.split('').map((level) => {
    const cell = document.createElement('span')
    if (level === '.') cell.dataset.empty = ''
    else cell.dataset.level = level
    graph.append(cell)
    return cell
  })
  element.append(caption, figure, more, graph)

  const sums = sumCarDays(year.days)
  let counted = 0
  let lit = 0
  let shown: number | undefined

  return {
    /** Driven on by `speed` over `delta` seconds; put away and started over once it's 0. */
    advance(speed: number, delta: number) {
      if (speed === 0) {
        if (!element.isConnected) return
        element.remove()
        for (const cell of cells) delete cell.dataset.lit
        counted = lit = 0
        shown = undefined
        return
      }
      if (!element.isConnected) document.body.append(element)
      counted = Math.min(counted + (speed * delta) / COUNT, 1)
      // A day at a time and evenly through the calendar, the totals jumping with the trips.
      const at = Math.min(Math.floor(counted * year.days.length), year.days.length - 1)
      for (; lit <= at; lit++) cells[lit]!.dataset.lit = ''
      // Once it's through, the year's caption over its totals.
      const step = counted === 1 ? -1 : at
      if (step !== shown) {
        shown = step
        const figures = formatCarSum(sums[at]!)
        caption.textContent = step === -1 ? year.caption : formatCarDate(getCarDate(year.start, at))
        value.data = figures.km
        charged.textContent = figures.charged
        spent.textContent = figures.spent
      }
      element.style.opacity = String(Math.min(speed * 2, 1))
    },
    dispose() {
      element.remove()
    },
  }
}
