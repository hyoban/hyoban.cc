---
publishedAt: "2024-01-09T22:50:08+08:00"
sourceUrl: "https://twitter.com/hyoban_cc/status/1744733296561631347"
media: []
---

感觉学到了一个 jotai 的小技巧，write 这样写 (get, _set, anAtom: AnyAtom) =&gt; get(anAtom), 然后 setSelf 就变成了一个 writeGetter。
它是没有响应式的 get，可以用来读一些不想建立响应式的 atom。
https://github.com/jotaijs/jotai-cache/pull/7/files
