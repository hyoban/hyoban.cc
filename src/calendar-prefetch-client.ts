const calendarMonthPathPattern = /^\/calendar\/\d{4}\/\d{2}\/$/
const pageCache = new Map<string, Promise<string | undefined>>()
const parser = new DOMParser()

type NetworkInformation = {
  effectiveType?: string
  saveData?: boolean
}

function getPageKey(url: URL) {
  return `${url.origin}${url.pathname}${url.search}`
}

function canPrefetch() {
  const connection = (navigator as Navigator & {
    connection?: NetworkInformation
  }).connection

  return !connection?.saveData && !connection?.effectiveType?.includes('2g')
}

function prefetchPage(url: URL) {
  const key = getPageKey(url)
  const cachedPage = pageCache.get(key)

  if (cachedPage) {
    return cachedPage
  }

  const page = fetch(key, {
    credentials: 'same-origin',
    headers: { accept: 'text/html' },
  })
    .then(async (response) => {
      if (
        !response.ok
        || !response.headers.get('content-type')?.includes('text/html')
        || getPageKey(new URL(response.url)) !== key
      ) {
        return undefined
      }

      return response.text()
    })
    .catch(() => undefined)

  pageCache.set(key, page)
  void page.then((html) => {
    if (!html) {
      pageCache.delete(key)
    }
  })

  return page
}

function prefetchLink(target: EventTarget | null) {
  if (!canPrefetch() || !(target instanceof Element)) {
    return
  }

  const link = target.closest<HTMLAnchorElement>('a[data-calendar-month-link]')

  if (link) {
    void prefetchPage(new URL(link.href))
  }
}

function prefetchAdjacentMonths() {
  if (!canPrefetch()) {
    return
  }

  for (const link of document.querySelectorAll<HTMLAnchorElement>(
    'a[data-calendar-month-prefetch="load"]',
  )) {
    void prefetchPage(new URL(link.href))
  }
}

document.addEventListener('astro:before-preparation', (event) => {
  if (!calendarMonthPathPattern.test(event.to.pathname)) {
    return
  }

  const cachedPage = pageCache.get(getPageKey(event.to))

  if (!cachedPage) {
    return
  }

  const defaultLoader = event.loader

  event.loader = async () => {
    const html = await cachedPage

    if (!html || event.signal.aborted) {
      await defaultLoader()
      return
    }

    const newDocument = parser.parseFromString(html, 'text/html')

    if (!newDocument.querySelector('[name="astro-view-transitions-enabled"]')) {
      await defaultLoader()
      return
    }

    newDocument.querySelectorAll('noscript').forEach(element => element.remove())
    event.newDocument = newDocument
  }
})

document.addEventListener('mouseenter', event => prefetchLink(event.target), true)
document.addEventListener('focusin', event => prefetchLink(event.target))
document.addEventListener('astro:page-load', prefetchAdjacentMonths)
prefetchAdjacentMonths()
