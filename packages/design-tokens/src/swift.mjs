import { camel, resolveColors } from './kotlin.mjs'

// Renders the design tokens as the Apple apps' Tokens.swift: the colours in light and dark, the
// 4pt steps, the corners, the shadow, the blur, the sizes, the opacities, the six text styles and
// the springs, by the tokens' own names in camel case, as Android's Tokens.kt has them.

const FILE_NOTE =
  '// Generated from packages/design-tokens by `vp run design:tokens`. Do not edit by hand.'

const px = (value) => {
  const match = /^(-?\d+(?:\.\d+)?)px$/.exec(String(value))
  if (!match) throw new Error(`Not a px length: ${value}`)
  return Number(match[1])
}

const hex2 = (n) => Math.round(n).toString(16).padStart(2, '0').toUpperCase()

/** A colour value as the arguments of Tokens' `rgb`: `0xRRGGBB` and, when not opaque, its alpha. */
export function rgb(value) {
  let match = /^#([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(value)
  if (match) {
    const hex = `0x${match[1].toUpperCase()}`
    if (match[2] === undefined || match[2].toLowerCase() === 'ff') return hex
    return `${hex}, ${Number((Number.parseInt(match[2], 16) / 255).toFixed(4))}`
  }
  match = /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)$/.exec(value)
  if (match) {
    const hex = `0x${hex2(match[1])}${hex2(match[2])}${hex2(match[3])}`
    const alpha = match[4] === undefined ? 1 : Number(match[4])
    return alpha === 1 ? hex : `${hex}, ${alpha}`
  }
  throw new Error(`Not a colour Swift can take: ${value}`)
}

const WEIGHTS = { 400: 'regular', 500: 'medium', 600: 'semibold', 700: 'bold' }

const number = (n) => String(n)

export function renderSwift(tokens, motion) {
  const out = [FILE_NOTE, '', 'import SwiftUI', '']

  // Colours: one struct, a value for each theme.
  out.push(
    "/// The design system's colours in one theme; `Tokens.light` and `Tokens.dark` hold them.",
    'public struct Tokens: Sendable {',
  )
  for (const token of tokens.color.tokens) out.push(`    public let ${camel(token.name)}: Color`)
  out.push('}', '', 'extension Tokens {')
  out.push(
    '    private static func rgb(_ hex: UInt32, _ alpha: Double = 1) -> Color {',
    '        Color(',
    '            .sRGB,',
    '            red: Double((hex >> 16) & 0xFF) / 255,',
    '            green: Double((hex >> 8) & 0xFF) / 255,',
    '            blue: Double(hex & 0xFF) / 255,',
    '            opacity: alpha',
    '        )',
    '    }',
    '',
  )
  for (const theme of tokens.color.themes) {
    const values = [...resolveColors(tokens, theme.id)]
    out.push(`    public static let ${camel(theme.id)} = Tokens(`)
    values.forEach(([token, value], index) =>
      out.push(
        `        ${camel(token)}: rgb(${rgb(value)})${index === values.length - 1 ? '' : ','}`,
      ),
    )
    out.push('    )', '')
  }
  const [first, second] = tokens.color.themes.map((theme) => camel(theme.id))
  if (first !== 'light' || second !== 'dark') throw new Error('The themes are light and dark')
  out.push(
    '    /// The colours of a colour scheme.',
    '    public static func of(_ scheme: ColorScheme) -> Tokens {',
    '        scheme == .dark ? dark : light',
    '    }',
    '',
  )
  const years = tokens.color.tokens.filter((token) => /^year-\d+$/.test(token.name))
  out.push(
    "    /// The map's year colours in order, the same in both themes and on the site.",
    `    public static let yearColors: [Color] = [${years.map((t) => `light.${camel(t.name)}`).join(', ')}]`,
    '}',
    '',
  )

  // The 4pt steps and what a gap means.
  out.push(
    '/// The 4pt grid every gap and padding is a step of, and the gaps that mean something.',
    'public enum Space {',
  )
  const spacing = new Map(tokens.spacing.tokens.map((token) => [token.name, token]))
  for (const token of tokens.spacing.tokens) {
    if (token.name === 'gutter') continue
    out.push(
      `    public static let ${camel(token.name.replace(/^space-/, ''))}: CGFloat = ${px(token.value)}`,
    )
  }
  // A window of medium width and up takes a wider margin, the one rule a size can't carry.
  out.push(
    '',
    "    /// The page's side margin in a window this wide: wider from 600pt up.",
    '    public static func gutter(width: CGFloat) -> CGFloat {',
    `        width >= 600 ? 32 : ${px(spacing.get('gutter').value)}`,
    '    }',
    '}',
    '',
  )

  // Corners: the capsule is drawn as a shape rather than a size.
  out.push(
    '/// The corner sizes, each a continuous curve where it is drawn.',
    'public enum Radius {',
  )
  for (const token of tokens.radius.tokens) {
    if (token.name === 'radius-full') continue
    out.push(
      `    public static let ${camel(token.name.replace(/^radius-/, ''))}: CGFloat = ${px(token.value)}`,
    )
  }
  out.push('}', '')

  // The one shadow, as the CSS it is written in: each platform draws it its own way.
  const [shadow] = tokens.shadow.tokens
  const match = /^0 (\d+)px (\d+)px rgba\(0, 0, 0, ([\d.]+)\)$/.exec(shadow.value)
  if (tokens.shadow.tokens.length !== 1 || !match) throw new Error(`Not one plain shadow`)
  out.push(
    '/// The one shadow, as the design writes it: black at `opacity`, `y` down, blurred by `blur`.',
    'public enum Shadow {',
    `    public static let y: CGFloat = ${match[1]}`,
    `    public static let blur: CGFloat = ${match[2]}`,
    `    public static let opacity: Double = ${match[3]}`,
    '}',
    '',
  )

  out.push('/// How far the page is blurred under what is fixed over it.', 'public enum Blur {')
  for (const token of tokens.blur.tokens) {
    out.push(
      `    public static let ${camel(token.name.replace(/^blur-/, ''))}: CGFloat = ${px(token.value)}`,
    )
  }
  out.push('}', '')

  out.push('/// The sizes every component shares.', 'public enum Size {')
  for (const token of tokens.size.tokens) {
    out.push(
      `    public static let ${camel(token.name.replace(/^size-/, ''))}: CGFloat = ${px(token.value)}`,
    )
  }
  out.push('}', '')

  out.push('/// How strong a wash or a fade is.', 'public enum Opacity {')
  for (const token of tokens.opacity.tokens) {
    out.push(
      `    public static let ${camel(token.name.replace(/^opacity-/, ''))}: Double = ${number(Number(token.value))}`,
    )
  }
  out.push('}', '')

  // Type: the system sans, with tabular figures in every style so numbers line up.
  out.push(
    '/// A text style: the system sans at a size, a line height and a weight, with tabular figures.',
    'public struct TextToken: Sendable {',
    '    public let size: CGFloat',
    '    public let lineHeight: CGFloat',
    '    public let weight: Font.Weight',
    '',
    '    public var font: Font {',
    '        .system(size: size, weight: weight).monospacedDigit()',
    '    }',
    '}',
    '',
    '/// The six text styles.',
    'public enum TextStyles {',
  )
  for (const group of tokens.type.groups) {
    for (const style of group.styles) {
      const weight = WEIGHTS[style.fontWeight]
      if (!weight) throw new Error(`No Font.Weight for ${style.fontWeight}`)
      if (px(style.letterSpacing === '0' ? '0px' : style.letterSpacing) !== 0)
        throw new Error(`${style.name} has letter spacing`)
      out.push(
        `    public static let ${camel(style.name)} = TextToken(size: ${px(style.fontSize)}, lineHeight: ${px(style.lineHeight)}, weight: .${weight})`,
      )
    }
  }
  out.push('}', '')

  out.push(
    "/// A spring that doesn't overshoot, by its damping ratio and stiffness at a mass of one.",
    'public struct SpringToken: Sendable {',
    '    public let dampingRatio: Double',
    '    public let stiffness: Double',
    '',
    '    public var animation: Animation {',
    '        .interpolatingSpring(mass: 1, stiffness: stiffness, damping: 2 * dampingRatio * stiffness.squareRoot())',
    '    }',
    '}',
    '',
    '/// The springs everything moves on.',
    'public enum Springs {',
  )
  for (const spring of motion.springs) {
    out.push(
      `    public static let ${camel(spring.name)} = SpringToken(dampingRatio: ${number(spring.dampingRatio)}, stiffness: ${number(spring.stiffness)})`,
    )
  }
  out.push('}', '')
  return out.join('\n')
}
