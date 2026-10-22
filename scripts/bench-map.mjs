import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { parseArgs } from 'node:util'

// Plays the map's scenarios in a headless Chrome made to stand in for an M1 Mac, and
// counts the frames each drops: a MacBook Air's window at about twice its pixels, for
// the GPU, and the CPU slowed 2×. A frame longer than 25ms is a drop. Against the dev
// server, which has to be running (`vp run dev`); nothing is written.
//
//   pnpm map:bench [--scenarios city-zoom,play-drive] [--repeat 2] [--settings '{"tilt":false}']
//                  [--dark] [--fog] [--phone] [--throttle 2] [--dpr 3.5] [--profile] [--compile]
//
// --settings is the map's settings as the sidebar stores them; --profile lists what the
// main thread ran during the dropped frames; --compile shows Mapbox's shader compile
// stalls. GPU times come from WebGL timer queries: a lightly loaded Apple GPU clocks
// down and reads slow, so they're a guide, and drops the measure.

const { values } = parseArgs({
  options: {
    base: { default: 'http://localhost:4321', type: 'string' },
    chrome: { type: 'string' },
    compile: { default: false, type: 'boolean' },
    dark: { default: false, type: 'boolean' },
    dpr: { type: 'string' },
    fog: { default: false, type: 'boolean' },
    phone: { default: false, type: 'boolean' },
    profile: { default: false, type: 'boolean' },
    repeat: { default: '1', type: 'string' },
    scenarios: { type: 'string' },
    settings: { type: 'string' },
    throttle: { default: '2', type: 'string' },
  },
})

const phone = values.phone
const viewport = phone ? { height: 844, width: 390 } : { height: 900, width: 1440 }
// Mapbox draws no more than 4096px across, so 3.5 comes to about twice a MacBook Air's
// pixels: the M1's GPU has about a third of this Mac's.
const pixelRatio = Number(values.dpr ?? (phone ? 3 : 3.5))
const throttle = Number(values.throttle)
const center = { x: viewport.width / 2, y: viewport.height / 2 }
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// Before the page's own scripts: times every animation frame and the GPU work in it, and
// sets the map's settings and the fog as the run asks.
const instrument = `(() => {
  const contexts = new Set()
  const getContext = HTMLCanvasElement.prototype.getContext
  HTMLCanvasElement.prototype.getContext = function (type, ...rest) {
    const gl = getContext.call(this, type, ...rest)
    if (gl && type === 'webgl2' && !gl.__timer) {
      gl.__timer = gl.getExtension('EXT_disjoint_timer_query_webgl2')
      if (gl.__timer) contexts.add(gl)
    }
    return gl
  }
  const bench = (window.__bench = { frames: [], gpu: [], long: [], recording: false })
  const raf = window.requestAnimationFrame.bind(window)
  const queries = []
  const cancelled = new Set()
  let queued = []
  let scheduled = false
  let nextId = 1
  let last
  const tick = (time) => {
    scheduled = false
    const callbacks = queued.filter(([id]) => !cancelled.has(id)).map(([, callback]) => callback)
    queued = []
    cancelled.clear()
    if (bench.recording) {
      if (last !== undefined) {
        bench.frames.push(time - last)
        if (time - last > 25) bench.long.push([last, time])
      }
      last = time
    } else last = undefined
    const started = []
    for (const gl of contexts) {
      try { const query = gl.createQuery(); gl.beginQuery(gl.__timer.TIME_ELAPSED_EXT, query); started.push([gl, query]) } catch {}
    }
    for (const callback of callbacks) {
      try { callback(time) } catch (error) { setTimeout(() => { throw error }) }
    }
    for (const [gl, query] of started) {
      try { gl.endQuery(gl.__timer.TIME_ELAPSED_EXT); queries.push([gl, query, bench.recording]) } catch {}
    }
    for (let index = queries.length - 1; index >= 0; index -= 1) {
      const [gl, query, recorded] = queries[index]
      try {
        if (!gl.getQueryParameter(query, gl.QUERY_RESULT_AVAILABLE)) continue
        const ns = gl.getQueryParameter(query, gl.QUERY_RESULT)
        if (recorded && !gl.getParameter(gl.__timer.GPU_DISJOINT_EXT) && ns > 0) bench.gpu.push(ns / 1e6)
        gl.deleteQuery(query)
      } catch {}
      queries.splice(index, 1)
    }
    if (bench.recording && !scheduled) { scheduled = true; raf(tick) }
  }
  // Every frame's callbacks run inside one of these, so its GPU work can be timed whole.
  window.requestAnimationFrame = (callback) => {
    const id = nextId++
    queued.push([id, callback])
    if (!scheduled) { scheduled = true; raf(tick) }
    return id
  }
  window.cancelAnimationFrame = (id) => cancelled.add(id)
  window.__startRecording = () => {
    Object.assign(bench, { frames: [], gpu: [], long: [], recording: true })
    if (!scheduled) { scheduled = true; raf(tick) }
  }
  ${values.settings ? `localStorage.setItem('trip-map-settings', ${JSON.stringify(values.settings)})` : `localStorage.removeItem('trip-map-settings')`}
  localStorage.setItem('trip-map-fog', ${values.fog ? "'on'" : "'off'"})
})()`

// The dev server serves Mapbox as one module: importing it again gives the page's own,
// whose map is caught as it next draws, for its shader stats.
const catchMap = `(async () => {
  const url = performance.getEntriesByType('resource').map((entry) => entry.name).find((name) => /deps\\/mapbox-gl\\.js/.test(name))
  if (!url) return
  const { Map } = (await import(url)).default
  const render = Map.prototype._render
  Map.prototype._render = function (...rest) { window.__map = this; return render.apply(this, rest) }
})()`

const summarize = `(() => {
  const bench = window.__bench
  bench.recording = false
  const frames = bench.frames.toSorted((a, b) => a - b)
  const gpu = bench.gpu.toSorted((a, b) => a - b)
  const total = bench.frames.reduce((sum, frame) => sum + frame, 0)
  const at = (list, share) => (list.length ? +list[Math.min(list.length - 1, Math.floor(list.length * share))].toFixed(1) : null)
  return {
    drops: frames.filter((frame) => frame > 25).length,
    fps: total ? +(frames.length / (total / 1000)).toFixed(1) : null,
    frames: frames.length,
    gpu50: at(gpu, 0.5),
    gpu95: at(gpu, 0.95),
    p95: at(frames, 0.95),
    worst: Math.round(frames.at(-1) ?? 0),
  }
})()`

const compileStats = `(() => {
  const stats = window.__map?.painter?.context?._compileStats
  return stats && {
    maxStallMs: Math.round(stats.maxStallMs),
    onDemand: stats.onDemand,
    precompiled: stats.precompiled,
    stalls: (stats.stalls ?? []).slice(-8).map((stall) => (typeof stall === 'object' ? (stall.name ?? '') + ' ' + Math.round(stall.ms ?? stall.duration ?? 0) + 'ms' : stall)),
    totalStallMs: Math.round(stats.totalStallMs),
  }
})()`

const chrome = await startChrome()
const browser = await connect(chrome.url)

try {
  const response = await fetch(`${values.base}/map/`).catch(() => undefined)

  if (!response?.ok) {
    throw new Error(
      `The dev server isn't answering at ${values.base}: start it with \`vp run dev\`.`,
    )
  }

  const scenarios = createScenarios()
  const names = values.scenarios?.split(',') ?? Object.keys(scenarios)
  const unknown = names.filter((name) => !scenarios[name])

  if (unknown.length > 0) {
    throw new Error(
      `No such scenario: ${unknown.join(', ')}. There are ${Object.keys(scenarios).join(', ')}.`,
    )
  }

  console.log(
    `${viewport.width}×${viewport.height} at ${pixelRatio}×, CPU ${throttle}× slower` +
      `${values.dark ? ', dark' : ''}${values.fog ? ', fog on' : ''}${values.settings ? `, settings ${values.settings}` : ''}\n`,
  )

  for (let run = 0; run < Number(values.repeat); run += 1) {
    for (const name of names) {
      await scenarios[name]()
    }
  }
} finally {
  browser.close()
  await chrome.stop()
}

/** The scenarios, each a page and what's done on it while its frames are counted. */
function createScenarios() {
  const zoomIntoShanghai = async (page) => {
    const point = (await page.markerAt('上海')) ?? center
    await page.wheel(point, -300, 22, 90)
  }

  return {
    // The whole country, dragged about, as the map opens.
    'country-pan': () =>
      measure('country-pan', '/map/', async (page) => {
        for (const [dx, dy] of [
          [300, 0],
          [0, -200],
          [-300, 0],
          [0, 200],
          [250, 120],
        ]) {
          await page.drag(center, dx, dy)
        }
      }),
    // Into Shanghai's streets, its buildings standing, then about them.
    'city-zoom': () =>
      measure('city-zoom', '/map/', async (page) => {
        await zoomIntoShanghai(page)
        await sleep(1500)
        for (const [dx, dy] of [
          [250, 0],
          [0, -150],
          [-250, 0],
        ])
          await page.drag(center, dx, dy)
      }),
    // At street level, turned and tipped by a right-drag.
    'city-rotate': () =>
      measure(
        'city-rotate',
        '/map/',
        async (page) => {
          await page.drag(center, 300, 0, { button: 'right' })
          await page.drag(center, 0, -120, { button: 'right' })
          await page.drag(center, -300, 80, { button: 'right' })
        },
        async (page) => {
          await zoomIntoShanghai(page)
          await sleep(3000)
        },
      ),
    // The busiest cluster opened, and every place framed again.
    'cluster-fit': () =>
      measure('cluster-fit', '/map/', async (page) => {
        const point = (await page.busiestCluster()) ?? center
        await page.click(point)
        await sleep(2500)
        await page.key('Escape')
        await sleep(2500)
      }),
    // The fog swept in, out and in again, and the map dragged under it.
    fog: () =>
      measure('fog', '/map/', async (page) => {
        for (const wait of [2000, 1500, 1500]) {
          await page.evaluate(`document.querySelector('[data-trip-map-fog]').click()`)
          await sleep(wait)
        }
        for (const dx of [300, -300]) await page.drag(center, dx, 0)
      }),
    // A trip through the mountains, and a long drive, playing.
    'play-yunnan': () => measure('play-yunnan', '/trip/2026-yunnan/play/', () => sleep(15_000)),
    'play-drive': () =>
      measure('play-drive', '/trip/2026-national-day-road-trip/play/', () => sleep(15_000)),
  }
}

/** Opens `path`, does `before` and then `act` with the frames counted, and prints them. */
async function measure(name, path, act, before) {
  const page = await browser.open(path)

  try {
    await before?.(page)

    if (values.compile) await page.evaluate(catchMap)
    if (values.profile) await page.startProfile()

    const start = await page.evaluate(`(window.__startRecording(), performance.now())`)
    await act(page)

    const profile = values.profile ? await page.stopProfile() : undefined
    const result = await page.evaluate(summarize)

    console.log(`${name.padEnd(13)} ${formatResult(result)}`)

    if (profile) {
      printProfile(profile, start, await page.evaluate('window.__bench.long'))
    }

    if (values.compile) {
      console.log(`  shaders: ${JSON.stringify(await page.evaluate(compileStats))}`)
    }
  } finally {
    await page.close()
  }
}

function formatResult({ drops, fps, frames, gpu50, gpu95, p95, worst }) {
  return [
    `${String(drops).padStart(3)} dropped of ${String(frames).padStart(4)}`,
    `${fps} fps`,
    `p95 ${p95}ms`,
    `worst ${worst}ms`,
    `GPU ${gpu50}/${gpu95}ms`,
  ].join('  ')
}

/** What the main thread ran during the dropped frames, heaviest first, callers and all. */
function printProfile(profile, start, long) {
  const nodes = new Map(profile.nodes.map((node) => [node.id, node]))
  const parents = new Map()

  for (const node of profile.nodes) {
    for (const child of node.children ?? []) parents.set(child, node.id)
  }

  const totals = new Map()
  let time = profile.startTime

  profile.samples.forEach((sample, index) => {
    time += profile.timeDeltas[index]

    // Profile times are µs from its start, the page's ms from its own: both start together.
    const at = start + (time - profile.startTime) / 1000

    if (!long.some(([from, to]) => at >= from && at <= to)) {
      return
    }

    const seen = new Set()

    for (let id = sample; id; id = parents.get(id)) {
      const frame = nodes.get(id).callFrame
      const key = `${frame.functionName || '(anonymous)'} ${frame.url.split('/').pop().split('?')[0]}:${frame.lineNumber + 1}`

      if (!seen.has(key)) {
        seen.add(key)
        totals.set(key, (totals.get(key) ?? 0) + profile.timeDeltas[index])
      }
    }
  })

  for (const [key, us] of [...totals]
    .filter(([key]) => !/^\((root|idle)\)/.test(key))
    .sort((a, b) => b[1] - a[1])
    .slice(0, 20)) {
    console.log(`  ${(us / 1000).toFixed(0).padStart(5)}ms ${key}`)
  }
}

/** A headless Chrome of its own, on the GPU, with a throwaway profile. */
async function startChrome() {
  const path =
    values.chrome ??
    process.env.CHROME_PATH ??
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

  if (!existsSync(path)) {
    throw new Error(`No Chrome at ${path}: give its path with --chrome or CHROME_PATH.`)
  }

  const profile = await mkdtemp(join(tmpdir(), 'bench-map-'))
  const process_ = spawn(
    path,
    [
      '--headless=new',
      '--remote-debugging-port=0',
      `--user-data-dir=${profile}`,
      '--use-angle=metal',
      '--ignore-gpu-blocklist',
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

/** The DevTools protocol over one socket, and pages opened on it. */
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
    async open(path) {
      const { targetId } = await send('Target.createTarget', { url: 'about:blank' })
      const { sessionId } = await send('Target.attachToTarget', { flatten: true, targetId })
      const command = (method, params) => send(method, params, sessionId)
      const evaluate = async (expression) =>
        (await command('Runtime.evaluate', { awaitPromise: true, expression, returnByValue: true }))
          .result?.value
      const mouse = (type, { x, y }, extra = {}) =>
        command('Input.dispatchMouseEvent', { button: 'left', type, x, y, ...extra })

      await command('Emulation.setDeviceMetricsOverride', {
        ...viewport,
        deviceScaleFactor: pixelRatio,
        mobile: phone,
      })
      await command('Emulation.setEmulatedMedia', {
        features: [{ name: 'prefers-color-scheme', value: values.dark ? 'dark' : 'light' }],
      })
      await command('Page.enable')
      await command('Page.bringToFront')
      await command('Page.addScriptToEvaluateOnNewDocument', { source: instrument })
      await command('Page.navigate', { url: values.base + path })
      // Loaded at full speed, the map's first tiles in; slowed for what's measured.
      await sleep(7000)
      await command('Emulation.setCPUThrottlingRate', { rate: throttle })

      return {
        async busiestCluster() {
          return evaluate(`(() => {
            const counts = [...document.querySelectorAll('.mapboxgl-marker')]
              .map((element) => [element, Number(element.textContent.trim())])
              .filter(([, count]) => count > 1)
              .sort((a, b) => b[1] - a[1])
            const box = counts[0]?.[0].getBoundingClientRect()
            return box && { x: box.x + box.width / 2, y: box.y + box.height / 2 }
          })()`)
        },
        async click(point) {
          await mouse('mousePressed', point, { clickCount: 1 })
          await mouse('mouseReleased', point, { clickCount: 1 })
        },
        close: () => send('Target.closeTarget', { targetId }),
        async drag(from, dx, dy, { button = 'left' } = {}) {
          const steps = 30
          await mouse('mousePressed', from, { button, clickCount: 1 })
          for (let step = 1; step <= steps; step += 1) {
            const point = { x: from.x + (dx * step) / steps, y: from.y + (dy * step) / steps }
            await mouse('mouseMoved', point, { button, buttons: button === 'right' ? 2 : 1 })
            await sleep(16)
          }
          await mouse(
            'mouseReleased',
            { x: from.x + dx, y: from.y + dy },
            { button, clickCount: 1 },
          )
        },
        evaluate,
        async key(key) {
          await command('Input.dispatchKeyEvent', { code: key, key, type: 'keyDown' })
          await command('Input.dispatchKeyEvent', { code: key, key, type: 'keyUp' })
        },
        async markerAt(label) {
          return evaluate(`(() => {
            const marker = [...document.querySelectorAll('.mapboxgl-marker')].find((element) =>
              (element.getAttribute('aria-label') ?? element.textContent ?? '').includes(${JSON.stringify(label)}))
            const box = marker?.getBoundingClientRect()
            return box && { x: box.x + box.width / 2, y: box.y + box.height / 2 }
          })()`)
        },
        async startProfile() {
          await command('Profiler.enable')
          await command('Profiler.setSamplingInterval', { interval: 250 })
          await command('Profiler.start')
        },
        async stopProfile() {
          return (await command('Profiler.stop')).profile
        },
        async wheel(point, deltaY, times, gap) {
          for (let index = 0; index < times; index += 1) {
            await mouse('mouseWheel', point, { deltaX: 0, deltaY })
            await sleep(gap)
          }
        },
      }
    },
  }
}
