import type { Trip, TripImage } from '@hyoban/trip-map'
import type { Moment, ResolvedMomentMedia } from '@/moments'

export type { TripImage }

/** The still image of each photo, live photo or video poster, sized for trip cards. */
export function getTripImages(media: ResolvedMomentMedia[]): TripImage[] {
  return media.flatMap((item) => {
    const image = item.type === 'video' ? item.poster : item.src

    if (!image) {
      return []
    }

    return [
      {
        alt: item.alt,
        height: image.height,
        src:
          image.sources.find((source) => source.width >= 960)?.src ??
          image.sources.at(-1)?.src ??
          image.src,
        srcset: image.sources.map((source) => `${source.src} ${source.width}w`).join(', '),
        thumbnail: image.sources[0]?.src ?? image.src,
        ...(item.type === 'live-photo' ? { video: item.video.src } : {}),
        width: image.width,
      },
    ]
  })
}

/** Resolves a trip's chosen cover photos; a typo fails the build instead of dropping a photo. */
export function getTripCovers(trip: Trip<Moment>) {
  return (trip.covers ?? []).map((cover) => {
    const slash = cover.lastIndexOf('/')
    const id = cover.slice(0, slash)
    const file = cover.slice(slash + 1)
    const item = trip.stops
      .find((stop) => stop.id === id)
      ?.media.find((media) =>
        (media.type === 'video' ? media.poster : media.src)?.src.endsWith(`/${file}`),
      )
    const [image] = item ? getTripImages([item]) : []

    if (!image) {
      throw new Error(`Cover "${cover}" isn't a photo of a Moment in trip "${trip.id}".`)
    }

    return image
  })
}
