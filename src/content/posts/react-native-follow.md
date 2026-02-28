---
title: "Re: 从零开始的 React Native 之旅"
link: react-native-follow
pubDate: "2024-07-21T16:48:51.143Z"
---

## 为什么要写 rn

一个是我自己还没正儿八经地写过 rn，想试试它的体验怎么样。
加上最近对 Follow 这个 RSS 阅读器很感兴趣，但是它暂时还没移动端，可以作为我边学习边实践的对象。
再有就是最近开始上班了，自己老是没什么动力在下班后写点想写的代码，有个目标更容易让自己专注。

## 准备工作

### hello world

好了，废话不多说，让我们跑起来第一个 app 吧。
不过正所谓“工欲善其事，必先利其器”，我们先准备好环境。

一般来说你只需要安装好 Xcode 就行了，不过如果你像我一样，最近升级了 macOS beta 的话，就会麻烦一些：

1. App Store 里的 Xcode 是不能打开的，和系统版本不匹配。
2. 下载完的 Xcode beta 没办法直接打开，提示 `the plug-in or one of its prerequisite plug-ins may be missing or damaged and may need to be reinstalled.`，需要手动安装 `Xcode.app/Contents/Resources/Packages/` 下的安装包。参见 https://forums.developer.apple.com/forums/thread/660860
3. 命令行中需要 select 到你在用的 beta 版 Xcode，`xcode-select -s /Applications/Xcode-beta.app`。

然后就是需要一个 nice 的脚手架，我不太熟悉 rn 这边的技术栈，看完 [State of React Native](https://stateofreactnative.com) 就选择了之前在 Twitter 上看到的 [Create Expo Stack](https://rn.new)。

https://x.com/DanStepanov/status/1800306385797980320

它除了作为一个 expo 项目的脚手架之外，还给你提供了很多主流技术栈的组合选项，这对于我想尽快开始写 app 非常友好。
最终我选择的组合是：

```sh
npx create-expo-stack@latest follow-app --expo-router --tabs --tamagui --pnpm --eas
```

### 处理深色模式

脚手架默认为只支持浅色模式，强迫症表示不能接受，所以首先处理一下先。
参考这个 [issue](https://github.com/facebook/react-native/issues/31806)，我需要修改 expo 的设置为：

```json
{
  "expo": {
    "userInterfaceStyle": "automatic",
    "ios": {
      "userInterfaceStyle": "automatic"
    },
    "android": {
      "userInterfaceStyle": "automatic"
    }
  }
}
```

然后你的 `useColorScheme` 就能正常获得用户当前选择的主题模式。
不过需要注意的是，修改完这个配置，你需要再执行一次 `expo prebuild`，确保 Info.plist 文件里 key 为 `UIUserInterfaceStyle` 的值为 `Automatic`。

## 正戏开始

好了，现在我们来写 Follow app 吧！

### 登录账号

虽然 expo 文档有很详细的 [Authentication 接入文档](https://docs.expo.dev/guides/authentication)，但我们不需要使用它。
Follow 的网页端已经处理好了，我们只需要调用网页端的登录，为 app 注册处理网页登录后会跳转的 scheme 链接就好。

首先设置好 app 的 scheme，在 app config 里面设置 `scheme: 'follow'`，然后运行一下 `expo prebuild`。

用 `expo-web-browser` 打开 Follow 登录页面：

```ts
await WebBrowser.openBrowserAsync('https://dev.follow.is/login')
```

然后用 `expo-linking` 注册 url 的监听事件，在接收到登录网页调起的 url 信息后，解析里面的 token。

```ts
Linking.addEventListener('url', ({ url }) => {
  const { hostname, queryParams } = Linking.parse(url)
  if (hostname === 'auth' && queryParams !== null && typeof queryParams.token === 'string') {
    WebBrowser.dismissBrowser()
    if (Platform.OS !== 'web') {
      SecureStore.setItemAsync(SECURE_AUTH_TOKEN_KEY, queryParams.token)
    }
  }
})
```

这里还遇到的一个问题是 iPhone 上 Safari 的异步函数里的 `window.open` 会无效，需要加上 `target="_top"` 的参数。
参考 https://stackoverflow.com/q/20696041/15548365

因为 url 会跳到 auth 这个页面，我们可以加个让它跳到主页的路由 `app/auth.tsx`。

```tsx
import { router } from 'expo-router'

export default function Auth() {
  router.navigate('/')
  return null
}
```

OK，这样我们就已经能够获取到用户的认证凭据了。
来试试调个接口看看。

### 获取用户信息

在 rn 中发起网络请求看起来和 web 没有区别，我们仍然可以使用自己喜欢的库。

```ts
function useSession() {
  return useSWR(URL_TO_FOLLOW_SERVER, async (url) => {
    const authToken = await SecureStore.getItemAsync(SECURE_AUTH_TOKEN_KEY)
    const response = await fetch(url, {
      headers: {
        cookie: `authjs.session-token=${authToken}`,
      },
      credentials: 'omit',
    })
    const data = (await response.json()) as Session
    return data
  })
}
```

这里我暂时做了一点反常的设置，是因为 rn 中基于 cookie 的身份验证存在一些 [已知的问题](https://reactnative.dev/docs/network#known-issues-with-fetch-and-cookie-based-authentication)，如果不设置 `credentials: 'omit'` 的话，就会在第二次请求时设置不正确的 cookie，导致请求失败。
这里是参考 https://github.com/facebook/react-native/issues/23185#issuecomment-1148130842 的做法。

有了数据我们就可以渲染页面，这里先简单写写：

```tsx
export default function UserInfo() {
  const { data: session, mutate } = useSession()

  return (
    <YStack flex={1} padding={20}>
      {session
        ? (
            <YStack>
              <XStack gap={24} alignItems="center">
                <Image
                  source={{
                    uri: session.user.image,
                    height: 100,
                    width: 100,
                  }}
                  borderRadius={50}
                />
                <YStack gap={8}>
                  <Text color="$color12" fontSize="$8" fontWeight="600">
                    {session.user.name}
                  </Text>
                  <Text color="$color12" fontSize="$5">
                    {session.user.email}
                  </Text>
                </YStack>
              </XStack>
            </YStack>
          )
        : (
            <Button onPress={handlePressButtonAsync}>Login</Button>
          )}
    </YStack>
  )
}
```

好了，来看看现在的效果。

:::div{style="max-width: 400px"}

<video>
  <source src="https://ipfs.crossbell.io/ipfs/QmfLkDiQ74q1V9wXUBkHXCZGNU4dPLSB9dabXurEearWSa" type="video/mp4" />
</video>

:::

啊哦，看起来 Follow 的网页端还需要做点移动端适配，我又可以水 PR 了。

## 主题系统

初始化项目的时候我选的 Tamagui，但是当我要开始自定义主题系统的时候，看文档看得我头晕😵‍💫。
加上它大包大揽的风格，让我切换到了 [Unistyles](https://reactnativeunistyles.vercel.app/start/introduction/)。

它的主题系统就是普通的对象，我只需要将我十分喜欢的 Radix Color 传递给它就好。
和 Tailwind 的配色不同的是，它为每个颜色都设计了对应的深色，支持深色主题变得十分简单。

```ts
export const lightTheme = {
  colors: {
    ...accent,
    ...accentA,
  },
} as const

export const darkTheme = {
  colors: {
    ...accentDark,
    ...accentDarkA,
  },
} as const
```

因为要传递的颜色还比较多，容易忘记在对应的深色主题也添加上对应的主题，可以通过类型检查来进行约束。
参考 [How to test your types](https://www.totaltypescript.com/how-to-test-your-types) 一文。

```ts
type Expect<T extends true> = T
type Equal<X, Y> = (<T>() => T extends X ? 1 : 2) extends <T>() => T extends Y ? 1 : 2 ? true : false
type _ExpectLightAndDarkThemesHaveSameKeys = Expect<Equal<
  keyof typeof lightTheme.colors,
  keyof typeof darkTheme.colors
>>
```

此外，你可以利用它的运行时来轻松修改主题，那么写像下面这样的动态主题切换就十分简单了。

https://x.com/0xhyoban/status/1815764236494377263

```ts
UnistylesRuntime.updateTheme(
  UnistylesRuntime.themeName,
  oldTheme => ({
    ...oldTheme,
    colors: {
      ...oldTheme.colors,
      ...accent,
      ...accentA,
      ...accentDark,
      ...accentDarkA,
    },
  }),
)
```

## Local First

如果说是写网页的话，不做 Local First 还情有所原。
APP 作为可以跑 SQLite 的环境，没什么理由不能在无网的环境中打开。
目前我的想法是 APP 主要和本地的数据库进行交互，利用网络请求来进行数据的同步。

关于技术栈的选型，毫不犹豫的就选择了 drizzle，原因有如下几点：

1. 目前 Follow 的 server 端也在用，我甚至能 copy 很多表的定义。
2. 比起 Prisma 这种利用代码生成来做类型的库，我还是更喜欢用 ts 来写表定义，让类型即时刷新。
3. Expo [官方文档](https://docs.expo.dev/versions/latest/sdk/sqlite/#drizzle-orm) 推荐的和 Expo SQLite 的整合就是 drizzle，[Prisma 的集成](https://github.com/prisma/react-native-prisma) 还处在 Early Access 阶段。

Expo SQLite 提供了 `addDatabaseChangeListener` 的接口，使得我们可以实时获得数据库中最新的数据，drizzle 就提供了 `useLiveQuery` 的封装。
不过目前它的 hook 存在没有正确处理 `useEffect` 依赖数组的问题：

https://x.com/0xhyoban/status/1817150515094147279

此外，我们还需要对结果进行缓存，否则来会切换页面时会有很多不必要的数据库查询。
所以，我们自己利用 swr 来包装一个 hook。

```ts
import type { AnySQLiteSelect } from 'drizzle-orm/sqlite-core'
import type { Key } from 'swr'
import type { SWRSubscriptionOptions } from 'swr/subscription'
import { is, SQL, Subquery } from 'drizzle-orm'
import { getTableConfig, getViewConfig, SQLiteTable, SQLiteView } from 'drizzle-orm/sqlite-core'
import { SQLiteRelationalQuery } from 'drizzle-orm/sqlite-core/query-builders/query'
import { addDatabaseChangeListener } from 'expo-sqlite/next'
import useSWRSubscription from 'swr/subscription'

export function useQuerySubscription<
  T extends
  | Pick<AnySQLiteSelect, '_' | 'then'>
  | SQLiteRelationalQuery<'sync', unknown>,
  SWRSubKey extends Key,
>(
  query: T,
  key: SWRSubKey,
) {
  function subscribe(_key: SWRSubKey, { next }: SWRSubscriptionOptions<Awaited<T>, any>) {
    const entity = is(query, SQLiteRelationalQuery)
    // @ts-expect-error
      ? query.table
      // @ts-expect-error
      : (query as AnySQLiteSelect).config.table

    if (is(entity, Subquery) || is(entity, SQL)) {
      next(new Error('Selecting from subqueries and SQL are not supported in useQuerySubscription'))
      return
    }

    query.then((data) => { next(undefined, data) })
      .catch((error) => { next(error) })

    let listener: ReturnType<typeof addDatabaseChangeListener> | undefined

    if (is(entity, SQLiteTable) || is(entity, SQLiteView)) {
      const config = is(entity, SQLiteTable) ? getTableConfig(entity) : getViewConfig(entity)

      let queryTimeout: NodeJS.Timeout | undefined
      listener = addDatabaseChangeListener(({ tableName }) => {
        if (config.name === tableName) {
          if (queryTimeout) {
            clearTimeout(queryTimeout)
          }
          queryTimeout = setTimeout(() => {
            query.then((data) => { next(undefined, data) })
              .catch((error) => { next(error) })
          }, 0)
        }
      })
    }

    return () => {
      listener?.remove()
    }
  }

  return useSWRSubscription<Awaited<T>, any, SWRSubKey>(
    key,
    subscribe as any,
  )
}
```

注意这里的 queryTimeout ！！！。
由于表变化可能十分频繁，我们需要取消掉之前的查询，否则会影响查询的效率。
Drizzle 还不支持用 AbortSignal 来取消查询，所以用 setTimeout 来处理。

https://github.com/drizzle-team/drizzle-orm/issues/1602

OK，这样我们只要在请求数据的时候正确地设置 key，就能高效地获取最新的数据了。
配合下拉刷新和定时同步数据，我们的 APP 就能够实现基本的 Local First 了。

最后一起看看它现在的样子！

:::div{style="max-width: 400px"}

<video>
  <source src="https://ipfs.crossbell.io/ipfs/QmQcyByJdtfqeD9jb5UjmNPNBURjWhGKxoWkZpA8wxivoW" type="video/mp4" />
</video>

:::

## 分享你写的 iOS App

这篇笔记是记录我在分发 App 给到别人测试的过程中踩的坑，希望能让你少踩一次，当然前提你需要有 Apple Developer 的账号。

参考 Expo 的 [Share pre-release versions of your app](https://docs.expo.dev/guides/sharing-preview-releases) 一文，你有以下三种方式分享你 App 的预览版。

1. Internal distribution
2. TestFlight 内部测试
3. TestFlight 外部测试

### 内部分发

- 通过内部分发的方式，每台测试设备需要使用临时的配置文件，并且每年只能使用此方法分发至最多 100 部 iPhone。
- 临时配置文件的需要需要获取设备的 UDID。要么你需要让用户自己通过 Mac Xcode 连接来获取，要么需要通过安装配置文件来获取（你需要建立和测试者之间的信任）。
- 每次注册测试设备到 Apple，你都需要等待 Apple 来处理，这可能会花上一天的时间。
- 每次注册完新的设备，你都需要重新进行 build。
- 这种方式分发的应用需要用户在手机上开启开发者模式。

综上，这种方式只适用于很小范围内的内部测试。

### TestFlight 内部测试

TestFlight 内部测试需要你为测试者分配你的 Apple Developer 账号权限，它不需要将你的 App 提交审核。
所以它同样只适用于小范围的内部测试。

### TestFlight 外部测试

TestFlight 外部测试可以以多种方式来分发你的 App 到用户，比如通过邮箱添加或是链接添加，这也是最常见的外部测试方式。

它的要求是你需要提交 App 到审核，提交时还显示需要你提供用于测试人员测试的账号，但实际上你可以忽略提交这个信息。
据我提交的体验来说，首次提交会需要一天的时间，但也不会不让通过。
后面的审核都是即时通过的机审，很方便。

顺便一提，填联系信息时，手机号的报错并不正确，你只是需要添加上 +86。

### 总结

在你想要分享你写的 App 给别人使用时，我推荐你首先尝试 TestFlight 外部测试来分发，即时你还没准备好审核。
如果首次审核直接过的话，那就皆大欢喜了。

使用 expo 和 eas 来构建并提交 App 十分方便，你只需要：

```sh
npx eas build --profile production --local
npx eas submit -p ios
```

当然，别忘了更新你的 eas 配置：

```json
{
  "cli": {
    "appVersionSource": "remote"
  },
  "build": {
    "development": {
      "developmentClient": true,
      "distribution": "internal"
    },
    "preview": {
      "distribution": "internal"
    },
    "production": {
      "autoIncrement": true
    }
  },
  "submit": {
    "production": {
      "ios": {
        "ascAppId": "123456"
      }
    }
  }
}
```
