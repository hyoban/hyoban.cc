import { getSeed, SEED_PARAMETER } from './year-random.ts'

// A year review as pictures to share: the page's own content drawn in the browser by
// modern-screenshot, everything shown at once as it ends up. As one long picture with the
// site's mark at the foot; or cut between its parts and laid out on squares, a few short
// parts to a square and a tall one shrunk to fit its own, nine at most where shrinking a
// little more gets there, each marked and numbered. A long
// picture on a phone is the page as the phone lays it out; everything else is drawn from
// the page loaded again out of sight at the narrowest wide layout, where the footprint and
// the days, which spread wider than the words on a big screen, keep to their column, and
// a square isn't left with a phone's tall, narrow columns. The photos come from
// image.hyoban.cc, which answers with Access-Control-Allow-Origin so the drawing can be
// read back. A phone hands the pictures to the share sheet; anything else saves them, the
// squares as one ZIP. Loaded only once a picture is asked for (./year-share.ts), so none of
// it, nor modern-screenshot or fflate, weighs on the page's load.

// The most pixels one canvas may hold on iOS Safari; past it the drawing comes out blank.
const MAX_PIXELS = 16_000_000
const MAX_SCALE = 3
const PADDING = { x: 32, y: 48 }
const IMAGE_WAIT = 10_000
/** A square's room for its mark, and the space between the parts on it, in CSS pixels. */
const SQUARE_FOOTER = 32
const SQUARE_GAP = 40
/**
 * How far past the column a square still takes from the drawing, in CSS pixels: the year's
 * numerals and the clock's labels lean out of it a little, and keep their edges.
 */
const SQUARE_BLEED = 24
/** As many squares as a post takes in a grid of nine. */
const MAX_SQUARES = 9
/** How much more than a square holds a run may take to keep within MAX_SQUARES, shrunk. */
const MAX_STRETCH = 1.6

export type Mode = 'long' | 'square'
/** A part of the page, from the heading down: where it lies in the drawing, in CSS pixels. */
type Block = { top: number; height: number }
type Drawing = { canvas: HTMLCanvasElement; scale: number; width: number; blocks: Block[] }

/**
 * The narrowest screen the page lays out wide for (40rem), with room for the wide
 * sections' margins (2rem) so they come out as wide as the words.
 */
const FRAME_WIDTH = 672
const CONTENT = '.site-page__main'

/** Starts `image` loading now if it waits to be scrolled to, and settles once it has. */
function load(image: HTMLImageElement) {
  image.loading = 'eager'
  if (image.complete) return Promise.resolve()
  return new Promise((resolve) => {
    image.addEventListener('load', resolve, { once: true })
    image.addEventListener('error', resolve, { once: true })
  })
}

/** The page loaded again out of sight at FRAME_WIDTH, for its content's layout. */
async function openWide() {
  const frame = document.createElement('iframe')
  frame.setAttribute('aria-hidden', 'true')
  frame.tabIndex = -1
  Object.assign(frame.style, {
    position: 'fixed',
    top: '0',
    left: '-20000px',
    width: `${FRAME_WIDTH}px`,
    height: '800px',
    border: '0',
    pointerEvents: 'none',
  })
  const loaded = new Promise((resolve) => frame.addEventListener('load', resolve, { once: true }))
  // This visit's seed, so the frame shows the same photos in the same order as the page.
  const url = new URL(location.href)
  url.searchParams.set(SEED_PARAMETER, String(getSeed()))
  frame.src = url.href
  document.body.append(frame)
  await loaded
  const page = frame.contentDocument
  // No scrollbar: where one takes room (Windows, or macOS set to show them always), the
  // column narrows by it while the wide sections, sized by 100vw, don't, and spill over.
  page?.documentElement.style.setProperty('overflow', 'hidden')
  const content = page?.querySelector<HTMLElement>(CONTENT)
  if (!content) {
    frame.remove()
    throw new Error('The page did not load to be drawn.')
  }
  return { content, close: () => frame.remove() }
}

/** Whether `node` is an element matching `selector`, from this page or one in a frame. */
const matches = (node: Node, selector: string) =>
  node.nodeType === Node.ELEMENT_NODE && (node as Element).matches(selector)

/** Draws `content` on a canvas as sharp as one may be, with where each of its parts lies. */
async function drawYear(content: HTMLElement): Promise<Drawing> {
  const page = content.ownerDocument
  const root = page.documentElement
  root.classList.add('is-sharing')

  // Every section as the reader ends up seeing it, and every count at its total.
  for (const section of page.querySelectorAll('.year-section')) {
    section.classList.add('is-revealed')
  }
  const format = new Intl.NumberFormat('zh-CN')
  for (const value of page.querySelectorAll<HTMLElement>('.year-stats__value [data-value]')) {
    value.textContent = format.format(Number(value.dataset.value))
  }

  try {
    // Photos further down wait to load until they're scrolled to; the picture needs them
    // all, but not one that never comes.
    await Promise.race([
      Promise.all([...content.querySelectorAll('img')].map(load)),
      new Promise((resolve) => setTimeout(resolve, IMAGE_WAIT)),
    ])
    await page.fonts.ready
    const { domToCanvas } = await import('modern-screenshot')
    const width = content.offsetWidth + PADDING.x * 2
    const height = content.offsetHeight + PADDING.y * 2
    const scale = Math.min(MAX_SCALE, Math.sqrt(MAX_PIXELS / (width * height)))

    const top = content.getBoundingClientRect().top
    const blocks = [...content.children]
      .filter(
        (child) =>
          child.getBoundingClientRect().height > 0 &&
          !matches(child, '[data-share-skip], .year-share-mark, script, style'),
      )
      .map((child) => {
        const rect = child.getBoundingClientRect()
        return { top: PADDING.y + rect.top - top, height: rect.height }
      })

    const canvas = await domToCanvas(content, {
      width,
      height,
      scale,
      backgroundColor: getComputedStyle(page.body).backgroundColor,
      style: { margin: '0', padding: `${PADDING.y}px ${PADDING.x}px`, boxSizing: 'border-box' },
      filter: (node) => !matches(node, '[data-share-skip]'),
      // A photo the page showed may sit in the browser's cache from before it was allowed
      // to be read back, without the header that allows it: ask for it again.
      fetch: { requestInit: { cache: 'reload' } },
    })
    return { canvas, scale, width: content.offsetWidth, blocks }
  } finally {
    root.classList.remove('is-sharing')
  }
}

/**
 * The parts in runs of squares, no more than MAX_SQUARES when they can be: packed into
 * `room`, and if that takes too many, into a little more room each time, a run then
 * shrunk to fit its square, as far as MAX_STRETCH.
 */
function packSquares(blocks: Block[], room: number) {
  for (let stretch = 1; ; stretch += 0.05) {
    const squares = packRuns(blocks, room * stretch)
    if (squares.length <= MAX_SQUARES || stretch >= MAX_STRETCH) return squares
  }
}

/** The parts in runs that fit `room` each, in order; a part too tall for one has its own. */
function packRuns(blocks: Block[], room: number) {
  const squares: Block[][] = []
  let height = Infinity
  for (const block of blocks) {
    const added = height + SQUARE_GAP + block.height
    if (squares.length > 0 && added <= room) {
      squares.at(-1)!.push(block)
      height = added
    } else {
      squares.push([block])
      height = block.height
    }
  }
  return squares
}

/** The drawing cut between its parts and laid out on squares, each with the site's mark. */
function drawSquares({ canvas, scale, width, blocks }: Drawing, mark: string) {
  const side = width + PADDING.x * 2
  const room = side - PADDING.y * 2 - SQUARE_FOOTER
  const style = getComputedStyle(document.body)
  const squares = packSquares(blocks, room)

  return squares.map((run, index) => {
    const square = document.createElement('canvas')
    square.width = square.height = Math.round(side * scale)
    const context = square.getContext('2d')!
    context.scale(scale, scale)
    context.fillStyle = style.backgroundColor
    context.fillRect(0, 0, side, side)

    // The run in the middle of the room above the mark, shrunk to fit it with its gaps.
    const gaps = SQUARE_GAP * (run.length - 1)
    const fit = Math.min(1, (room - gaps) / run.reduce((total, block) => total + block.height, 0))
    const height = run.reduce((total, block) => total + block.height * fit, 0)
    let y = PADDING.y + (room - height - gaps) / 2
    for (const block of run) {
      const taken = width + SQUARE_BLEED * 2
      const drawn = { width: taken * fit, height: block.height * fit }
      context.drawImage(
        canvas,
        (PADDING.x - SQUARE_BLEED) * scale,
        block.top * scale,
        taken * scale,
        block.height * scale,
        (side - drawn.width) / 2,
        y,
        drawn.width,
        drawn.height,
      )
      y += drawn.height + SQUARE_GAP
    }

    context.font = `500 13px ${style.fontFamily}`
    context.textBaseline = 'alphabetic'
    context.fillStyle = style.getPropertyValue('--color-tx-3')
    const baseline = side - PADDING.y / 2
    context.textAlign = 'left'
    context.fillText('Hyoban', PADDING.x, baseline)
    context.textAlign = 'right'
    context.fillText(`${mark} · ${index + 1}/${squares.length}`, side - PADDING.x, baseline)
    return square
  })
}

function toJpeg(canvas: HTMLCanvasElement) {
  return new Promise<Blob>((resolve, reject) =>
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('The picture could not be encoded.'))),
      'image/jpeg',
      0.92,
    ),
  )
}

/**
 * Saves `files` through a link, for a browser without a share sheet to hand them to:
 * several as one ZIP named `name`, stored as they are, JPEGs not shrinking any further.
 */
export async function download(files: File[], name: string) {
  let file = files[0]!
  if (files.length > 1) {
    const { zipSync } = await import('fflate')
    const entries = await Promise.all(
      files.map(async (entry) => [entry.name, new Uint8Array(await entry.arrayBuffer())] as const),
    )
    const zip = zipSync(Object.fromEntries(entries), { level: 0 })
    file = new File([zip], name, { type: 'application/zip' })
  }
  const url = URL.createObjectURL(file)
  Object.assign(document.createElement('a'), { href: url, download: file.name }).click()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

/** The pictures of the year review in `content`, as JPEG files named for `year`. */
export async function drawPictures(mode: Mode, content: HTMLElement, year: string) {
  const frame = mode === 'square' || innerWidth > FRAME_WIDTH ? await openWide() : undefined
  try {
    const drawing = await drawYear(frame?.content ?? content)
    const canvases = mode === 'long' ? [drawing.canvas] : drawSquares(drawing, `hyoban.cc/${year}`)
    // Encoded before the frame goes: a canvas of its page draws nothing once it's gone.
    return await Promise.all(
      canvases.map(async (canvas, index) => {
        const name = mode === 'long' ? `hyoban-${year}.jpg` : `hyoban-${year}-${index + 1}.jpg`
        return new File([await toJpeg(canvas)], name, { type: 'image/jpeg' })
      }),
    )
  } finally {
    frame?.close()
  }
}
