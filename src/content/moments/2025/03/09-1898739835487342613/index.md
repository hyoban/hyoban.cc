---
hidden: true
publishedAt: "2025-03-09T22:17:08+08:00"
sourceUrl: "https://x.com/hyoban_cc/status/1898739835487342613"
media:
  - type: image
    file: "image-1.jpg"
    alt: ""
---

用nativewind和eslint-plugin-tailwindcss可能会遇到两个坑。一是monorepo下使用不同的tailwind config可能会读到被错误 cache 的，https://github.com/francoismassart/eslint-plugin-tailwindcss/pull/389。二是 nativewind 的 preset 会根据是否是 tailwind 插件环境来返回不同的内容，导致 no-custom-classname 规则的结果不对。
