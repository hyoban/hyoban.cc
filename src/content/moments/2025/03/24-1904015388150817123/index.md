---
publishedAt: "2025-03-24T11:40:18+08:00"
sourceUrl: "https://twitter.com/hyoban_cc/status/1904015388150817123"
media: []
---

GitHub action 默认是不让外部的 PR 访问 secrets
和 variables 的，这很安全。但是我们 build 需要的话，就会让外部 PR 报错，此时不妨跳过它。使用 if: ${{ contains(github.head_ref || github.ref, 'refs/heads/') }} 这样的判断就好。参考 https://github.com/ZcashFoundation/zebra/pull/7956
