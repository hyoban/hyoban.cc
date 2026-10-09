// Both the calendar and map use the same still/Live Photo playback lifecycle.
export function initializeLightboxMedia(dialog: HTMLDialogElement, signal: AbortSignal) {
  const image = dialog.querySelector<HTMLImageElement>('[data-lightbox-image]')!
  const video = dialog.querySelector<HTMLVideoElement>('[data-lightbox-video]')!
  const toggle = dialog.querySelector<HTMLButtonElement>('[data-lightbox-live-toggle]')!
  const status = dialog.querySelector<HTMLElement>('[data-lightbox-live-status]')!
  let source: string | undefined
  let revision = 0
  let playing = false

  function stop() {
    revision += 1
    playing = false
    video.pause()
    video.hidden = true
    video.removeAttribute('src')
    video.load()
    toggle.disabled = false
    toggle.textContent = '播放实况'
    toggle.setAttribute('aria-pressed', 'false')
  }

  toggle.addEventListener(
    'click',
    async () => {
      if (playing) {
        stop()
        return
      }

      if (!source) return

      const currentRevision = ++revision
      status.textContent = ''
      toggle.disabled = true
      toggle.textContent = '加载中…'
      video.src = source
      video.muted = true

      try {
        await video.play()

        if (currentRevision !== revision) return

        playing = true
        video.hidden = false
        toggle.disabled = false
        toggle.textContent = '停止实况'
        toggle.setAttribute('aria-pressed', 'true')
      } catch {
        if (currentRevision !== revision) return

        stop()
        status.textContent = '实况暂时无法播放，仍可查看照片。'
      }
    },
    { signal },
  )

  video.addEventListener('ended', stop, { signal })
  video.addEventListener(
    'error',
    () => {
      if (!video.hasAttribute('src')) return
      stop()
      status.textContent = '实况暂时无法播放，仍可查看照片。'
    },
    { signal },
  )
  dialog.addEventListener('close', stop, { signal })
  document.addEventListener(
    'visibilitychange',
    () => {
      if (document.hidden) stop()
    },
    { signal },
  )
  signal.addEventListener('abort', stop, { once: true })

  return {
    show(item: HTMLElement) {
      stop()
      status.textContent = ''
      image.src = item.dataset.lightboxSrc ?? ''
      image.alt = item.dataset.lightboxAlt ?? ''
      source = item.dataset.lightboxVideo
      toggle.hidden = !source
    },
  }
}
