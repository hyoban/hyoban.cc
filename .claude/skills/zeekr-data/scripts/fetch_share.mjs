#!/usr/bin/env node
// Open Zeekr monthly-report share links in headless Chrome and print the
// month JSON each page loads (POST .../open/shareQuery), one object per link.
//
// The endpoint only answers requests the page itself signs, so the page is
// loaded as a browser would and its response is read over the DevTools
// protocol.
//
// Usage: node fetch_share.mjs <share-url>... > months.json

import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const CHROME = process.env.CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const urls = process.argv.slice(2);
if (!urls.length) {
  console.error("usage: fetch_share.mjs <share-url>...");
  process.exit(2);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const profile = mkdtempSync(join(tmpdir(), "zeekr-share-"));
const chrome = spawn(CHROME, [
  "--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profile}`,
  "--no-first-run", "--no-default-browser-check", "about:blank",
], { stdio: "ignore" });

try {
  const portFile = join(profile, "DevToolsActivePort");
  for (let i = 0; i < 100 && !existsSync(portFile); i++) await sleep(100);
  const port = readFileSync(portFile, "utf8").split("\n")[0];
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const ws = new WebSocket(targets.find((t) => t.type === "page").webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener("open", r, { once: true }));

  let seq = 0;
  const pending = new Map();
  const listeners = new Set();
  ws.addEventListener("message", ({ data }) => {
    const msg = JSON.parse(data);
    if (msg.id && pending.has(msg.id)) {
      pending.get(msg.id)(msg);
      pending.delete(msg.id);
    } else for (const l of listeners) l(msg);
  });
  const send = (method, params = {}) => new Promise((resolve) => {
    const id = ++seq;
    pending.set(id, resolve);
    ws.send(JSON.stringify({ id, method, params }));
  });

  await send("Network.enable");
  const out = [];
  for (const url of urls) {
    const body = await new Promise((resolve) => {
      let reqId = null;
      const timer = setTimeout(() => { listeners.delete(on); resolve(null); }, 20000);
      const on = async (msg) => {
        if (msg.method === "Network.responseReceived" && msg.params.response.url.includes("/open/shareQuery")) {
          reqId = msg.params.requestId;
        } else if (msg.method === "Network.loadingFinished" && msg.params.requestId === reqId) {
          clearTimeout(timer);
          listeners.delete(on);
          const r = await send("Network.getResponseBody", { requestId: reqId });
          resolve(r.result?.body ?? null);
        }
      };
      listeners.add(on);
      void send("Page.navigate", { url });
    });
    const parsed = body && JSON.parse(body);
    if (!parsed || parsed.code !== "0") console.error(`no data for ${url}: ${body}`);
    out.push({ url, data: parsed?.data ?? null });
  }
  ws.close();
  console.log(JSON.stringify(out, null, 2));
} finally {
  chrome.kill();
  await sleep(300);
  rmSync(profile, { recursive: true, force: true });
}
