// Three.js only loads for a car (src/car-model.ts) about to come into sight, and each is let
// go when the router swaps the page out. One that can't be drawn shows its still instead.

let disposers: (() => void)[] = []

function initializeCarModels() {
  const frames = document.querySelectorAll<HTMLElement>('.car-model:not(.is-mounted)')
  if (!frames.length) return
  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        const frame = entry.target as HTMLElement
        observer.unobserve(frame)
        frame.classList.add('is-mounted')
        import('@/car-model-client')
          .then(({ mountCarModel }) =>
            mountCarModel(frame, frame.dataset.src!, frame.dataset.fullSrc),
          )
          .then((dispose) => disposers.push(dispose))
          .catch(() => {
            frame.classList.remove('is-mounted')
            frame.classList.add('is-failed')
          })
      }
    },
    { rootMargin: '50% 0px' },
  )
  for (const frame of frames) observer.observe(frame)
}

document.addEventListener('astro:page-load', initializeCarModels)
document.addEventListener('astro:before-swap', () => {
  for (const dispose of disposers) dispose()
  disposers = []
})
