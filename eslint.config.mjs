import antfu from '@antfu/eslint-config'
import hyoban from 'eslint-plugin-hyoban'

export default antfu(
  {
    astro: true,
    formatters: true,
    ignores: ['**/.obsidian/**'],
  },
  {
    files: ['**/*.md'],
    plugins: {
      hyoban,
    },
    rules: {
      'hyoban/md-one-sentence-per-line': 'error',
    },
  },
)
