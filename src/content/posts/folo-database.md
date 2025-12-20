---
title: Folo 中的状态管理 - 数据库篇
link: folo-database
pubDate: "2025-06-17T10:50:53.264Z"
---

最近将 [Folo](https://follow.is) 桌面端和移动端中的状态管理合并到了同一的模块中，就想着记录一下相关的设计和踩坑经验。（很多是从 [Innei](https://innei.in) 的实践中总结出来的，学习到了很多）

文章大概会有两到三篇，本文中主要介绍数据库的选型和整合。

## 为什么需要数据库？

如果应用较为简单，一般可以直接使用 [TanStack Query](https://tanstack.com/query/latest) / [SWR](https://swr.vercel.app) 的 Cache 来持久化请求到的数据，以改善应用首屏加载的加载体验。但是这样的话，一般对于缓存数据的操作会比较麻烦，也可能缺少类型安全。因此手动控制数据的持久化和预加载，将缓存的管理变得和 TanStack Query/SWR 无关，可能长期看来更好维护。

## 数据库的选型

因为在移动端使用了 [Expo SQLite](https://docs.expo.dev/versions/latest/sdk/sqlite), 为了保持数据库 schema 一致，避免写两套数据库操作的代码，在桌面端就使用了 SQLite WASM 的方案。或许也可以看看 [PGlite](https://pglite.dev)。

在浏览器中运行 SQLite 一般可以使用以下几个库：

- [sql.js](https://github.com/sql-js/sql.js) 已知的第一个在 Web 浏览器中直接使用 sqlite3 的程序
  - 只支持内存数据库，除了一次性导入导出整个数据库文件外，不支持持久化。
- [wa-sqlite](https://github.com/rhashimoto/wa-sqlite) 已知的第一个的 [OPFS](https://developer.mozilla.org/en-US/docs/Web/API/File_System_API/Origin_private_file_system) 存储实现 sqlite3 数据库，支持很多类型的 VFS[（来源）](https://github.com/rhashimoto/wa-sqlite/tree/master/src/examples#vfs-comparison)。
- [SQLite Wasm](https://github.com/sqlite/sqlite-wasm) sqlite3 WebAssembly 的 javascript 包装
  - [SQLocal](https://github.com/DallasHoff/sqlocal)，在 SQLite Wasm 上构建，添加了更高级别的抽象，以便与 SQLite Wasm 交互[（来源）](https://github.com/DallasHoff/sqlocal/issues/42#issuecomment-2319543677)。包括与 Kysely 和 Drizzle ORM 的集成。

关于这三者的比较相关信息，可以查看 [how is this different from the @rhashimoto/wa-sqlite and sql.js?](https://github.com/sqlite/sqlite-wasm/issues/109)。从暴露出来的 API 访问级别来看是，SQLite Wasm < wa-sqlite < sql.js，SQLite Wasm 最底层。

最后，SQLocal 是 Folo 桌面端的数据库方案，因为它基于官方的 SQLite Wasm，由 SQLite 核心团队构建，在维护方面的表现应该会更好[（来源）](https://github.com/DallasHoff/sqlocal/issues/31#issuecomment-2209431300)。

## SQLite 在浏览器中的运行模式

SQLite 在浏览器中的运行模式主要有三种，在 [sqlite3 WebAssembly & JavaScript Documentation](https://sqlite.org/wasm/doc/trunk/persistence.md) 中有详细的介绍。

- Key-Value VFS (kvvfs)：在主 UI 线程中运行，使用如 localStorage 或 IndexedDB 来持久化数据。问题是存储空间有限，性能相对较差。
- The Origin-Private FileSystem (OPFS)：在 Worker 中运行，OPFS 对于浏览器的要求相对较高，需要 23 年 3 月之后的浏览器版本。
  - OPFS via sqlite3_vfs：需要 [COOP](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cross-Origin-Opener-Policy) 和 [COEP HTTP](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cross-Origin-Embedder-Policy) 标头以使用 `SharedArrayBuffer`，这个要求较高，比较难以满足。对于图片的加载和外站资源的引入都需要额外的配置。
  - OPFS SyncAccessHandle Pool VFS：不需要 COOP 和 COEP HTTP 标头，性能相对更好，但不支持并发连接，文件系统不透明（即并非将数据库保存为一个 sqlite 文件）

这些运行模式各有优劣，第一种性能较差，存储空间有限，但对浏览器的要求最低，因此仍有很多应用使用它来存储数据库到 indexedDB。第二种对于 COOP 和 COEP HTTP 标头的要求较高，难以满足，但第三种的并发支持又比较麻烦有限。因此，可以在条件允许的情况下，使用第二种，否则回退到第三种。值得一提的是，PGlite 的文件系统也很相似，在浏览器中同样是 In-memory FS、IndexedDB FS、OPFS AHP FS 三种 [（来源）](https://pglite.dev/docs/filesystems)。

前面提到 OPFS SAH 不支持并发，默认情况下，用户打开两个窗口时就会出错。要如何解决呢？需要从多个客户端中协商出一个可以执行查询的，然后暂停其他客户端的使用。PGlite 也有类似的 [Multi-tab Worker](https://pglite.dev/docs/multi-tab-worker) 实现。目前 SQLocal 还没有对 OPFS SAH 的支持，相关的 issue 可以查看 [Allow using sqlite's OPFS_SAH backend](https://github.com/DallasHoff/sqlocal/issues/39)。我基于作者的实现分支进行了一些探索，实现了基础的支持，但目前测试还未完全通过 [（PR）](https://github.com/DallasHoff/sqlocal/pull/76)。

所以 Folo 中会使用哪种运行模式呢？在本地使用网页代理来开发时，由于跨源运行 worker 的限制，会使用 Key-Value VFS；网页端和桌面端的生产环境中，因为 COOP 和 COEP HTTP 标头的条件无法满足，使用 OPFS SAH VFS；

不过桌面端 Electron 中，也可以直接开启 `SharedArrayBuffer` 的支持，来使用 OPFS via sqlite3_vfs。

```ts
app.commandLine.appendSwitch("enable-features", "SharedArrayBuffer")
```

值得一提的是，由于 Electron 中使用的协议不同，一般是 `file://` 或是自定义的 `app://`，因此为了访问安全环境的中才有的 API，需要注册协议。

```ts
// https://github.com/getsentry/sentry-electron/issues/661
protocol.registerSchemesAsPrivileged([
  {
    scheme: "sentry-ipc",
    privileges: { bypassCSP: true, corsEnabled: true, supportFetchAPI: true, secure: true },
  },
  {
    scheme: "app",
    privileges: {
      standard: true,
      bypassCSP: true,
      supportFetchAPI: true,
      secure: true,
    },
  },
])
```

由于 `registerSchemesAsPrivileged` 这个 API 最好[只被调用一次](https://www.electronjs.org/docs/latest/api/protocol#protocolregisterschemesasprivilegedcustomschemes)，所以如果使用了 sentry 的话，推荐将它的的 `registerSchemesAsPrivileged` 调用给 patch 掉，然后在自己的代码中调用。

## 如何为多端复用代码？

显然桌面端和移动端的 SQLite Client 是不同的，所以在打包的时候需要为不同的平台导入不同的文件。Folo 的代码使用后缀来区分，比如 `db.desktop.ts` 用于桌面端，`db.rn.ts` 用于移动端。Vite 可以通过插件来实现[（代码）](https://github.com/RSSNext/Folo/blob/5ddf4b8b18392dfa0b4236fdc0b1392f664ad494/apps/desktop/plugins/vite/specific-import.ts)，Metro 可以通过自定义 `resolver.resolveRequest` 来实现[（代码）](https://github.com/RSSNext/Folo/blob/5ddf4b8b18392dfa0b4236fdc0b1392f664ad494/apps/mobile/metro.config.js#L28)。

这样就可以给每个平台提供不同的数据库实现了。`db.ts` 中定义类型，`db.desktop.ts` 和 `db.rn.ts` 中实现具体逻辑。这里由于使用了 Drizzle ORM，所以自然用上了 Drizzle 的数据表类型定义，来给数据库的操作提供一定的类型安全。至于实际的数据库操作，则和平常写 Drizzle 的代码没有区别。

```ts
// db.ts
import type { BaseSQLiteDatabase } from "drizzle-orm/sqlite-core/db"

import type * as schema from "./schemas"

type DB =
  | BaseSQLiteDatabase<"async", any, typeof schema>
  | BaseSQLiteDatabase<"sync", any, typeof schema>

export declare const sqlite: unknown
export declare const db: DB
export declare function initializeDB(): void
export declare function migrateDB(): Promise<void>
export declare function exportDB(): Promise<Blob>
```

## 数据库迁移

- Drizzle Kit 有非常好用的 migrate 工具，可以通过 `drizzle-kit generate` 命令来生成迁移文件。它和 Expo SQLite 的整合使用已经有完善的[文档](https://orm.drizzle.team/docs/connect-expo-sqlite#expo-sqlite-migrations-with-drizzle-kit)来说明，这里不多赘述。桌面端的迁移可以基于这套方案。
- 因为 migrate 的运行时代码并不依赖 Node，所以也可以在 Web 端来运行[（代码）](https://github.com/RSSNext/Folo/blob/5ddf4b8b18392dfa0b4236fdc0b1392f664ad494/packages/internal/database/src/migrator.ts)。
- 由于生成的 SQL 文件引入语句是直接 import 的，所以为了照顾移动端，这里不使用 Vite 的 [`?raw`](https://vite.dev/guide/assets#importing-asset-as-string)，而是自定义一个插件，将 SQL 文件文本转成正常的 js 模块导出[（代码）](https://github.com/RSSNext/Folo/blob/5ddf4b8b18392dfa0b4236fdc0b1392f664ad494/apps/desktop/configs/vite.render.config.ts#L53-L65)。

## 最后

这一套下来就能在 Folo 中使用单独的包来维护数据库增删改查相关的逻辑，并且多端的代码实现了复用，减少维护的成本和潜在的实现不一致导致的问题。

最后留一个小 Tip，Drizzle ORM 的更新操作处理更新值的时候有些麻烦，需要手写每一列名，且没有类型安全，可以创建一个简单的 helper 函数[（来源）](https://github.com/drizzle-team/drizzle-orm/issues/1728#issuecomment-2148635569)。

## 阅读更多

- [The Current State of SQLite Persistence on the Web: February 2024 Update](https://www.powersync.com/blog/sqlite-persistence-on-the-web)
- [How we sped up Notion in the browser with WASM SQLite](https://www.notion.com/blog/how-we-sped-up-notion-in-the-browser-with-wasm-sqlite)
