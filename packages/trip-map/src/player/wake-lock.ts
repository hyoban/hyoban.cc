// Keeps the screen on while a trip is presented or recorded. The browser lets go of
// the lock whenever the tab is hidden, so it's taken again when the tab comes back.

export function createScreenWakeLock(signal: AbortSignal) {
  let held = false
  let sentinel: WakeLockSentinel | undefined

  async function request() {
    if (!held || sentinel || document.visibilityState !== 'visible' || !navigator.wakeLock) {
      return
    }

    try {
      const next = await navigator.wakeLock.request('screen')

      if (held && !sentinel) {
        sentinel = next
        next.addEventListener('release', () => {
          if (sentinel === next) {
            sentinel = undefined
          }
        })
      } else {
        void next.release()
      }
    } catch {
      // Refused, say on low battery: the screen just follows its usual timeout.
    }
  }

  function release() {
    held = false
    void sentinel?.release()
    sentinel = undefined
  }

  document.addEventListener('visibilitychange', () => void request(), { signal })
  signal.addEventListener('abort', release)

  return {
    hold() {
      held = true
      void request()
    },
    release,
  }
}
