import { iconsPlugin } from '@egoist/tailwindcss-icons'
import typography from '@tailwindcss/typography'
import type { Config } from 'tailwindcss'
import defaultTheme from 'tailwindcss/defaultTheme'
import plugin from 'tailwindcss/plugin'
import animate from 'tailwindcss-animate'

function radixColors(color: string) {
  const scale = Array.from({ length: 12 }, (_, i) => [
    [`${i + 1}`, `var(--${color}-${i + 1})`],
    [`a${i + 1}`, `var(--${color}-a${i + 1})`],
  ]).flat()
  return Object.fromEntries(scale) as Record<string, string>
}

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
      gray: radixColors('gray'),
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
            '--tw-prose-body': 'var(--gray-12)',
            '--tw-prose-headings': 'var(--gray-12)',
            '--tw-prose-lead': 'var(--gray-11)',
            '--tw-prose-links': 'var(--gray-12)',
            '--tw-prose-bold': 'var(--gray-12)',
            '--tw-prose-counters': 'var(--gray-10)',
            '--tw-prose-bullets': 'var(--gray-8)',
            '--tw-prose-hr': 'var(--gray-6)',
            '--tw-prose-quotes': 'var(--gray-11)',
            '--tw-prose-quote-borders': 'var(--gray-6)',
            '--tw-prose-captions': 'var(--gray-11)',
            '--tw-prose-code': 'var(--gray-12)',
            '--tw-prose-pre-code': 'var(--gray-12)',
            '--tw-prose-pre-bg': 'var(--gray-2)',
            '--tw-prose-th-borders': 'var(--gray-6)',
            '--tw-prose-td-borders': 'var(--gray-6)',
            '--tw-prose-invert-body': 'var(--gray-12)',
            '--tw-prose-invert-headings': 'var(--gray-12)',
            '--tw-prose-invert-lead': 'var(--gray-11)',
            '--tw-prose-invert-links': 'var(--gray-12)',
            '--tw-prose-invert-bold': 'var(--gray-12)',
            '--tw-prose-invert-counters': 'var(--gray-10)',
            '--tw-prose-invert-bullets': 'var(--gray-8)',
            '--tw-prose-invert-hr': 'var(--gray-6)',
            '--tw-prose-invert-quotes': 'var(--gray-11)',
            '--tw-prose-invert-quote-borders': 'var(--gray-6)',
            '--tw-prose-invert-captions': 'var(--gray-11)',
            '--tw-prose-invert-code': 'var(--gray-12)',
            '--tw-prose-invert-pre-code': 'var(--gray-12)',
            '--tw-prose-invert-pre-bg': 'var(--gray-2)',
            '--tw-prose-invert-th-borders': 'var(--gray-6)',
            '--tw-prose-invert-td-borders': 'var(--gray-6)',
            'pre': {
              'border-width': '1px',
              'border-color': 'var(--gray-6)',
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
              'background-color': 'var(--gray-2)',
              'border-color': 'var(--gray-6)',
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

} satisfies Config

export default config
