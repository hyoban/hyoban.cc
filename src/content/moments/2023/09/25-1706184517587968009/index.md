---
publishedAt: "2023-09-25T13:51:03+08:00"
sourceUrl: "https://twitter.com/hyoban_cc/status/1706184517587968009"
media: []
---

之前抄 @nmdfzf404 的 prettier 配置来处理 pnpm-lock.yaml 的格式化，最近确定 https://github.com/planet-matrix/prettier-config 时发现他也移除了全部的 plugin，哈哈哈。

除了保持配置的简洁，现在我还可以添加这一条配置，来让 pnpm 完全不提升依赖。

public-hoist-pattern[]=""
