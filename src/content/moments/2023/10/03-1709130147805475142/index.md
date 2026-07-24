---
hidden: true
publishedAt: "2023-10-03T16:55:55+08:00"
sourceUrl: "https://x.com/hyoban_cc/status/1709130147805475142"
media: []
---

看起来，我修复了 tailwindcss-icons 的一个顽疾，它的 3，16，26 号 issue 都是有关于和 prettier 一起使用的 bug。然后第 17 号 pr 给出了解决问题的关键，就是在 vscode 的 prettier 插件执行时，拿不到正确的 call path。1/n
https://github.com/egoist/tailwindcss-icons/pull/17
