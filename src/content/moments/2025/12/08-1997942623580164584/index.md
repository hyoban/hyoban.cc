---
hidden: true
publishedAt: "2025-12-08T16:13:36+08:00"
sourceUrl: "https://twitter.com/hyoban_cc/status/1997942623580164584"
media:
  - type: image
    file: "image-1.jpg"
    alt: ""
---

vecel.json 支持给 function 配置 includeFiles，部署 hono 时也支持自定义 outputDirectory 来自定义 entry。但是它预检查 functions 的时候只固定检查 src 文件夹下，搞得我老以为自己写的有问题。目前只能暂时把产物文件夹设置成 src 了。
https://github.com/vercel/vercel/pull/14429
