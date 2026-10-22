import { createHash } from 'node:crypto'
import { mkdir, readFile, rm, readdir, stat, utimes, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import satori from 'satori'
import sharp from 'sharp'
import { getAssetUrl } from '@/asset-url'
import { SITE_DESCRIPTION, SITE_TITLE } from '@/consts'

export const OG_IMAGE_WIDTH = 1200
export const OG_IMAGE_HEIGHT = 630
// The most the photos' side of a card may take: more than half of it, the words beside them.
const OG_PHOTOS_WIDTH = 660
const OG_PHOTOS_GAP = 4

// The site's light theme (see index.css).
const COLORS = { bg: '#fdfdfc', border: '#e9e9e7', tx: '#21201c', tx2: '#63635e', tx3: '#73736c' }
const FONT_FAMILY = 'Noto Sans SC'

interface OgImageOptions {
  /** Left out for the site's own card, which shows the avatar, name and bio large. */
  title?: string | undefined
  description?: string | undefined
  date?: string | undefined
  /** Photos on the card's right side, the first four laid out as `getPhotoTiles` says. */
  photos?: string[] | undefined
}

type Node = { type: string; props: Record<string, unknown> }

function h(
  type: string,
  style: Record<string, unknown>,
  children?: unknown,
  props: Record<string, unknown> = {},
): Node {
  return { type, props: { ...props, style, children } }
}

// Thousands of cards are drawn in one build, each fetching its photos, fonts and emoji: a
// connection reset or a server's 5xx along the way is tried again, twice, before the
// build gives up.
async function fetchRetrying(url: string, attempts = 3): Promise<Response> {
  for (let attempt = 1; ; attempt++) {
    try {
      const response = await fetch(url)
      if (attempt < attempts && (response.status >= 500 || response.status === 429)) {
        await response.body?.cancel()
      } else {
        return response
      }
    } catch (error) {
      if (attempt >= attempts) throw error
    }
    await new Promise((resolve) => setTimeout(resolve, 500 * 2 ** (attempt - 1)))
  }
}

let avatarPromise: Promise<string> | undefined
function getAvatar() {
  avatarPromise ??= fetchRetrying(getAssetUrl('site/avatar-cropped.png')).then(async (response) => {
    if (!response.ok) throw new Error(`Failed to fetch the avatar: ${response.status}`)
    const png = await sharp(Buffer.from(await response.arrayBuffer()))
      .resize(320)
      .png()
      .toBuffer()
    return `data:image/png;base64,${png.toString('base64')}`
  })
  return avatarPromise
}

// Google Fonts subsets the font to the text it is given, so each card fetches only the
// glyphs it draws. Without a browser user agent the CSS points at a TrueType file,
// which satori can read (it cannot read woff2).
async function loadFont(weight: 400 | 500, text: string) {
  const query = new URLSearchParams({
    family: `${FONT_FAMILY}:wght@${weight}`,
    text: [...new Set(text)].join(''),
  })
  const css = await fetchRetrying(`https://fonts.googleapis.com/css2?${query}`).then((response) =>
    response.text(),
  )
  const url = css.match(/src: url\((.+?)\) format\('(?:truetype|opentype)'\)/)?.[1]
  if (!url) throw new Error(`No font in the Google Fonts response for weight ${weight}`)
  return fetchRetrying(url).then((response) => response.arrayBuffer())
}

const emojiCache = new Map<string, Promise<string>>()

// Noto Sans SC has no emoji, which a Moment's words often have: satori draws each one as
// Twemoji's picture of it. Twemoji names a file by its code points, leaving out the
// variation selector unless the emoji joins others.
function getEmoji(emoji: string) {
  const codePoints = Array.from(emoji, (character) => character.codePointAt(0)!.toString(16))
  const name = (
    emoji.includes('\u200d') ? codePoints : codePoints.filter((point) => point !== 'fe0f')
  ).join('-')
  let promise = emojiCache.get(name)
  if (!promise) {
    promise = fetchRetrying(
      `https://cdn.jsdelivr.net/gh/jdecked/twemoji@15.1.0/assets/svg/${name}.svg`,
    ).then(async (response) => {
      if (!response.ok) throw new Error(`No Twemoji for ${emoji} (${name}): ${response.status}`)
      return `data:image/svg+xml;base64,${Buffer.from(await response.arrayBuffer()).toString('base64')}`
    })
    emojiCache.set(name, promise)
  }
  return promise
}

function siteCard(avatar: string) {
  return h('div', { display: 'flex', alignItems: 'center', gap: 48 }, [
    h('img', { width: 160, height: 160, borderRadius: 9999 }, undefined, {
      src: avatar,
      width: 160,
      height: 160,
    }),
    h('div', { display: 'flex', flexDirection: 'column', gap: 8 }, [
      h('div', { fontSize: 72, fontWeight: 500, color: COLORS.tx }, SITE_TITLE),
      h('div', { fontSize: 40, color: COLORS.tx2 }, SITE_DESCRIPTION),
    ]),
  ])
}

function pageCard(avatar: string, { title, description, date, photos }: OgImageOptions) {
  const photo = Boolean(photos?.length)
  return [
    h('div', { display: 'flex', alignItems: 'center', gap: 20 }, [
      h('img', { width: 64, height: 64, borderRadius: 9999 }, undefined, {
        src: avatar,
        width: 64,
        height: 64,
      }),
      h('div', { fontSize: 32, fontWeight: 500, color: COLORS.tx }, SITE_TITLE),
    ]),
    h(
      'div',
      { display: 'flex', flexDirection: 'column', gap: 24, width: '100%' },
      [
        h(
          'div',
          {
            display: 'block',
            // Beside a photo the words get less than half the card.
            fontSize: photo ? 40 : 64,
            fontWeight: 500,
            lineHeight: photo ? 1.5 : 1.3,
            color: COLORS.tx,
            // A card without a description, a Moment's, has room for more of its words.
            lineClamp: description ? 2 : photo ? 6 : 3,
          },
          title,
        ),
        description &&
          h(
            'div',
            { display: 'block', fontSize: 32, lineHeight: 1.5, color: COLORS.tx2, lineClamp: 2 },
            description,
          ),
        date && h('div', { fontSize: 28, color: COLORS.tx3 }, date),
      ].filter(Boolean),
    ),
  ]
}

/** A 1200×630 PNG share card in the site's quiet style. */
export async function renderOgImage(options: OgImageOptions = {}) {
  const [avatar, photoPanel] = await Promise.all([
    getAvatar(),
    options.photos?.length ? renderPhotos(options.photos) : undefined,
  ])
  const site = !options.title
  const boldText = site ? SITE_TITLE : `${SITE_TITLE}${options.title}`
  const regularText = site ? SITE_DESCRIPTION : `${options.description ?? ''}${options.date ?? ''}`
  const [regular, medium] = await Promise.all([
    loadFont(400, regularText || ' '),
    loadFont(500, boldText),
  ])

  const content = h(
    'div',
    {
      flex: 1,
      minWidth: 0,
      height: '100%',
      boxSizing: 'border-box',
      display: 'flex',
      flexDirection: 'column',
      justifyContent: site ? 'center' : 'space-between',
      padding: photoPanel ? 56 : 80,
    },
    site ? siteCard(avatar) : pageCard(avatar, options),
  )
  const root = h(
    'div',
    {
      width: OG_IMAGE_WIDTH,
      height: OG_IMAGE_HEIGHT,
      display: 'flex',
      backgroundColor: COLORS.bg,
      fontFamily: FONT_FAMILY,
    },
    [content, photoPanel].filter(Boolean),
  )

  const svg = await satori(root as Parameters<typeof satori>[0], {
    width: OG_IMAGE_WIDTH,
    height: OG_IMAGE_HEIGHT,
    fonts: [
      { name: FONT_FAMILY, data: regular, weight: 400, style: 'normal' },
      { name: FONT_FAMILY, data: medium, weight: 500, style: 'normal' },
    ],
    loadAdditionalAsset: async (code, segment) => (code === 'emoji' ? getEmoji(segment) : []),
  })
  return sharp(Buffer.from(svg)).png().toBuffer()
}

type Tile = { height: number; left: number; top: number; width: number }
type Photo = { aspect: number; data: Buffer }
type Layout = { index: number } | { children: Layout[]; direction: 'row' | 'column' }

// The photos' side is as wide as its photos need at the card's height, up to the most
// it may take; never so narrow that the words spread across the whole card.
const OG_PHOTOS_MIN_WIDTH = 280
// A tile with a side shorter than this shows too little of its photo.
const OG_PHOTO_MIN_SIDE = 180

/** Every way to lay out `indexes` in order as nested rows and columns. */
function getLayouts(indexes: number[], direction?: 'row' | 'column'): Layout[] {
  if (indexes.length === 1) return [{ index: indexes[0]! }]
  if (!direction) return [...getLayouts(indexes, 'row'), ...getLayouts(indexes, 'column')]
  const inner = direction === 'row' ? 'column' : 'row'
  const layouts: Layout[] = []
  // Each way to cut the photos into two or more runs, each run laid out the other way.
  for (let cuts = 1; cuts < 2 ** (indexes.length - 1); cuts++) {
    const runs: number[][] = [[indexes[0]!]]
    for (let index = 1; index < indexes.length; index++) {
      if (cuts & (1 << (index - 1))) runs.push([])
      runs.at(-1)!.push(indexes[index]!)
    }
    let combinations: Layout[][] = [[]]
    for (const run of runs) {
      const options = getLayouts(run, inner)
      combinations = combinations.flatMap((children) =>
        options.map((option) => [...children, option]),
      )
    }
    layouts.push(...combinations.map((children) => ({ children, direction })))
  }
  return layouts
}

/**
 * A layout's width at height `height` with every photo uncropped, as `a * height + b`:
 * a row adds its children's widths, a column stacks them at one width.
 */
function getNaturalWidth(layout: Layout, photos: Photo[]): { a: number; b: number } {
  if ('index' in layout) return { a: photos[layout.index]!.aspect, b: 0 }
  const children = layout.children.map((child) => getNaturalWidth(child, photos))
  const gaps = OG_PHOTOS_GAP * (children.length - 1)
  if (layout.direction === 'row') {
    return {
      a: children.reduce((sum, child) => sum + child.a, 0),
      b: children.reduce((sum, child) => sum + child.b, gaps),
    }
  }
  const a = 1 / children.reduce((sum, child) => sum + 1 / child.a, 0)
  return { a, b: a * (children.reduce((sum, child) => sum + child.b / child.a, 0) - gaps) }
}

/** Fits a layout into `box`, sharing each row's width and each column's height by what its photos need. */
function placeLayout(layout: Layout, photos: Photo[], box: Tile, tiles: Tile[]) {
  if ('index' in layout) {
    tiles[layout.index] = box
    return
  }
  const row = layout.direction === 'row'
  const sizes = layout.children.map((child) => {
    const { a, b } = getNaturalWidth(child, photos)
    return row ? a * box.height + b : (box.width - b) / a
  })
  const space = (row ? box.width : box.height) - OG_PHOTOS_GAP * (sizes.length - 1)
  const total = sizes.reduce((sum, size) => sum + size, 0)
  let offset = row ? box.left : box.top
  layout.children.forEach((child, index) => {
    const size = (sizes[index]! / total) * space
    placeLayout(
      child,
      photos,
      row
        ? { left: offset, top: box.top, width: size, height: box.height }
        : { left: box.left, top: offset, width: box.width, height: size },
      tiles,
    )
    offset += size + OG_PHOTOS_GAP
  })
}

/**
 * The photos' tiles and the side's width: of every way to lay them out, the one that
 * crops them least, with no tile too small, at the width that layout needs. A tall
 * screenshot alone keeps its shape on a narrow side; two landscape photos stack.
 */
function getPhotoTiles(photos: Photo[]) {
  let best: { cost: number; tiles: Tile[]; width: number } | undefined
  for (const layout of getLayouts(photos.map((_, index) => index))) {
    const { a, b } = getNaturalWidth(layout, photos)
    const width = Math.round(
      Math.min(OG_PHOTOS_WIDTH, Math.max(OG_PHOTOS_MIN_WIDTH, a * OG_IMAGE_HEIGHT + b)),
    )
    const tiles: Tile[] = []
    placeLayout(layout, photos, { left: 0, top: 0, width, height: OG_IMAGE_HEIGHT }, tiles)
    let cost = 0
    tiles.forEach((tile, index) => {
      const ratio = tile.width / tile.height / photos[index]!.aspect
      const area = (tile.width * tile.height) / (width * OG_IMAGE_HEIGHT)
      cost += area * (1 - Math.min(ratio, 1 / ratio))
      cost += Math.max(0, 1 - Math.min(tile.width, tile.height) / OG_PHOTO_MIN_SIDE)
    })
    if (!best || cost < best.cost - 1e-6) best = { cost, tiles, width }
  }
  return best!
}

async function renderPhotos(urls: string[]) {
  const photos = await Promise.all(urls.slice(0, 4).map(fetchPhoto))
  const { tiles, width } = getPhotoTiles(photos)
  const images = await Promise.all(photos.map((photo, index) => cropPhoto(photo, tiles[index]!)))

  return h(
    'div',
    {
      position: 'relative',
      display: 'flex',
      flexShrink: 0,
      width,
      height: OG_IMAGE_HEIGHT,
    },
    tiles.map((tile, index) =>
      h('img', { position: 'absolute', ...tile }, undefined, {
        src: images[index],
        width: tile.width,
        height: tile.height,
      }),
    ),
  )
}

/** A photo and its shape as it is shown, turned by its EXIF orientation. */
async function fetchPhoto(url: string): Promise<Photo> {
  const response = await fetchRetrying(url)
  if (!response.ok) throw new Error(`Failed to fetch ${url}: ${response.status}`)
  const data = Buffer.from(await response.arrayBuffer())
  const { height, width } = (await sharp(data).metadata()).autoOrient
  return { aspect: width / height, data }
}

/** A photo cropped to its tile, at twice the tile's size, as a data URL. */
async function cropPhoto(photo: Photo, tile: Tile) {
  const jpeg = await sharp(photo.data)
    .rotate()
    .resize(Math.round(tile.width * 2), Math.round(tile.height * 2), { fit: 'cover' })
    .jpeg({ quality: 85 })
    .toBuffer()
  return `data:image/jpeg;base64,${jpeg.toString('base64')}`
}

// Drawn cards are kept between builds in node_modules/.astro, the directory Cloudflare's
// build cache keeps for an Astro project, named by what they draw and by this file's
// source, so a change to either draws them again. A card that no build has used for a
// month is removed.
const CARD_CACHE = join(process.cwd(), 'node_modules', '.astro', 'share-cards')
const CARD_CACHE_MAX_AGE = 30 * 24 * 60 * 60 * 1000

let cardCacheReady: Promise<string> | undefined
function getCardCacheVersion() {
  cardCacheReady ??= (async () => {
    await mkdir(CARD_CACHE, { recursive: true })
    const now = Date.now()
    for (const name of await readdir(CARD_CACHE)) {
      const file = join(CARD_CACHE, name)
      if (now - (await stat(file)).mtimeMs > CARD_CACHE_MAX_AGE) await rm(file, { force: true })
    }
    const source = await readFile(join(process.cwd(), 'src', 'og-image.ts'), 'utf8')
    return createHash('sha256').update(source).digest('hex')
  })()
  return cardCacheReady
}

/** The card `render` draws for `inputs`, from an earlier build when one drew the same. */
export async function cachedCard(name: string, inputs: unknown, render: () => Promise<Buffer>) {
  const version = await getCardCacheVersion()
  const key = createHash('sha256')
    .update(JSON.stringify([version, name, inputs]))
    .digest('hex')
  const file = join(CARD_CACHE, `${key}-${name}`)
  try {
    const card = await readFile(file)
    const now = new Date()
    await utimes(file, now, now)
    return card
  } catch {}
  const card = await render()
  await writeFile(file, card)
  return card
}

export function pngResponse(image: Buffer) {
  return new Response(new Uint8Array(image), { headers: { 'Content-Type': 'image/png' } })
}
