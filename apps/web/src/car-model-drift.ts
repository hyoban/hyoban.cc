import { smoothstep } from './car-model-speed.ts'
import {
  BufferAttribute,
  BufferGeometry,
  CanvasTexture,
  Group,
  Mesh,
  MeshBasicMaterial,
  Sprite,
  SpriteMaterial,
  type Vector3,
} from 'three'

// What the 3D car (src/car-model-client.ts) leaves as it drifts, swung round fast at speed or
// sliding into its place as it drives in: smoke off its rear tyres, rising, spreading and
// thinning out behind it, and two marks of rubber from them, pale over the dark and dark over
// the light, carried back as the floor rushes past at speed and fading out a few lengths
// behind. Both follow its rear wheels as its tail swings out.

/** How many puffs can be in the air at once, how many a second come off each tyre at full
 * drift, how long each lasts, in seconds, and how thick it is at its thickest. */
const PUFFS = 96
const PUFF_RATE = 36
const PUFF_LIFE = 1.4
const PUFF_ALPHA = 0.36
/** How far a puff spreads, as a share of how far at speed, in the car's frame, whose canvas
 * would cut it off in a line. */
const FRAMED_BILLOW = 0.4
/** How much of the floor's speed a puff is left behind at, hanging in the air. */
const PUFF_DRAG = 0.3
/** How near the eye, in car lengths, a puff is gone, and how far it's whole, so seen from
 * behind the smoke doesn't fog the whole view. */
const PUFF_NEAR = [0.3, 0.9] as const
/** How many points each mark is drawn through, and how far apart, in car lengths: as long as
 * that reaches behind it; and how wide it is, as a share of the car's width. */
const MARK_POINTS = 64
const MARK_SPACING = 0.08
const MARK_WIDTH = 0.12
/** The marks' grey, rubber smeared pale over the dark or dark over the light, and how much
 * they show at their freshest. */
const MARK_PALE = 0.5
const MARK_DARK = 0.12
const MARK_ALPHA = 0.55
/** How fast the floor goes by at full speed, in car lengths a second. */
const GROUND = 3

/** A rear tyre's contact patch, in the car's own space. */
type Tyre = { x: number; z: number }

/** How the car's drifting: by `slide` (0 to 1), its tail swung out by `yaw`, its middle `z`
 * along its way and the floor going by under it at `speed` (1 at full speed), over `delta`
 * seconds, seen from `eye`; its marks `pale` over the dark, else dark over the light, and its
 * smoke `framed` in the car's own frame rather than the whole screen. */
type DriftState = {
  slide: number
  yaw: number
  z: number
  speed: number
  pale: boolean
  framed: boolean
  delta: number
  eye: Vector3
}

/** A puff of smoke: how it goes, how big it grows to and how long it's been out. */
type Puff = { sprite: Sprite; vx: number; vy: number; size: number; age: number }

/** The drift's smoke and marks for a car of `size`, off its rear tyres at `tyres`. */
export function createDrift(size: Vector3, tyres: readonly Tyre[]) {
  const group = new Group()
  const width = size.x * MARK_WIDTH

  // Soft round puffs, pale grey, each its own material for its own fade.
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = 64
  const context = canvas.getContext('2d')!
  const gradient = context.createRadialGradient(32, 32, 0, 32, 32, 32)
  gradient.addColorStop(0, 'rgb(255 255 255 / 1)')
  gradient.addColorStop(0.5, 'rgb(255 255 255 / 0.45)')
  gradient.addColorStop(1, 'rgb(255 255 255 / 0)')
  context.fillStyle = gradient
  context.fillRect(0, 0, 64, 64)
  const puffMap = new CanvasTexture(canvas)
  const puffs: Puff[] = Array.from({ length: PUFFS }, () => {
    const sprite = new Sprite(
      new SpriteMaterial({ map: puffMap, color: 0xb4b4b4, transparent: true, depthWrite: false }),
    )
    sprite.visible = false
    sprite.renderOrder = 2
    group.add(sprite)
    return { sprite, vx: 0, vy: 0, size: 0, age: PUFF_LIFE }
  })
  let nextPuff = 0
  let owed = 0

  // Each mark a ribbon through its tyre's last points, newest first, each point's darkness in
  // its colour's alpha, falling off along it.
  const marks = tyres.map(() => {
    const points = Array.from({ length: MARK_POINTS }, () => ({ x: 0, z: 0, strength: 0 }))
    const geometry = new BufferGeometry()
    const positions = new BufferAttribute(new Float32Array(MARK_POINTS * 2 * 3), 3)
    const colors = new BufferAttribute(new Float32Array(MARK_POINTS * 2 * 4), 4)
    const index: number[] = []
    for (let point = 0; point < MARK_POINTS - 1; point++) {
      const at = point * 2
      index.push(at, at + 1, at + 2, at + 1, at + 3, at + 2)
    }
    geometry.setIndex(index)
    geometry.setAttribute('position', positions)
    geometry.setAttribute('color', colors)
    const mesh = new Mesh(
      geometry,
      new MeshBasicMaterial({ vertexColors: true, transparent: true, depthWrite: false }),
    )
    mesh.frustumCulled = false
    mesh.visible = false
    // Over the floor, under the car's shadow.
    mesh.renderOrder = -0.75
    group.add(mesh)
    return { points, positions, colors, mesh }
  })
  const spots = tyres.map(() => ({ x: 0, z: 0 }))
  const spacing = size.z * MARK_SPACING
  let laid = 0
  // Where the car last was along its way, for how far its tyres have gone over the floor;
  // whether the marks were last pale; and whether any puff or mark is still to be seen, so a
  // car that hasn't drifted lately, or stands still on its marks, does nothing.
  let last = 0
  let wasPale = true
  let puffing = false
  let marked = false

  return {
    group,
    update({ slide, yaw, z, speed, pale, framed, delta, eye }: DriftState) {
      const travel = GROUND * size.z * speed * delta
      const moved = travel + Math.abs(z - last)
      last = z
      const marking = moved > 0 || slide > 0 || pale !== wasPale
      if (!puffing && slide === 0 && !(marked && marking)) return
      // The tyres where they meet the floor, the tail swung out about the car's middle.
      const cos = Math.cos(yaw)
      const sin = Math.sin(yaw)
      for (const [at, { x, z: along }] of tyres.entries()) {
        spots[at]!.x = x * cos + along * sin
        spots[at]!.z = -x * sin + along * cos + z
      }

      const billow = framed ? FRAMED_BILLOW : 1
      owed += slide * PUFF_RATE * tyres.length * delta
      for (; owed >= 1; owed--) {
        const puff = puffs[nextPuff]!
        nextPuff = (nextPuff + 1) % PUFFS
        const spot = spots[nextPuff % spots.length]!
        puff.sprite.position.set(
          spot.x + (Math.random() - 0.5) * width,
          size.y * 0.15,
          spot.z + (Math.random() - 0.5) * width,
        )
        puff.vx = (Math.sign(spot.x) || 1) * size.x * (0.3 + Math.random() * 0.5) * billow
        puff.vy = size.y * (0.4 + Math.random() * 0.4) * billow
        puff.size = size.x * billow
        puff.age = 0
      }
      puffing = false
      for (const puff of puffs) {
        const { sprite } = puff
        sprite.visible = puff.age < PUFF_LIFE
        if (!sprite.visible) continue
        puffing = true
        puff.age += delta
        const life = Math.min(puff.age / PUFF_LIFE, 1)
        sprite.position.x += puff.vx * delta
        sprite.position.y += puff.vy * delta
        sprite.position.z -= travel * PUFF_DRAG
        sprite.scale.setScalar(puff.size * (0.5 + 2 * life))
        const near = smoothstep(
          size.z * PUFF_NEAR[0],
          size.z * PUFF_NEAR[1],
          sprite.position.distanceTo(eye),
        )
        sprite.material.opacity = PUFF_ALPHA * (1 - life) ** 1.5 * Math.min(life * 10, 1) * near
      }

      if (!marking) return
      wasPale = pale
      const grey = pale ? MARK_PALE : MARK_DARK
      // Carried back with the floor, a point left behind each time the tyres have gone a
      // spacing over it, the newest always where the tyre is now.
      laid += moved
      const steps = Math.min(Math.floor(laid / spacing), MARK_POINTS)
      laid -= steps * spacing
      marked = false
      for (const [side, { points, positions, colors, mesh }] of marks.entries()) {
        for (const point of points) point.z -= travel
        // The head left where it was and new ones put before it, or else the head moved on.
        points.unshift(...points.splice(MARK_POINTS - steps))
        for (let at = 0; at < Math.max(steps, 1); at++) {
          Object.assign(points[at]!, spots[side], { strength: slide })
        }
        for (const [at, { x, z: along, strength }] of points.entries()) {
          const alpha = MARK_ALPHA * strength * (1 - at / MARK_POINTS)
          positions.setXYZ(at * 2, x - width / 2, 0.003, along)
          positions.setXYZ(at * 2 + 1, x + width / 2, 0.003, along)
          colors.setXYZW(at * 2, grey, grey, grey, alpha)
          colors.setXYZW(at * 2 + 1, grey, grey, grey, alpha)
          if (alpha > 0) marked = true
        }
        positions.needsUpdate = colors.needsUpdate = true
        mesh.visible = marked
      }
    },
    /** Whether any puff is still rising, which moves on its own. */
    get smoking() {
      return puffing
    },
    /** Let go of the puffs' texture; the rest goes with the scene. */
    dispose() {
      puffMap.dispose()
    },
  }
}
