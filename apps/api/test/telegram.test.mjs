import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'vite-plus/test'

import { syncChannel, TelegramError } from '../src/telegram.ts'
import { call, setUp, snapshot, token } from './setup.mjs'

// The Telegram channel, brought up to date with the Moments as they publish, change and
// go, against a bot that keeps the calls it is sent.

const dockMoment = JSON.parse(readFileSync(new URL('fixtures/dock-moment.json', import.meta.url)))
const photo = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4])
const owner = { auth: token }
const link = `https://hyoban.cc/m/${dockMoment.uid.toLowerCase()}/`
const nanjing = snapshot.places.nanjing.name

/** The repository's data/, with the channel synced up to it as the migration leaves it on deploy. */
function setUpChannel() {
  const setup = setUp()

  setup.importSnapshot()
  setup.sqlite.exec(`
    UPDATE meta SET value = '2026-01-01T00:00:00Z' WHERE key = 'telegram_since';
    UPDATE meta SET value = (SELECT value FROM meta WHERE key = 'revision') WHERE key = 'telegram_revision';
  `)

  const calls = []
  let nextMessage = 100
  const telegram = {
    failing: false,
    async call(method, body) {
      if (telegram.failing) {
        throw new TelegramError(500, 'Internal Server Error')
      }

      const params = body instanceof FormData ? Object.fromEntries(body) : body

      calls.push({ method, params })

      if (method === 'sendMediaGroup') {
        return JSON.parse(params.media).map(() => ({ message_id: nextMessage++ }))
      }

      return method.startsWith('send') ? { message_id: nextMessage++ } : true
    },
  }
  const sync = () =>
    syncChannel({
      db: setup.env.DB,
      bucket: setup.bucket,
      telegram,
      chat: '@channel',
      now: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    })

  return { ...setup, calls, telegram, sync }
}

async function publish(env, changes = {}) {
  const moment = { ...dockMoment, places: ['nanjing'], ...changes }

  await call(env, 'PUT', `/v1/moments/${moment.uid}/media/${moment.media[0]?.uid}/image`, {
    ...owner,
    body: photo,
    headers: { 'Content-Type': 'image/jpeg' },
  })

  const { status } = await call(env, 'POST', '/v1/changes', {
    ...owner,
    body: { moments: [moment] },
  })

  assert.equal(status, 200)
}

test('a Moment published is posted once, its words under its photo with the place and link', async () => {
  const { env, calls, sync } = setUpChannel()

  assert.deepEqual(
    await sync(),
    { posted: 0, edited: 0, deleted: 0 },
    'the Moments before stay off',
  )

  await publish(env)

  assert.deepEqual(await sync(), { posted: 1, edited: 0, deleted: 0 })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].method, 'sendPhoto')
  assert.equal(calls[0].params.chat_id, '@channel')
  assert.equal(calls[0].params.caption, `在码头等船。\n\n📍 ${nanjing}\n${link}`)
  assert.equal(
    calls[0].params.photo,
    'https://image.hyoban.cc/cdn-cgi/image/width=2560,format=jpeg/moments/01m3vdbhc042d43kwzwe67yp1t/01m3vdbhc1hw3w1z58armsh236.jpg',
  )
  assert.deepEqual(await sync(), { posted: 0, edited: 0, deleted: 0 }, 'and not again')
})

test("a meal's post adds each one's share and its verdict to the place", async () => {
  const { env, calls, sync } = setUpChannel()

  await sync()
  await publish(env, {
    food: { dishes: ['盐水鸭'], verdict: 'good', bill: { amount: 177.4, people: 5 } },
  })

  assert.deepEqual(await sync(), { posted: 1, edited: 0, deleted: 0 })
  assert.equal(
    calls[0].params.caption,
    `在码头等船。\n\n📍 ${nanjing}\u2003💰 人均 ¥35\u2003😋 好吃\n${link}`,
  )
})

test('changed words edit the caption; a Moment the site no longer shows is deleted', async () => {
  const { env, calls, sync } = setUpChannel()

  await publish(env)
  await sync()
  await publish(env, { text: '船来了。', updatedAt: '2026-10-01T18:00:00+08:00' })

  assert.deepEqual(await sync(), { posted: 0, edited: 1, deleted: 0 })
  assert.deepEqual(calls.at(-1), {
    method: 'editMessageCaption',
    params: { chat_id: '@channel', message_id: 100, caption: `船来了。\n\n📍 ${nanjing}\n${link}` },
  })

  await publish(env, { text: '船来了。', hidden: true, updatedAt: '2026-10-01T18:10:00+08:00' })

  assert.deepEqual(await sync(), { posted: 0, edited: 0, deleted: 1 })
  assert.deepEqual(calls.at(-1), {
    method: 'deleteMessages',
    params: { chat_id: '@channel', message_ids: [100] },
  })
})

test('words too long for a caption follow the photos as a message of their own', async () => {
  const { env, calls, sync } = setUpChannel()
  const text = '长'.repeat(1100)

  await publish(env, { text })
  await sync()

  assert.deepEqual(
    calls.map((entry) => entry.method),
    ['sendPhoto', 'sendMessage'],
  )
  assert.equal(calls[0].params.caption, undefined)
  assert.equal(calls[1].params.text, `${text}\n\n📍 ${nanjing}\n${link}`)
})

test('a Moment published before the channel was first synced is never posted', async () => {
  const { env, sqlite, calls, sync } = setUpChannel()

  sqlite.exec(`UPDATE meta SET value = '2999-01-01T00:00:00Z' WHERE key = 'telegram_since'`)
  await publish(env)

  assert.deepEqual(await sync(), { posted: 0, edited: 0, deleted: 0 })
  assert.equal(calls.length, 0)
})

test('a run that fails is picked up by the next; one while another runs leaves the channel alone', async () => {
  const { env, sqlite, telegram, calls, sync } = setUpChannel()

  await publish(env)
  telegram.failing = true

  await assert.rejects(sync())
  telegram.failing = false
  sqlite.exec(`INSERT INTO meta (key, value) VALUES ('telegram_lease', '2999-01-01T00:00:00Z')`)

  assert.equal(await sync(), null)

  sqlite.exec(`DELETE FROM meta WHERE key = 'telegram_lease'`)

  assert.deepEqual(await sync(), { posted: 1, edited: 0, deleted: 0 })
  assert.equal(calls.length, 1)
})
