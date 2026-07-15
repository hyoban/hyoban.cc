---
hidden: true
publishedAt: "2023-12-16T14:33:55+08:00"
sourceUrl: "https://twitter.com/hyoban_cc/status/1735911112804303254"
media: []
---

https://github.com/egoist/tailwindcss-icons 有个潜在的问题是你不能直接安装 iconify/json 然后用任何图标。因为会把所有的图标名都注册到 tailwind 插件的自动补全里，导致你的编辑器很卡。
所以我就仿照 iconify/tailwind 的实现，额外提供一个使用任意图标的插件。这样好像就可以鱼和熊掌都要了。
