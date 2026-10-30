import type { Mode } from './year-share-pictures.ts'

// The year review's share button (src/pages/[year].astro) and its menu: a long picture or
// squares. What draws them (./year-share-pictures.ts) loads only once one is picked, so
// the page loads with this alone.

/** Hands `files` to the share sheet; false when the tap that asked for it has gone stale. */
async function share(files: File[], title: string) {
  try {
    await navigator.share({ files, title })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'NotAllowedError') return false
    // Closing the sheet is an AbortError, and nothing to report.
    if (!(error instanceof DOMException && error.name === 'AbortError')) throw error
  }
  return true
}

export function initializeYearShare() {
  const menu = document.querySelector<HTMLElement>('[data-year-share]')
  const content = document.querySelector<HTMLElement>('.site-page__main')
  const toggle = menu?.querySelector<HTMLButtonElement>('[data-share-toggle]')
  const options = menu?.querySelector<HTMLElement>('[data-share-options]')
  const status = menu?.querySelector<HTMLElement>('[data-share-status]')
  if (!menu || !content || !toggle || !options || !status) return

  const label = toggle.textContent
  // What the pictures are named and stamped for, a year or `life`, and what they're shared as.
  const year = menu.dataset.yearShare!
  const title = menu.dataset.shareTitle!
  // Pictures kept for a second tap, when the share sheet wouldn't open after the wait.
  let ready: File[] | undefined

  const setOpen = (open: boolean) => {
    options.hidden = !open
    toggle.setAttribute('aria-expanded', String(open))
  }
  document.addEventListener('click', (event) => {
    if (!menu.contains(event.target as Node)) setOpen(false)
  })
  menu.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      setOpen(false)
      toggle.focus()
    }
  })

  toggle.addEventListener('click', async () => {
    // Busy drawing: marked rather than disabled, which would drop the focus off it.
    if (toggle.getAttribute('aria-disabled') === 'true') return
    if (ready) {
      const files = ready
      ready = undefined
      toggle.textContent = label
      await share(files, title)
      return
    }
    setOpen(options.hidden !== false)
  })

  options.addEventListener('click', async (event) => {
    const mode = (event.target as Element).closest<HTMLElement>('[data-share-mode]')?.dataset
      .shareMode as Mode | undefined
    if (!mode) return
    setOpen(false)
    // The option picked goes with the menu: the focus comes back to the button.
    toggle.focus()

    const phone = matchMedia('(pointer: coarse)').matches
    toggle.setAttribute('aria-disabled', 'true')
    toggle.textContent = '生成中…'
    status.textContent = '正在生成图片'
    try {
      const { download, drawPictures } = await import('./year-share-pictures.ts')
      const files = await drawPictures(mode, content, year)
      if (phone && navigator.canShare?.({ files })) {
        if (!(await share(files, title))) {
          ready = files
          toggle.textContent = '分享图片 ↗'
          status.textContent = '图片已生成，再按一次分享'
          return
        }
        status.textContent = ''
      } else {
        await download(files, `hyoban-${year}.zip`)
        status.textContent = '图片已下载'
      }
      toggle.textContent = label
    } catch (error) {
      console.error(error)
      toggle.textContent = '生成失败，再试一次'
      status.textContent = '图片生成失败'
    } finally {
      toggle.removeAttribute('aria-disabled')
    }
  })
}
