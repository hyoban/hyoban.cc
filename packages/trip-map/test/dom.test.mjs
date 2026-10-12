// @vitest-environment happy-dom
import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { collectMapElements, collectPanelElements } from '../src/elements.ts'
import { renderShortcuts, renderTripPanel, renderTripStage, toScriptJson } from '../src/markup.ts'
import { resolveMessages, zhCN } from '../src/messages.ts'

const trip = {
  covers: [],
  dayCount: 2,
  drivingHours: 0,
  drivingKm: 0,
  end: '2026-10-02',
  id: 'test',
  legs: [],
  photoCount: 0,
  placeCount: 1,
  start: '2026-10-01',
  stops: [],
  title: 'A <trip> & "its" title',
  trailingWaypoints: [],
  weather: {},
}

test('the panel markup is what the player looks for, with the words escaped', () => {
  const panel = document.createElement('section')
  const markup = renderTripPanel({
    messages: zhCN,
    music: [{ src: '/a.mp3', title: 'A & B' }],
    trip,
  })

  panel.innerHTML = markup

  const elements = collectPanelElements(panel)

  assert.equal(elements.title.textContent, trip.title)
  assert.equal(elements.setupMusic.options.length, 2)
  assert.equal(elements.setupMusic.options[0].textContent, 'A & B')
  assert.equal(elements.ratioButtons.length, 2)
  assert.equal(elements.play.querySelector('svg')?.dataset.icon, 'pause')
  assert.equal(elements.previous.getAttribute('title'), '上一站（←）')
  assert.ok(markup.includes('&lt;trip&gt; &amp; &quot;its&quot;'))
})

test('a site replaces words in the markup', () => {
  const messages = resolveMessages({ locale: 'en-US', previous: 'Previous', present: 'Present' })
  const panel = document.createElement('section')

  panel.innerHTML = renderTripPanel({ messages })
  assert.equal(
    panel.querySelector('[data-trip-map-previous]').getAttribute('aria-label'),
    'Previous',
  )
  assert.equal(panel.querySelector('[data-trip-map-setup-title]').textContent, 'Present')
  // Without a trip the card is empty, waiting for one.
  assert.equal(panel.querySelector('[data-trip-map-body]').childElementCount, 0)
})

test('the stage and the shortcut panel render their parts', () => {
  const stage = document.createElement('div')
  const shortcuts = document.createElement('dialog')

  stage.innerHTML = renderTripStage({
    brand: { image: '/avatar.webp', name: 'Hyoban' },
    messages: zhCN,
  })
  shortcuts.innerHTML = renderShortcuts(zhCN)

  assert.equal(
    stage.querySelector('.trip-map-stage__brand img')?.getAttribute('src'),
    '/avatar.webp',
  )
  assert.ok(stage.querySelector('[data-trip-map-stage-center]'))
  assert.equal(shortcuts.querySelectorAll('section').length, 3)
  assert.equal(shortcuts.querySelectorAll('kbd').length, 17)
})

test('a page missing parts is told which, all at once', () => {
  const page = document.createElement('main')

  page.innerHTML = '<div data-trip-map-canvas></div><p data-trip-map-loading></p>'
  assert.throws(
    () => collectMapElements(page),
    (error) =>
      error.message.includes('[data-trip-map-drawer]') &&
      error.message.includes('[data-trip-map-panel]') &&
      !error.message.includes('[data-trip-map-canvas]'),
  )
})

test('inlined JSON cannot close its script tag early', () => {
  assert.equal(toScriptJson({ text: '</script><b>' }), '{"text":"\\u003c/script>\\u003cb>"}')
})
