---
publishedAt: "2023-09-07T09:36:05+08:00"
sourceUrl: "https://twitter.com/hyoban_cc/status/1699597372651893042"
media: []
---

想了想，感觉没有必要的情况下真不用上 Next.js。如果只是需要 ssr 的话，用 Vite 加上 h3 也能完成。
1. React 本身提供 renderToString 和 hydrateRoot
2. Vite 本身支持作为 middleware 来处理 ssr https://vitejs.dev/guide/ssr.html
3. 配合 swr，只需要在服务端提供预渲染的数据就好
