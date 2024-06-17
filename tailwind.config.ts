import { iconsPlugin } from '@egoist/tailwindcss-icons'
import typography from '@tailwindcss/typography'
import type { Config } from 'tailwindcss'
import defaultTheme from 'tailwindcss/defaultTheme'

import { radixThemesPlugin } from './radix-themes-tailwind'

const config = {
  content: [
    './src/**/*.{ts,tsx}',
    './node_modules/sakuin/output/index.js',
  ],
  future: {
    hoverOnlyWhenSupported: true,
  },
  theme: {
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
    radixThemesPlugin({}),
    typography(),
    iconsPlugin({ scale: 1.3 }),
  ],
} satisfies Config

export default config
