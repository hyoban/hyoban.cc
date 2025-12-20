import type { Config } from 'tailwindcss'
import { iconsPlugin } from '@egoist/tailwindcss-icons'
import typographyPlugin from '@tailwindcss/typography'

export default {
  theme: {
    extend: {
      typography: ({ theme }) => {
        return {
          DEFAULT: {
            css: {
              '--tw-prose-body': theme('colors.flexoki[700]'),
              '--tw-prose-headings': theme('colors.flexoki[900]'),
              '--tw-prose-lead': theme('colors.flexoki[600]'),
              '--tw-prose-links': theme('colors.tx'),
              '--tw-prose-bold': theme('colors.flexoki[900]'),
              '--tw-prose-counters': theme('colors.flexoki[500]'),
              '--tw-prose-bullets': theme('colors.flexoki[300]'),
              '--tw-prose-hr': theme('colors.flexoki[200]'),
              '--tw-prose-quotes': theme('colors.flexoki[900]'),
              '--tw-prose-quote-borders': theme('colors.flexoki[200]'),
              '--tw-prose-captions': theme('colors.flexoki[500]'),
              '--tw-prose-kbd': theme('colors.flexoki[900]'),
              '--tw-prose-kbd-shadows': theme('colors.flexoki[900]'),
              '--tw-prose-code': theme('colors.flexoki[900]'),
              '--tw-prose-pre-code': theme('colors.flexoki[200]'),
              '--tw-prose-pre-bg': theme('colors.flexoki[800]'),
              '--tw-prose-th-borders': theme('colors.flexoki[300]'),
              '--tw-prose-td-borders': theme('colors.flexoki[200]'),
              '--tw-prose-invert-body': theme('colors.flexoki[300]'),
              '--tw-prose-invert-headings': theme('colors.tx'),
              '--tw-prose-invert-lead': theme('colors.flexoki[400]'),
              '--tw-prose-invert-links': theme('colors.tx'),
              '--tw-prose-invert-bold': theme('colors.tx'),
              '--tw-prose-invert-counters': theme('colors.flexoki[400]'),
              '--tw-prose-invert-bullets': theme('colors.flexoki[600]'),
              '--tw-prose-invert-hr': theme('colors.flexoki[700]'),
              '--tw-prose-invert-quotes': theme('colors.flexoki[100]'),
              '--tw-prose-invert-quote-borders': theme('colors.flexoki[700]'),
              '--tw-prose-invert-captions': theme('colors.flexoki[400]'),
              '--tw-prose-invert-kbd': theme('colors.tx'),
              '--tw-prose-invert-kbd-shadows': theme('colors.tx'),
              '--tw-prose-invert-code': theme('colors.tx'),
              '--tw-prose-invert-pre-code': theme('colors.flexoki[300]'),
              '--tw-prose-invert-pre-bg': theme('rgb(0 0 0 / 50%)'),
              '--tw-prose-invert-th-borders': theme('colors.flexoki[600]'),
              '--tw-prose-invert-td-borders': theme('colors.flexoki[700]'),
            },
          },
        }
      },
    },
  },
  plugins: [
    typographyPlugin,
    iconsPlugin(),
  ],
} satisfies Config
