import type { AnimationFrameProvider } from 'mapbox-gl'

// One animation frame for the page's own work and the map's drawing. Asking the browser
// for its frame, the map may draw first in a frame, showing where the trip's camera and
// route were left the frame before, while the head on the route and the labels are put
// where they are now: the two slip a frame apart as the camera moves. Here the map draws
// last, after the work asked for with `requestFrame`, so what that work moved is drawn
// in the same frame.

type Callback = (now: number) => void

const work = new Map<number, Callback>()
const draws = new Map<number, Callback>()
let nextHandle = 1
let scheduled = false

/** Runs `callback` in the next frame, before the map draws. */
export function requestFrame(callback: Callback) {
  return enqueue(work, callback)
}

export function cancelFrame(handle: number) {
  work.delete(handle)
  draws.delete(handle)
}

/** For `animationFrameProvider`: the map's draw, after the frame's work. */
export const mapFrames: AnimationFrameProvider = {
  cancelAnimationFrame: cancelFrame,
  requestAnimationFrame: (callback) => enqueue(draws, callback),
}

function enqueue(queue: Map<number, Callback>, callback: Callback) {
  const handle = nextHandle++

  queue.set(handle, callback)

  if (!scheduled) {
    scheduled = true
    requestAnimationFrame(run)
  }

  return handle
}

function run(now: number) {
  scheduled = false
  // Work asked for while the frame runs is the next frame's; a draw asked for by this
  // frame's work is this frame's.
  flush(work, now, nextHandle)
  flush(draws, now, nextHandle)
}

function flush(queue: Map<number, Callback>, now: number, until: number) {
  // In the order asked for, which handles follow.
  for (const [handle, callback] of queue) {
    if (handle >= until) {
      break
    }

    queue.delete(handle)

    try {
      callback(now)
    } catch (error) {
      reportError(error)
    }
  }
}
