import satori from 'satori'
import sharp from 'sharp'
import { getAssetUrl } from '@/asset-url'
import { SITE_DESCRIPTION, SITE_TITLE } from '@/consts'

export const OG_IMAGE_WIDTH = 1200
export const OG_IMAGE_HEIGHT = 630

// The site's light theme (see index.css).
const COLORS = { bg: '#fdfdfc', border: '#e9e9e7', tx: '#21201c', tx2: '#63635e', tx3: '#73736c' }
const FONT_FAMILY = 'Noto Sans SC'

interface OgImageOptions {
  /** Left out for the site's own card, which shows the avatar, name and bio large. */
  title?: string | undefined
  description?: string | undefined
  date?: string | undefined
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

function pageCard(avatar: string, { title, description, date }: OgImageOptions) {
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
            fontSize: 64,
            fontWeight: 500,
            lineHeight: 1.3,
            color: COLORS.tx,
            lineClamp: 2,
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
  const avatar = await getAvatar()
  const site = !options.title
  const boldText = site ? SITE_TITLE : `${SITE_TITLE}${options.title}`
  const regularText = site ? SITE_DESCRIPTION : `${options.description ?? ''}${options.date ?? ''}`
  const [regular, medium] = await Promise.all([
    loadFont(400, regularText || ' '),
    loadFont(500, boldText),
  ])

  const root = h(
    'div',
    {
      width: OG_IMAGE_WIDTH,
      height: OG_IMAGE_HEIGHT,
      boxSizing: 'border-box',
      display: 'flex',
      flexDirection: 'column',
      justifyContent: site ? 'center' : 'space-between',
      padding: 80,
      backgroundColor: COLORS.bg,
      fontFamily: FONT_FAMILY,
    },
    site ? siteCard(avatar) : pageCard(avatar, options),
  )

  const svg = await satori(root as Parameters<typeof satori>[0], {
    width: OG_IMAGE_WIDTH,
    height: OG_IMAGE_HEIGHT,
    fonts: [
      { name: FONT_FAMILY, data: regular, weight: 400, style: 'normal' },
      { name: FONT_FAMILY, data: medium, weight: 500, style: 'normal' },
    ],
  })
  return sharp(Buffer.from(svg)).png().toBuffer()
}

export function pngResponse(image: Buffer) {
  return new Response(new Uint8Array(image), { headers: { 'Content-Type': 'image/png' } })
}
