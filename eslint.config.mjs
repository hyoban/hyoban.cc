import antfu, { isInEditorEnv } from '@antfu/eslint-config'

export default antfu({
  astro: true,
  formatters: true,
  typescript: {
    tsconfigPath: !isInEditorEnv() ? 'tsconfig.json' : undefined,
  },
})
