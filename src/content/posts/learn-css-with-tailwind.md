---
title: 你可以用 Tailwind 来学习 CSS
link: learn-css-with-tailwind
pubDate: "2023-08-16T13:06:42.431Z"
---

## 如果你还不太懂 HTML 和 CSS

HTML 大概是长成下面这样子的。每个 HTML 标签都有自己的含义（如表示图片或者链接）；或由一组标签包围，或者自闭合；可以层级嵌套。N 多个 HTML 标签共同组合而成为你在浏览器中看到的网页。

```html
<img alt="A dog on an iPad" src="/assets/images/dog-ipad.jpg" />
<p class="text">Here is <a href="https://example.com">a link</a></p>
```

如果你对有哪些常用的 HTML 标签感兴趣，可以看这个 Simple.css 的 [Demo 网页](https://simplecss.org/demo)。

但是，如果只有 HTML，你的网页可能看起来会非常简陋，只有浏览器所提供的默认样式。所以，我们需要 CSS 来为我们的网页添加样式。

```css
.text {
  color: #333;
  font-size: 16px;
  line-height: 1.5;
}
```

`.text` 是一个 CSS 类选择器，它会匹配到 HTML 中 `class` 中包含 `text` 的标签，从而为其添加样式。这里我们简单设置了一下文本的颜色，大小和行高。

结合 HTML 和 CSS，你就已经可以做出好看的静态网页了。如果你对还有哪些 CSS 的语法感兴趣，别急，看完这边文章你会得到一个极佳的学习文档。

## Tailwind 是什么

> A utility-first CSS framework packed with classes like flex, pt-4, text-center and rotate-90 that can be composed to build any design, directly in your markup.

以上是 Tailwind 的官方介绍。简单来说，它就是一个原子化 CSS 框架，提供了很多工具类来让你可以直接在 HTML 为你的网页添加样式。

在开始介绍如何通过 Tailwind 学习 CSS 之前，我们先来看看 Tailwind 帮我们做了什么：

1. 扫描你的 HTML 文件，找到所有的 `class` 属性
1. 根据这些 `class` 属性中匹配的工具类，如 `flex m-1`，生成对应的 CSS 内容

是的，就这么简单。你完全可以在不使用 Tailwind 的情况下，手动写出 CSS 的内容。它不像 Vue.js 或者 React 那样，理解它们所做的事情需要付出很大的学习成本。

## 为什么要用 Tailwind 来学习 CSS

与通过 MDN 的文档来学习相比：

1. Tailwind 的文档更加简洁，更多图例；MDN 的文档更加详细，更多概念。
   ![justify-start doc](https://s2.loli.net/2023/08/16/NhYoerOTa6ZjFRB.png)
1. Tailwind 分类更加清晰，更能突出重点；MDN 的文档更加全面，但是容易让人迷失。
   - CSS 的知识点很多，但是日常开发中我们只会用到其中的一部分。
1. Tailwind 的文档有助于你更快了解一些关键概念，如 [深色模式支持](https://tailwindcss.com/docs/dark-mode) 和 [响应式设计](https://tailwindcss.com/docs/responsive-design)。
1. Tailwind 的 [主题系统](https://tailwindcss.com/docs/theme) 有助于你写出更有设计感的页面。
1. Tailwind 提供一个功能强大的 [Playground](https://play.tailwindcss.com)，让你可以直接在浏览器中进行学习。

### 如果你是个设计师

作为一个设计师，如果你只会使用设计工具来做设计的话，那么可能会遇到的一个问题是：

> 开发：你这个设计我不好实现啊！！

但是，如果你用代码做设计，他再敢这么说，你就直接把 Tailwind Playground 的链接丢给他，看他还敢多说话。

同时，和开发用一套技术意味着你们能获得效率上的提升：

1. 开发不需要自己将整套设计一点点的对应到代码实现中
1. 设计能确保开发百分百还原自己的设计

## 最后要强调的

并非所有的 Tailwind 工具类都十分直观且简单，就好像 `flex-row` 等于 `flex-direction: row;`一样。`grid-cols-1` 对应生成的 CSS 要稍复杂一些，它等于 `grid-template-columns: repeat(1, minmax(0, 1fr));`。在你没有理解它帮你生成的 CSS 的含义之前，请不要继续只看 Tailwind 的文档，你应该做的是去 MDN 的文档中学习这个属性的具体含义。

在 Playground 中，你可以通过自动补全和将鼠标移上去的方式来看到某个工具类对应生成的 CSS。所以，开始学习 Tailwind 吧，即使你不会 CSS。它优秀的文档和 Playground 会成为你学习的好帮手。

## 一个插件

在 vscode 中，你可以通过安装我写的一个插件来看到哪些文本会生成对应的 CSS。

https://github.com/hyoban/tailwindcss-classname-highlight
