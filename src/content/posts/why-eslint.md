---
title: 为什么是 ESLint
link: why-eslint
pubDate: "2024-05-17T16:14:11.183Z"
---

## 前言

这不是一篇比较 ESLint 和其他相关工具优劣进行拉踩的文章，而是介绍我选择使用 ESLint 来进行代码检查和格式化的原因。虽然不可避免地会提到其他工具，但我觉得每个流行工具的存在都是因为它们有自己的特点和优势。作为用户，我们只需要根据自己的需求和喜好来选择适合自己的工具。如果工具存在问题，我们可以通过反馈和贡献来帮助改进。

## 我看中的 ESLint 的优势

### 更不固执己见的格式化

关于为什么不使用如 [Prettier](https://prettier.io) 或者 [dprint](https://dprint.dev) 之类的格式化工具，我觉得 Anthony Fu 的 [Why I don't use Prettier](https://antfu.me/posts/why-not-prettier) 一文已经说的足够清楚。在此，我补充一点自己的想法。

配置 `printWidth` 并不能告诉 Prettier 我们希望在何时希望换行。你通过增加 `printWidth` 的值来回避一个不该换行的长字符串变量，就可能会导致一个你期望会换行的多参数函数的参数没有被正确地换行。`// prettier-ignore` 的问题和 `// @ts-ignore` 一样，我们虽然成功告诉工具这里不要换行，但同时也失去了这里本可以应用的其它格式化规则。

尽管 Prettier 的哲学是用户不需要考虑格式化的配置选项，让你把代码放心的交给它来变得漂亮。但实际上现有的可配置选项可能是 Prettier 能成为现在 js 社区最流行的格式化工具的原因之一。想象一下，你会使用一个不能控制是用 `tab` 还是 `space` 来缩进的格式化工具吗？考虑到 [`tab` 和 `space` 之争](https://github.com/prettier/prettier/issues/7475) 基本上是五五开的情况，如果没有这个选项的话，Prettier 至少会少一半的用户？

此外，对于相对没那么多争论的选项，Prettier 的固执己见可能会给用户带来一些困扰。例如 Prettier 会强制在文件末尾保留一行空行，这是 [不可配置的](https://github.com/prettier/prettier/issues/6360)。尽管这个行为是大多数人所支持且有利于 git 等工具的，但对于无法忍受的人来说，他们不得不寻找一些 hack 的手段或者寻找别的工具来替代 Prettier。

是的，我们有其它格式化工具可以选择，如 dprint，它的性能表现更好且提供更多的配置选项。但同样的，它仍然没有解决何时代码应该换行的问题。这不是个 bug，而是它们本身的工作模式所决定的。此外，即便 dprint 配置选项更多，但你永远不可能满足所有人的需求，在 ESlint 的世界里，你可以轻松地通过配置现有规则的选项或是写插件来实现你自己的需求。

当我注意到 ESLint Stylistic 现有的规则貌似没有处理 jsx 中一行多个参数间的空格时，我只需要把当前的代码放到 typescript-eslint 的 [playground](https://typescript-eslint.io/play) 中，然后根据右边的的 ESTree 展示的数据结构来写插件，就可以 [实现自己的需求](https://github.com/hyoban/eslint-plugin-hyoban/blob/main/src/jsx-attribute-spacing.ts)。

### 可拓展性

https://x.com/antfu7/status/1788023588244574262

通过使用性能更好的语言来重写原本用 js 写的工具，可以获得更好的性能。但是，这通常是有代价的。使用 Rust 来编写的 lint 工具一般无法轻松的自定义规则，所以只有在 ESLint 社区中极其流行的规则能够得到移植。这意味着我们无法在第一时间拥有官方推出的 ESLint 插件，如随着 React Compiler 发布的 [eslint-plugin-react-compiler](https://github.com/facebook/react/tree/main/compiler/packages/eslint-plugin-react-compiler)；也无法拥有 ESLint 社区中小众但十分有用的插件，如 [ESLint Plugin
Command](https://eslint-plugin-command.antfu.me)。

除了扩展规则之外，ESLint 还支持扩展 lint 的语言，如今你可以轻松的在 Vue，JSON，YAML，Toml，Markdown，Astro，Svelte 等等语言中使用 ESLint 来进行代码检查。但对于使用原生语言所编写的 lint 工具来说，他们通常只能优先支持最主流的语言。比如如果你使用 Biome，那么在你写 Vue 项目时你就暂时无法使用它，还是需要回到 ESLint。我不太喜欢不同项目中使用不同工具导致的不一致性。

### 优秀的生态

这一节中，我并不想再提及 ESLint 的插件生态有多么丰富，我们来聊聊 [ESLint VSCode](https://github.com/microsoft/vscode-eslint) 插件。除了我们每天使用的保存自动修复功能，它还提供了一些其它有用的功能。

在使用 ESlint 时，有些规则我们希望能自动修复，但却不是在保存时马上修复。比如移除未使用的 import，又或是将 let 马上换成 const（我们可能很快就会对变量重新赋值）。此时，我们可以使用 `eslint.codeActionsOnSave.rules` 设置。

```jsonc
{
  "eslint.codeActionsOnSave.rules": [
    "!prefer-const",
    "!unused-imports/no-unused-imports",
    "*"
  ]
}
```

配合 `lint-staged` 与 `simple-git-hooks`，我们可以实现在编辑器中忽略部分规则，然后在 commit 之前将其自动修复。

另一个十分有用的设置是 `eslint.rules.customizations`。前面我们关闭了部分规则的自动修复，但是编辑器还是会将其显示为错误。通过这个设置，我们可以将这些规则的严重程度降低或是彻底关闭。

```jsonc
{
  "eslint.rules.customizations": [
    { "rule": "@stylistic/*", "severity": "off" },
    { "rule": "@stylistic/no-tabs", "severity": "default" },
    { "rule": "@stylistic/max-statements-per-line", "severity": "default" }
    { "rule": "antfu/consistent-list-newline", "severity": "off" },
    { "rule": "prefer-const", "severity": "off" },
    { "rule": "unused-imports/no-unused-imports", "severity": "off" },
    { "rule": "simple-import-sort/*", "severity": "off" },
  ]
}
```

这个设置对于能将 ESLint 作为一个代码格式化工具十分有用，我们可以直接关闭 ESLint Stylistic 中的规则在编辑器中的错误显示，同时保留它们的自动修复功能。在 [下一个版本](https://github.com/microsoft/vscode-eslint/pull/1841) 中，它还允许你调整全部可自动修复的规则的严重程度。

### 类型感知的 lint 规则

基于 Rust 的 lint 工具很快，但是却没有使用类型信息进行 lint 的能力，Josh Goldberg 在 [Rust-Based JavaScript Linters: Fast, But No Typed Linting Right Now](https://www.joshuakgoldberg.com/blog/rust-based-javascript-linters-fast-but-no-typed-linting-right-now/) 一文中进行了十分详细的介绍。

oxlint 最近进行了尝试，但是这似乎也导致了它回到了 JavaScript 的速度。

https://x.com/boshen_c/status/1783632651506823204

Biome 开始准备实现 Type-aware linter。

https://x.com/biomejs/status/1800858872896487889

## 我不那么在乎的 ESLint 的“缺点”

### 性能

你可以看到非常多的 benchmark 展现出 oxlint，biome 等工具的性能远远超过 ESLint。但是，从我的使用场景来看，性能问题好像没有那么重要。

在编辑器中的实时 lint 和 precommit 时的 lint 一般都只需要对少量文件进行检查，我们可以将完整的 lint 过程交给 CI。CI 并不会阻塞我们本地的开发流程，我们只需要在 CI 报错时再在本地对特定文件进行 lint 即可。

我遇到的在编辑器中依然会出现性能问题的情况是，当项目逐渐变大，我们开启基于类型检查的规则会导致编辑器的保存操作出现明显的延迟。但这时我们也不用完全妥协，关闭基于类型检查的规则。ESLint Flat Config 的灵活性允许我们在编辑器中关闭特定的规则。在终端或是 CI 环境中，我们依然可以进行完整的 lint。

对于我自己的 ESLint Config，可以使用如下的配置。

```js
import defineConfig from "eslint-config-hyoban";

const isInEditor = !!(
  (process.env.VSCODE_PID ||
    process.env.VSCODE_CWD ||
    process.env.JETBRAINS_IDE ||
    process.env.VIM) &&
  !process.env.CI
);

export default defineConfig({
  typeChecked: isInEditor ? false : "essential",
});
```

你也可以尝试一下 tsslint，它是一个与 TypeScript 语言服务器无缝集成的轻量级检查工具。

https://github.com/johnsoncodehk/tsslint

### 非官方推荐

ESLint 和 typescript-eslint 官方都决定 [废弃格式化代码相关的规则](https://eslint.org/blog/2023/10/deprecating-formatting-rules/)，同时他们并 [不推荐使用 ESLint 来进行格式化](https://typescript-eslint.io/troubleshooting/formatting)，而是推荐配合 Prettier 等格式化工具来使用。但实际上我并不觉得这是一个问题，废弃这些规则并将其转到社区来维护实际上是个好事，我们现在有 [ESLint Stylistic](https://eslint.style) 这样的开箱即用的工具，并且它表现的非常好。

### 配置复杂，升级麻烦

最近的 ESLint 9.0 让很多人觉得 ESLint 的大版本升级十分复杂，主要遇到的问题是新的的配置文件格式导致我们需要重写写配置，以及 API 的 breaking change 导致很多使用的插件在 9.0 中无法使用。

但是，我觉得这是一个短暂的问题，新的配置文件带来了很多有用的新工具和用法，这是利大于弊的。如 [ESLint Config Inspector](https://github.com/eslint/config-inspector) 可以帮助我们更好编写和测试配置文件；可以根据项目安装的依赖来动态的生成配置（只在安装了 react 的项目中开启 react hooks 相关的规则）。

API 的 breaking change 带来的问题也可以通过多种方式来解决：

1. 给上游插件写 PR 来适配 ESLint v9，很多情况下我们只需要修改几行代码，在 ESLint v9 中使用新的 API，保留旧的 API 作为兼容
1. 暂时使用 ESLint v8，等待插件插件适配（我们依然可以使用 Flat Config）
1. 使用官方推出的 [ESLint Compatibility Utilities](https://eslint.org/blog/2024/05/eslint-compatibility-utilities) 来帮助我们升级

## 结语

需要再次强调的是，这只是我的个人感受和观点，它可能存在考虑得不对的地方，欢迎你和我交流，也欢迎你分享你的观点。

如果你现在想要尝试 ESLint All In One 的话，我十分推荐你从 Anthony Fu 的 ESLint config 起手，它支持非常多的语言和框架，你也可以在其基础之上灵活的进行配置。

https://github.com/antfu/eslint-config

如果你主要写 TypeScript 和 React 的话，也推荐你使用我的 ESLint config 试试。我配置规则的哲学是尽可能使用插件预设的规则，在此基础上按照我的习惯进行调整，同时提供 `strict` 和 `typeChecked` 选项进行不同级别的调整。

https://github.com/hyoban/eslint-config-hyoban
