import { createHash } from 'node:crypto'
import { mkdir, readFile, rm, readdir, stat, utimes, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import satori from 'satori'
import sharp from 'sharp'
import { getAssetUrl } from '@/asset-url'
import { SITE_DESCRIPTION, SITE_TITLE } from '@/consts'

export const OG_IMAGE_WIDTH = 1200
export const OG_IMAGE_HEIGHT = 630
// The photos' side of a card with photos: more than half of it, the words beside them.
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
  /** Photos filling the card's right side, the first four laid out as `getPhotoTiles` says. */
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

let avatarPromise: Promise<string> | undefined
function getAvatar() {
  avatarPromise ??= fetch(getAssetUrl('site/avatar-cropped.png')).then(async (response) => {
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
  const css = await fetch(`https://fonts.googleapis.com/css2?${query}`).then((response) =>
    response.text(),
  )
  const url = css.match(/src: url\((.+?)\) format\('(?:truetype|opentype)'\)/)?.[1]
  if (!url) throw new Error(`No font in the Google Fonts response for weight ${weight}`)
  return fetch(url).then((response) => response.arrayBuffer())
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
    promise = fetch(
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

/**
 * Where each photo sits on the photos' side: one fills it, two stand side by side, three
 * are one tall beside two, four make a grid.
 */
function getPhotoTiles(count: number): Tile[] {
  const width = OG_PHOTOS_WIDTH
  const height = OG_IMAGE_HEIGHT
  const halfWidth = (width - OG_PHOTOS_GAP) / 2
  const halfHeight = (height - OG_PHOTOS_GAP) / 2
  const right = halfWidth + OG_PHOTOS_GAP
  const bottom = halfHeight + OG_PHOTOS_GAP

  switch (Math.min(count, 4)) {
    case 1:
      return [{ left: 0, top: 0, width, height }]
    case 2:
      return [
        { left: 0, top: 0, width: halfWidth, height },
        { left: right, top: 0, width: halfWidth, height },
      ]
    case 3:
      return [
        { left: 0, top: 0, width: halfWidth, height },
        { left: right, top: 0, width: halfWidth, height: halfHeight },
        { left: right, top: bottom, width: halfWidth, height: halfHeight },
      ]
    default:
      return [
        { left: 0, top: 0, width: halfWidth, height: halfHeight },
        { left: right, top: 0, width: halfWidth, height: halfHeight },
        { left: 0, top: bottom, width: halfWidth, height: halfHeight },
        { left: right, top: bottom, width: halfWidth, height: halfHeight },
      ]
  }
}

async function renderPhotos(urls: string[]) {
  const tiles = getPhotoTiles(urls.length)
  const photos = await Promise.all(tiles.map((tile, index) => getPhoto(urls[index]!, tile)))

  return h(
    'div',
    {
      position: 'relative',
      display: 'flex',
      flexShrink: 0,
      width: OG_PHOTOS_WIDTH,
      height: OG_IMAGE_HEIGHT,
    },
    tiles.map((tile, index) =>
      h('img', { position: 'absolute', ...tile }, undefined, {
        src: photos[index],
        width: tile.width,
        height: tile.height,
      }),
    ),
  )
}

/** A photo cropped to its tile, at twice the tile's size, as a data URL. */
async function getPhoto(url: string, tile: Tile) {
  const response = await fetch(url)
  if (!response.ok) throw new Error(`Failed to fetch ${url}: ${response.status}`)
  const jpeg = await sharp(Buffer.from(await response.arrayBuffer()))
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
