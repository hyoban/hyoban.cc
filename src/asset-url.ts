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
  const { origin, pathname } = new URL(getAssetUrl(image.key))
  const widths = new Set([
    ...RESIZED_WIDTHS.filter((width) => width < image.width),
    Math.min(image.width, 1920),
  ])

  return [...widths].map((width) => ({
    src: `${origin}/cdn-cgi/image/width=${width},format=auto,onerror=redirect${pathname}`,
    width,
  }))
}
