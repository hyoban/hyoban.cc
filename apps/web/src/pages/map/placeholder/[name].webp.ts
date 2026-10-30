import type { APIRoute } from 'astro'
import { getStaticMapUrl } from '@hyoban/trip-map'
import sharp from 'sharp'
import {
  getMapPlaceholderKeys,
  getMapPlaceholderView,
  MAP_PLACEHOLDER_SIZE,
  MAP_PLACEHOLDER_TOKEN,
  mapPlaceholderThemes,
} from '@/map-placeholder'
import { cachedOgAsset } from '@/og-cache'

export async function getStaticPaths() {
  return (await getMapPlaceholderKeys()).flatMap((key) =>
    mapPlaceholderThemes.map((theme) => ({
      params: { name: `${key}-${theme.name}` },
      props: { key, style: theme.style },
    })),
  )
}

type Props = { key: string; style: string }

export const GET: APIRoute<Props> = async ({ params, props: { key, style } }) => {
  const { camera } = await getMapPlaceholderView(key)
  const url = getStaticMapUrl(camera, {
    height: MAP_PLACEHOLDER_SIZE,
    style,
    token: MAP_PLACEHOLDER_TOKEN,
    width: MAP_PLACEHOLDER_SIZE,
  })
  // Kept between builds while the places stay where they are.
  const image = await cachedOgAsset(
    `map-${params.name}.webp`,
    url.replace(/access_token=[^&]*/, ''),
    async () => {
      const response = await fetch(url)

      if (!response.ok) {
        throw new Error(
          `The map placeholder ${params.name} failed: ${response.status} ${await response.text()}`,
        )
      }

      return sharp(Buffer.from(await response.arrayBuffer()))
        .webp({ quality: 60 })
        .toBuffer()
    },
  )

  return new Response(new Uint8Array(image), { headers: { 'Content-Type': 'image/webp' } })
}
