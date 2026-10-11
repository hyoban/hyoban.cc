import 'number-flow'
import type { TripImage } from './types'

// Small DOM builders and helpers the player and its cards share.

// The side card's photo is at most this wide, so the browser picks a fitting source.
export const IMAGE_SIZES = '(max-width: 48rem) calc(100vw - 3.5rem), 20rem'

export function createElement(tag: string, className: string, text: string) {
  const element = document.createElement(tag)
  element.className = className
  element.textContent = text
  return element
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
  // Narrow screens keep one row so the map stays visible above the card.
  const mosaic = createElement(
    'div',
    'mt-3 grid shrink-0 grid-cols-3 gap-1 @max-[48rem]:[&>:nth-child(n+4)]:hidden',
    '',
  )

  mosaic.setAttribute('aria-hidden', 'true')

  for (const cover of covers) {
    const image = document.createElement('img')
    image.className = 'block aspect-square w-full rounded-[0.3125rem] bg-inline object-cover'
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

export function createWeather(weather: { icon: string; label: string }, className: string) {
  const element = createElement('p', className, '')
  const icon = createElement('span', weather.icon, '')

  icon.setAttribute('aria-hidden', 'true')
  element.append(icon, weather.label)
  return element
}

/** A Lucide icon as an image, read from the mask its utility class generates. */
export async function loadIconImage(className: string) {
  const probe = document.createElement('span')

  probe.className = className
  probe.style.position = 'absolute'
  probe.style.visibility = 'hidden'
  document.body.append(probe)

  const style = getComputedStyle(probe)
  const mask = style.maskImage || style.getPropertyValue('-webkit-mask-image')
  const url = /url\(["']?(.*?)["']?\)$/.exec(mask)?.[1]

  probe.remove()

  if (!url) {
    return undefined
  }

  const image = new Image()
  image.src = url

  try {
    await image.decode()
    return image
  } catch {
    return undefined
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

/**
 * Redraws a captured video track onto a canvas of a fixed size. A tab capture can
 * change size mid-recording (a crop settling, the window or zoom changing), and an
 * avc1 MP4 only records the first size, so later frames decode as garbage.
 */
export function createSteadyTrack(source: MediaStreamTrack, width: number, height: number) {
  const video = document.createElement('video')
  const canvas = document.createElement('canvas')
  const context = canvas.getContext('2d')
  const [track] = canvas.captureStream(30).getVideoTracks()
  let stopped = false
  let markReady: () => void
  const ready = new Promise<void>((resolve) => (markReady = resolve))

  canvas.width = width
  canvas.height = height
  video.muted = true
  video.playsInline = true
  video.srcObject = new MediaStream([source])

  const draw = () => {
    if (stopped) {
      return
    }

    if (video.videoWidth > 0) {
      context?.drawImage(video, 0, 0, width, height)
      markReady()
    }

    video.requestVideoFrameCallback(draw)
  }

  video.requestVideoFrameCallback(draw)
  void video.play().catch(() => {})

  return {
    /** Settles once the first frame is on the canvas. */
    ready,
    stop() {
      stopped = true
      video.srcObject = null
      track?.stop()
    },
    track,
  }
}
