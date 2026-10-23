// A year review as one long picture to share: the page's own content drawn in the browser
// by modern-screenshot, everything shown at once as it ends up, with the site's mark at the
// foot. The photos come from image.hyoban.cc, which answers with Access-Control-Allow-Origin
// so the picture can be read back. A phone hands it to the share sheet, anything else
// saves it.

// The most pixels one canvas may hold on iOS Safari; past it the drawing comes out blank.
const MAX_PIXELS = 16_000_000
const MAX_SCALE = 3
const PADDING = { x: 32, y: 48 }
const IMAGE_WAIT = 10_000

/** Starts `image` loading now if it waits to be scrolled to, and settles once it has. */
function load(image: HTMLImageElement) {
  image.loading = 'eager'
  if (image.complete) return Promise.resolve()
  return new Promise((resolve) => {
    image.addEventListener('load', resolve, { once: true })
    image.addEventListener('error', resolve, { once: true })
  })
}

/** Draws `content` as a JPEG, as sharp as the canvas allows. */
async function drawYear(content: HTMLElement) {
  const root = document.documentElement
  root.classList.add('is-sharing')

  // Every section as the reader ends up seeing it, and every count at its total.
  for (const section of document.querySelectorAll('.year-section')) {
    section.classList.add('is-revealed')
  }
  const format = new Intl.NumberFormat('zh-CN')
  for (const value of document.querySelectorAll<HTMLElement>('.year-stats__value [data-value]')) {
    value.textContent = format.format(Number(value.dataset.value))
  }

  try {
    // Photos further down wait to load until they're scrolled to; the picture needs them
    // all, but not one that never comes.
    await Promise.race([
      Promise.all([...content.querySelectorAll('img')].map(load)),
      new Promise((resolve) => setTimeout(resolve, IMAGE_WAIT)),
    ])
    await document.fonts.ready
    const { domToBlob } = await import('modern-screenshot')
    const width = content.offsetWidth + PADDING.x * 2
    const height = content.offsetHeight + PADDING.y * 2
    const scale = Math.min(MAX_SCALE, Math.sqrt(MAX_PIXELS / (width * height)))

    return await domToBlob(content, {
      width,
      height,
      scale,
      type: 'image/jpeg',
      quality: 0.92,
      backgroundColor: getComputedStyle(document.body).backgroundColor,
      style: { margin: '0', padding: `${PADDING.y}px ${PADDING.x}px`, boxSizing: 'border-box' },
      filter: (node) => !(node instanceof Element && node.matches('[data-share-skip]')),
      // A photo the page showed may sit in the browser's cache from before it was allowed
      // to be read back, without the header that allows it: ask for it again.
      fetch: { requestInit: { cache: 'reload' } },
    })
  } finally {
    root.classList.remove('is-sharing')
  }
}

/** Saves `file` through a link, for a browser without a share sheet to hand it to. */
function download(file: File) {
  const url = URL.createObjectURL(file)
  const link = Object.assign(document.createElement('a'), { href: url, download: file.name })
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

/** Hands `file` to the share sheet; false when the tap that asked for it has gone stale. */
async function share(file: File, title: string) {
  try {
    await navigator.share({ files: [file], title })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'NotAllowedError') return false
    // Closing the sheet is an AbortError, and nothing to report.
    if (!(error instanceof DOMException && error.name === 'AbortError')) throw error
  }
  return true
}

export function initializeYearShare() {
  const button = document.querySelector<HTMLButtonElement>('[data-year-share]')
  const content = document.querySelector<HTMLElement>('.site-page__main')
  if (!button || !content) return

  const label = button.textContent
  const year = button.dataset.yearShare!
  const title = `${year} 年`
  // A drawing kept for a second tap, when the share sheet wouldn't open after the wait.
  let ready: File | undefined

  button.addEventListener('click', async () => {
    const phone = matchMedia('(pointer: coarse)').matches
    if (ready) {
      const file = ready
      ready = undefined
      button.textContent = label
      await share(file, title)
      return
    }

    button.disabled = true
    button.textContent = '生成中…'
    try {
      const blob = await drawYear(content)
      const file = new File([blob], `hyoban-${year}.jpg`, { type: 'image/jpeg' })
      if (phone && navigator.canShare?.({ files: [file] })) {
        if (!(await share(file, title))) {
          ready = file
          button.textContent = '分享图片 ↗'
          return
        }
      } else {
        download(file)
      }
      button.textContent = label
    } catch (error) {
      console.error(error)
      button.textContent = '生成失败，再试一次'
    } finally {
      button.disabled = false
    }
  })
}
