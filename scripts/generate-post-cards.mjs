import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'
import siteConfig from '../site.config.json' with { type: 'json' }

// Lays a post out as 3:4 cards for Xiaohongshu (1242×1656): a cover of its title,
// ogDescription, description and ogImage, then its text and images in order, flowed card by card, with
// long paragraphs split between sentences. Rendered in a headless Chrome; the PNGs go to
// .private/post-cards/<slug>/, ready to post in order.
//
//   vp run posts:cards here [--out dir] [--chrome path]

const { positionals, values } = parseArgs({
  allowPositionals: true,
  options: {
    chrome: { type: 'string' },
    out: { type: 'string' },
  },
})

const root = fileURLToPath(new URL('../', import.meta.url))
const siteHost = new URL(siteConfig.site.url).host
const slug = positionals[0]

if (!slug) {
  throw new Error('Give the post, as in `vp run posts:cards here`.')
}

const source = await readFile(join(root, 'apps/web/src/content/posts', `${slug}.md`), 'utf8')
const { body, data } = splitFrontmatter(source)
const blocks = parseBlocks(body)
const cover = data.ogImage ?? blocks.find((block) => block.type === 'image')?.src
const outDir = values.out ?? join(root, '.private/post-cards', slug)
const workDir = await mkdtemp(join(tmpdir(), 'post-cards-'))
const page = join(workDir, 'cards.html')

await writeFile(
  page,
  renderPage({
    blocks,
    cover,
    date: new Date(data.pubDate),
    description: data.description ?? '',
    subtitle: (data.ogDescription ?? '').replace(/[。.]$/, ''),
    link: `${siteHost}/${data.link ?? slug}`,
    title: data.title,
  }),
)

const chrome = await startChrome()

try {
  const browser = await connect(chrome.url)
  const tab = await browser.open(pathToFileURL(page).href)
  const count = await tab.evaluate('window.cardsReady')

  await mkdir(outDir, { recursive: true })

  for (let index = 0; index < count; index += 1) {
    const file = join(outDir, `${String(index + 1).padStart(2, '0')}.png`)
    await writeFile(file, await tab.capture(index))
  }

  browser.close()
  console.log(`${count} cards in ${outDir}`)
} finally {
  await chrome.stop()
  await rm(workDir, { force: true, recursive: true })
}

function splitFrontmatter(text) {
  const match = /^---\n([\s\S]*?)\n---\n/.exec(text)
  const data = {}

  for (const line of match?.[1].split('\n') ?? []) {
    const field = /^(\w+):\s*(.*)$/.exec(line)

    if (field) {
      data[field[1]] = field[2].replace(/^(['"])(.*)\1$/, '$2')
    }
  }

  return { body: text.slice(match?.[0].length ?? 0), data }
}

/** Headings, paragraphs, list items, quotes, code and images; enough for the posts here. */
function parseBlocks(text) {
  const result = []
  const lines = text.split('\n')

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]

    if (line.startsWith('```')) {
      const code = []

      while (++index < lines.length && !lines[index].startsWith('```')) {
        code.push(lines[index])
      }
      result.push({ text: code.join('\n'), type: 'code' })
      continue
    }

    const image = /^!\[([^\]]*)\]\(([^)\s]+)[^)]*\)\s*$/.exec(line)
    const heading = /^#{1,6}\s+(.*)$/.exec(line)
    const item = /^\s*(?:[-*+]|\d+\.)\s+(.*)$/.exec(line)

    if (image) {
      result.push({ alt: image[1], src: image[2], type: 'image' })
    } else if (heading) {
      result.push({ html: inline(heading[1]), type: 'heading' })
    } else if (item) {
      result.push({ html: inline(item[1]), type: 'item' })
    } else if (line.startsWith('>')) {
      result.push({ html: inline(line.replace(/^>\s?/, '')), type: 'quote' })
    } else if (line.trim()) {
      const paragraph = [line]

      while (
        lines[index + 1]?.trim() &&
        !/^(#|!\[|```|>|\s*([-*+]|\d+\.)\s)/.test(lines[index + 1])
      ) {
        paragraph.push(lines[++index])
      }
      result.push({ html: inline(paragraph.join('')), type: 'paragraph' })
    }
  }

  return result
}

/** Inline markdown as HTML; a link keeps only its text, since a picture can't be tapped. */
function inline(text) {
  return escapeHtml(text)
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*]+)\*/g, '$1<em>$2</em>')
}

function escapeHtml(text) {
  return text.replace(/[&<>"]/g, (char) => `&#${char.charCodeAt(0)};`)
}

function renderPage({ blocks, cover, date, description, link, subtitle, title }) {
  const stamp = `${date.getFullYear()}.${String(date.getMonth() + 1).padStart(2, '0')}`

  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<style>
  :root { --bg: #fdfdfc; --border: #e9e9e7; --inline: #f6f5f3; --tx: #21201c; --tx-2: #63635e; --tx-3: #8a8a83; }
  * { box-sizing: border-box; margin: 0; }
  body { background: var(--bg); color: var(--tx); font-family: -apple-system, "PingFang SC", "Hiragino Sans GB", sans-serif; text-autospace: normal; -webkit-font-smoothing: antialiased; }
  .card { position: relative; display: flex; flex-direction: column; width: 621px; height: 828px; padding: 40px 44px; overflow: hidden; background: var(--bg); }
  .bar { display: flex; justify-content: space-between; gap: 24px; margin-bottom: 26px; font-size: 14px; color: var(--tx-3); font-variant-numeric: tabular-nums; }
  .bar span:first-child { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
  .body { flex: 1; min-height: 0; display: flex; flex-direction: column; }
  .body > * + * { margin-top: 14px; }
  .body > .heading:not(:first-child) { margin-top: 28px; }
  .heading { font-size: 27px; font-weight: 600; line-height: 1.35; text-wrap: balance; }
  .paragraph, .item, .quote { font-size: 19px; line-height: 1.8; text-wrap: pretty; }
  .item { padding-left: 1.1em; text-indent: -1.1em; }
  .item::before { content: '·'; display: inline-block; width: 1.1em; text-indent: 0; color: var(--tx-3); }
  .quote { padding-left: 14px; border-left: 3px solid var(--border); color: var(--tx-2); }
  code { font: 0.9em ui-monospace, Menlo, monospace; background: var(--inline); border-radius: 4px; padding: 0 4px; }
  .code { font: 14px/1.6 ui-monospace, Menlo, monospace; white-space: pre-wrap; background: var(--inline); border-radius: 10px; padding: 14px 16px; }
  .image { display: flex; flex-direction: column; align-items: center; }
  .image img { display: block; max-width: 100%; border-radius: 12px; box-shadow: 0 0 0 1px var(--border); }
  .image figcaption { align-self: stretch; margin-top: 8px; font-size: 14px; line-height: 1.5; color: var(--tx-3); }
  .end { margin-top: auto !important; padding-top: 22px; border-top: 1px solid var(--border); font-size: 15px; color: var(--tx-2); }
  .cover { padding-bottom: 0; }
  .cover h1 { font-size: ${[...title].length <= 8 ? 92 : 44}px; font-weight: 650; line-height: 1.15; letter-spacing: -0.01em; margin-top: 18px; text-wrap: balance; }
  .cover h2 { font-size: 30px; font-weight: 600; line-height: 1.4; margin-top: 18px; text-wrap: balance; }
  .cover h2:empty { display: none; }
  .cover p { font-size: 20px; line-height: 1.75; color: var(--tx-2); margin-top: 18px; text-wrap: pretty; }
  .cover .art { flex: 1; min-height: 0; margin-top: 34px; border-radius: 18px 18px 0 0; overflow: hidden; box-shadow: 0 0 0 1px var(--border); }
  .cover .art img { display: block; width: 100%; height: 100%; object-fit: cover; object-position: top; }
</style>
</head>
<body>
<div id="cards"></div>
<script>
const title = ${JSON.stringify(title)}
const blocks = ${JSON.stringify(blocks)}
const cover = ${JSON.stringify(cover ?? null)}
const description = ${JSON.stringify(description)}
const subtitle = ${JSON.stringify(subtitle)}
const link = ${JSON.stringify(link)}
const stamp = ${JSON.stringify(stamp)}
const host = ${JSON.stringify(link.split('/')[0])}
${paginate.toString()}
paginate()
</script>
</body>
</html>`
}

/** Runs in the page: fills the cards one block at a time, measuring as it goes. */
function paginate() {
  const cards = document.querySelector('#cards')
  const MIN_IMAGE = 240
  const MAX_IMAGE = 560

  const element = (tag, className, html) => {
    const node = document.createElement(tag)
    node.className = className
    if (html !== undefined) node.innerHTML = html
    return node
  }

  const newCard = () => {
    const card = element('section', 'card')
    card.innerHTML = '<div class="bar"><span></span><span class="page"></span></div>'
    card.querySelector('.bar span').textContent = title
    const body = element('div', 'body')
    card.append(body)
    cards.append(card)
    return body
  }

  const fits = (body) => body.scrollHeight <= body.clientHeight
  const room = (body) =>
    body.clientHeight -
    (body.lastElementChild
      ? body.lastElementChild.offsetTop + body.lastElementChild.offsetHeight - body.offsetTop + 14
      : 0)

  const build = async () => {
    const coverCard = element('section', 'card cover')
    coverCard.innerHTML =
      '<div class="bar"><span></span><span></span></div><h1></h1><h2></h2><p></p>'
    coverCard.querySelector('.bar span:first-child').textContent = host
    coverCard.querySelector('.bar span:last-child').textContent = stamp
    coverCard.querySelector('h1').textContent = title
    coverCard.querySelector('h2').textContent = subtitle
    coverCard.querySelector('p').textContent = description
    if (cover) {
      const art = element('div', 'art', '<img>')
      art.querySelector('img').src = cover
      coverCard.append(art)
    }
    cards.append(coverCard)

    // Images are measured, so they must have loaded.
    const images = new Map()
    await Promise.all(
      blocks
        .filter((block) => block.type === 'image')
        .concat(cover ? [{ src: cover }] : [])
        .map(
          (block) =>
            new Promise((resolve) => {
              const image = new Image()
              image.onload = image.onerror = () => resolve(images.set(block.src, image))
              image.src = block.src
            }),
        ),
    )
    await document.fonts.ready

    const queue = [...blocks]
    let body = newCard()

    while (queue.length > 0) {
      const block = queue.shift()

      if (block.type === 'image') {
        const image = images.get(block.src)
        const figure = element('figure', 'image', '<img>')
        const img = figure.querySelector('img')
        img.src = block.src
        if (block.alt)
          figure.append(
            element(
              'figcaption',
              '',
              block.alt.replace(/[&<>]/g, (c) => '&#' + c.charCodeAt(0) + ';'),
            ),
          )
        const width = body.clientWidth
        const natural = image.naturalWidth
          ? (width * image.naturalHeight) / image.naturalWidth
          : MAX_IMAGE
        const caption = block.alt ? 36 : 0
        let height = Math.min(natural, MAX_IMAGE)
        const left = room(body) - caption

        if (height > left) {
          if (left >= MIN_IMAGE && body.childElementCount > 0) height = left
          else if (body.childElementCount > 0) {
            body = newCard()
            queue.unshift(block)
            continue
          }
        }
        img.style.height = height + 'px'
        body.append(figure)
        continue
      }

      if (block.type === 'heading') {
        // A heading keeps a few lines of what follows beside it.
        if (body.childElementCount > 0 && room(body) < 180) body = newCard()
        body.append(element('h2', 'heading', block.html))
        continue
      }

      const tag = block.type === 'code' ? 'pre' : 'p'
      const node = element(tag, block.type)
      if (block.type === 'code') node.textContent = block.text
      else node.innerHTML = block.html
      body.append(node)
      if (fits(body)) continue
      node.remove()

      // Split between sentences: as much as fits here, the rest on the next card.
      const pieces = block.type === 'code' ? [] : block.html.split(/(?<=[。！？；!?])/)
      let taken = 0
      const part = element(tag, block.type)
      body.append(part)
      while (taken < pieces.length) {
        part.innerHTML = pieces.slice(0, taken + 1).join('')
        if (!fits(body)) break
        taken += 1
      }
      part.remove()

      if (taken > 0 && taken < pieces.length) {
        part.innerHTML = pieces.slice(0, taken).join('')
        body.append(part)
        queue.unshift({ ...block, html: pieces.slice(taken).join(''), continued: true })
      } else {
        queue.unshift(block)
      }
      if (body.childElementCount === 0) {
        // Too long for an empty card even so; let it run off rather than loop.
        body.append(node)
        queue.shift()
      }
      body = newCard()
    }

    const end = element('div', 'end')
    end.textContent = '全文 ' + link
    body.append(end)
    if (!fits(body)) {
      end.remove()
      newCard().append(end)
    }

    const all = [...cards.children]
    all.forEach((card, index) => {
      const page = card.querySelector('.page')
      if (page)
        page.textContent =
          String(index + 1).padStart(2, '0') + ' / ' + String(all.length).padStart(2, '0')
    })
    window.cardsReady = all.length
  }

  window.cardsReady = build()
}

/** A headless Chrome of its own, with a throwaway profile. */
async function startChrome() {
  const path =
    values.chrome ??
    process.env.CHROME_PATH ??
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

  if (!existsSync(path)) {
    throw new Error(`No Chrome at ${path}: give its path with --chrome or CHROME_PATH.`)
  }

  const profile = await mkdtemp(join(tmpdir(), 'post-cards-chrome-'))
  const process_ = spawn(
    path,
    [
      '--headless=new',
      '--remote-debugging-port=0',
      `--user-data-dir=${profile}`,
      '--allow-file-access-from-files',
      '--no-first-run',
      'about:blank',
    ],
    { stdio: 'ignore' },
  )

  // Chrome writes the port it picked into the profile once it listens.
  let port

  for (let tries = 0; tries < 100 && !port; tries += 1) {
    await sleep(100)
    port = await readFile(join(profile, 'DevToolsActivePort'), 'utf8')
      .then((text) => text.split('\n')[0])
      .catch(() => undefined)
  }

  if (!port) {
    process_.kill()
    throw new Error("Chrome didn't start.")
  }

  const { webSocketDebuggerUrl } = await (
    await fetch(`http://127.0.0.1:${port}/json/version`)
  ).json()

  return {
    async stop() {
      process_.kill()
      await sleep(200)
      await rm(profile, { force: true, recursive: true })
    },
    url: webSocketDebuggerUrl,
  }
}

/** The DevTools protocol over one socket, and a page opened on it. */
async function connect(url) {
  const socket = new WebSocket(url)
  const pending = new Map()
  let nextId = 0

  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    pending.get(message.id)?.(message)
    pending.delete(message.id)
  })
  await new Promise((resolve) => socket.addEventListener('open', resolve, { once: true }))

  const send = (method, params = {}, sessionId) =>
    new Promise((resolve, reject) => {
      const id = ++nextId
      pending.set(id, (message) =>
        message.error
          ? reject(new Error(`${method}: ${message.error.message}`))
          : resolve(message.result),
      )
      socket.send(JSON.stringify({ id, method, params, sessionId }))
    })

  return {
    close: () => socket.close(),
    async open(pageUrl) {
      const { targetId } = await send('Target.createTarget', { url: 'about:blank' })
      const { sessionId } = await send('Target.attachToTarget', { flatten: true, targetId })
      const command = (method, params) => send(method, params, sessionId)
      const evaluate = async (expression) =>
        (await command('Runtime.evaluate', { awaitPromise: true, expression, returnByValue: true }))
          .result?.value

      // Twice the 621px card gives 1242px, the width Xiaohongshu shows at full quality.
      await command('Emulation.setDeviceMetricsOverride', {
        deviceScaleFactor: 2,
        height: 828,
        mobile: false,
        width: 621,
      })
      await command('Page.enable')
      await command('Page.navigate', { url: pageUrl })

      for (let tries = 0; tries < 100; tries += 1) {
        if (await evaluate('document.readyState === "complete"')) break
        await sleep(100)
      }

      return {
        async capture(index) {
          const y = await evaluate(
            `document.querySelectorAll('#cards > .card')[${index}].offsetTop`,
          )
          const { data } = await command('Page.captureScreenshot', {
            captureBeyondViewport: true,
            clip: { height: 828, scale: 1, width: 621, x: 0, y },
            format: 'png',
          })
          return Buffer.from(data, 'base64')
        },
        evaluate,
      }
    },
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
