import antfu from '@antfu/eslint-config'
import hyoban from 'eslint-plugin-hyoban'

export default antfu(
  {
    astro: true,
    formatters: true,
    ignores: ['**/.obsidian/**'],
  },
  {
    ignores: ['**/*.md/**/*.?([cm])[jt]s?(x)'],
  },
  {
    files: ['**/*.md'],
    plugins: {
      hyoban,
    },
    rules: {
      'hyoban/md-one-sentence-per-line': 'error',
      'markdown/require-alt-text': 'off',
    },
  },
)
