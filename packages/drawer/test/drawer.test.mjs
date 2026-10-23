// @vitest-environment happy-dom
import assert from 'node:assert/strict'
import { afterEach, test } from 'vite-plus/test'
import { createDrawer } from '../src/index.ts'

const frames = () => new Promise((resolve) => setTimeout(resolve, 50))

function setUp(markup = '') {
  document.body.innerHTML = `
    <button id="trigger">Open</button>
    <div id="backdrop"></div>
    <aside id="popup" hidden>
      <h2>Title</h2>
      <button data-drawer-close>Close</button>
      ${markup}
    </aside>`

  return {
    backdrop: document.querySelector('#backdrop'),
    popup: document.querySelector('#popup'),
    trigger: document.querySelector('#trigger'),
  }
}

afterEach(() => {
  document.body.innerHTML = ''
})

test('opens from its trigger, comes in, and closes from its close button', async () => {
  const { backdrop, popup, trigger } = setUp()
  const changes = []
  const completes = []
  const drawer = createDrawer({
    backdrop,
    modal: false,
    onOpenChange: (open, details) => changes.push([open, details.reason]),
    onOpenChangeComplete: (open) => completes.push(open),
    popup,
    triggers: [trigger],
  })

  assert.equal(popup.hidden, true)
  assert.equal(backdrop.hidden, true)
  assert.equal(popup.dataset.swipeDirection, 'down')
  assert.equal(popup.dataset.swipeAxis, 'y')
  assert.equal(popup.getAttribute('role'), 'dialog')

  trigger.focus()
  trigger.click()
  assert.equal(drawer.isOpen, true)
  assert.equal(popup.hidden, false)
  assert.ok(popup.hasAttribute('data-open'))
  assert.ok(popup.hasAttribute('data-starting-style'))
  assert.equal(trigger.getAttribute('aria-expanded'), 'true')
  assert.ok(document.activeElement === popup, 'focus on the popup')

  await frames()
  assert.ok(!popup.hasAttribute('data-starting-style'))
  assert.deepEqual(completes, [true])

  popup.querySelector('[data-drawer-close]').click()
  assert.equal(drawer.isOpen, false)
  assert.equal(drawer.isMounted, true)
  assert.ok(popup.hasAttribute('data-ending-style'))
  assert.ok(popup.hasAttribute('data-closed'))

  await frames()
  assert.equal(drawer.isMounted, false)
  assert.equal(popup.hidden, true)
  assert.ok(!popup.hasAttribute('data-ending-style'))
  assert.deepEqual(changes, [
    [true, 'trigger-press'],
    [false, 'close-press'],
  ])
  assert.deepEqual(completes, [true, false])
  // Focus back where it was.
  assert.ok(document.activeElement === trigger, 'focus back on the trigger')
})

test('keeps its state when a change is canceled', () => {
  const { popup } = setUp()
  const drawer = createDrawer({
    modal: false,
    onOpenChange: (_open, details) => details.cancel(),
    popup,
  })

  assert.equal(drawer.open(), false)
  assert.equal(drawer.isOpen, false)
  assert.equal(popup.hidden, true)
})

test('opens as the page rendered it, without coming in', () => {
  const { popup } = setUp()
  popup.hidden = false
  const drawer = createDrawer({ defaultOpen: true, modal: false, popup })

  assert.equal(drawer.isOpen, true)
  assert.equal(popup.hidden, false)
  assert.ok(!popup.hasAttribute('data-starting-style'))
})

test('closes on Escape and on a press outside, unless told not to', async () => {
  const { popup } = setUp()
  const drawer = createDrawer({ modal: false, popup })

  drawer.open()
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  assert.equal(drawer.isOpen, false)

  await frames()
  drawer.open()
  document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, button: 0 }))
  assert.equal(drawer.isOpen, false)

  const kept = createDrawer({
    disableEscapeDismissal: true,
    disablePointerDismissal: true,
    modal: false,
    popup: document.body.appendChild(document.createElement('div')),
  })

  kept.open()
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, button: 0 }))
  assert.equal(kept.isOpen, true)
})

test('a nested drawer steps the one under it back, which Escape leaves open', async () => {
  const { popup } = setUp(
    '<aside id="child" hidden><button data-drawer-close>Close</button></aside>',
  )
  const child = popup.querySelector('#child')
  const parent = createDrawer({ modal: false, popup })
  const nested = createDrawer({ modal: false, parent, popup: child })

  parent.open()
  nested.open()
  assert.ok(popup.hasAttribute('data-nested-drawer-open'))
  assert.equal(popup.style.getPropertyValue('--nested-drawers'), '1')
  assert.ok(child.hasAttribute('data-nested'))

  // The child's close button closes the child alone.
  child.querySelector('[data-drawer-close]').click()
  assert.equal(nested.isOpen, false)
  assert.equal(parent.isOpen, true)
  assert.ok(!popup.hasAttribute('data-nested-drawer-open'))

  await frames()
  nested.open()
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  assert.equal(nested.isOpen, false)
  assert.equal(parent.isOpen, true)
})

test('rests on its snap points, the last one expanded, and goes back to the first', async () => {
  const { popup } = setUp()
  const points = []
  const drawer = createDrawer({
    modal: false,
    onSnapPointChange: (point) => points.push(point),
    popup,
    snapPoints: [0.5, 1],
  })

  drawer.open()
  assert.equal(drawer.snapPoint, 0.5)
  assert.ok(popup.hasAttribute('data-snap-points'))
  assert.ok(!popup.hasAttribute('data-expanded'))

  drawer.setSnapPoint(1)
  assert.equal(drawer.snapPoint, 1)
  assert.ok(popup.hasAttribute('data-expanded'))

  drawer.close()
  await frames()
  assert.equal(drawer.snapPoint, 0.5)
  assert.deepEqual(points, [1, 0.5])
})

test('turns to swipe another way', () => {
  const { popup } = setUp()
  const drawer = createDrawer({ modal: false, popup, swipeDirection: 'right' })

  assert.equal(popup.dataset.swipeAxis, 'x')
  drawer.setSwipeDirection('up')
  assert.equal(popup.dataset.swipeDirection, 'up')
  assert.equal(drawer.swipeDirection, 'up')
})

test('takes back what it wrote once destroyed', () => {
  const { popup } = setUp()
  const drawer = createDrawer({ defaultOpen: true, modal: false, popup, snapPoints: [0.5, 1] })

  drawer.destroy()
  assert.deepEqual(
    [...popup.attributes]
      .map((attribute) => attribute.name)
      .filter((name) => name.startsWith('data-')),
    [],
  )
  assert.ok(!popup.getAttribute('style')?.includes('--drawer-'))
})

test('leaves Escape to a dialog open over it', () => {
  const { popup } = setUp()
  const drawer = createDrawer({ modal: false, popup })
  const dialog = document.body.appendChild(document.createElement('dialog'))
  const button = dialog.appendChild(document.createElement('button'))

  drawer.open()
  dialog.show()
  button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  assert.equal(drawer.isOpen, true)
})

test('stands at full height under a nested drawer, whatever point it rests on', () => {
  const { popup } = setUp('<aside id="child" hidden></aside>')
  const parent = createDrawer({ modal: false, popup, snapPoints: ['200px', 1] })
  const nested = createDrawer({ modal: false, parent, popup: popup.querySelector('#child') })

  parent.open()
  nested.open()
  assert.equal(popup.style.getPropertyValue('--drawer-snap-point-offset'), '0px')
  assert.ok(popup.hasAttribute('data-nested-drawer-open'))
})
