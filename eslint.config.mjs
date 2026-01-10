import antfu from '@antfu/eslint-config'

export default antfu({
  astro: true,
  formatters: true,
  ignores: ['src/content/posts'],
})
