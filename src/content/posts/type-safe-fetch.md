---
title: 如何让 fetch 变得类型安全
link: type-safe-fetch
description: 总所周知，发网络请求传递参数和获取返回值时只能凭感觉，但是我们可以做一点类型体操来解决这个问题
pubDate: "2023-08-09T04:56:48.915Z"
---

## 前言

```ts
fetch("https://jsonplaceholder.typicode.com/todos/1")
  .then((response) => response.json())
  .then((json) => console.log(json));
```

当你直接使用如上的代码来发起网络请求时，你会发现你的 IDE 无法给你提供任何关于 `json` 的类型提示。这很合理，没人知道这个接口会返回什么格式的数据。于是你需要进行如下几个步骤：

1. 手动测试这个接口，获取返回值
1. 根据返回值的内容，利用如 [transform.tools] 之类的工具生成对应的类型定义
1. 将类型定义复制到你的项目中
1. 使用 `as` 来标识返回值的类型

这样下来你也只是能获得接口正常返回时 `json` 的类型而已，还有很多问题需要被解决：

1. 手动请求得到返回值不够准确，比如调用接口出错时，返回值的类型可能会发生变化
1. 请求的 `url` 和输入的参数没有任何类型提示
1. 当后端接口变动时，tsc 无法给出报错

## 解决方案的基础

ts 中有个非常好用的功能是 `as const` 断言，给变量添加这个断言后，变量的类型会被推断为更具体的字面量类型，同时会禁止对变量的修改。下面是一个简单的例子，可以看到 `navigateTo` 这个函数的参数变的更加安全了，我们不能传递一个未被定义的路由名称字符串。

```ts
const routes = {
  home: "/",
  about: "/about",
} as const;

declare function navigateTo(route: keyof typeof routes): void;

navigateTo("404");
// Argument of type '"404"' is not assignable to parameter of type '"home" | "about"'.
```

这就是我们能实现类型安全的 fetch 的基础。

## 定义路由结构

首先我们需要定义一个路由结构，这个结构包含了一个接口的全部必要信息（请求路径，输入，输入，以及可能出现的错误码）。同时这里我们使用 `zod` 来进行运行时的数据格式校验。

```ts
// add.ts
import { z } from "zod";

export const path = "/add" as const;

export const input = z.object({
  a: z.number(),
  b: z.number(),
});

export const data = z.object({
  result: z.number(),
});

export const errCode = ["NO_LOGIN"] as const;
```

定义多个接口之后，在 `index.ts` 中导出所有的接口。

```ts
// index.ts
export * as Add from "./add";
```

然后我们就能拿到全部的路由信息

```ts
import * as routesWithoutPrefixObj from "./interface/index";

const routesWithoutPrefix = Object.values(
  routesWithoutPrefixObj
) as ValueOfObjectArray<typeof routesWithoutPrefixObj>;
```

## 定义公共类型

定义通用返回结构，公共前缀和未知错误码。我们并不是接口直接返回数据，而是返回一个包含了错误码和数据的对象。

```ts
const routesWithoutPrefix = Object.values(
  routesWithoutPrefixObj
) as ValueOfObjectArray<typeof routesWithoutPrefixObj>;

export const prefix = "/api";
export type Prefix = typeof prefix;
export const unknownError = "UNKNOWN_ERROR" as const;

export type OutputType<T, Err extends readonly string[]> =
  | {
      err: ArrayToUnion<Err> | typeof unknownError;
      data: null;
    }
  | {
      err: null;
      data: T;
    };
```

计算出带前缀的实际路由

```ts
export const routes = routesWithoutPrefix.map((r) => {
  return {
    ...r,
    path: `${prefix}${r.path}`,
  };
}) as unknown as AddPathPrefixForRoutes<typeof routesWithoutPrefix>;
```

## 转换路由结构

到此为止，我们只是拿到了全部路由对象组成的数组，这对于我们实现类型安全的 fetch 来说并不方便。我们需要将这个数组转换成一个对象，这个对象的 key 是路由的请求路径，value 是路由的其它信息。如此，调用 fetch 时，我们就能获得更好的补全体验，在输入确定的路径之后，能得到正确的输入类型和输出类型。

```ts
type RouteItem = {
  readonly path: string;
  input: z.AnyZodObject;
  data: z.AnyZodObject;
  errCode: readonly string[];
};

type Helper<T> = T extends RouteItem
  ? Record<
      T["path"],
      {
        input: z.infer<T["input"]>;
        data: z.infer<T["data"]>;
        errCode: T["errCode"];
        output: OutputType<z.infer<T["data"]>, T["errCode"]>;
      }
    >
  : never;

export type DistributedHelper<T> = T extends RouteItem ? Helper<T> : never;

export type Route = UnionToIntersection<
  DistributedHelper<ArrayToUnion<typeof routes>>
>;
```

如此我们就能得到最终包含全部路由信息的类型 `Route`，这个类型的大致结构如下：

```ts
type Route = Record<"/api/add", {
    input: {
        a: number;
        b: number;
    };
    data: {
        result: number;
    };
    errCode: readonly ["NO_LOGIN"];
    output: OutputType<{
        result: number;
    }, readonly ["NO_LOGIN"]>;
}> & Record<"/api/dataSet/delDataSet", {
    ...;
}> & ... 13 more ... & Record<...>
```

## 为 fetch 添加类型

可以看到，只需要约束 fetch 的第一个参数 path 为 `keyof Route`，第二个参数为对应路由的输入类型，就能得到正确的输出类型。为了实现上的简单，所有的 http 请求都使用 `POST` 方法，所有的输入参数都从 body 中传递。

```ts
export const myFetch = async <Path extends keyof Route>(
  path: Path,
  input: Route[Path]["input"]
) => {
  try {
    const res = await fetch(path, {
      method: "POST",
      headers: headers,
      body: input instanceof FormData ? input : JSON.stringify(input),
    });
    const data = await (res.json() as Promise<Route[Path]["output"]>);
    if (data.err) {
      throw new CustomError(data.err);
    }
    return data.data as Route[Path]["data"];
  } catch (err) {
    if (err instanceof CustomError) {
      throw err;
    }

    if (err instanceof Error) {
      throw new CustomError(err.message);
    }

    throw new CustomError(unknownError);
  }
};

class CustomError<T extends string> extends Error {
  errorCode: T;

  constructor(msg: string) {
    super(msg);
    Object.setPrototypeOf(this, CustomError.prototype);
    this.errorCode = msg as T;
  }
}
```

nice，让我们来看看使用效果吧。

![ScreenShot 2023-08-09 12.38.53.gif](https://s2.loli.net/2023/08/09/7LaBo5QqRWFAHVm.gif)

## 最后

文章写到这里就算是结束了，取决你自己的需求，你还可以做如下事情：

1. 将它和 useSWR 组合起来，这样你就不必为每个网络接口单独封装函数了
1. 在 fetch 中封装更多逻辑，比如自动携带登录所需的 token 和在登录状态过期时自动的路由拦截
1. 单独处理非 json 交互的接口，比如文件上传下载
1. 说服你的后端，让它用 ts 来给你写接口，这样这些路由定义就不需要你手动写了，也更安全

如果你感谢兴趣，以下是一些被用到但是没有在文章中展开的类型计算。当然，这份实现未必是最好的，如果你有任何改进的想法，欢迎与我讨论，非常感谢。

```ts
export type ValueOfObjectArray<
  T,
  RestKey extends unknown[] = UnionToTuple<keyof T>
> = RestKey extends []
  ? []
  : RestKey extends [infer First, ...infer Rest]
  ? First extends keyof T
    ? [T[First], ...ValueOfObjectArray<T, Rest>]
    : never
  : never;

// https://github.com/type-challenges/type-challenges/issues/2835
type LastUnion<T> = UnionToIntersection<
  T extends any ? (x: T) => any : never
> extends (x: infer L) => any
  ? L
  : never;

type UnionToTuple<T, Last = LastUnion<T>> = [T] extends [never]
  ? []
  : [...UnionToTuple<Exclude<T, Last>>, Last];

type AddPrefix<T, P extends string = ""> = T extends RouteItem
  ? {
      path: `${P}${T["path"]}`;
      input: T["input"];
      data: T["data"];
      errCode: T["errCode"];
    }
  : never;

export type AddPrefixForArray<Arr> = Arr extends readonly []
  ? []
  : Arr extends readonly [infer A, ...infer B]
  ? [AddPrefix<A, Prefix>, ...AddPrefixForArray<B>]
  : never;

export type DistributedHelper<T> = T extends RouteItem ? Helper<T> : never;

export type ArrayToUnion<T extends readonly any[]> = T[number];

export type UnionToIntersection<U> = (
  U extends any ? (k: U) => void : never
) extends (k: infer I) => void
  ? I
  : never;
```

[transform.tools]: https://transform.tools/json-to-typescript
