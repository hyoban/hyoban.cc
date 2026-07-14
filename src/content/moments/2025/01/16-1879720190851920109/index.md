---
publishedAt: "2025-01-16T10:39:51+08:00"
sourceUrl: "https://twitter.com/hyoban_cc/status/1879720190851920109"
media: []
---

这个 bug 本身也有点意思，在 react 里，文本节点和条件表达式在一起的话，最好给文本节点包上一个标签，不然在使用谷歌翻译时就会遇到问题。类似的 bad case 你可以在 https://www.npmjs.com/package/eslint-plugin-react-google-translate 看到。
