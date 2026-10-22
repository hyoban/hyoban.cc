// Whether this device is too slow for Mapbox Standard, so the map starts flat instead,
// on the classic map. Standard in 3D asks several times what the classic map does of
// a device: replaying a trip with a CPU six times slower than a fast laptop's, Standard
// drew under 30 frames a second and classic 60; on a software renderer, 9 and 18.
//
// Three signs: a renderer drawing WebGL on the CPU, little memory, and a short piece of
// work, like a map's, that takes the CPU too long. Judged once a session, so the map
// keeps one look from page to page.

const SESSION_KEY = 'trip-map-slow-device'
// The work below takes about 2.5ms on a fast laptop (Apple M4 Pro), and about 2.4ms more
// for each time slower a CPU is: past this, about five times slower.
const SLOW_WORK_MS = 12
// Gigabytes, as Chromium rounds a device's memory down to tell it.
const LOW_MEMORY_GB = 2

export function isSlowDevice(): boolean {
  try {
    const judged = sessionStorage.getItem(SESSION_KEY)

    if (judged) {
      return judged === 'slow'
    }
  } catch {
    // Judged afresh each time, then.
  }

  const slow = judgeDevice({
    memoryGb: (navigator as Navigator & { deviceMemory?: number }).deviceMemory,
    renderer: getRenderer(),
    workMs: () => measureWork(),
  })

  try {
    sessionStorage.setItem(SESSION_KEY, slow ? 'slow' : 'fast')
  } catch {
    // Not remembered.
  }

  return slow
}

/** The verdict from what's known of the device; the work is only measured if need be. */
export function judgeDevice({
  memoryGb,
  renderer,
  workMs,
}: {
  memoryGb: number | undefined
  renderer: string | undefined
  workMs: () => number
}) {
  return (
    (renderer !== undefined && isSoftwareRenderer(renderer)) ||
    (memoryGb !== undefined && memoryGb <= LOW_MEMORY_GB) ||
    workMs() > SLOW_WORK_MS
  )
}

/** Whether a WebGL renderer draws on the CPU: SwiftShader, llvmpipe, Microsoft's and the like. */
export function isSoftwareRenderer(renderer: string) {
  return /swiftshader|llvmpipe|softpipe|software|basic render/i.test(renderer)
}

/** The WebGL renderer's name, from a context made for the purpose and let go at once. */
function getRenderer() {
  const canvas = document.createElement('canvas')
  const gl = canvas.getContext('webgl2') ?? canvas.getContext('webgl')

  if (!gl) {
    return undefined
  }

  // Chromium names the renderer only through the extension; Firefox names it anyway.
  const info = gl.getExtension('WEBGL_debug_renderer_info')
  const renderer = String(gl.getParameter(info ? info.UNMASKED_RENDERER_WEBGL : gl.RENDERER))

  gl.getExtension('WEBGL_lose_context')?.loseContext()
  return renderer
}

/**
 * The middle of three runs of the work, after one to let the engine compile it: one run
 * slowed by something else, or one that got lucky, doesn't move it.
 */
function measureWork() {
  const runs: number[] = []

  for (let run = 0; run < 4; run++) {
    const start = performance.now()
    work()

    if (run > 0) {
      runs.push(performance.now() - start)
    }
  }

  return runs.sort((first, second) => first - second)[1]!
}

/** Points made, projected and sorted, as a map does with its tiles' features. */
function work() {
  const points: { x: number; y: number; z: number }[] = []

  for (let index = 0; index < 24_000; index++) {
    const angle = index * 0.01
    points.push({ x: Math.cos(angle) * index, y: Math.sin(angle) * index, z: Math.sqrt(index) })
  }

  for (const point of points) {
    const scale = 1 / (1 + point.z * 0.001)
    point.x *= scale
    point.y *= scale
  }

  points.sort((first, second) => first.x - second.x || first.y - second.y)
  return points[0]?.x
}
