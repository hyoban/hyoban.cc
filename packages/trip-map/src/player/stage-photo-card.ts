import { createElement, createLiveVideo, createVideoPlayer } from './dom.ts'
import { FADE_IN, FADE_OUT, PHOTO_SWITCH, prefersReducedMotion } from './motion.ts'
import type { Messages } from '../messages.ts'
import type { TripDataStop } from './types.ts'

/**
 * The stop's photo card in the story layout: one card per stop, its words below a
 * frame that eases to each photo's shape as the photos fade through.
 */
export function createStagePhotoCard(container: HTMLElement, messages: Messages) {
  let current: { frame?: HTMLElement; frameWidth: number; photoMax: number } | undefined

  /** Sends the current card off; another can come in meanwhile. */
  function clear() {
    current = undefined

    for (const card of container.children) {
      leave(card as HTMLElement)
    }
  }

  function leave(card: HTMLElement) {
    if (card.dataset.leaving !== undefined) {
      return
    }

    card.dataset.leaving = ''
    setTimeout(() => card.remove(), prefersReducedMotion() ? 0 : 300)
  }

  /**
   * Builds the stop's card with its full text first, then gives the photo frame
   * whatever height is left, so the card always fits and the text is never cut.
   */
  function show(stop: TripDataStop, stopIndex: number) {
    const card = document.createElement('figure')
    const frame =
      stop.images.length > 0
        ? createElement('div', 'trip-map-polaroid__frame', '')
        : stop.video
          ? createVideoPlayer(stop.video, 'trip-map-polaroid__video', messages.videoTitle)
          : undefined

    clear()

    // The place, day and time are on the map and header, so the card is just the
    // photos and the Moment's words.
    if (!frame && !stop.text) {
      return
    }

    card.className = 'trip-map-polaroid'
    card.dataset.tilt = stopIndex % 2 === 0 ? 'left' : 'right'
    card.append(...(frame ? [frame] : []))

    if (stop.text) {
      const caption = document.createElement('figcaption')
      caption.append(createElement('p', 'trip-map-polaroid__text', stop.text))
      card.append(caption)
    }

    container.append(card)
    current = {
      ...(frame ? { frame } : {}),
      frameWidth: frame?.clientWidth ?? 0,
      photoMax: Math.max(container.clientHeight - card.offsetHeight, 0),
    }
  }

  /** Cross-fades to another photo while the frame eases to its height. */
  function showPhoto(stop: TripDataStop, imageIndex: number) {
    const image = stop.images[imageIndex]
    const frame = current?.frame

    if (!current || !frame || !image || frame instanceof HTMLIFrameElement) {
      return
    }

    const ratio = image.width / image.height
    const height = Math.min(current.frameWidth / ratio, current.photoMax)
    const img = document.createElement('img')
    const firstPhoto = frame.childElementCount === 0

    img.src = image.src
    img.srcset = image.srcset
    img.sizes = `${Math.round(current.frameWidth)}px`
    img.alt = ''
    img.style.width = `${height * ratio}px`
    img.style.height = `${height}px`

    const previous = [...frame.querySelectorAll<HTMLImageElement>('img')].at(-1)
    const animate = !firstPhoto && !prefersReducedMotion()

    // Fades through, as on the side card: the old photo goes before the new one shows.
    for (const old of frame.querySelectorAll<HTMLElement>('img, video')) {
      if (animate) {
        void old
          .animate(FADE_OUT, PHOTO_SWITCH)
          .finished.catch(() => {})
          .finally(() => old.remove())
      } else {
        old.remove()
      }
    }

    if (firstPhoto) {
      // The card's own entrance animation covers the first photo.
      frame.style.transition = 'none'
      frame.style.height = `${height}px`
      void frame.offsetHeight
      frame.style.transition = ''
    } else {
      frame.style.height = `${height}px`
    }

    frame.append(img)

    if (animate) {
      // Grows or shrinks from the old photo's size along with the frame.
      img.animate(
        [
          {
            ...FADE_IN[0],
            height: previous?.style.height ?? img.style.height,
            width: previous?.style.width ?? img.style.width,
          },
          FADE_IN[1]!,
          { ...FADE_IN[2], height: img.style.height, width: img.style.width },
        ],
        PHOTO_SWITCH,
      )
    }

    // A live photo moves once over its still, as on the side card.
    if (image.video && !prefersReducedMotion()) {
      const video = createLiveVideo(image.video, '')

      video.style.width = img.style.width
      video.style.height = img.style.height
      frame.append(video)

      if (animate) {
        video.animate(FADE_IN, PHOTO_SWITCH)
      }
      void video.play().catch(() => video.remove())
    }
  }

  return { clear, show, showPhoto }
}
