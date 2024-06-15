import { iconsPlugin } from '@egoist/tailwindcss-icons'
import * as radixColors from '@radix-ui/colors'
import typography from '@tailwindcss/typography'
import type { Config } from 'tailwindcss'
import defaultTheme from 'tailwindcss/defaultTheme'
import plugin from 'tailwindcss/plugin'
import animate from 'tailwindcss-animate'
import { createPlugin } from 'windy-radix-palette'
import windyRadixTypography from 'windy-radix-typography'

const colors = createPlugin({
  colors: {
    gray: radixColors.gray,
    grayA: radixColors.grayA,
    grayP3: radixColors.grayP3,
    grayP3A: radixColors.grayP3A,
    grayDark: radixColors.grayDark,
    grayDarkA: radixColors.grayDarkA,
    grayDarkP3: radixColors.grayDarkP3,
    grassDarkP3A: radixColors.grassDarkP3A,
  },
})

/**
 * Composite utility classes using `@apply`.
 *
 * @see https://github.com/tailwindlabs/tailwindcss/discussions/2049
 */
function apply(...classes: string[]) {
  const processedClasses = classes
    .filter(className => className !== '')
    .map(className => className.replaceAll(' ', '_'))
  return { [`@apply ${processedClasses.join(' ')}`]: {} }
}

const config = {
  darkMode: 'class',
  content: [
    './src/**/*.{ts,tsx}',
    './node_modules/sakuin/output/index.js',
  ],
  future: {
    hoverOnlyWhenSupported: true,
  },
  theme: {
    colors: {
      transparent: 'transparent',
      current: 'currentColor',
      inherit: 'inherit',
    },
    extend: {
      fontFamily: {
        mono: [
          'Monolisa Variable',
          'Monolisa',
          'Input Mono',
          'Input',
          ...defaultTheme.fontFamily.mono,
        ],
      },
      typography: {
        DEFAULT: {
          css: {
            'pre': {
              'border-width': '1px',
              'border-color': 'var(--gray6)',
            },
            'img': {
              'border-radius': '.375rem',
            },
            'code ::before': {
              content: '""',
            },
            'code ::after': {
              content: '""',
            },
            'code': {
              'border-radius': '.375rem',
              'border-width': '1px',
              'background-color': 'var(--gray2)',
              'border-color': 'var(--gray6)',
              'padding': '2px 3.6px',
              'font-size': '.8571429em',
            },
            'a': {
              'text-decoration': 'underline',
              'text-underline-position': 'from-font',
              'text-decoration-thickness': '1px',
              'font-weight': '400',
              'color': 'unset',
            },
          },
        },
      },
    },
  },
  plugins: [
    colors.plugin,
    plugin(({ addComponents, theme }) => {
      const palette = theme('colors')
      if (!palette)
        return

      for (const [colorName, color] of Object.entries(palette)) {
        if (typeof color === 'string')
          continue

        addComponents({
          [`.bg-${colorName}-app`]: apply(`bg-${colorName}-1`),
          [`.bg-${colorName}-subtle`]: apply(`bg-${colorName}-2`),
          [`.bg-${colorName}-ui`]: apply(`bg-${colorName}-3`, `hover:bg-${colorName}-4`, `active:bg-${colorName}-5`),
          [`.bg-${colorName}-ghost`]: apply(`bg-transparent`, `hover:bg-${colorName}-4`, `active:bg-${colorName}-5`),
          [`.bg-${colorName}-action`]: apply(`bg-${colorName}-4`, `hover:bg-${colorName}-5`, `active:bg-${colorName}-6`),
          // shouldApplyForeground ? `text-${foregroundColorName}-12` : '',
          [`.bg-${colorName}-solid`]: apply(`bg-${colorName}-9`, `hover:bg-${colorName}-10`),
          [`.border-${colorName}-dim`]: apply(`border-${colorName}-6`),
          [`.border-${colorName}-normal`]: apply(`border-${colorName}-7`, `hover:border-${colorName}-8`),
          [`.divide-${colorName}-dim`]: apply(`divide-${colorName}-6`),
          [`.divide-${colorName}-normal`]: apply(`divide-${colorName}-7`, `hover:divide-${colorName}-8`),
          [`.text-${colorName}-dim`]: apply(`text-${colorName}-11`),
          [`.text-${colorName}-normal`]: apply(`text-${colorName}-12`),
        })
      }
    }),
    animate,
    typography(),
    iconsPlugin({ scale: 1.3 }),
  ],
  presets: [
    windyRadixTypography,
  ],
} satisfies Config

export default config
