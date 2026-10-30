// Renders the design tokens as the Android app's ui/theme/Tokens.kt: the colours in light and
// dark, the 4dp steps and the gaps, the corners, the shadows, the opacities, the 13 text styles
// and the springs. The app reads these by the tokens' own names, in camel case.

const FILE_NOTE =
  '// Generated from packages/design-tokens by `vp run design:tokens`. Do not edit by hand.'

/** `border-strong` → `borderStrong`, `year-1` → `year1`. */
export const camel = (name) => name.replace(/[-.]([a-z0-9])/g, (_, c) => c.toUpperCase())

const px = (value) => {
  const match = /^(-?\d+(?:\.\d+)?)px$/.exec(String(value))
  if (!match) throw new Error(`Not a px length: ${value}`)
  return Number(match[1])
}

const hex2 = (n) => Math.round(n).toString(16).padStart(2, '0').toUpperCase()

/** A colour value as Compose's 0xAARRGGBB. */
export function argb(value) {
  let match = /^#([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(value)
  if (match) return `0x${(match[2] ?? 'ff').toUpperCase()}${match[1].toUpperCase()}`
  match = /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)$/.exec(value)
  if (match) {
    const alpha = match[4] === undefined ? 1 : Number(match[4])
    return `0x${hex2(alpha * 255)}${hex2(match[1])}${hex2(match[2])}${hex2(match[3])}`
  }
  throw new Error(`Not a colour Kotlin can take: ${value}`)
}

/** Every colour token's value in [theme], aliases followed and a missing theme taking the first's. */
export function resolveColors(tokens, theme) {
  const first = tokens.color.themes[0].id
  const byName = new Map(tokens.color.tokens.map((token) => [token.name, token]))
  const raw = (token) =>
    typeof token.value === 'string' ? token.value : (token.value[theme] ?? token.value[first])
  const resolve = (name, depth = 0) => {
    const token = byName.get(name)
    if (!token || depth > 16) throw new Error(`No colour token ${name}`)
    const value = raw(token)
    const alias = /^\{(.+)\}$/.exec(value)
    return alias ? resolve(alias[1], depth + 1) : value
  }
  return new Map(tokens.color.tokens.map((token) => [token.name, resolve(token.name)]))
}

const WEIGHTS = { 400: 'Normal', 500: 'Medium', 600: 'SemiBold', 700: 'Bold' }

const float = (n) => `${Number.isInteger(n) ? `${n}.0` : n}f`

export function renderKotlin(tokens, motion) {
  const out = [FILE_NOTE, '', 'package cc.hyoban.moments.ui.theme', '']
  out.push(
    'import androidx.compose.runtime.Composable',
    'import androidx.compose.runtime.Immutable',
    'import androidx.compose.runtime.ReadOnlyComposable',
    'import androidx.compose.ui.graphics.Color',
    'import androidx.compose.ui.text.TextStyle',
    'import androidx.compose.ui.text.font.FontFamily',
    'import androidx.compose.ui.text.font.FontWeight',
    'import androidx.compose.ui.unit.Dp',
    'import androidx.compose.ui.unit.dp',
    'import androidx.compose.ui.unit.em',
    'import androidx.compose.ui.unit.sp',
    'import cc.hyoban.moments.ui.components.isMediumWidth',
    'import cc.hyoban.moments.ui.design.Typography',
    '',
  )

  // Colours: one class, a value for each theme.
  out.push(
    "/** The design system's colours in one theme; [LightTokens] and [DarkTokens] hold them. */",
  )
  out.push('@Immutable', 'class Tokens(')
  for (const token of tokens.color.tokens) {
    out.push(`    val ${camel(token.name)}: Color,`)
  }
  out.push(')', '')
  for (const theme of tokens.color.themes) {
    const values = resolveColors(tokens, theme.id)
    const name = `${theme.id[0].toUpperCase()}${theme.id.slice(1)}Tokens`
    out.push(`val ${name} = Tokens(`)
    for (const [token, value] of values) out.push(`    ${camel(token)} = Color(${argb(value)}),`)
    out.push(')', '')
  }
  const years = tokens.color.tokens.filter((token) => /^year-\d+$/.test(token.name))
  out.push("/** The map's year colours in order, the same in both themes and on the site. */")
  out.push(
    `val YearColors: List<Color> = listOf(${years.map((t) => `LightTokens.${camel(t.name)}`).join(', ')})`,
    '',
  )

  // The 4dp steps and what a gap means.
  const spacing = new Map(tokens.spacing.tokens.map((token) => [token.name, token]))
  out.push(
    '/** The 4dp grid every gap and padding is a step of, and the gaps that mean something. */',
  )
  out.push('object Space {')
  for (const token of tokens.spacing.tokens) {
    if (token.name.startsWith('gutter')) continue
    const name = token.name.replace(/^space-/, '').replace(/^gap-/, '')
    out.push(`    val ${camel(name)} = ${px(token.value)}.dp`)
  }
  const gutter = spacing.get('gutter')
  const wide = spacing.get('gutter-wide')
  out.push(
    `    val gutter: Dp\n        @Composable @ReadOnlyComposable get() = if (isMediumWidth()) ${px(wide.value)}.dp else ${px(gutter.value)}.dp`,
  )
  out.push('}', '')

  // Corners: the capsule is Corners.full, drawn as a shape rather than a size.
  out.push('/** The corner sizes, each a continuous curve where it is drawn. */', 'object Radius {')
  for (const token of tokens.radius.tokens) {
    if (token.name === 'radius-full') continue
    out.push(`    val ${camel(token.name.replace(/^radius-/, ''))} = ${px(token.value)}.dp`)
  }
  out.push('}', '')

  // Shadows: softShadow draws a level as a blur of 6× and an offset of 1.5× it, at 10% black.
  out.push(
    '/** The shadows as levels, which softShadow draws: a blur of 6× the level, 1.5× it down, 10% black. */',
  )
  out.push('object Elevation {')
  for (const token of tokens.shadow.tokens) {
    const value = typeof token.value === 'string' ? token.value : Object.values(token.value)[0]
    let level = 0
    if (value !== 'none') {
      const match = /^0 (\d+)px (\d+)px rgba\(0, 0, 0, 0\.1\)$/.exec(value)
      if (!match || Number(match[1]) * 4 !== Number(match[2]))
        throw new Error(`softShadow can't draw ${token.name}: ${value}`)
      level = Number(match[2]) / 6
    }
    out.push(`    val level${token.name.replace(/^elevation-/, '')} = ${level}.dp`)
  }
  out.push('}', '')

  out.push('/** How strong a wash or a fade is. */', 'object Opacity {')
  for (const token of tokens.opacity.tokens) {
    out.push(
      `    const val ${camel(token.name.replace(/^opacity-/, ''))} = ${float(Number(token.value))}`,
    )
  }
  out.push('}', '')

  // Type: the system's sans for words, its monospace for figures, with tabular figures where
  // a phone maps the monospace to the sans.
  out.push(
    'private fun style(size: Int, line: Int, weight: FontWeight, mono: Boolean) = TextStyle(',
    '    fontSize = size.sp,',
    '    lineHeight = line.sp,',
    '    fontWeight = weight,',
    '    letterSpacing = 0.em,',
    '    fontFamily = if (mono) FontFamily.Monospace else null,',
    '    fontFeatureSettings = if (mono) "tnum" else null,',
    ')',
    '',
    '/** The 13 text styles. */',
    'val TokenTypography = Typography(',
  )
  for (const group of tokens.type.groups) {
    for (const style of group.styles) {
      const family = style.family ?? group.family
      const weight = WEIGHTS[style.fontWeight]
      if (!weight) throw new Error(`No FontWeight for ${style.fontWeight}`)
      if (px(style.letterSpacing === '0' ? '0px' : style.letterSpacing) !== 0)
        throw new Error(`${style.name} has letter spacing`)
      out.push(
        `    ${camel(style.name)} = style(${px(style.fontSize)}, ${px(style.lineHeight)}, FontWeight.${weight}, mono = ${family === 'mono'}),`,
      )
    }
  }
  out.push(')', '')

  out.push(
    "/** A spring that doesn't overshoot. */",
    'class SpringToken(val dampingRatio: Float, val stiffness: Float)',
    '',
  )
  out.push('/** The springs everything moves on. */', 'object Springs {')
  for (const spring of motion.springs) {
    out.push(
      `    val ${camel(spring.name)} = SpringToken(${float(spring.dampingRatio)}, ${float(spring.stiffness)})`,
    )
  }
  out.push('}', '')
  return out.join('\n')
}
