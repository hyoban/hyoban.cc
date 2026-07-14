---
publishedAt: "2024-08-10T16:42:27+08:00"
sourceUrl: "https://twitter.com/hyoban_cc/status/1822191769091461159"
media: []
---

If you upgrade to ESLint 9.9.0 and want to use eslint.config.ts, do not forget to add the following settings to your vscode settings.

{
  "eslint.options": {
    "flags": ["unstable_ts_config"]
  }
}
