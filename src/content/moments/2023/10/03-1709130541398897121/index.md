---
hidden: true
publishedAt: "2023-10-03T16:57:29+08:00"
sourceUrl: "https://x.com/hyoban_cc/status/1709130541398897121"
media:
  - type: image
    file: "image-1.jpg"
    alt: ""
---

再然后我借助它 caller-path 的思路，手动 new 一个 Error，拿到里面的 stack，然后用正则匹配文件路径，就可以拿到正确的路径了。虽然考虑了 windows 的情况，也在发包后测试了，但是因为感觉做法有点脏还是很担心有什么情况没考虑到。3/n
