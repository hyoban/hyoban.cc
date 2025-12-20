---
title: 我如何开始写一个 TypeScript 库
link: starter-ts
pubDate: "2024-02-15T07:14:39.208Z"
---

要全自己折腾的话，或许会陷入无尽的坑，所以我选择从 antfu 的 [starter-ts] 开始，按照自己的习惯进行一些改造。

## 技术栈选择

### TypeScript + ESLint + Prettier

[TypeScript] 自不必多说，我使用 [ESLint] 来检查和格式化代码。

如果你和 [Prettier] 的 `printWidth` 也做过斗争并且不能忍受的话，请看 [Why I don't use Prettier]，可能会喜欢用 ESLint 来格式化代码，并且我们现在有了 [ESLint Stylistic] 这种开箱即用的配置。

要了解更多，请查看 [为什么是 ESLint](https://hyoban.xlog.app/why-eslint)。

### pnpm + bunchee + tsx + vitest

为了方便的测试我们写的库，[pnpm] 的 [workspace][pnpm workspace] 是必不可少的，可以很方便的开一个 playground。此外，它默认不提升依赖的特性也能够防止我们不小心引用了没有定义的依赖。我的 `.npmrc` 为：

```ini
ignore-workspace-root-check=true
public-hoist-pattern=[]
```

使用 [bunchee] 来完成打包任务，它读取 package.json 中的 `exports` 字段作为打包的输入输出，无需手动指定配置。此外它的 esm 打包结果看起来也更好，tsup 存在 [ESM output with CJS content](https://github.com/egoist/tsup/issues/701) 的问题。

如果你希望更清楚精细的控制打包流程，可以使用 [rollup] 配合一些插件来自己写配置。这里有一些常用的插件推荐。

1. [rollup-plugin-dts]
1. [rollup-plugin-swc] 或者 [rollup-plugin-esbuild]
1. [@rollup/plugin-node-resolve]
1. [@rollup/plugin-commonjs]

如果你想看看类似 bunchee 的其它选择，可以看看 [unbuild] 和 [tsup]。tsup 的 `--dts-resolve` 选项在你想要打包一些依赖的时候很有用。

开发过程中，非必要的情况下，基本上没人想先打包再跑代码。因此，我使用 [tsx] 来直接执行 ts 文件，用 [vitest] 来测试代码。

## 正确设置 `package.json`

### 便捷地维护包的基本信息

作为一个起手模板，它需要提前写好包的基本信息，并可以在开一个新坑的时候快速的查找替换。

基本信息处于两个位置，一个是 `package.json`，一个是 `README.md`，通过全局替换 `pkg-placeholder` 和 `$description$` 可以让你的包快速就位并发布。

### 设置包导出的内容

首先你可以阅读 [Ship ESM & CJS in one Package]，[Types for Submodules] 和 [moduleResolution 总结] 这几篇文章来了解同时发布 esm 和 cjs 两种格式包相关的基础信息。
然后可以使用 [publint]，[arethetypeswrong]，[modern-guide-to-packaging-js-library] 这些工具来检查你的包是否符合规范。

```sh
npx publint
npx -p @arethetypeswrong/cli attw --pack .
```

我最终得出的配置如下：

```json
{
  "sideEffects": false,
  "exports": {
    ".": {
      "import": {
        "types": "./dist/index.d.ts",
        "default": "./dist/index.js"
      },
      "require": {
        "types": "./dist/index.d.cts",
        "default": "./dist/index.cjs"
      }
    }
  },
  "main": "./dist/index.cjs",
  "module": "./dist/index.js",
  "types": "./dist/index.d.ts",
  "typesVersions": {
    "*": {
      "*": ["./dist/*", "./dist/index.d.ts"]
    }
  },
  "files": ["dist"]
}
```

### 保持依赖定义正确

使用 [knip] 查找项目中未使用的文件、依赖项和导出。

```sh
npx knip
```

使用 [taze] 来手动更新依赖或者使用 [renovatebot] 来定时自动更新。

## 自动修复 lint

为了保证 commit 的代码符合代码规范，可以使用 [simple-git-hooks] 来在 commit 之前进行检查。

```json
{
  "simple-git-hooks": {
    "pre-commit": "pnpm lint-staged"
  },
  "lint-staged": {
    "*": "eslint --fix"
  }
  "scripts": {
    "prepare": "simple-git-hooks",
  }
}
```

不过这种方案存在的问题是，如果你的 ESLint 配置发生了变化，那 commit 的代码依然可能存在需要修复的问题。所以我更倾向于在 CI 中进行检查。使用 [git-auto-commit-action] 来自动修复并 apply 到当前分支。这样还有一个好处是你不需要 require 其它贡献者完全设置好正确的环境。

## 发版流程

要使我们的包版本号 +1，大抵有以下几个步骤：

1. 进行发版前的检查
1. 更新版本号
1. 发布到 npm
1. commit && tag
1. 写这次更新的日志，在 GitHub 上发布 release

如果每次都要手动做这些事情，那就太麻烦了，所以我们可以使用 [release-it] 来帮助我们自动化这些步骤。借助 release-it 的 hooks 功能和自定义插件，我们能够方便的定义需要的发版流程。

release-it 对于 pnpm workspace 的支持几乎为零，在 monorepo 发布多个包的情况下需要切换到其它方案，我写了个插件提供简单的支持。

https://github.com/hyoban/release-it-pnpm

在这个插件中我还按照自己的喜好自动决定下一个版本号，以及整合 [bumpp] 与 [changelogithub]。

## 我的 starter

我将我收拾完的 starter 开源了，如果你和我的喜好一样的话，可以基于它来改造自己的 starter。

https://github.com/hyoban/starter-ts

[Ship ESM & CJS in one Package]: https://antfu.me/posts/publish-esm-and-cjs
[Types for Submodules]: https://antfu.me/posts/types-for-sub-modules
[publint]: https://github.com/bluwy/publint
[arethetypeswrong]: https://github.com/arethetypeswrong/arethetypeswrong.github.io
[modern-guide-to-packaging-js-library]: https://github.com/frehner/modern-guide-to-packaging-js-library
[starter-ts]: https://github.com/antfu/starter-ts
[ESLint]: https://github.com/eslint/eslint
[TypeScript]: https://github.com/microsoft/TypeScript
[simple-git-hooks]: https://github.com/toplenboren/simple-git-hooks
[pnpm]: https://github.com/pnpm/pnpm
[tsup]: https://github.com/egoist/tsup
[tsx]: https://github.com/privatenumber/tsx
[vitest]: https://github.com/vitest-dev/vitest
[rollup]: https://github.com/rollup/rollup
[rollup-plugin-dts]: https://github.com/Swatinem/rollup-plugin-dts
[rollup-plugin-swc]: https://github.com/SukkaW/rollup-plugin-swc
[rollup-plugin-esbuild]: https://github.com/egoist/rollup-plugin-esbuild
[unbuild]: https://github.com/unjs/unbuild
[@rollup/plugin-node-resolve]: https://github.com/rollup/plugins/tree/master/packages/node-resolve
[@rollup/plugin-commonjs]: https://github.com/rollup/plugins/tree/master/packages/commonjs
[knip]: https://github.com/webpro/knip
[taze]: https://github.com/antfu/taze
[renovatebot]: https://docs.renovatebot.com
[bunchee]: https://github.com/huozhi/bunchee
[pnpm workspace]: https://pnpm.io/workspaces
[release-it]: https://github.com/release-it/release-it
[Why I don't use Prettier]: https://antfu.me/posts/why-not-prettier
[Prettier]: https://github.com/prettier/prettier
[ESLint Stylistic]: https://eslint.style/
[moduleResolution 总结]: https://juejin.cn/post/7221551421833314360
[bumpp]: https://github.com/antfu/bumpp
[changelogithub]: https://github.com/antfu/changelogithub
[git-auto-commit-action]: https://github.com/stefanzweifel/git-auto-commit-action
