import type TinySDF from '@mapbox/tiny-sdf'
import type { Map as MapboxMap } from 'mapbox-gl'

type GlyphManager = NonNullable<MapboxMap['style']>['glyphManager']
type Glyph = NonNullable<GlyphManager['entries'][string]['glyphs'][number]>
type RasterizedGlyph = ReturnType<TinySDF['draw']> & { id: number }

export type GlyphRequest = {
  requestId: number
  fontFamily: string
  weight: string
  ids: number[]
}

export type GlyphResponse = {
  requestId: number
  glyphs?: RasterizedGlyph[]
  error?: string
}

/**
 * Mapbox rasterizes local glyphs synchronously. Fill its cache in a worker before
 * letting it answer tile requests, so a batch of new labels cannot hold up a frame.
 * This adapter follows Mapbox GL JS 3.33's cache, weights and glyph metrics; its
 * browser checks compare the worker's glyphs against Mapbox's own rasterizer.
 */
export function installLocalGlyphWorker(map: MapboxMap, signal: AbortSignal) {
  if (signal.aborted || typeof Worker === 'undefined' || typeof OffscreenCanvas === 'undefined') {
    return
  }

  let worker: Worker

  try {
    worker = new Worker(new URL('./local-glyph-worker.ts', import.meta.url), { type: 'module' })
  } catch {
    return
  }

  let nextId = 0
  let failed = false
  let disposed = false
  let currentManager: GlyphManager | undefined
  let restore: (() => void) | undefined
  const pending = new Map<
    number,
    {
      resolve: (glyphs: RasterizedGlyph[]) => void
      reject: (error: Error) => void
      timeout: ReturnType<typeof setTimeout>
    }
  >()

  function stop(error: Error) {
    failed = true
    worker.terminate()

    for (const request of pending.values()) {
      clearTimeout(request.timeout)
      request.reject(error)
    }

    pending.clear()
  }

  worker.onmessage = (event: MessageEvent<GlyphResponse>) => {
    const { requestId, glyphs, error } = event.data

    if (error || !glyphs) {
      stop(new Error(error ?? 'The glyph worker returned no glyphs.'))
      return
    }

    const request = pending.get(requestId)

    if (request) {
      clearTimeout(request.timeout)
      request.resolve(glyphs)
    }
    pending.delete(requestId)
  }
  worker.onerror = (event) => {
    event.preventDefault()
    stop(new Error(event.message || 'The glyph worker could not start.'))
  }
  worker.onmessageerror = () => stop(new Error('The glyph worker response could not be read.'))

  function rasterize(fontFamily: string, weight: string, ids: number[]) {
    return new Promise<RasterizedGlyph[]>((resolve, reject) => {
      if (failed || disposed) {
        reject(new Error('The glyph worker is unavailable.'))
        return
      }

      const requestId = ++nextId
      const request: GlyphRequest = { requestId, fontFamily, weight, ids }

      const timeout = setTimeout(() => stop(new Error('The glyph worker timed out.')), 10_000)

      pending.set(requestId, { resolve, reject, timeout })

      try {
        worker.postMessage(request)
      } catch (cause) {
        stop(cause instanceof Error ? cause : new Error(String(cause)))
      }
    })
  }

  function install() {
    const manager = map.style?.glyphManager

    if (manager === currentManager) {
      return
    }

    restore?.()
    restore = undefined
    currentManager = undefined

    if (!manager?.localFontFamily || failed || disposed) {
      return
    }

    currentManager = manager
    const original = manager.getGlyphs.bind(manager)
    // Mapbox's declaration calls each weight's cache a GlyphRange, but at runtime
    // it is a map from code point to StyleGlyph.
    const cache = manager.localGlyphs as unknown as Record<string, Record<number, Glyph>>
    const generating = new Map<string, Promise<void>>()
    let sample: Glyph | undefined

    manager.getGlyphs = (stacks, callback) => {
      if (failed || signal.aborted) {
        original(stacks, callback)
        return
      }

      const waiting = new Set<Promise<void>>()

      for (const [stack, ids] of Object.entries(stacks)) {
        const local = ids.filter((id) => manager._doesCharSupportLocalGlyph(id))

        if (local.length === 0) {
          continue
        }

        const weight = /bold/i.test(stack)
          ? '900'
          : /medium/i.test(stack)
            ? '500'
            : /light/i.test(stack)
              ? '200'
              : '400'

        // One native glyph gives us Mapbox's own bitmap class without copying its
        // image implementation or transferring an entire font to the worker.
        if (!sample) {
          original({ [stack]: [local[0]!] }, () => undefined)
          sample = cache[weight]?.[local[0]!]
        }

        if (!sample) {
          original(stacks, callback)
          return
        }

        const Bitmap = sample.bitmap.constructor as new (
          size: { width: number; height: number },
          data: Uint8ClampedArray,
        ) => Glyph['bitmap']
        const missing = [...new Set(local)].filter((id) => {
          const queued = generating.get(`${weight}:${id}`)

          if (queued) {
            waiting.add(queued)
          }

          return !cache[weight]?.[id] && !queued
        })

        // Transfer bounded batches, so installing their bitmap views also stays small.
        for (let offset = 0; offset < missing.length; offset += 64) {
          const batch = missing.slice(offset, offset + 64)
          const task = rasterize(manager.localFontFamily!, weight, batch).then((glyphs) => {
            for (const glyph of glyphs) {
              cache[weight]![glyph.id] = {
                id: glyph.id,
                bitmap: new Bitmap(glyph, glyph.data),
                metrics: {
                  width: glyph.glyphWidth / 2,
                  height: glyph.glyphHeight / 2,
                  left: glyph.glyphLeft / 2,
                  top: glyph.glyphTop / 2 - 27,
                  advance: glyph.glyphAdvance / 2,
                  localGlyph: true,
                },
              }
              generating.delete(`${weight}:${glyph.id}`)
            }
          })

          for (const id of batch) {
            generating.set(`${weight}:${id}`, task)
          }

          waiting.add(task)
        }
      }

      if (waiting.size === 0) {
        original(stacks, callback)
        return
      }

      // The unmodified manager still resolves remote characters, clones the bitmaps
      // for its tile workers and supplies the style's font metrics.
      const deliver = () => {
        if (!disposed && manager === currentManager) original(stacks, callback)
      }

      void Promise.all(waiting).then(deliver, deliver)
    }
    restore = () => {
      manager.getGlyphs = original
    }
  }

  install()
  map.on('style.load', install)

  function dispose() {
    if (disposed) return
    disposed = true
    map.off('style.load', install)
    map.off('remove', dispose)
    signal.removeEventListener('abort', dispose)
    stop(new Error('The map was removed.'))

    restore?.()
    restore = undefined
    currentManager = undefined
  }

  map.on('remove', dispose)
  signal.addEventListener('abort', dispose, { once: true })
}
