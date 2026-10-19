import type { Theme } from './theme.ts'

// The page's theme as the map and the player read it, apart from theme.ts so the
// DOM-free entry doesn't need the DOM's types.

const FALLBACK: Theme = { border: '#e9e9e7', ink: '#21201c', muted: '#73736c', paper: '#fdfdfc' }

/** Reads `--color-tx`, `--color-bg`, `--color-tx-3` and `--color-border` off an element. */
export function readTheme(element: Element): Theme {
  const style = getComputedStyle(element)
  const read = (name: string, fallback: string) => style.getPropertyValue(name).trim() || fallback

  return {
    border: read('--color-border', FALLBACK.border),
    ink: read('--color-tx', FALLBACK.ink),
    muted: read('--color-tx-3', FALLBACK.muted),
    paper: read('--color-bg', FALLBACK.paper),
  }
}
