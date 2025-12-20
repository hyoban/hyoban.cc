---
title: 实现一个满意的深色模式切换按钮
link: dark-mode
pubDate: "2024-01-04T13:15:06.994Z"
---

## 它会是什么样子

一：从外观和交互上来说：

1. 只有一个按钮，通过单击的方式来切换，而不是一个三选的 Dropdown Menu
1. 服务端渲染友好，按钮能直接反映当前主题是否为深色
1. 页面刷新时不会出现闪烁
1. 切换时页面颜色整体过渡，不会出现不一致

二：从处理逻辑上来说：

1. 用户偏好可以持久化到浏览器存储
1. 用户偏好可以无感的恢复到系统偏好

![ScreenShot 2024-01-04 19.10.00](https://s2.loli.net/2024/04/21/ZyQXY2EUq5aphm3.gif)

我会使用 [Jotai](https://jotai.org) 来实现，我喜欢 Jotai。

## 获取系统偏好状态

通过 [Media queries](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_media_queries/Using_media_queries#targeting_media_features) 的 [prefers-color-scheme](https://developer.mozilla.org/en-US/docs/Web/CSS/@media/prefers-color-scheme) 和 [matchMedia](https://developer.mozilla.org/en-US/docs/Web/API/Window/matchMedia) 来获取系统偏好是否为深色。

[onMount](https://jotai.org/docs/core/atom) 函数会在 atom 被订阅时执行，在取消订阅时执行其返回的函数。加上判断浏览器环境的逻辑来兼容服务端渲染。

```ts
function atomSystemDark() {
  const isSystemDarkAtom = atom<boolean | null>(null);

  isSystemDarkAtom.onMount = (set) => {
    if (typeof window === "undefined") return;
    const matcher = window.matchMedia("(prefers-color-scheme: dark)");
    const update = () => {
      set(matcher.matches);
    };
    update();
    matcher.addEventListener("change", update);
    return () => {
      matcher.removeEventListener("change", update);
    };
  };
  return isSystemDarkAtom;
}
```

## 什么时候应该是深色

1. 用户的偏好为深色
1. 系统偏好为深色，且用户偏好不为浅色

```ts
type Theme = "system" | "light" | "dark";

function isDarkMode(setting?: Theme | null, isSystemDark?: boolean | null) {
  return setting === "dark" || (!!isSystemDark && setting !== "light");
}
```

## 深色状态的读取和切换

1. 读取用户的主题偏好和系统偏好来判断当前是否为深色模式，用户主题偏好使用 [atomWithStorage](https://jotai.org/docs/utilities/storage) 存储到浏览器（Fixes 2-1）。
1. 借助 [jotai-effect](https://github.com/jotaijs/jotai-effect) 来处理副作用，同步状态到 html 页面，并在用户偏好和当前系统偏好一致时，将用户偏好恢复到系统偏好（Fixes 2-2）。
1. 切换按钮的点击回调不接收参数，根据当前用户偏好和系统偏好来更新用户偏好。

```ts
function atomDark() {
  const isSystemDarkAtom = atomSystemDark();
  const themeAtom = atomWithStorage<Theme>(storageKey, "system");

  const toggleDarkEffect = atomEffect((get, set) => {
    const theme = get(themeAtom);
    const isSystemDark = get(isSystemDarkAtom);
    const isDark = isDarkMode(theme, isSystemDark);
    document.documentElement.classList.toggle("dark", isDark);

    if (
      (theme === "dark" && isSystemDark) ||
      (theme === "light" && !isSystemDark)
    ) {
      set(themeAtom, "system");
    }
  });

  return atom(
    (get) => {
      get(toggleDarkEffect);
      const theme = get(themeAtom);
      const isSystemDark = get(isSystemDarkAtom);
      return isDarkMode(theme, isSystemDark);
    },
    (get, set) => {
      const theme = get(themeAtom);
      const isSystemDark = get(isSystemDarkAtom);
      set(
        themeAtom,
        theme === "system" ? (isSystemDark ? "light" : "dark") : "system"
      );
    }
  );
}
```

## 我们有能用的 hook 了

相比于直接使用 atom，为 `atomDark` 创建一个自定义 hook 会是一个更好的选择。因为 Jotai 的 write 函数是没有响应式的，直接使用 atom 可能会只使用到 `toggleDark` 函数，此时读取到的状态是不正确的。

```ts
const isDarkAtom = atomDark();

function useDark() {
  const isDark = useAtomValue(isDarkAtom);
  const toggleDark = useSetAtom(isDarkAtom) as () => void;
  return { isDark, toggleDark };
}
```

## 来一个按钮

1. 使用 [tailwindcss-icons](https://github.com/egoist/tailwindcss-icons) 和 [Lucide](https://lucide.dev) 来引入图标表示当前主题状态（Fixes 1-1）。
1. 通过使用 tailwind 的 [Dark Mode](https://tailwindcss.com/docs/dark-mode) 支持来正确显示状态图标而不读取 `isDark` 状态使得服务端渲染友好（Fixes 1-2）。
1. 加一点 Transition 动画效果。

```tsx
function AppearanceSwitch() {
  const { toggleDark } = useDark();

  return (
    <button onClick={toggleDark} className="flex">
      <div className="i-lucide-sun scale-100 dark:scale-0 transition-transform duration-500 rotate-0 dark:-rotate-90" />
      <div className="i-lucide-moon absolute scale-0 dark:scale-100 transition-transform duration-500 rotate-90 dark:rotate-0" />
    </button>
  );
}
```

## 页面闪烁怎么解决？

当浏览器加载的页面样式和用户偏好不一致时，就会出现页面闪烁，更新样式的情况。我们需要在页面加载前注入脚本来确保主题正确。（Fixes 1-3）

如果你使用 Vite，可以在 `index.html` 中注入脚本：

```html
<script>
  !(function () {
    var e =
        window.matchMedia &&
        window.matchMedia("(prefers-color-scheme: dark)").matches,
      t = localStorage.getItem("use-dark") || '"system"';
    ('"dark"' === t || (e && '"light"' !== t)) &&
      document.documentElement.classList.toggle("dark", !0);
  })();
</script>
```

如果你使用 Next.js，可以使用 `dangerouslySetInnerHTML` 来注入脚本。值得一提的是，我们需要使用 `suppressHydrationWarning` 来忽略 React 在客户端水合时的警告。因为我们在客户端切换了 `html` 节点的 `className`，这可能会和服务端渲染的结果不一致。

```tsx
function ThemeProvider({ children }: { children: React.ReactNode }) {
  return (
    <>
      <script
        dangerouslySetInnerHTML={{
          __html: "here",
        }}
      ></script>
      {children}
    </>
  );
}

function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html suppressHydrationWarning>
      <body>
        <ThemeProvider>{children}</ThemeProvider>
      </body>
    </html>
  );
}
```

## 在切换时禁用 transition

在 [Disable transitions on theme toggle](https://paco.me/writing/disable-theme-transitions) 一文中，已经十分详细的解释了这样做的原因。因为我们不希望在切换时部分组件的颜色过渡和页面主题的过渡节奏不一致。（Fixes 1-4）

![transition demo](https://paco.me/img/disable-theme-transitions/before.gif)

这很好，但是我们的主题切换按钮有用到 `transition`，我们需要能给部分组件开白名单，可以使用 css 的 [\:not](https://developer.mozilla.org/en-US/docs/Web/CSS/:not) 伪类来实现。

```ts
/**
 * credit: https://github.com/pacocoursey/next-themes/blob/cd67bfa20ef6ea78a814d65625c530baae4075ef/packages/next-themes/src/index.tsx#L285
 */
export function disableAnimation(disableTransitionExclude: string[] = []) {
  const css = document.createElement("style");
  css.append(
    document.createTextNode(
      `
*${disableTransitionExclude.map((s) => `:not(${s})`).join("")} {
  -webkit-transition: none !important;
  -moz-transition: none !important;
  -o-transition: none !important;
  -ms-transition: none !important;
  transition: none !important;
}
      `
    )
  );
  document.head.append(css);

  return () => {
    // Force restyle
    (() => window.getComputedStyle(document.body))();

    // Wait for next tick before removing
    setTimeout(() => {
      css.remove();
    }, 1);
  };
}
```

## 最后

处理完以上问题，我就有了一个满意的深色模式切换按钮了。我将其发布到了 [npm](https://www.npmjs.com/package/jotai-dark) 上，你可以直接使用。你可以在 GitHub 上查看完整的代码和示例。

https://github.com/hyoban/jotai-dark
