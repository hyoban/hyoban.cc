import TinySDF from '@mapbox/tiny-sdf'
import type { GlyphRequest, GlyphResponse } from './local-glyphs.ts'

const rasterizers = new Map<string, TinySDF>()

globalThis.onmessage = (event: MessageEvent<GlyphRequest>) => {
  const { requestId, fontFamily, weight, ids } = event.data

  try {
    const key = `${fontFamily}:${weight}`
    let rasterizer = rasterizers.get(key)

    if (!rasterizer) {
      // Mapbox GL JS 3.33 rasterizes at twice its 24px glyph size.
      rasterizer = new TinySDF({
        fontFamily,
        fontWeight: weight,
        fontSize: 48,
        buffer: 6,
        radius: 16,
      })
      rasterizers.set(key, rasterizer)
    }

    const glyphs = ids.map((id) => ({ id, ...rasterizer.draw(String.fromCodePoint(id)) }))
    const response: GlyphResponse = { requestId, glyphs }

    globalThis.postMessage(response, { transfer: glyphs.map((glyph) => glyph.data.buffer) })
  } catch (cause) {
    const response: GlyphResponse = {
      requestId,
      error: cause instanceof Error ? cause.message : String(cause),
    }

    globalThis.postMessage(response)
  }
}
