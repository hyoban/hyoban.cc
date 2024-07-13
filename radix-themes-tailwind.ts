/* eslint-disable @cspell/spellchecker */
import { accentColors, grayColors } from '@radix-ui/themes/props'
import { withOptions } from 'tailwindcss/plugin'

type CreateArrayWithLengthX<
    LENGTH extends number,
    ACC extends unknown[] = [],
> = ACC['length'] extends LENGTH
  ? ACC
  : CreateArrayWithLengthX<LENGTH, [...ACC, 1]>

type NumericRange<
   START_ARR extends number[],
   END extends number,
   ACC extends number = never,
>
= START_ARR['length'] extends END
  ? ACC | END
  : NumericRange<[...START_ARR, 1], END, ACC | START_ARR['length']>

const radixColorScales = 12
type RadixColorScales = NumericRange<CreateArrayWithLengthX<1>, typeof radixColorScales>

interface TokenOptions {
  number: RadixColorScales
  useTailwindColorNames?: boolean
  alpha?: boolean
}

function getColorTokenName(options: TokenOptions): number | string {
  const { number, useTailwindColorNames, alpha } = options

  const map = {
    1: 25,
    2: 50,
    3: 100,
    4: 200,
    5: 300,
    6: 400,
    7: 500,
    8: 600,
    9: 700,
    10: 800,
    11: 900,
    12: 950,
  } as const

  if (!useTailwindColorNames) {
    return alpha ? `a${number}` : number
  }

  return alpha ? (`a${map[number]}`) : (map[number])
}

interface ColorOptions {
  color: string
  useTailwindColorNames?: boolean
  alpha?: boolean
}

function getColorDefinitions(options: ColorOptions) {
  const { color, alpha, useTailwindColorNames } = options

  const colors = Array.from({ length: radixColorScales })
    .reduce<Record<string, string>>(
      (acc, _, i) => {
        acc[
          getColorTokenName(
            {
              number: i + 1 as RadixColorScales,
              useTailwindColorNames,
              alpha,
            },
          )
        ] = `var(--${color}-${alpha ? 'a' : ''}${i + 1})`
        return acc
      },
      {},
    )

  if (!alpha) {
    colors.surface = `var(--${color}-surface)`
    colors.indicator = `var(--${color}-indicator)`
    colors.track = `var(--${color}-track)`
    colors.contrast = `var(--${color}-contrast)`
  }

  return colors
}

type RadixColor = Exclude<
  (typeof accentColors)[number] | (typeof grayColors)[number],
  'auto'
>

type MissingTailwindColorsMap = Partial<
  Record<
    'zinc' | 'neutral' | 'stone' | 'emerald' | 'fuchsia' | 'rose',
    RadixColor | Record<string, string>
  >
>

interface RadixThemesPluginOptions {
  includeBase?: boolean
  useTailwindColorNames?: boolean
  useTailwindRadiusNames?: boolean
  mapMissingTailwindColors?: boolean | MissingTailwindColorsMap
}

export const radixThemesPlugin = withOptions(
  // eslint-disable-next-line unicorn/consistent-function-scoping
  () => () => {},
  ({
    includeBase = false,
    useTailwindColorNames = false,
    mapMissingTailwindColors = false,
  }: RadixThemesPluginOptions) => {
    function generateTailwindColors(colorName: string) {
      const c = {
        ...getColorDefinitions({
          color: colorName,
          alpha: false,
          useTailwindColorNames,
        }),
        ...getColorDefinitions({
          color: colorName,
          alpha: true,
          useTailwindColorNames,
        }),
      }

      return c
    }

    const allRadixColors = [...accentColors, ...grayColors].reduce<
      Record<string, Record<string, string>>
    >((acc, colorName) => {
      if (colorName === 'auto')
        return acc
      acc[colorName] = { ...generateTailwindColors(colorName) }
      return acc
    }, {})

    let mappingsOfMissingTailwindColors: MissingTailwindColorsMap = {}
    if (typeof mapMissingTailwindColors === 'boolean') {
      mappingsOfMissingTailwindColors = {
        zinc: generateTailwindColors('sand'),
        neutral: generateTailwindColors('sage'),
        stone: generateTailwindColors('mauve'),
        emerald: generateTailwindColors('grass'),
        fuchsia: generateTailwindColors('plum'),
        rose: generateTailwindColors('crimson'),
      }
    }
    else if (typeof mapMissingTailwindColors === 'object') {
      mappingsOfMissingTailwindColors = {
        zinc:
          typeof mapMissingTailwindColors.zinc === 'string'
            ? generateTailwindColors(mapMissingTailwindColors.zinc)
            : mapMissingTailwindColors.zinc,
        neutral:
          typeof mapMissingTailwindColors.neutral === 'string'
            ? generateTailwindColors(mapMissingTailwindColors.neutral)
            : mapMissingTailwindColors.neutral,
        stone:
          typeof mapMissingTailwindColors.stone === 'string'
            ? generateTailwindColors(mapMissingTailwindColors.stone)
            : mapMissingTailwindColors.stone,
        emerald:
          typeof mapMissingTailwindColors.emerald === 'string'
            ? generateTailwindColors(mapMissingTailwindColors.emerald)
            : mapMissingTailwindColors.emerald,
        fuchsia:
          typeof mapMissingTailwindColors.fuchsia === 'string'
            ? generateTailwindColors(mapMissingTailwindColors.fuchsia)
            : mapMissingTailwindColors.fuchsia,
        rose:
          typeof mapMissingTailwindColors.rose === 'string'
            ? generateTailwindColors(mapMissingTailwindColors.rose)
            : mapMissingTailwindColors.rose,
      }
    }

    return {
      darkMode: 'class',
      // @keep-sorted
      theme: {
        borderRadius: {
          ...(
            includeBase
              ? {
                  'none': '0px',
                  'sm': '0.125rem',
                  'DEFAULT': '0.25rem',
                  'md': '0.375rem',
                  'lg': '0.5rem',
                  'xl': '0.75rem',
                  '2xl': '1rem',
                  '3xl': '1.5rem',
                  'full': '9999px',
                }
              : {}
          ),
          'none': '0px',
          'sm': 'var(--radius-1)',
          'DEFAULT': 'var(--radius-2)',
          'md': 'var(--radius-3)',
          'lg': 'var(--radius-4)',
          'xl': 'var(--radius-5)',
          '2xl': 'var(--radius-6)',
          'full': '9999px',
        },
        colors: ({ colors }) => ({
          ...(includeBase
            ? {
                inherit: colors.inherit,
                current: colors.current,
                transparent: colors.transparent,
                black: colors.black,
                white: colors.white,
                slate: colors.slate,
                gray: colors.gray,
                zinc: colors.zinc,
                neutral: colors.neutral,
                stone: colors.stone,
                red: colors.red,
                orange: colors.orange,
                amber: colors.amber,
                yellow: colors.yellow,
                lime: colors.lime,
                green: colors.green,
                emerald: colors.emerald,
                teal: colors.teal,
                cyan: colors.cyan,
                sky: colors.sky,
                blue: colors.blue,
                indigo: colors.indigo,
                violet: colors.violet,
                purple: colors.purple,
                fuchsia: colors.fuchsia,
                pink: colors.pink,
                rose: colors.rose,
              }
            : {}),

          inherit: colors.inherit,
          current: colors.current,
          black: colors.black,
          white: colors.white,

          background: 'var(--color-background)',
          overlay: 'var(--color-overlay)',
          panel: {
            solid: 'var(--color-panel-solid)',
            translucent: 'var(--color-panel-translucent)',
          },
          surface: 'var(--color-surface)',
          transparent: 'var(--color-transparent)',

          ...allRadixColors,
          ...mappingsOfMissingTailwindColors,
          accent: generateTailwindColors('accent'),
          gray: generateTailwindColors('gray'),
        }),
        fontFamily: {
          ...(
            includeBase
              ? {
                  sans: [
                    'ui-sans-serif',
                    'system-ui',
                    'sans-serif',
                    '"Apple Color Emoji"',
                    '"Segoe UI Emoji"',
                    '"Segoe UI Symbol"',
                    '"Noto Color Emoji"',
                  ],
                  serif: ['ui-serif', 'Georgia', 'Cambria', '"Times New Roman"', 'Times', 'serif'],
                  mono: [
                    'ui-monospace',
                    'SFMono-Regular',
                    'Menlo',
                    'Monaco',
                    'Consolas',
                    '"Liberation Mono"',
                    '"Courier New"',
                    'monospace',
                  ],
                }
              : {}
          ),

          sans: 'var(--default-font-family)',
          mono: 'var(--code-font-family)',

          code: 'var(--code-font-family)',
          strong: 'var(--strong-font-family)',
          heading: 'var(--heading-font-family)',
          em: 'var(--em-font-family)',
          quote: 'var(--quote-font-family)',
        },
        fontSize: {
          ...(
            includeBase
              ? {
                  'xs': ['0.75rem', { lineHeight: '1rem' }],
                  'sm': ['0.875rem', { lineHeight: '1.25rem' }],
                  'base': ['1rem', { lineHeight: '1.5rem' }],
                  'lg': ['1.125rem', { lineHeight: '1.75rem' }],
                  'xl': ['1.25rem', { lineHeight: '1.75rem' }],
                  '2xl': ['1.5rem', { lineHeight: '2rem' }],
                  '3xl': ['1.875rem', { lineHeight: '2.25rem' }],
                  '4xl': ['2.25rem', { lineHeight: '2.5rem' }],
                  '5xl': ['3rem', { lineHeight: '1' }],
                  '6xl': ['3.75rem', { lineHeight: '1' }],
                  '7xl': ['4.5rem', { lineHeight: '1' }],
                  '8xl': ['6rem', { lineHeight: '1' }],
                  '9xl': ['8rem', { lineHeight: '1' }],
                }
              : {}
          ),

          'xs': [
            'var(--font-size-1)',
            {
              letterSpacing: 'var(--letter-spacing-1)',
              lineHeight: 'var(--line-height-1)',
            },
          ],
          'sm': [
            'var(--font-size-2)',
            {
              letterSpacing: 'var(--letter-spacing-2)',
              lineHeight: 'var(--line-height-2)',
            },
          ],
          'base': [
            'var(--font-size-3)',
            {
              letterSpacing: 'var(--letter-spacing-3)',
              lineHeight: 'var(--line-height-3)',
            },
          ],
          'lg': [
            'var(--font-size-4)',
            {
              letterSpacing: 'var(--letter-spacing-4)',
              lineHeight: 'var(--line-height-4)',
            },
          ],
          'xl': [
            'var(--font-size-5)',
            {
              letterSpacing: 'var(--letter-spacing-5)',
              lineHeight: 'var(--line-height-5)',
            },
          ],
          '2xl': [
            'var(--font-size-6)',
            {
              letterSpacing: 'var(--letter-spacing-6)',
              lineHeight: 'var(--line-height-6)',
            },
          ],
          '3xl': [
            'var(--font-size-7)',
            {
              letterSpacing: 'var(--letter-spacing-7)',
              lineHeight: 'var(--line-height-7)',
            },
          ],
          '4xl': [
            'var(--font-size-8)',
            {
              letterSpacing: 'var(--letter-spacing-8)',
              lineHeight: 'var(--line-height-8)',
            },
          ],
          '5xl': [
            'var(--font-size-9)',
            {
              letterSpacing: 'var(--letter-spacing-9)',
              lineHeight: 'var(--line-height-9)',
            },
          ],
        },
        fontWeight: {
          ...(
            includeBase
              ? {
                  thin: '100',
                  extralight: '200',
                  light: '300',
                  normal: '400',
                  medium: '500',
                  semibold: '600',
                  bold: '700',
                  extrabold: '800',
                  black: '900',
                }
              : {}
          ),
          light: 'var(--font-weight-light)',
          normal: 'var(--font-weight-regular)',
          medium: 'var(--font-weight-medium)',
          bold: 'var(--font-weight-bold)',
        },
        letterSpacing: {
          ...(
            includeBase
              ? {
                  tighter: '-0.05em',
                  tight: '-0.025em',
                  normal: '0em',
                  wide: '0.025em',
                  wider: '0.05em',
                  widest: '0.1em',
                }
              : {}
          ),

          1: 'var(--letter-spacing-1)',
          2: 'var(--letter-spacing-2)',
          3: 'var(--letter-spacing-3)',
          normal: 'var(--letter-spacing-3)',
          4: 'var(--letter-spacing-4)',
          5: 'var(--letter-spacing-5)',
          6: 'var(--letter-spacing-6)',
          7: 'var(--letter-spacing-7)',
          8: 'var(--letter-spacing-8)',
          9: 'var(--letter-spacing-9)',
        },
        lineHeight: {
          ...(
            includeBase
              ? {
                  none: '1',
                  tight: '1.25',
                  snug: '1.375',
                  normal: '1.5',
                  relaxed: '1.625',
                  loose: '2',
                  3: '.75rem',
                  4: '1rem',
                  5: '1.25rem',
                  6: '1.5rem',
                  7: '1.75rem',
                  8: '2rem',
                  9: '2.25rem',
                  10: '2.5rem',
                }
              : {}
          ),

          4: 'var(--line-height-1)',
          5: 'var(--line-height-2)',
          6: 'var(--line-height-3)',
          6.5: 'var(--line-height-4)',
          7: 'var(--line-height-5)',
          7.5: 'var(--line-height-6)',
          8: 'var(--line-height-7)',
          10: 'var(--line-height-8)',
          15: 'var(--line-height-9)',
        },
        spacing: {
          px: '1px',
          0: '0px',
          0.5: 'calc(2px * var(--scaling))',
          1: 'var(--space-1)',
          1.5: 'calc(6px * var(--scaling))',
          2: 'var(--space-2)',
          2.5: 'calc(10px * var(--scaling))',
          3: 'var(--space-3)',
          3.5: 'calc(14px * var(--scaling))',
          4: 'var(--space-4)',
          5: 'calc(20px * var(--scaling))',
          6: 'var(--space-5)',
          7: 'calc(28px * var(--scaling))',
          8: 'var(--space-6)',
          9: 'calc(36px * var(--scaling))',
          10: 'var(--space-7)',
          11: 'calc(44px * var(--scaling))',
          12: 'var(--space-8)',
          14: 'calc(56px * var(--scaling))',
          16: 'var(--space-9)',
          20: 'calc(80px * var(--scaling))',
          24: 'calc(96px * var(--scaling))',
          28: 'calc(112px * var(--scaling))',
          32: 'calc(128px * var(--scaling))',
          36: 'calc(144px * var(--scaling))',
          40: 'calc(160px * var(--scaling))',
          44: 'calc(176px * var(--scaling))',
          48: 'calc(192px * var(--scaling))',
          52: 'calc(208px * var(--scaling))',
          56: 'calc(224px * var(--scaling))',
          60: 'calc(240px * var(--scaling))',
          64: 'calc(256px * var(--scaling))',
          72: 'calc(288px * var(--scaling))',
          80: 'calc(320px * var(--scaling))',
          96: 'calc(384px * var(--scaling))',
        },
      },
    }
  },
)
