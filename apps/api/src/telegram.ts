import { isMomentShown, type MomentRecord } from '@hyoban/data-spec'
import {
  collectionSchema,
  entryToMoment,
  isInCollection,
  isMomentEntry,
} from '@hyoban/data-spec/entry'
import type { Bucket } from './bucket.ts'
import { readAll, revisionQuery, toRevision, type Database } from './database.ts'
import { IMAGE_ORIGIN } from './site.ts'
import { toEntry } from './writes.ts'

// The Moments the site shows, posted to the Telegram channel as they publish: the words
// with when and where it was and a meal's share and verdict, under its photos and videos, with no
// link. A Moment that changes edits its post, replacing what media changed in place, or
// posts again when its media changed in number; one the site no longer shows has its post
// deleted. A Moment written into a plan isn't posted until it is made a real one, and one made
// planned after its post has the post deleted. Only Moments published since the channel was first synced are posted, so a
// restore of the backup never floods it. Runs
// after a write the site shows, after `POST /v2/build` and on the Worker's schedule, which
// picks up whatever an earlier run left.

/** Telegram's limits: a caption, a message's text, an album and a file a bot sends. */
const CAPTION_LIMIT = 1024
const TEXT_LIMIT = 4096
const ALBUM_LIMIT = 10
const UPLOAD_LIMIT = 50 * 1024 * 1024
/** How long a run holds the channel, so two at once don't post a Moment twice. */
const LEASE_MINUTES = 10

const SINCE = 'telegram_since'
const REVISION = 'telegram_revision'
const LEASE = 'telegram_lease'

/** A photo by the URL Telegram fetches it from, or a video uploaded from the bucket. */
type PostMedia = { type: 'photo'; url: string } | { type: 'video'; key: string }

/** What a Moment is posted as. */
interface Post {
  text: string
  media: PostMedia[]
}

/** A Moment as posted: the post and the channel's messages, the media's first. */
interface Posted extends Post {
  messages: number[]
  /** The message that holds the words: the first media's, as its caption, or its own. */
  textMessage: number
  caption: boolean
}

export interface Telegram {
  call<T>(method: string, body: Record<string, unknown> | FormData): Promise<T>
}

export class TelegramError extends Error {
  constructor(
    readonly code: number,
    description: string,
  ) {
    super(description)
  }
}

interface Message {
  message_id: number
}

/** The Bot API with a bot's token; a call Telegram holds back is tried again after the wait it asks for. */
export function createTelegram(token: string): Telegram {
  return {
    async call<T>(method: string, body: Record<string, unknown> | FormData) {
      for (let attempt = 0; ; attempt++) {
        const response = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
          method: 'POST',
          ...(body instanceof FormData
            ? { body }
            : { body: JSON.stringify(body), headers: { 'Content-Type': 'application/json' } }),
        })
        const answer = (await response.json()) as {
          ok: boolean
          result?: T
          description?: string
          error_code?: number
          parameters?: { retry_after?: number }
        }

        if (answer.ok) {
          return answer.result as T
        }

        const wait = answer.parameters?.retry_after

        if (answer.error_code === 429 && wait !== undefined && wait <= 30 && attempt < 2) {
          await new Promise((resolve) => setTimeout(resolve, wait * 1000))
          continue
        }

        throw new TelegramError(answer.error_code ?? response.status, answer.description ?? '')
      }
    },
  }
}

export interface ChannelContext {
  db: Database
  bucket: Bucket
  telegram: Telegram
  /** The channel, as `@name` or its numeric id. */
  chat: string
  /** When the run happens, as the records stamp it. */
  now: string
}

/**
 * Brings the channel up to date with the Moments changed since the last run, in the order
 * they happened. A run that fails stops there and the next starts again from the same
 * revision; a Moment already posted as it is then is left alone. Gives how many Moments
 * it posted, edited and deleted, or null when another run holds the channel.
 */
export async function syncChannel(context: ChannelContext) {
  const { db, now } = context
  const until = new Date(Date.parse(now) + LEASE_MINUTES * 60_000)
    .toISOString()
    .replace(/\.\d{3}Z$/, 'Z')
  const lease = await db
    .prepare(
      `INSERT INTO meta (key, value) VALUES (?1, ?2)
       ON CONFLICT (key) DO UPDATE SET value = excluded.value WHERE meta.value < ?3
       RETURNING value`,
    )
    .bind(LEASE, until, now)
    .first()

  if (!lease) {
    return null
  }

  try {
    return await postChanges(context)
  } finally {
    await db.prepare(`DELETE FROM meta WHERE key = ?1 AND value = ?2`).bind(LEASE, until).run()
  }
}

interface ChangedRow {
  key: string
  deleted_at: string | null
  record: string
}

async function postChanges(context: ChannelContext) {
  const { db } = context
  const meta = (key: string) => db.prepare(`SELECT value FROM meta WHERE key = ?1`).bind(key)
  const [revisionRows, sinceRows, doneRows] = await readAll(db, [
    revisionQuery(db),
    meta(SINCE),
    meta(REVISION),
  ])
  const revision = toRevision(revisionRows)
  const since = Date.parse((sinceRows as { value: string }[])[0]?.value ?? '')
  const done = toRevision(doneRows)
  // The Moments that changed, and anything posted that changed into what isn't one, its words
  // gone, so its post goes too.
  const [changed, posts, places, collections] = await readAll(db, [
    db
      .prepare(
        `SELECT key, deleted_at, record FROM entries
         WHERE rev > ?1 AND rev <= ?2
           AND (kind = 'moment' OR key IN (SELECT uid FROM telegram_posts))
         ORDER BY at, key`,
      )
      .bind(done, revision),
    db
      .prepare(
        `SELECT uid, post FROM telegram_posts
         WHERE uid IN (SELECT key FROM entries WHERE rev > ?1 AND rev <= ?2)`,
      )
      .bind(done, revision),
    db.prepare(`SELECT key AS id, json_extract(record, '$.name') AS name FROM places`),
    db.prepare(`SELECT record FROM collections WHERE deleted_at IS NULL ORDER BY start_day, key`),
  ])
  const postedBy = new Map(
    (posts as { uid: string; post: string }[]).map((row) => [
      row.uid,
      JSON.parse(row.post) as Posted,
    ]),
  )
  const placeNames = new Map(
    (places as { id: string; name: string }[]).map((row) => [row.id, row.name]),
  )
  // The trips a post may name: only those not private by their own say.
  const trips = (collections as { record: string }[])
    .map((row) => collectionSchema.parse(JSON.parse(row.record)))
    .filter((trip) => trip.visibility !== 'private')
  const result = { posted: 0, edited: 0, deleted: 0 }

  for (const row of changed as ChangedRow[]) {
    const entry = row.deleted_at ? null : toEntry(row)
    const moment = entry && isMomentEntry(entry) ? entryToMoment(entry) : null
    const posted = postedBy.get(row.key)
    const trip = entry
      ? trips.find((collection) => isInCollection(collection, entry))?.title
      : undefined
    // A planned Moment hasn't happened yet: the channel waits until it is made a real one.
    const post =
      moment && isMomentShown(moment) && !moment.planned
        ? toPost(moment, placeNames, trip, context.now)
        : null

    if (!posted) {
      // Never posted: posted now if it was published since the channel was first synced.
      const publishedAt = Date.parse(moment?.published?.at ?? '')

      if (post && publishedAt >= since) {
        await keep(db, row.key, await send(context, post))
        result.posted++
      }

      continue
    }

    if (!post) {
      await removeMessages(context, posted.messages)
      await db.prepare(`DELETE FROM telegram_posts WHERE uid = ?1`).bind(row.key).run()
      result.deleted++
      continue
    }

    if (post.text === posted.text && sameMedia(post, posted)) {
      continue
    }

    if (post.media.length === posted.media.length && fitsAsBefore(post, posted)) {
      await editInPlace(context, posted, post)
      await keep(db, row.key, { ...posted, ...post })
    } else {
      await removeMessages(context, posted.messages)
      await keep(db, row.key, await send(context, post))
    }

    result.edited++
  }

  await db
    .prepare(`UPDATE meta SET value = ?2 WHERE key = ?1`)
    .bind(REVISION, String(revision))
    .run()

  return result
}

const VERDICTS = { good: '😋 好吃', ok: '😌 还行', bad: '😖 踩雷' } as const
const WORTH = { good: '👍 值', ok: '👌 还行', bad: '👎 不值' } as const

/**
 * The words and media a Moment is posted with: its own words exactly, then a line of the
 * place, when it was, the public trip it was on, each one's share of what it cost, and how a meal was and whether the cost was worth it,
 * as the app shares it. A cost without its people was one's. A Moment with neither words, a line nor media has nothing
 * to post.
 */
function toPost(
  moment: MomentRecord,
  placeNames: Map<string, string>,
  trip: string | undefined,
  now: string,
): Post | null {
  const place = moment.places[0] && placeNames.get(moment.places[0])
  // A meal's cost is its bill and its verdict how it tasted; any other cost says whether it was worth it.
  const meal = moment.food
  const spent = meal?.bill ?? moment.cost
  const share =
    spent?.amount !== undefined && Math.round(spent.amount / Math.max(spent.people ?? 1, 1))
  const symbol = spent?.currency && spent.currency !== 'CNY' ? `${spent.currency} ` : '¥'
  const line = [
    place && `📍 ${place}`,
    `🕐 ${when(moment.occurredAt, now)}`,
    trip && `🧳 ${trip}`,
    share !== false && share !== undefined && `💰 人均 ${symbol}${share}`,
    meal?.verdict && VERDICTS[meal.verdict],
    moment.cost?.verdict && WORTH[moment.cost.verdict],
  ]
    .filter(Boolean)
    .join(' ')
  const text = [moment.text, line].filter(Boolean).join('\n\n')
  const media = moment.media.flatMap((item) => toPostMedia(item))

  return text || media.length > 0 ? { text, media } : null
}

/**
 * When a Moment was, at its own clock as the app shares it: `10月1日 17:40`, the year only in
 * another year than now's there, and the day alone for one known only by its day.
 */
function when(occurredAt: string, now: string) {
  const [, year, month, day, time, offset] =
    /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}:\d{2})[^+Z-]*(Z|[+-]\d{2}:\d{2}))?/.exec(occurredAt) ?? []
  // The year where it was; a day alone is weighed by UTC's.
  const sign = offset?.startsWith('-') ? -1 : 1
  const shift =
    !offset || offset === 'Z'
      ? 0
      : sign * (Number(offset.slice(1, 3)) * 60 + Number(offset.slice(4)))
  const thisYear = new Date(Date.parse(now) + shift * 60_000).getUTCFullYear()
  const date = `${Number(month)}月${Number(day)}日`
  return [Number(year) === thisYear ? date : `${year}年${date}`, time].filter(Boolean).join(' ')
}

function toPostMedia(media: MomentRecord['media'][number]): PostMedia[] {
  if (!media.published) {
    return []
  }

  if (media.type !== 'video') {
    // A live photo goes as its still. Cloudflare Images makes a JPEG Telegram takes
    // whatever the original is, under the 5 MB it fetches a photo up to.
    return [
      { type: 'photo', url: transformed('image/width=2560,format=jpeg', media.published.key) },
    ]
  }

  // A video too big for a bot to send goes as its first frame.
  return media.published.bytes <= UPLOAD_LIMIT
    ? [{ type: 'video', key: media.published.key }]
    : [
        {
          type: 'photo',
          url: transformed('media/mode=frame,time=0s,width=1920,format=jpg', media.published.key),
        },
      ]
}

function transformed(options: string, key: string) {
  const path = key
    .split('/')
    .map((segment) => encodeURIComponent(segment))
    .join('/')

  return `${IMAGE_ORIGIN}/cdn-cgi/${options}/${path}`
}

function sameMedia(post: Post, posted: Post) {
  return JSON.stringify(post.media) === JSON.stringify(posted.media)
}

/** Whether the words still fit the one message they went in, so it can be edited. */
function fitsAsBefore(post: Post, posted: Posted) {
  return posted.caption
    ? post.text.length <= CAPTION_LIMIT
    : post.text.length <= TEXT_LIMIT && posted.messages.at(-1) === posted.textMessage
}

/** Keeps what a Moment was posted as. */
async function keep(db: Database, uid: string, posted: Posted) {
  await db
    .prepare(
      `INSERT INTO telegram_posts (uid, post) VALUES (?1, ?2)
       ON CONFLICT (uid) DO UPDATE SET post = excluded.post`,
    )
    .bind(uid, JSON.stringify(posted))
    .run()
}

/**
 * Posts a Moment: its media as albums of ten, the words as the first one's caption when
 * they fit, else after them as messages of their own. When a part fails, what was posted
 * already is deleted again, so the next run posts it whole.
 */
async function send(context: ChannelContext, post: Post): Promise<Posted> {
  const caption = post.media.length > 0 && post.text.length <= CAPTION_LIMIT
  const messages: number[] = []

  try {
    for (let start = 0; start < post.media.length; start += ALBUM_LIMIT) {
      const album = post.media.slice(start, start + ALBUM_LIMIT)

      messages.push(...(await sendAlbum(context, album, caption && start === 0 ? post.text : '')))
    }

    let textMessage = messages[0]

    if (!caption) {
      for (let start = 0; start < post.text.length; start += TEXT_LIMIT) {
        const message = await context.telegram.call<Message>('sendMessage', {
          chat_id: context.chat,
          text: post.text.slice(start, start + TEXT_LIMIT),
          link_preview_options: { is_disabled: true },
        })

        messages.push(message.message_id)
        textMessage ??= message.message_id
      }
    }

    return { ...post, messages, textMessage: textMessage as number, caption }
  } catch (caught) {
    await removeMessages(context, messages)
    throw caught
  }
}

/** A photo or video as Telegram's InputMedia, a video's file attached to the form. */
async function toInputMedia(
  context: ChannelContext,
  form: FormData,
  item: PostMedia,
  index: number,
) {
  if (item.type === 'photo') {
    return { type: 'photo', media: item.url }
  }

  const object = await context.bucket.get(item.key)

  if (!object) {
    throw new Error(`The video ${item.key} isn't in the bucket.`)
  }

  const name = `file${index}`
  const type = object.httpMetadata?.contentType ?? 'video/mp4'

  form.append(name, new File([await new Response(object.body).blob()], name, { type }))

  return { type: 'video', media: `attach://${name}`, supports_streaming: true }
}

/** One album, or a photo or video on its own, which Telegram won't take as an album. */
async function sendAlbum(context: ChannelContext, album: PostMedia[], caption: string) {
  const form = new FormData()
  const media = await Promise.all(
    album.map((item, index) => toInputMedia(context, form, item, index)),
  )

  form.append('chat_id', context.chat)

  if (media.length === 1) {
    const [item] = media as [{ type: string; media: string }]
    const method = item.type === 'photo' ? 'sendPhoto' : 'sendVideo'

    form.append(item.type, item.media)

    if (caption) {
      form.append('caption', caption)
    }

    if (item.type === 'video') {
      form.append('supports_streaming', 'true')
    }

    return [(await context.telegram.call<Message>(method, form)).message_id]
  }

  form.append(
    'media',
    JSON.stringify(caption ? [{ ...media[0], caption }, ...media.slice(1)] : media),
  )

  const messages = await context.telegram.call<Message[]>('sendMediaGroup', form)

  return messages.map((message) => message.message_id)
}

/**
 * Edits a post whose media keep their number: each photo or video that changed is replaced
 * in its own message, which keeps its place in the album, and then the words. Telegram can't
 * add to an album or take from it, so a post whose media change in number is posted again.
 */
async function editInPlace(context: ChannelContext, posted: Posted, post: Post) {
  let captioned = false

  for (const [index, item] of post.media.entries()) {
    if (JSON.stringify(item) === JSON.stringify(posted.media[index])) {
      continue
    }

    const messageId = posted.messages[index] as number
    // Replacing a photo drops its caption unless the new one carries it.
    const caption = posted.caption && messageId === posted.textMessage
    const form = new FormData()
    const media = await toInputMedia(context, form, item, 0)

    form.append('chat_id', context.chat)
    form.append('message_id', String(messageId))
    form.append('media', JSON.stringify(caption ? { ...media, caption: post.text } : media))
    await ignoreNotModified(context.telegram.call('editMessageMedia', form))
    captioned ||= caption
  }

  if (!captioned && post.text !== posted.text) {
    await editText(context, posted, post.text)
  }
}

async function ignoreNotModified(call: Promise<unknown>) {
  try {
    await call
  } catch (caught) {
    if (!(caught instanceof TelegramError && /not modified/.test(caught.message))) {
      throw caught
    }
  }
}

async function editText(context: ChannelContext, posted: Posted, text: string) {
  await ignoreNotModified(
    context.telegram.call(
      posted.caption ? 'editMessageCaption' : 'editMessageText',
      posted.caption
        ? { chat_id: context.chat, message_id: posted.textMessage, caption: text }
        : {
            chat_id: context.chat,
            message_id: posted.textMessage,
            text,
            link_preview_options: { is_disabled: true },
          },
    ),
  )
}

/**
 * Deletes a post's messages. One Telegram won't delete, as one deleted by hand already,
 * is logged and left, so it doesn't hold up the rest of the channel.
 */
async function removeMessages(context: ChannelContext, messages: number[]) {
  if (messages.length === 0) {
    return
  }

  try {
    await context.telegram.call('deleteMessages', {
      chat_id: context.chat,
      message_ids: messages,
    })
  } catch (caught) {
    if (!(caught instanceof TelegramError && caught.code === 400)) {
      throw caught
    }

    console.error('Telegram would not delete', messages, caught.message)
  }
}
