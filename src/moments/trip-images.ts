import type { Trip, TripImage } from '@hyoban/trip-map'
import { tripCovers } from '@/data/trips'
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

/** Resolves a trip's chosen cover photos; a stale uid fails the build instead of dropping a photo. */
export function getTripCovers(trip: Trip<Moment>) {
  return (tripCovers.get(trip.id) ?? []).map((cover) => {
    const item = trip.stops
      .find((stop) => stop.uid === cover.moment)
      ?.media.find((media) => media.uid === cover.media)
    const [image] = item ? getTripImages([item]) : []

    if (!image) {
      throw new Error(
        `Cover ${cover.moment}/${cover.media} isn't a photo of a Moment in trip "${trip.id}".`,
      )
    }

    return image
  })
}

/**
 * Up to three photos across the top of a trip's card, as the app has them (`coversOf`):
 * its chosen covers, or else the first photo of each of its days' Moments, oldest first.
 */
export function getTripCollage(trip: Trip<Moment>, moments: readonly Moment[], limit = 3) {
  const covers = getTripCovers(trip)

  if (covers.length > 0) {
    return covers.slice(0, limit)
  }

  return moments
    .filter((moment) => moment.dateKey >= trip.start && moment.dateKey <= trip.end)
    .flatMap((moment) => getTripImages(moment.media.slice(0, 1)))
    .slice(0, limit)
}
