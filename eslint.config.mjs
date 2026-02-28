import antfu from '@antfu/eslint-config'
import hyoban from 'eslint-plugin-hyoban'

export default antfu(
  {
    astro: true,
    formatters: true,
  },
  {
    files: ['**/*.md'],
    plugins: {
      hyoban,
    },
    rules: {
      'hyoban/markdown-paragraph-wrapping': 'error',
    },
  },
)
