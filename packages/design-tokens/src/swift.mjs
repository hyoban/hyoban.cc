import { camel, resolveColors } from './kotlin.mjs'

// Renders the design tokens as the iOS app's Design/Tokens.swift: the colours in light and dark,
// the 4pt steps, the corners, the opacities, the six text styles and the springs. The app reads
// these by the tokens' own names, in camel case, as Android's Tokens.kt does.

const FILE_NOTE =
  '// Generated from packages/design-tokens by `vp run design:tokens`. Do not edit by hand.'

const px = (value) => {
  const match = /^(-?\d+(?:\.\d+)?)px$/.exec(String(value))
  if (!match) throw new Error(`Not a px length: ${value}`)
  return Number(match[1])
}

/** A colour value as red, green, blue and alpha from 0 to 1. */
export function rgba(value) {
  let match = /^#([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(value)
  if (match) {
    const hex = match[1]
    const channels = [0, 2, 4].map((i) => Number.parseInt(hex.slice(i, i + 2), 16) / 255)
    return [...channels, match[2] ? Number.parseInt(match[2], 16) / 255 : 1]
  }
  match = /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)$/.exec(value)
  if (match) {
    return [match[1], match[2], match[3]].map((c) => Number(c) / 255).concat(Number(match[4] ?? 1))
  }
  throw new Error(`Not a colour Swift can take: ${value}`)
}

const number = (n) => `${Math.round(n * 10000) / 10000}`

const color = (value) => {
  const [r, g, b, a] = rgba(value)
  return `Rgba(${number(r)}, ${number(g)}, ${number(b)}, ${number(a)})`
}

const WEIGHTS = { 400: 'regular', 500: 'medium', 600: 'semibold', 700: 'bold' }

/** The UIKit text style each of the six scales with under Dynamic Type. */
const SCALES_WITH = {
  'title-page': 'title1',
  title: 'title3',
  'body-reading': 'body',
  body: 'subheadline',
  label: 'subheadline',
  caption: 'caption1',
}

export function renderSwift(tokens, motion) {
  const out = [FILE_NOTE, '', 'import SwiftUI', 'import UIKit', '']

  const [light, dark] = tokens.color.themes.map((theme) => resolveColors(tokens, theme.id))
  out.push(
    "/// The design system's colours, each with its light and dark value, following the system's appearance.",
    'nonisolated enum Tokens {',
  )
  for (const token of tokens.color.tokens) {
    const a = light.get(token.name)
    const b = dark.get(token.name)
    out.push(`    static let ${camel(token.name)} = Color.dynamic(${color(a)}, ${color(b)})`)
  }
  const years = tokens.color.tokens.filter((token) => /^year-\d+$/.test(token.name))
  out.push('')
  out.push("    /// The map's year colours in order, the same in both themes and on the site.")
  out.push(`    static let yearColors: [Color] = [${years.map((t) => camel(t.name)).join(', ')}]`)
  const series = tokens.color.tokens.filter((token) => /^series-\d+$/.test(token.name))
  out.push("    /// The charts' series colours in order.")
  out.push(
    `    static let seriesColors: [Color] = [${series.map((t) => camel(t.name)).join(', ')}]`,
  )
  out.push('}', '')

  out.push('/// The 4pt grid every gap and padding is a step of, and the gaps that mean something.')
  out.push('nonisolated enum Space {')
  for (const token of tokens.spacing.tokens) {
    out.push(
      `    static let ${camel(token.name.replace(/^space-/, ''))}: CGFloat = ${px(token.value)}`,
    )
  }
  out.push('}', '')

  out.push('/// The corner sizes, each drawn as a continuous curve.', 'nonisolated enum Radius {')
  for (const token of tokens.radius.tokens) {
    if (token.name === 'radius-full') continue
    out.push(
      `    static let ${camel(token.name.replace(/^radius-/, ''))}: CGFloat = ${px(token.value)}`,
    )
  }
  out.push('}', '')

  out.push('/// How strong a wash or a fade is.', 'nonisolated enum Opacity {')
  for (const token of tokens.opacity.tokens) {
    out.push(
      `    static let ${camel(token.name.replace(/^opacity-/, ''))}: Double = ${token.value}`,
    )
  }
  out.push('}', '')

  out.push(
    '/// The six text styles: the system sans with tabular figures, each scaling with Dynamic Type',
    '/// as the system style beside it does.',
    'nonisolated enum TextStyle: CaseIterable, Sendable {',
  )
  const styles = tokens.type.groups.flatMap((group) => group.styles)
  out.push(`    case ${styles.map((style) => camel(style.name)).join(', ')}`, '')
  for (const [field, type, read] of [
    ['size', 'CGFloat', (s) => px(s.fontSize)],
    ['lineHeight', 'CGFloat', (s) => px(s.lineHeight)],
    [
      'weight',
      'UIFont.Weight',
      (s) => {
        const weight = WEIGHTS[s.fontWeight]
        if (!weight) throw new Error(`No weight for ${s.fontWeight}`)
        return `.${weight}`
      },
    ],
    ['scalesWith', 'UIFont.TextStyle', (s) => `.${SCALES_WITH[s.name]}`],
  ]) {
    out.push(`    var ${field}: ${type} {`, '        switch self {')
    for (const style of styles) out.push(`        case .${camel(style.name)}: ${read(style)}`)
    out.push('        }', '    }', '')
  }
  out.pop()
  out.push('}', '')

  out.push(
    '/// The springs everything moves on, none overshooting, by damping ratio and stiffness at a mass of 1.',
    'nonisolated enum Springs {',
  )
  for (const spring of motion.springs) {
    const damping = 2 * spring.dampingRatio * Math.sqrt(spring.stiffness)
    out.push(
      `    static let ${camel(spring.name)} = Animation.interpolatingSpring(mass: 1, stiffness: ${spring.stiffness}, damping: ${number(damping)})`,
    )
  }
  out.push('}', '')
  return out.join('\n')
}
