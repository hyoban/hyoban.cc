import path from 'node:path'

import { defineConfig } from 'eslint-config-hyoban'
import { getTsconfig } from 'get-tsconfig'

const tsconfig = getTsconfig()

function getAlias() {
  const alias = tsconfig.config.compilerOptions.paths

  if (!alias)
    return

  return Object.entries(alias)
    .filter(([key, value]) =>
      key.endsWith('/*')
      && value.length === 1
      && value[0].endsWith('/*'),
    )
    .map(([key, value]) => ([key.slice(0, -2), value[0].slice(0, -2)]))
}

export default defineConfig(
  {
    fileCase: 'kebabCase',
    cspell: true,
    tailwindCSS: { order: false },
    strict: true,
    typeChecked: true,
  },
  {
    plugins: {
      custom: {
        rules: {
          'no-complex-relative-import': {
            meta: {
              fixable: true,
            },
            create(context) {
              return {
                ImportDeclaration(node) {
                  const importSource = String(node.source.value)
                  const isRelative = importSource.startsWith('../')
                  if (!isRelative)
                    return
                  const level = importSource.split('/').filter(i => i === '..').length
                  if (level <= 1)
                    return
                  const { filename } = context
                  const absoluteImportPath = path.normalize(path.join(path.dirname(filename), importSource))
                  const basePath = process.cwd()

                  const alias = getAlias()

                  if (!alias)
                    return

                  const allRelativePath = alias.map(
                    ([,aliasPath]) => path.relative(
                      path.join(basePath, aliasPath),
                      absoluteImportPath,
                    ),
                  )

                  const allNewImportPath = allRelativePath.map(
                    (relativePath, index) => `${alias[index][0]}/${relativePath}`,
                  )
                  const newImportPath = allNewImportPath.sort((a, b) => a.length - b.length)[0]

                  context.report({
                    node,
                    message: `Use '${newImportPath}' instead of complex relative import path`,
                    fix(fixer) {
                      return fixer.replaceText(node.source, `'${newImportPath}'`)
                    },
                  })
                },
              }
            },
          },
          'no-alias-import': {
            meta: {
              fixable: true,
            },
            create(context) {
              return {
                ImportDeclaration(node) {
                  const alias = getAlias()

                  if (!alias)
                    return

                  const importSource = String(node.source.value)
                  const isAliasImport = alias.some(([aliasKey]) => importSource.startsWith(aliasKey))
                  if (!isAliasImport)
                    return

                  const basePath = process.cwd()
                  const [aliasKey, aliasPath] = alias.find(([aliasKey]) => importSource.startsWith(aliasKey))
                  const relativeImportPath = importSource.replace(aliasKey, aliasPath)
                  const absoluteImportPath = path.normalize(path.join(basePath, relativeImportPath))

                  context.report({
                    node,
                    message: `Use relative import path instead of alias import path`,
                    fix(fixer) {
                      return fixer.replaceText(node.source, `'${path.relative(path.dirname(context.filename), absoluteImportPath)}'`)
                    },
                  })
                },
              }
            },
          },
          'no-import-js-extension': {
            meta: {
              fixable: true,
            },
            create(context) {
              return {
                ImportDeclaration(node) {
                  const a = tsconfig.config.compilerOptions.moduleResolution
                  if (a !== 'Bundler')
                    return

                  const importSource = String(node.source.value)
                  if (!importSource.endsWith('.js'))
                    return

                  context.report({
                    node,
                    message: `Use import path without '.js' extension`,
                    fix(fixer) {
                      return fixer.replaceText(node.source, `'${importSource.slice(0, -3)}'`)
                    },
                  })
                },
              }
            },
          },
        },
      },
    },
    rules: {
      'custom/no-complex-relative-import': 'error',
      'custom/no-import-js-extension': 'error',
    },
  },
)
