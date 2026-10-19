import type { PublishedImage } from '@hyoban/data-spec'
import assetConfig from './data/asset-config.json' with { type: 'json' }

export function getAssetUrl(key: string) {
  const encodedKey = key
    .split('/')
    .map((segment) => encodeURIComponent(segment))
    .join('/')

  return `${assetConfig.origin}/${encodedKey}`
}

/** The widths a kept original is resized to: what the pipeline gives its variants and still. */
const RESIZED_WIDTHS = [480, 960, 1920]

/**
 * An original the API kept as uploaded, resized by Cloudflare Images to each width up to
 * its own, in the best format the browser takes. Past the free plan's monthly limit a new
 * size falls back to the original instead of failing.
 */
export function getResizedSources(image: PublishedImage) {
  return getTransformedSources(
    image.key,
    image.width,
    (width) => `image/width=${width},format=auto,onerror=redirect`,
  )
}

/**
 * The first frame of a video the API kept as uploaded, as a JPEG from Cloudflare's Media
 * Transformations at each width up to the video's own: its poster.
 */
export function getFrameSources(key: string, width: number) {
  return getTransformedSources(
    key,
    width,
    (frameWidth) => `media/mode=frame,time=0s,width=${frameWidth},format=jpg`,
  )
}

function getTransformedSources(key: string, ownWidth: number, options: (width: number) => string) {
  const { origin, pathname } = new URL(getAssetUrl(key))
  const widths = new Set([
    ...RESIZED_WIDTHS.filter((width) => width < ownWidth),
    Math.min(ownWidth, 1920),
  ])

  return [...widths].map((width) => ({
    src: `${origin}/cdn-cgi/${options(width)}${pathname}`,
    width,
  }))
}
