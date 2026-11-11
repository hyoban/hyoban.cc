/* oxlint-disable typescript/unbound-method -- Method references here are compared for identity, not invoked. */
import assert from 'node:assert/strict'
import { afterEach, test, vi } from 'vite-plus/test'
import { installLocalGlyphWorker } from '../src/map/local-glyphs.ts'

class Bitmap {
  constructor({ width, height }, data = new Uint8Array(width * height)) {
    Object.assign(this, { width, height, data })
  }

  clone() {
    return new Bitmap(this, new Uint8Array(this.data))
  }
}

class GlyphWorker {
  static instances = []
  messages = []
  terminated = false

  constructor() {
    GlyphWorker.instances.push(this)
  }

  postMessage(message) {
    this.messages.push(message)
  }

  reply() {
    for (const message of this.messages.splice(0)) {
      this.onmessage({
        data: {
          requestId: message.requestId,
          glyphs: message.ids.map((id) => ({
            id,
            width: 2,
            height: 2,
            data: new Uint8ClampedArray([id % 256, 1, 2, 3]),
            glyphWidth: 2,
            glyphHeight: 2,
            glyphLeft: 2,
            glyphTop: 8,
            glyphAdvance: 4,
          })),
        },
      })
    }
  }

  terminate() {
    this.terminated = true
  }
}

function manager() {
  return {
    localFontFamily: 'sans-serif',
    localGlyphs: { 200: {}, 400: {}, 500: {}, 900: {} },
    nativeDraws: 0,
    _doesCharSupportLocalGlyph: (id) => id >= 0x4e00 && id <= 0x9fff,
    getGlyphs(stacks, callback) {
      const result = {}

      for (const [stack, ids] of Object.entries(stacks)) {
        const weight = /bold/i.test(stack) ? '900' : /medium/i.test(stack) ? '500' : '400'
        const glyphs = {}

        for (const id of ids) {
          let glyph = this.localGlyphs[weight][id]

          if (!glyph) {
            this.nativeDraws++
            glyph = {
              id,
              bitmap: new Bitmap({ width: 2, height: 2 }),
              metrics: { width: 1, height: 1, left: 1, top: -23, advance: 2, localGlyph: true },
            }
            this.localGlyphs[weight][id] = glyph
          }

          glyphs[id] = { ...glyph, bitmap: glyph.bitmap.clone() }
        }

        result[stack] = { glyphs, ascender: 20, descender: -4 }
      }

      callback(null, result)
    },
  }
}

const cleanups = []

function setup() {
  vi.stubGlobal('Worker', GlyphWorker)
  vi.stubGlobal('OffscreenCanvas', class {})
  const controller = new AbortController()
  const listeners = new Map()
  const map = {
    style: { glyphManager: manager() },
    on(type, listener) {
      listeners.set(type, listener)
    },
    off(type, listener) {
      if (listeners.get(type) === listener) listeners.delete(type)
    },
  }

  cleanups.push(() => controller.abort())
  installLocalGlyphWorker(map, controller.signal)
  return { map, controller, emit: (type) => listeners.get(type)?.(), listeners }
}

function request(manager, stacks) {
  return new Promise((resolve, reject) => {
    manager.getGlyphs(stacks, (error, glyphs) => (error ? reject(error) : resolve(glyphs)))
  })
}

afterEach(() => {
  for (const cleanup of cleanups.splice(0)) cleanup()
  GlyphWorker.instances = []
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

test('deduplicates overlapping requests and lets Mapbox clone worker glyphs for each tile', async () => {
  const { map } = setup()
  const glyphs = map.style.glyphManager
  const worker = GlyphWorker.instances[0]
  const first = request(glyphs, { Regular: [19968, 19969, 19970] })
  const second = request(glyphs, { Regular: [19969, 19970, 19971] })

  assert.equal(glyphs.nativeDraws, 1)
  assert.deepEqual(
    worker.messages.flatMap((message) => message.ids),
    [19969, 19970, 19971],
  )
  worker.reply()
  const [a, b] = await Promise.all([first, second])

  assert.equal(glyphs.nativeDraws, 1)
  assert.equal(a.Regular.ascender, 20)
  assert.equal(a.Regular.descender, -4)
  assert.deepEqual(Object.keys(a.Regular.glyphs), ['19968', '19969', '19970'])
  assert.deepEqual(a.Regular.glyphs[19969].metrics, {
    width: 1,
    height: 1,
    left: 1,
    top: -23,
    advance: 2,
    localGlyph: true,
  })
  assert.deepEqual([...a.Regular.glyphs[19969].bitmap.data], [19969 % 256, 1, 2, 3])
  assert.notEqual(a.Regular.glyphs[19969].bitmap.data, b.Regular.glyphs[19969].bitmap.data)

  await request(glyphs, { Regular: [19969, 19970] })
  assert.equal(worker.messages.length, 0)
  assert.equal(glyphs.nativeDraws, 1)
})

test('keeps weights separate, bounds transfers and delegates characters outside CJK', async () => {
  const { map } = setup()
  const glyphs = map.style.glyphManager
  const worker = GlyphWorker.instances[0]
  const latin = await request(glyphs, { Regular: [65] })
  assert.ok(latin.Regular.glyphs[65])
  assert.equal(worker.messages.length, 0)

  const ids = Array.from({ length: 140 }, (_, index) => 19968 + index)
  const all = request(glyphs, { Regular: ids, Bold: ids, Medium: ids })
  assert.deepEqual(
    [...new Set(worker.messages.map((message) => message.weight))],
    ['400', '900', '500'],
  )
  assert.ok(worker.messages.every((message) => message.ids.length <= 64))
  worker.reply()
  const result = await all
  assert.equal(Object.keys(result.Bold.glyphs).length, 140)
  assert.equal(Object.keys(result.Medium.glyphs).length, 140)
  assert.equal(glyphs.nativeDraws, 2)
})

test('falls back to Mapbox if the worker errors or does not answer', async () => {
  vi.useFakeTimers()
  const { map } = setup()
  const glyphs = map.style.glyphManager
  const result = request(glyphs, { Regular: [19968, 19969] })

  await vi.advanceTimersByTimeAsync(10_000)
  assert.ok((await result).Regular.glyphs[19969])
  assert.equal(GlyphWorker.instances[0].terminated, true)
  assert.equal(glyphs.nativeDraws, 2)
  await request(glyphs, { Regular: [19970] })
  assert.equal(glyphs.nativeDraws, 3)
})

test('recovers from a worker error with multiple requests in flight', async () => {
  const { map } = setup()
  const glyphs = map.style.glyphManager
  const first = request(glyphs, { Regular: [19968, 19969] })
  const second = request(glyphs, { Bold: [19969, 19970] })
  const worker = GlyphWorker.instances[0]
  let prevented = false

  worker.onerror({
    message: 'Worker unavailable',
    preventDefault: () => {
      prevented = true
    },
  })
  const result = await Promise.all([first, second])
  assert.equal(prevented, true)
  assert.equal(worker.terminated, true)
  assert.ok(result[0].Regular.glyphs[19969])
  assert.ok(result[1].Bold.glyphs[19970])
})

test('reinstalls after a style change and releases its worker on map removal', async () => {
  const { map, emit, listeners } = setup()
  const previous = map.style.glyphManager
  const previousWrapped = previous.getGlyphs
  let staleCallbacks = 0

  previous.getGlyphs({ Regular: [19968, 19969] }, () => staleCallbacks++)
  const next = manager()
  map.style.glyphManager = next
  emit('style.load')
  assert.notEqual(previous.getGlyphs, previousWrapped)

  const result = request(next, { Regular: [19968, 19969] })
  GlyphWorker.instances[0].reply()
  assert.ok((await result).Regular.glyphs[19969])
  assert.equal(staleCallbacks, 0)
  emit('remove')
  assert.equal(GlyphWorker.instances[0].terminated, true)
  assert.equal(listeners.size, 0)
})

test('does not deliver queued work to an aborted map', async () => {
  const { map, controller } = setup()
  let callbacks = 0

  map.style.glyphManager.getGlyphs({ Regular: [19968, 19969] }, () => callbacks++)
  controller.abort()
  GlyphWorker.instances[0].reply()
  await Promise.resolve()
  await Promise.resolve()
  assert.equal(callbacks, 0)
  assert.equal(GlyphWorker.instances[0].terminated, true)
})

test('keeps native rasterization when OffscreenCanvas is unavailable or worker startup throws', () => {
  vi.stubGlobal('OffscreenCanvas', undefined)
  const map = { style: { glyphManager: manager() } }
  const original = map.style.glyphManager.getGlyphs
  installLocalGlyphWorker(map, new AbortController().signal)
  assert.equal(map.style.glyphManager.getGlyphs, original)

  vi.stubGlobal('OffscreenCanvas', class {})
  vi.stubGlobal(
    'Worker',
    class {
      constructor() {
        throw new Error('Worker blocked')
      }
    },
  )
  installLocalGlyphWorker(map, new AbortController().signal)
  assert.equal(map.style.glyphManager.getGlyphs, original)
})
