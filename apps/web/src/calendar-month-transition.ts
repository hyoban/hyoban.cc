// Going from one month of the calendar to another, the month's days slide the way time
// goes: a later month comes in from the right as the one before leaves to the left, an
// earlier one the other way (index.css). The step is marked on both pages' roots just for
// the transition, the router carrying the new page's root attributes over.

const MONTH_PATH = /^\/calendar\/(\d{4})\/(\d{2})\/$/
const STEP = 'data-calendar-month-step'

let step: 'later' | 'earlier' | undefined
let latest: ViewTransition | undefined

document.addEventListener('astro:before-preparation', (event) => {
  step = undefined
  latest = undefined
  document.documentElement.removeAttribute(STEP)
  const from = event.from.pathname.match(MONTH_PATH)
  const to = event.to.pathname.match(MONTH_PATH)
  if (!from || !to) return
  const fromKey = `${from[1]}-${from[2]}`
  const toKey = `${to[1]}-${to[2]}`
  if (fromKey === toKey) return
  step = toKey > fromKey ? 'later' : 'earlier'
  document.documentElement.setAttribute(STEP, step)
})

document.addEventListener('astro:before-swap', (event) => {
  if (!step) return
  event.newDocument.documentElement.setAttribute(STEP, step)
  step = undefined
  // Unless a quicker step has already begun again, cutting this one short.
  const transition = event.viewTransition
  latest = transition
  void transition.finished
    .catch(() => {})
    .then(() => {
      if (latest === transition) document.documentElement.removeAttribute(STEP)
    })
})
