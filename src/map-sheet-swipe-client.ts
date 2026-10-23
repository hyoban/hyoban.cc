// On a phone the map's sheets (a place, the lists, a trip, the settings) are pulled down
// to close: from the header, or from the content once it is scrolled to its top. The
// sheet follows the finger, and let go far or fast enough it slides away and its close
// button closes it, as a tap there would.
const listening = Symbol.for('hyoban.map-sheet-swipe')
const scope = window as unknown as Record<symbol, boolean>

const narrow = matchMedia('(width < 48rem)')
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)')
// How far the finger moves before the touch counts as a pull, or a scroll.
const SLOP = 8
// Let go past this share of the sheet's height, or faster than this (px/ms), and it closes.
const CLOSE_SHARE = 0.25
const CLOSE_VELOCITY = 0.5

type Pull = {
  sheet: HTMLElement
  content: HTMLElement | null
  startX: number
  startY: number
  lastY: number
  lastTime: number
  velocity: number
  pulling: boolean
}

let pull: Pull | undefined

if (!scope[listening]) {
  scope[listening] = true
  window.addEventListener('touchstart', onTouchStart, { passive: true })
  window.addEventListener('touchmove', onTouchMove, { passive: false })
  window.addEventListener('touchend', onTouchEnd)
  window.addEventListener('touchcancel', () => onTouchEnd())
}

function onTouchStart(event: TouchEvent) {
  pull = undefined

  if (!narrow.matches || event.touches.length !== 1) {
    return
  }

  const target = event.target as Element
  const sheet = target.closest<HTMLElement>('[data-trip-map] .trip-map-side')

  if (!sheet || sheet.hidden) {
    return
  }

  const header = target.closest('.trip-map-side__header')
  const content = header ? null : target.closest<HTMLElement>('.trip-map-side__content')

  // A touch below the header pulls only from the top of the content.
  if (!header && !content) {
    return
  }

  const touch = event.touches[0]!
  pull = {
    sheet,
    content,
    startX: touch.clientX,
    startY: touch.clientY,
    lastY: touch.clientY,
    lastTime: event.timeStamp,
    velocity: 0,
    pulling: false,
  }
}

function onTouchMove(event: TouchEvent) {
  if (!pull || event.touches.length !== 1) {
    return
  }

  const touch = event.touches[0]!
  const dx = touch.clientX - pull.startX
  const dy = touch.clientY - pull.startY

  if (!pull.pulling) {
    if (Math.abs(dx) < SLOP && Math.abs(dy) < SLOP) {
      return
    }

    // Sideways, up, or down a content that has room to scroll back: not a pull.
    if (
      Math.abs(dx) > Math.abs(dy) ||
      dy < 0 ||
      (pull.content && pull.content.scrollTop > 0) ||
      !event.cancelable
    ) {
      pull = undefined
      return
    }

    pull.pulling = true
    pull.startY = touch.clientY
  }

  event.preventDefault()
  const elapsed = event.timeStamp - pull.lastTime

  if (elapsed > 0) {
    pull.velocity = (touch.clientY - pull.lastY) / elapsed
  }

  pull.lastY = touch.clientY
  pull.lastTime = event.timeStamp
  pull.sheet.style.translate = `0 ${Math.max(0, touch.clientY - pull.startY)}px`
}

function onTouchEnd() {
  const ended = pull
  pull = undefined

  if (!ended?.pulling) {
    return
  }

  const { sheet } = ended
  const offset = Math.max(0, ended.lastY - ended.startY)
  const height = sheet.getBoundingClientRect().height
  sheet.style.translate = ''

  if (offset > height * CLOSE_SHARE || (ended.velocity > CLOSE_VELOCITY && offset > SLOP * 2)) {
    void slideAway(sheet, offset, height)
    return
  }

  sheet.animate([{ translate: `0 ${offset}px` }, { translate: '0 0' }], {
    duration: reducedMotion.matches ? 0 : 200,
    easing: 'cubic-bezier(0.2, 0, 0, 1)',
  })
}

async function slideAway(sheet: HTMLElement, offset: number, height: number) {
  // Held out of sight and faded, so the sheet's own exit, which starts from wherever it
  // is, plays unseen, and let go once the sheet is closed.
  const away = sheet.animate(
    [
      { translate: `0 ${offset}px`, opacity: 1 },
      { translate: `0 ${height + 16}px`, opacity: 1, offset: 0.99 },
      { translate: `0 ${height + 16}px`, opacity: 0 },
    ],
    {
      duration: reducedMotion.matches ? 0 : 200,
      easing: 'cubic-bezier(0.3, 0, 1, 1)',
      fill: 'forwards',
    },
  )

  await away.finished.catch(() => {})
  sheet.querySelector<HTMLButtonElement>('.trip-map-side__close')?.click()
  await Promise.all(
    sheet
      .getAnimations()
      .filter((animation) => animation !== away)
      .map((animation) => animation.finished.catch(() => {})),
  )
  away.cancel()
}

export {}
