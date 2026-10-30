// Both the calendar's and the map's scripts bring this, so it listens only once a window.
const listening = Symbol.for('hyoban.moment-entry')
const scope = window as unknown as Record<symbol, boolean>

if (!scope[listening]) {
  scope[listening] = true
  // Ahead of the client router's own click handler, which would follow the link.
  window.addEventListener('click', onShareClick, { capture: true })
  document.addEventListener('astro:page-load', revealSharedMoment)
}

// A shared link's Moment may be far down its day: bring it into view, in the drawer's
// own scroll on a desktop and the page's on a phone; not on a page coming back to where
// it was (the layout's script), which may be elsewhere by now.
function revealSharedMoment() {
  if (history.state?.scrollY > 0) return
  document.querySelector('[data-moment-shared]')?.scrollIntoView({ block: 'start' })
}

// A Moment's share link opens its page without script; with it, a phone gets the
// system share sheet and anything else gets the link copied, the icon turning to a check.
function onShareClick(event: MouseEvent) {
  const link = (event.target as Element | null)?.closest<HTMLAnchorElement>('a[data-moment-share]')

  if (!link || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
    return
  }

  event.preventDefault()
  event.stopPropagation()

  if (navigator.share && matchMedia('(pointer: coarse)').matches) {
    navigator.share({ url: link.href }).catch(() => {})
    return
  }

  void navigator.clipboard.writeText(link.href).then(
    () => {
      const icon = link.querySelector('[data-moment-share-icon]')
      const label = link.getAttribute('aria-label')
      icon?.classList.replace('i-lucide-share', 'i-lucide-check')
      link.setAttribute('aria-label', '已复制链接')
      announce('已复制链接')
      setTimeout(() => {
        icon?.classList.replace('i-lucide-check', 'i-lucide-share')
        if (label) link.setAttribute('aria-label', label)
      }, 1500)
    },
    () => {
      location.href = link.href
    },
  )
}

// A label changed under the focus isn't read out by most screen readers: a status is. One
// for the page, made the first time, then filled a moment later so it's heard as news.
function announce(text: string) {
  let status = document.querySelector<HTMLElement>('[data-moment-share-status]')

  if (!status) {
    status = Object.assign(document.createElement('p'), { className: 'sr-only' })
    status.setAttribute('role', 'status')
    status.setAttribute('data-moment-share-status', '')
    document.body.append(status)
  }

  status.textContent = ''
  setTimeout(() => (status.textContent = text), 100)
}
