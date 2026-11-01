import { z } from 'zod'

import { count, timestamp, ulid } from './primitives.ts'

// A Moment's photos and videos, as the app keeps them and the API publishes them to R2.

const r2Object = z.looseObject({
  /** The object's key in the bucket, like `moments/<moment uid>/<media uid>-960w.webp` (see `getMediaKey`). */
  key: z.string().min(1),
  contentType: z.string().min(1),
  bytes: count,
  etag: z.string().regex(/^[0-9a-f]{32}$/, "Use the object's MD5 ETag."),
})

/** An uploaded image with its responsive variants. */
export const publishedImageSchema = r2Object.extend({
  contentType: z.string().startsWith('image/'),
  width: count,
  height: count,
  variants: z.array(r2Object.extend({ width: count, height: count })).default([]),
  /**
   * Kept as uploaded rather than optimized, with no variants: the site has Cloudflare
   * Images resize it. What the API publishes; the create-moment skill optimizes instead.
   */
  original: z.boolean().optional(),
})

/** An uploaded video. */
export const publishedVideoSchema = r2Object.extend({
  contentType: z.string().startsWith('video/'),
  /**
   * Kept as uploaded rather than transcoded, with no poster: the site takes its first
   * frame from Cloudflare's Media Transformations. What the API publishes.
   */
  original: z.boolean().optional(),
})

/** Where a media item lives on the phone; only ever in the app's own store. */
const localMediaSchema = z.looseObject({
  uri: z.string().min(1),
  videoUri: z.string().min(1).optional(),
  sha256: z
    .string()
    .regex(/^[0-9a-f]{64}$/)
    .optional(),
  copied: z.boolean().default(false),
})

const mediaBase = {
  uid: ulid,
  /** Alt text; empty until someone writes it. */
  alt: z.string().default(''),
  takenAt: timestamp.optional(),
  /**
   * Kept for the record, a screenshot or a flat tyre, rather than worth showing off: it
   * shows in its own Moment but is never picked for a cover, a collage or a share card.
   */
  utility: z.boolean().optional(),
  local: localMediaSchema.optional(),
}

export const momentMediaSchema = z.discriminatedUnion('type', [
  z.looseObject({
    ...mediaBase,
    type: z.literal('image'),
    width: count,
    height: count,
    published: publishedImageSchema.optional(),
  }),
  z.looseObject({
    ...mediaBase,
    type: z.literal('live-photo'),
    width: count,
    height: count,
    published: publishedImageSchema.extend({ video: publishedVideoSchema }).optional(),
  }),
  z.looseObject({
    ...mediaBase,
    type: z.literal('video'),
    width: count.optional(),
    height: count.optional(),
    published: publishedVideoSchema.extend({ poster: publishedImageSchema.optional() }).optional(),
  }),
])

export type PublishedImage = z.output<typeof publishedImageSchema>
export type PublishedVideo = z.output<typeof publishedVideoSchema>

/** Where a Moment's media live in the bucket: `moments/<its uid, lowercase>/`. */
export function getMediaDirectory(momentUid: string) {
  return `moments/${momentUid.toLowerCase()}/`
}

/**
 * A media item's key in the bucket, named by uids alone so it never moves: the file
 * itself, or a `part` of it, a responsive variant (`960w`), a live photo's `video` or a
 * video's `poster`. Like `moments/01kv4t…/01kv4t…-960w.webp`.
 */
export function getMediaKey(
  momentUid: string,
  mediaUid: string,
  extension: string,
  part?: 'video' | 'poster' | `${number}w`,
) {
  return `${getMediaDirectory(momentUid)}${mediaUid.toLowerCase()}${part ? `-${part}` : ''}.${extension}`
}
