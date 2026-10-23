// @vitest-environment happy-dom
import assert from 'node:assert/strict'
import { afterEach, test } from 'vite-plus/test'
import { createScrollArea } from '../src/index.ts'

afterEach(() => {
  document.body.innerHTML = ''
})

test('draws a scrollbar over the viewport, and takes it away again', () => {
  document.body.innerHTML = '<aside id="root"><div id="viewport">Moments</div></aside>'
  const root = document.querySelector('#root')
  const viewport = document.querySelector('#viewport')
  const area = createScrollArea({ root, viewport })
  const scrollbar = root.querySelector('[data-scroll-area-scrollbar]')

  assert.ok(viewport.hasAttribute('data-scroll-area-viewport'))
  assert.equal(scrollbar.dataset.orientation, 'vertical')
  assert.equal(scrollbar.getAttribute('aria-hidden'), 'true')
  // A drawer's drag leaves it alone.
  assert.ok(scrollbar.hasAttribute('data-swipe-ignore'))
  assert.ok(scrollbar.querySelector('[data-scroll-area-thumb]'))
  // Nothing to scroll: no scrollbar.
  assert.equal(scrollbar.hidden, true)
  assert.equal(root.style.position, 'relative')

  area.destroy()
  assert.equal(root.querySelector('[data-scroll-area-scrollbar]'), null)
  assert.ok(!viewport.hasAttribute('data-scroll-area-viewport'))
  assert.ok(!root.hasAttribute('data-scroll-area-root'))
  assert.equal(root.style.position, '')
})

test('gives each way it scrolls a scrollbar of its own', () => {
  document.body.innerHTML = '<div><div id="viewport"></div></div>'
  const viewport = document.querySelector('#viewport')

  createScrollArea({ orientations: ['vertical', 'horizontal'], viewport })
  assert.deepEqual(
    [...viewport.parentElement.querySelectorAll('[data-scroll-area-scrollbar]')].map(
      (scrollbar) => scrollbar.dataset.orientation,
    ),
    ['vertical', 'horizontal'],
  )
})
