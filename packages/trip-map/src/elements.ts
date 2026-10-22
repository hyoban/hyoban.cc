// The elements the map and the player work with, found on the page by their
// `data-trip-map-*` attributes. The page's own ones are the site's to render (see
// README); the panel's, the stage's and the shortcut panel's are the package's own
// (see markup.ts), so their attributes are an internal matter.

type Spec = Record<string, string>

/** Finds one element per entry of `spec`, or throws naming every one that's missing. */
function collect<T extends Spec>(root: ParentNode, spec: T, what: string) {
  const missing: string[] = []
  const found = Object.fromEntries(
    Object.entries(spec).map(([name, selector]) => {
      const element = root.querySelector(selector)

      if (!element) {
        missing.push(selector)
      }

      return [name, element]
    }),
  ) as { [K in keyof T]: Element }

  if (missing.length > 0) {
    throw new Error(
      `The ${what} is missing ${missing.join(', ')}. See https://github.com/hyoban/hyoban.cc/tree/main/packages/trip-map#the-map-page for the markup it needs.`,
    )
  }

  return found
}

/** The map page's own elements: the canvas, the messages, the place drawer. */
export function collectMapElements(page: HTMLElement) {
  const required = collect(
    page,
    {
      canvas: '[data-trip-map-canvas]',
      drawer: '[data-trip-map-drawer]',
      drawerClose: '[data-trip-map-drawer-close]',
      drawerContent: '[data-trip-map-drawer-content]',
      drawerEyebrow: '[data-trip-map-drawer-eyebrow]',
      drawerTitle: '[data-trip-map-drawer-title]',
      fallback: '[data-trip-map-fallback]',
      fallbackMessage: '[data-trip-map-fallback-message]',
      fallbackRetry: '[data-trip-map-fallback-retry]',
      loading: '[data-trip-map-loading]',
      panel: '[data-trip-map-panel]',
      shortcuts: '[data-trip-map-shortcuts]',
      stage: '[data-trip-map-stage]',
    },
    'map page',
  )

  return {
    ...(required as {
      canvas: HTMLElement
      drawer: HTMLElement
      drawerClose: HTMLButtonElement
      drawerContent: HTMLElement
      drawerEyebrow: HTMLElement
      drawerTitle: HTMLElement
      fallback: HTMLElement
      fallbackMessage: HTMLElement
      fallbackRetry: HTMLButtonElement
      loading: HTMLElement
      panel: HTMLElement
      shortcuts: HTMLDialogElement
      stage: HTMLElement
    }),
    fogArea: page.querySelector<HTMLElement>('[data-trip-map-fog-area]'),
    fogButton: page.querySelector<HTMLButtonElement>('[data-trip-map-fog]'),
    fallbackPlaces: [...page.querySelectorAll<HTMLButtonElement>('[data-trip-map-place]')],
    legend: page.querySelector<HTMLElement>('[data-trip-map-legend]'),
    lightbox: page.querySelector<HTMLDialogElement>('[data-trip-map-lightbox]'),
    moments: page.querySelector<HTMLElement>('[data-trip-map-moments]'),
    momentsClose: page.querySelector<HTMLButtonElement>('[data-trip-map-moments-close]'),
    momentsContent: page.querySelector<HTMLElement>('[data-trip-map-moments-content]'),
    momentsEyebrow: page.querySelector<HTMLElement>('[data-trip-map-moments-eyebrow]'),
    momentsTitle: page.querySelector<HTMLElement>('[data-trip-map-moments-title]'),
    page,
    screenshotButton: page.querySelector<HTMLButtonElement>('[data-trip-map-screenshot]'),
    tripLinks: [...page.querySelectorAll<HTMLAnchorElement>('[data-trip-map-trip-link]')],
    tripList: page.querySelector<HTMLElement>('[data-trip-map-trip-list]'),
    tripListClose: page.querySelector<HTMLButtonElement>('[data-trip-map-trip-list-close]'),
    tripListOpeners: [...page.querySelectorAll<HTMLElement>('[data-trip-map-trip-list-open]')],
    tripYearGroups: [...page.querySelectorAll<HTMLElement>('[data-trip-map-trip-year]')],
    yearClear: page.querySelector<HTMLButtonElement>('[data-trip-map-year-clear]'),
    yearButtons: [...page.querySelectorAll<HTMLButtonElement>('[data-trip-map-year]')],
  }
}

export type MapElements = ReturnType<typeof collectMapElements>

/** The trip panel's parts, as `renderTripPanel` lays them out. */
export function collectPanelElements(panel: HTMLElement) {
  return {
    ...(collect(
      panel,
      {
        actions: '[data-trip-map-actions]',
        announcer: '[data-trip-map-announcer]',
        body: '[data-trip-map-body]',
        close: '[data-trip-map-close]',
        controls: '[data-trip-map-controls]',
        exportButton: '[data-trip-map-export]',
        eyebrow: '[data-trip-map-eyebrow]',
        header: '[data-trip-map-header]',
        music: '[data-trip-map-music]',
        next: '[data-trip-map-next]',
        play: '[data-trip-map-play]',
        present: '[data-trip-map-present]',
        previous: '[data-trip-map-previous]',
        progress: '[data-trip-map-progress]',
        setup: '[data-trip-map-setup]',
        setupCancel: '[data-trip-map-setup-cancel]',
        setupMusic: '[data-trip-map-setup-music]',
        setupSafeArea: '[data-trip-map-setup-safe-area]',
        setupSafeAreaRow: '[data-trip-map-setup-safe-area-row]',
        setupStart: '[data-trip-map-setup-start]',
        setupTitle: '[data-trip-map-setup-title]',
        speed: '[data-trip-map-speed]',
        title: '[data-trip-map-title]',
      },
      'trip panel',
    ) as {
      actions: HTMLElement
      announcer: HTMLElement
      body: HTMLElement
      close: HTMLButtonElement
      controls: HTMLElement
      exportButton: HTMLButtonElement
      eyebrow: HTMLElement
      header: HTMLElement
      music: HTMLButtonElement
      next: HTMLButtonElement
      play: HTMLButtonElement
      present: HTMLButtonElement
      previous: HTMLButtonElement
      progress: HTMLOListElement
      setup: HTMLElement
      setupCancel: HTMLButtonElement
      setupMusic: HTMLSelectElement
      setupSafeArea: HTMLInputElement
      setupSafeAreaRow: HTMLElement
      setupStart: HTMLButtonElement
      setupTitle: HTMLElement
      speed: HTMLButtonElement
      title: HTMLElement
    }),
    panel,
    ratioButtons: [...panel.querySelectorAll<HTMLButtonElement>('[data-trip-map-setup-ratio]')],
  }
}

export type PanelElements = ReturnType<typeof collectPanelElements>

/** The presentation stage's parts, as `renderTripStage` lays them out. */
export function collectStageElements(stage: HTMLElement) {
  return {
    ...(collect(
      stage,
      {
        center: '[data-trip-map-stage-center]',
        close: '[data-trip-map-stage-close]',
        eyebrow: '[data-trip-map-stage-eyebrow]',
        header: '[data-trip-map-stage-header]',
        music: '[data-trip-map-stage-music]',
        photos: '[data-trip-map-stage-photos]',
        progress: '[data-trip-map-stage-progress]',
        safe: '[data-trip-map-stage-safe]',
        title: '[data-trip-map-stage-title]',
      },
      'trip stage',
    ) as {
      center: HTMLElement
      close: HTMLButtonElement
      eyebrow: HTMLElement
      header: HTMLElement
      music: HTMLButtonElement
      photos: HTMLElement
      progress: HTMLElement
      safe: HTMLElement
      title: HTMLElement
    }),
    stage,
  }
}

export type StageElements = ReturnType<typeof collectStageElements>
