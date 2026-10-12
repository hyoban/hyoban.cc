import 'number-flow'
import { getIconBody, ICON_CLASS, type IconName } from '../icons.ts'
import { MOSAIC_CLASS, MOSAIC_IMAGE_CLASS } from './intro.ts'
import type { TripImage } from './types.ts'

// Small DOM builders and helpers the player and its cards share.

// The side card's photo is at most this wide, so the browser picks a fitting source.
export const IMAGE_SIZES = '(max-width: 48rem) calc(100vw - 3.5rem), 20rem'

export function createElement(tag: string, className: string, text: string) {
  const element = document.createElement(tag)
  element.className = className
  element.textContent = text
  return element
}

/** The class the map's hover names share, styled in index.css. */
export const MAP_POPUP_CLASS = 'calendar-map-popup'

/**
 * A hover name on the map, for a route, a place or a group of them: a small grey line
 * over the name, and a hint under it when there is one.
 */
export function createPopupContent({
  hint,
  meta,
  title,
}: {
  hint?: string | undefined
  meta: string
  title: string
}) {
  const content = document.createElement('div')

  content.append(
    createElement('p', 'calendar-map-popup__meta', meta),
    createElement('p', 'calendar-map-popup__title', title),
  )

  if (hint) {
    content.append(createElement('p', 'calendar-map-popup__hint', hint))
  }

  return content
}

export function queryRequired<T extends Element>(root: ParentNode, selector: string) {
  const element = root.querySelector<T>(selector)

  if (!element) {
    throw new Error(`Missing required calendar map element: ${selector}`)
  }

  return element
}

/** Reads JSON the page inlined in a `<script type="application/json">`. */
export function parseJson<T>(root: ParentNode, selector: string, fallback: T): T {
  const text = root.querySelector(selector)?.textContent

  try {
    return text ? (JSON.parse(text) as T) : fallback
  } catch {
    return fallback
  }
}

/**
 * A number that counts up from zero once it is on the page, its digits rolling into
 * place (number-flow; skipped for reduced motion).
 */
export function createCountUp(value: number) {
  const flow = document.createElement('number-flow')

  flow.locales = 'zh-CN'
  flow.update(0)
  // A frame later it is in the page, so the change animates.
  requestAnimationFrame(() => flow.update(value))
  return flow
}

/**
 * Wraps a read so it runs at most once per animation frame; calls later in the same
 * frame get the first result.
 */
export function perFrame<T>(read: () => T) {
  let time: CSSNumberish | null | undefined
  let value: T

  return () => {
    const now = document.timeline.currentTime

    if (now === null || now !== time) {
      time = now
      value = read()
    }

    return value
  }
}

/** Starts loading a photo at the size the side card will show it. */
export function preloadImage(source: TripImage) {
  const image = new Image()
  image.sizes = IMAGE_SIZES
  image.srcset = source.srcset
  image.src = source.src
  return image
}

/** Starts loading a thumbnail, as the thumbnail strip shows it. */
export function preloadThumbnail(source: TripImage) {
  const image = new Image()
  image.src = source.thumbnail
  return image
}

/** A grid of the trip's cover photos for the intro and summary cards. */
export function createMosaic(covers: readonly TripImage[]) {
  const mosaic = createElement('div', MOSAIC_CLASS, '')

  mosaic.setAttribute('aria-hidden', 'true')

  for (const cover of covers) {
    const image = document.createElement('img')
    image.className = MOSAIC_IMAGE_CLASS
    image.src = cover.thumbnail
    image.alt = ''
    image.decoding = 'async'
    mosaic.append(image)
  }

  return mosaic
}

/** A muted live photo clip that plays once, then fades back to the still under it. */
export function createLiveVideo(src: string, className: string) {
  const video = document.createElement('video')

  video.className = className
  video.muted = true
  video.playsInline = true
  video.src = src
  video.setAttribute('aria-hidden', 'true')
  video.addEventListener(
    'ended',
    () => {
      video.style.opacity = '0'
      setTimeout(() => video.remove(), 450)
    },
    { once: true },
  )
  return video
}

/** A stop's linked video, playing by itself while its card is up. */
export function createVideoPlayer(src: string, className: string) {
  const player = document.createElement('iframe')

  player.className = `calendar-map-video ${className}`.trim()
  player.src = src
  player.title = '哔哩哔哩视频'
  player.allow = 'autoplay; fullscreen; picture-in-picture'
  player.allowFullscreen = true
  player.referrerPolicy = 'strict-origin-when-cross-origin'
  return player
}

export function createWeather(weather: { icon: IconName; label: string }, className: string) {
  const element = createElement('p', className, '')
  element.append(createIcon(weather.icon), weather.label)
  return element
}

/** An inline icon, decorative, the size of the text around it (see styles.css). */
export function createIcon(name: IconName) {
  const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg')

  icon.setAttribute('class', ICON_CLASS)
  icon.setAttribute('viewBox', '0 0 24 24')
  icon.setAttribute('aria-hidden', 'true')
  setIcon(icon, name)
  return icon
}

/** Changes which icon an `<svg>` from `createIcon` shows. */
export function setIcon(icon: SVGSVGElement, name: IconName) {
  if (icon.dataset.icon !== name) {
    icon.dataset.icon = name
    icon.innerHTML = getIconBody(name)
  }
}

/**
 * Puts an icon in a button's icon slot, its first child, in place of whatever the
 * page rendered there.
 */
export function setButtonIcon(button: Element, name: IconName) {
  const slot = button.firstElementChild

  if (slot instanceof SVGSVGElement && slot.dataset.icon !== undefined) {
    setIcon(slot, name)
  } else if (slot) {
    slot.replaceWith(createIcon(name))
  } else {
    button.prepend(createIcon(name))
  }
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')

  link.href = url
  link.download = filename
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
