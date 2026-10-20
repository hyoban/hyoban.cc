import { defineConfig } from 'vite-plus'

export default defineConfig({
  // `vp pack` at the root builds the one library in the workspace; the site itself is
  // built by Astro through `vp run build`.
  defaultPackage: { pack: './packages/trip-map' },
  staged: {
    '*': 'vp check --fix',
  },
  fmt: {
    // data/ is written by the API's backup and the scripts in their own canonical JSON,
    // the Android app under apps/android is formatted by Android Studio, and
    // .claude/worktrees holds other checkouts of the repository.
    ignorePatterns: ['data/**', 'apps/android/**', '.claude/**'],
    singleQuote: true,
    semi: false,
  },
  test: {
    // .claude/worktrees holds other checkouts of the repository, each with its own tests.
    exclude: ['**/node_modules/**', '**/.git/**', '.claude/**'],
  },
  lint: {
    jsPlugins: [{ name: 'vite-plus', specifier: 'vite-plus/oxlint-plugin' }],
    rules: { 'vite-plus/prefer-vite-plus-imports': 'error' },
    options: { typeAware: true, typeCheck: true },
  },
})
