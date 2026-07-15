---
hidden: true
publishedAt: "2026-01-17T20:39:50+08:00"
sourceUrl: "https://twitter.com/hyoban_cc/status/2012505140918698457"
media:
  - type: image
    file: "image-1.jpg"
    alt: ""
---

第二个是 Multithread Linting，可以让你在大型项目的 lint 时间减少一半以上。不过在 CI 里，和实际会被分配的资源有关，提升一般比你本机的提升要小。以 Dify 的 lint 为例，在 CI 里不开 concurrency 的时间是 4m 12s，开
--concurrency 2 的话就是 2m 38s。
https://eslint.org/blog/2025/08/multithread-linting/
