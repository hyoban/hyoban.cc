import type { PanelElements } from '../elements.ts'
import { formatDate } from '../format.ts'
import { t, type Messages } from '../messages.ts'
import {
  createCountUp,
  createElement,
  createIcon,
  createLiveVideo,
  createMosaic,
  createVideoPlayer,
  createWeather,
  IMAGE_SIZES,
  preloadImage,
} from './dom.ts'
import { getTripMeta } from './intro.ts'
import { getSummaryStats } from './labels.ts'
import { createTransition, easeCardChange, easePhotoChange } from './motion.ts'
import {
  getPhotoAt,
  getSlideLabel,
  getStopEyebrow,
  getStopName,
  type SlideTiming,
} from './slides.ts'
import { getProgressAmount } from './timeline.ts'
import type { Slide, TripData, TripDataStop, TripImage } from './types.ts'

// The side card: the progress bar along its top and, below, the card for the slide
// showing, which eases from one to the next. A stop's card shows its photos, which
// fade through, its thumbnails and its words.

// The side card slides in from just below as a trip opens, and back down as it closes.
const PANEL_HIDDEN: Keyframe[] = [
  { opacity: 1, transform: 'none' },
  { opacity: 0, transform: 'translateY(0.75rem) scale(0.98)' },
]
const PANEL_MOTION: KeyframeAnimationOptions = {
  duration: 280,
  easing: 'cubic-bezier(0.22, 1, 0.36, 1)',
}

/** What the card shows for a slide. */
export type CardView = {
  covers: readonly TripImage[]
  index: number
  slide: Slide
  slideCount: number
  trip: TripData
}

export type SideCard = ReturnType<typeof createSideCard>

export function createSideCard({
  elements,
  isStageLayout,
  messages,
  onJump,
  onPhotoShown,
  onReplay,
  onSelectPhoto,
  onStepPhoto,
  reducedMotion,
  signal,
}: {
  elements: PanelElements
  /** Presenting or recording, the stage's cards stand in for this one. */
  isStageLayout: () => boolean
  messages: Messages
  /** A progress segment was chosen. */
  onJump: (index: number) => void
  /** A stop's photo came up; the stage's photo card shows the same one. */
  onPhotoShown: (stop: TripDataStop, imageIndex: number) => void
  onReplay: () => void
  /** A thumbnail was chosen: playback moves to that photo's time. */
  onSelectPhoto: (stop: TripDataStop, imageIndex: number) => void
  /** A swipe on the photo. */
  onStepPhoto: (direction: 1 | -1) => void
  reducedMotion: MediaQueryList
  signal: AbortSignal
}) {
  const { announcer, body, eyebrow, header, next, panel, previous, progress, title } = elements
  // Names the slide a progress segment jumps to, under the hovered or focused segment.
  const progressTip = createElement('div', 'trip-map-tip', '')
  // The photo showing, for a resize.
  let currentImage: TripImage | undefined
  const cardTransition = createTransition()
  const photoTransition = createTransition()
  let photo: HTMLImageElement | undefined
  let photoIndex = 0
  let thumbnails: HTMLButtonElement[] = []
  let liveVideo: HTMLVideoElement | undefined
  // A mouse resting on the card holds the current slide so it can be read.
  let hovering = false

  progressTip.hidden = true
  progressTip.setAttribute('aria-hidden', 'true')
  panel.append(progressTip)

  /** Renders the next card with `update`, easing the side card between the two. */
  function change(update: () => void) {
    if (panel.hidden || reducedMotion.matches || isStageLayout()) {
      cardTransition.end()
      update()
      return
    }

    easeCardChange(cardTransition, { body, header, panel }, update)
  }

  /** Shows the next photo with `update`, fading the side card's photo through. */
  function changePhoto(update: () => void) {
    if (!photo || reducedMotion.matches) {
      photoTransition.end()
      update()
      return
    }

    easePhotoChange(photoTransition, photo, () => liveVideo, update)
  }

  /** Plays a live photo's motion once over its still, then fades back to the still. */
  function playLivePhoto(image: TripImage) {
    const frame = photo?.parentElement

    liveVideo?.remove()
    liveVideo = undefined

    // The stage's photo card plays its own copy.
    if (!image.video || !frame || reducedMotion.matches || isStageLayout()) {
      return
    }

    liveVideo = createLiveVideo(image.video, 'trip-map-card__live')
    frame.append(liveVideo)
    void liveVideo.play().catch(() => liveVideo?.remove())
  }

  /**
   * Gives the side card's photo its size up front, from its proportions and the room
   * the card has, so the card's layout is right before the photo has loaded rather
   * than jumping when it arrives.
   */
  function sizePhoto(img: HTMLImageElement, image: TripImage) {
    const frame = img.parentElement
    const box = frame?.parentElement

    if (!frame || !box) {
      return
    }

    const boxStyle = getComputedStyle(box)
    // The card's content width, less the frame's border.
    const available =
      box.clientWidth -
      Number.parseFloat(boxStyle.paddingLeft) -
      Number.parseFloat(boxStyle.paddingRight) -
      (frame.offsetWidth - frame.clientWidth)

    img.style.width = ''
    img.style.height = ''

    const maxHeight = Number.parseFloat(getComputedStyle(img).maxHeight) || Infinity
    const ratio = image.width / image.height
    const width = Math.min(available, maxHeight * ratio)

    img.style.width = `${width}px`
    img.style.height = `${width / ratio}px`
  }

  function showPhoto(stop: TripDataStop, nextPhotoIndex: number) {
    const image = stop.images[nextPhotoIndex]

    if (!photo || !image) {
      return
    }

    photoIndex = nextPhotoIndex
    currentImage = image
    onPhotoShown(stop, nextPhotoIndex)

    photo.srcset = image.srcset
    photo.src = image.src
    photo.width = image.width
    photo.height = image.height
    photo.alt = image.alt
    sizePhoto(photo, image)
    playLivePhoto(image)

    for (const [thumbnailIndex, thumbnail] of thumbnails.entries()) {
      if (thumbnailIndex === nextPhotoIndex) {
        thumbnail.setAttribute('aria-current', 'true')
        thumbnail.scrollIntoView({
          behavior: reducedMotion.matches ? 'auto' : 'smooth',
          block: 'nearest',
          inline: 'nearest',
        })
      } else {
        thumbnail.removeAttribute('aria-current')
      }
    }
  }

  /**
   * Swiping the photo sideways moves to the next or previous one. The photo is also a
   * link to the day's page, so a swipe swallows the click it ends with; a tap still
   * opens the page. Vertical drags are left to scroll the card.
   */
  function addPhotoSwipe(link: HTMLElement) {
    let start: { x: number; y: number } | undefined
    let swiped = false

    link.style.touchAction = 'pan-y'
    link.addEventListener(
      'pointerdown',
      (event) => {
        start = { x: event.clientX, y: event.clientY }
        swiped = false
      },
      { signal },
    )
    link.addEventListener(
      'pointerup',
      (event) => {
        const dx = event.clientX - (start?.x ?? event.clientX)
        const dy = event.clientY - (start?.y ?? event.clientY)

        start = undefined

        if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy) * 1.5) {
          swiped = true
          onStepPhoto(dx < 0 ? 1 : -1)
        }
      },
      { signal },
    )
    link.addEventListener(
      'click',
      (event) => {
        if (swiped) {
          event.preventDefault()
          swiped = false
        }
      },
      { signal },
    )
    // A dragged link would otherwise start a native drag of the image.
    link.addEventListener('dragstart', (event) => event.preventDefault(), { signal })
  }

  function createThumbnails(stop: TripDataStop) {
    const list = createElement('div', 'trip-map-thumbs', '')

    thumbnails = stop.images.map((image, imageIndex) => {
      const button = createElement('button', 'trip-map-thumb', '') as HTMLButtonElement
      const thumbnail = document.createElement('img')

      button.type = 'button'
      button.setAttribute('aria-label', t(messages, 'viewPhoto', { index: imageIndex + 1 }))
      thumbnail.className = 'trip-map-thumb__image'
      thumbnail.src = image.thumbnail
      thumbnail.alt = ''
      thumbnail.decoding = 'async'
      button.append(thumbnail)
      button.addEventListener('click', () => onSelectPhoto(stop, imageIndex), { signal })
      return button
    })
    list.append(...thumbnails)
    return list
  }

  /** Renders the card for a slide. */
  function render({ covers, index, slide, slideCount, trip }: CardView) {
    // A stop's day and time ride with the car and its places are labelled on the map,
    // so the card shows no heading.
    header.classList.toggle('trip-map-sr-only', slide.type === 'stop')
    body.classList.toggle('trip-map-panel__body--stop', slide.type === 'stop')
    previous.disabled = index === 0
    next.disabled = index === slideCount - 1

    if (slide.type === 'intro') {
      eyebrow.textContent = ''
      title.textContent = trip.title
      body.replaceChildren(
        createElement('p', 'trip-map-card__meta', getTripMeta(trip, messages)),
        createMosaic(covers),
      )
    } else if (slide.type === 'day') {
      const weather = trip.weather[slide.day]

      eyebrow.textContent = t(messages, 'dayLabel', { day: slide.day })
      title.textContent = formatDate(slide.dateKey, messages.locale, { weekday: true })
      body.replaceChildren(
        ...(weather ? [createWeather(weather, 'trip-map-card__weather')] : []),
        createElement('p', 'trip-map-card__meta', slide.places.join(' → ')),
      )
    } else if (slide.type === 'stop') {
      const { stop } = slide
      const text = createElement('p', 'trip-map-card__text', stop.text)
      const link = document.createElement('a')
      const date = formatDate(stop.dateKey, messages.locale, { weekday: true })

      eyebrow.textContent = getStopEyebrow(stop, messages)
      title.textContent = getStopName(stop, messages)
      link.href = stop.href

      photo = undefined
      thumbnails = []
      photoIndex = 0

      if (stop.images.length > 0) {
        photo = document.createElement('img')
        link.className = 'trip-map-card__photo-link'
        link.setAttribute('aria-label', t(messages, 'viewDayMoments', { date }))
        photo.className = 'trip-map-card__photo'
        photo.sizes = IMAGE_SIZES
        photo.decoding = 'async'
        link.append(photo)

        if (stop.images.length > 1) {
          addPhotoSwipe(link)
        }
      } else {
        link.className = 'trip-map-card__link'
        link.textContent = messages.viewDayLink
      }

      // Text sits last, next to the fixed footer, so photos of other sizes don't move it.
      body.replaceChildren(
        // Presenting or recording, the stage's card plays it instead.
        ...(stop.video && stop.images.length === 0 && !isStageLayout()
          ? [createVideoPlayer(stop.video, 'trip-map-card__video', messages.videoTitle)]
          : []),
        link,
        ...(stop.images.length > 1 ? [createThumbnails(stop)] : []),
        ...(stop.text ? [text] : []),
      )
      showPhoto(stop, 0)

      for (const image of stop.images.slice(1)) {
        preloadImage(image)
      }
    } else {
      const stats = document.createElement('dl')
      const replay = createElement(
        'button',
        'trip-map-card__replay',
        messages.replay,
      ) as HTMLButtonElement

      eyebrow.textContent = ''
      title.textContent = trip.title
      stats.className = 'trip-map-card__stats'

      for (const [value, label] of getSummaryStats(trip, messages)) {
        const item = createElement('div', 'trip-map-card__stat', '')
        const number = createElement('dd', 'trip-map-card__stat-value', '')

        number.append(createCountUp(value))
        item.append(createElement('dt', 'trip-map-card__stat-label', label), number)
        stats.append(item)
      }

      replay.type = 'button'
      replay.prepend(createIcon('rotate-ccw'))
      replay.addEventListener('click', onReplay, { signal })
      body.replaceChildren(stats, replay, createMosaic(covers))
    }
  }

  /** Builds the progress bar: one segment per slide. */
  function buildProgress(slides: readonly Slide[]) {
    progress.replaceChildren(
      ...slides.map((slide, slideIndex) => {
        const item = createElement('li', 'trip-map-progress__item', '')
        item.dataset.slide = slide.type
        // A taller transparent hit area around the thin bar, so a segment is easy to tap.
        const button = createElement('button', 'trip-map-progress__button', '') as HTMLButtonElement
        const bar = createElement('span', 'trip-map-progress__bar', '')
        const fill = createElement('span', 'trip-map-progress__fill', '')

        button.type = 'button'
        button.setAttribute('aria-label', getSlideLabel(slide, messages))
        // A pointer is handled by the bar as a whole (see the scrubbing below); this
        // is Enter or Space on a focused segment.
        button.addEventListener(
          'click',
          (event) => {
            if (event.detail === 0) {
              onJump(slideIndex)
            }
          },
          { signal },
        )
        button.addEventListener(
          'pointerenter',
          (event) => {
            if (event.pointerType === 'mouse') {
              showTip(button)
            }
          },
          { signal },
        )
        button.addEventListener('pointerleave', hideTip, { signal })
        button.addEventListener(
          'focus',
          () => {
            if (button.matches(':focus-visible')) {
              showTip(button)
            }
          },
          { signal },
        )
        button.addEventListener('blur', hideTip, { signal })
        bar.dataset.progressBar = ''
        fill.dataset.progressFill = ''
        bar.append(fill)
        button.append(bar)
        item.append(button)
        return item
      }),
    )
  }

  function showTip(button: HTMLElement) {
    const label = button.getAttribute('aria-label')

    if (!label || panel.hidden) {
      return
    }

    progressTip.textContent = label
    progressTip.hidden = false

    const panelRect = panel.getBoundingClientRect()
    const buttonRect = button.getBoundingClientRect()
    const width = progressTip.offsetWidth
    const center = buttonRect.left + buttonRect.width / 2 - panelRect.left
    const left = Math.min(Math.max(center - width / 2, 8), panelRect.width - width - 8)

    progressTip.style.left = `${left}px`
    progressTip.style.top = `${buttonRect.bottom - panelRect.top + 2}px`
  }

  function hideTip() {
    progressTip.hidden = true
  }

  /** Fills the progress bar up to the current slide's `elapsed` of `duration`. */
  function renderProgress(index: number, elapsed: number, duration: number | undefined) {
    for (const [itemIndex, item] of [...progress.children].entries()) {
      const fill = item.querySelector<HTMLElement>('[data-progress-fill]')

      if (!fill) {
        continue
      }

      if (itemIndex === index) {
        item.firstElementChild?.setAttribute('aria-current', 'step')
      } else {
        item.firstElementChild?.removeAttribute('aria-current')
      }

      fill.style.scale = `${getProgressAmount(itemIndex, index, elapsed, duration)} 1`
    }
  }

  /** Brings the photo up to date with how far through its photos a stop is. */
  function syncPhoto(stop: TripDataStop, share: number, timing: SlideTiming) {
    const nextPhotoIndex = getPhotoAt(stop, share, timing)

    if (nextPhotoIndex !== photoIndex) {
      changePhoto(() => showPhoto(stop, nextPhotoIndex))
    }
  }

  /** The progress segment under `x`, clamped to the bar's ends. */
  function getProgressIndexAt(x: number) {
    const rect = progress.getBoundingClientRect()
    const fraction = Math.min(Math.max((x - rect.left) / Math.max(rect.width, 1), 0), 0.9999)

    return Math.floor(fraction * progress.children.length)
  }

  // The segments are too narrow to hit one by one on a phone, so a finger can slide
  // along the bar, naming the card under it, and lands on that card when lifted.
  let scrubIndex: number | undefined

  progress.addEventListener(
    'pointerdown',
    (event) => {
      if (event.button !== 0 || progress.children.length === 0) {
        return
      }

      scrubIndex = getProgressIndexAt(event.clientX)
      progress.setPointerCapture(event.pointerId)
    },
    { signal },
  )
  progress.addEventListener(
    'pointermove',
    (event) => {
      if (scrubIndex === undefined) {
        return
      }

      scrubIndex = getProgressIndexAt(event.clientX)
      const button = progress.children[scrubIndex]?.querySelector('button')

      if (button) {
        showTip(button)
      }
    },
    { signal },
  )
  progress.addEventListener(
    'pointerup',
    (event) => {
      const target = scrubIndex

      scrubIndex = undefined

      if (event.pointerType !== 'mouse') {
        hideTip()
      }

      if (target !== undefined) {
        onJump(target)
      }
    },
    { signal },
  )
  progress.addEventListener(
    'pointercancel',
    () => {
      scrubIndex = undefined
      hideTip()
    },
    { signal },
  )
  // Sliding along the bar mustn't scroll or pull the page.
  progress.style.touchAction = 'none'
  panel.addEventListener(
    'pointerenter',
    (event) => {
      hovering = event.pointerType === 'mouse'
      // The current progress segment breathes while the card is held (see styles.css).
      panel.toggleAttribute('data-trip-map-held', hovering)
    },
    { signal },
  )
  panel.addEventListener(
    'pointerleave',
    () => {
      hovering = false
      panel.removeAttribute('data-trip-map-held')
    },
    { signal },
  )
  window.addEventListener(
    'resize',
    () => {
      const image = currentImage

      // The photo's size follows the room the card has.
      if (photo && image && !photoTransition.running) {
        sizePhoto(photo, image)
      }
    },
    { signal },
  )
  /**
   * Leaves a copy of the card fading out where it was, since the card itself is
   * emptied and hidden at once. Its media are swapped for empty boxes the same size,
   * so the copy plays and loads nothing.
   */
  function leave() {
    if (panel.hidden || reducedMotion.matches || isStageLayout()) {
      return
    }

    cardTransition.end()
    photoTransition.end()

    const ghost = panel.cloneNode(true) as HTMLElement

    for (const media of ghost.querySelectorAll('video, iframe, audio')) {
      const box = document.createElement('div')
      box.className = media.className
      box.style.cssText = (media as HTMLElement).style.cssText
      media.replaceWith(box)
    }

    for (const element of ghost.querySelectorAll('[id]')) {
      element.removeAttribute('id')
    }

    ghost.removeAttribute('aria-labelledby')
    ghost.setAttribute('aria-hidden', 'true')
    ghost.inert = true
    ghost.style.pointerEvents = 'none'
    panel.after(ghost)
    ghost.animate(PANEL_HIDDEN, PANEL_MOTION).finished.then(
      () => ghost.remove(),
      () => ghost.remove(),
    )
    signal.addEventListener('abort', () => ghost.remove(), { once: true })
  }

  /** Empties and hides the card as a trip closes. */
  function clear() {
    panel.hidden = true
    progress.replaceChildren()
    hideTip()
    body.replaceChildren()
    photo = undefined
    thumbnails = []
    liveVideo = undefined
    currentImage = undefined
  }

  return {
    /** Tells screen readers which card came up; empty while autoplay runs. */
    announce(text: string) {
      announcer.textContent = text
    },
    buildProgress,
    change,
    changePhoto,
    clear,
    /** Shows the card; one the page didn't render slides in. */
    enter(rendered: boolean) {
      panel.hidden = false

      if (!rendered && !reducedMotion.matches && !isStageLayout()) {
        panel.animate(PANEL_HIDDEN.toReversed(), PANEL_MOTION)
      }
    },
    get hovering() {
      return hovering
    },
    hideTip,
    leave,
    get photoIndex() {
      return photoIndex
    },
    render,
    renderProgress,
    showPhoto,
    syncPhoto,
  }
}
