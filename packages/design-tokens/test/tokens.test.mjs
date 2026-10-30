import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'vite-plus/test'

import { YEAR_COLORS } from '../../trip-map/src/theme.ts'
import { kotlinFile, readTokens } from '../generate.mjs'
import { renderKotlin, resolveColors } from '../src/kotlin.mjs'

// The tokens are the one source of the design's values, so what is generated from them must be
// current, and the colours must keep the contrast the design system promises in both themes.

const { tokens, motion } = await readTokens()
const themes = tokens.color.themes.map((theme) => theme.id)

const luminance = (hex) => {
  const channels = [1, 3, 5].map((i) => Number.parseInt(hex.slice(i, i + 2), 16) / 255)
  const [r, g, b] = channels.map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
const contrast = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

test('the Android tokens are generated from the current tokens', async () => {
  assert.equal(await readFile(kotlinFile, 'utf8'), renderKotlin(tokens, motion))
})

test("the map's year colours are the site's", () => {
  const colors = resolveColors(tokens, themes[0])
  const years = [...colors].filter(([name]) => /^year-\d+$/.test(name)).map(([, value]) => value)
  assert.deepEqual(years, [...YEAR_COLORS])
})

test('text and controls keep their contrast in every theme', () => {
  for (const theme of themes) {
    const color = resolveColors(tokens, theme)
    const pair = (fg, bg, floor) =>
      assert.ok(contrast(color.get(fg), color.get(bg)) >= floor, `${fg} on ${bg} in ${theme}`)
    for (const ground of ['bg', 'surface']) {
      for (const text of ['tx', 'tx2', 'tx3', 'accent', 'danger', 'income']) pair(text, ground, 4.5)
      pair('border-strong', ground, 3)
    }
    for (const text of ['tx', 'tx2']) pair(text, 'fill', 4.5)
    // The main action is the text colour with the page's colour on it.
    pair('bg', 'tx', 4.5)
    pair('on-accent', 'accent', 4.5)
    // A block on a surface is a step the eye can tell apart.
    assert.ok(
      contrast(color.get('fill'), color.get('surface')) >= 1.05,
      `fill on surface in ${theme}`,
    )
  }
})
