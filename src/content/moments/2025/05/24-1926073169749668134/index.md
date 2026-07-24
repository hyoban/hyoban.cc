---
hidden: true
publishedAt: "2025-05-24T08:30:03+08:00"
sourceUrl: "https://x.com/hyoban_cc/status/1926073169749668134"
media: []
---

写了一个 sqlocal 的 demo，可以为 mobile、web 以及 electron 共享数据持久化和状态管理的逻辑了。
1. 为不同平台使用不同的 sqlite client，使用同一份访问 db 的代码，移动端仍然可以和之前一样用 expo sqlite。
2. drizzle-kit 的 migrate 功能在多端都可用
https://github.com/hyoban/sqlocal-demo
