import type { Config } from 'tailwindcss'
import { iconsPlugin } from '@egoist/tailwindcss-icons'

export default {
  plugins: [
    iconsPlugin(),
  ],
} satisfies Config
