import { parseHTML } from "./html";
import type { SourceMethod } from "../types";

const channel = globalThis as unknown as {
  postMessage: (value: unknown) => void;
  onmessage: ((event: MessageEvent) => void) | null;
};
let source: Record<SourceMethod, (...args: unknown[]) => Promise<unknown>>;
let serial = 0;
const requests = new Map<
  number,
  { resolve: (value: string) => void; reject: (error: Error) => void }
>();
// No filesystem, native bridge or UI access. All network traffic goes through sdk.request.
for (const name of [
  "fetch",
  "XMLHttpRequest",
  "WebSocket",
  "EventSource",
  "Worker",
  "SharedWorker",
  "BroadcastChannel",
  "importScripts",
  "indexedDB",
  "caches",
]) {
  Object.defineProperty(globalThis, name, {
    value: undefined,
    configurable: false,
    writable: false,
  });
}
channel.onmessage = async ({ data }) => {
  if (data.kind === "init") {
    try {
      const sdk = Object.freeze({
        request: (options: unknown) =>
          new Promise<string>((resolve, reject) => {
            const id = ++serial;
            requests.set(id, { resolve, reject });
            channel.postMessage({ kind: "request", id, options });
          }),
        parseHTML,
        resolveURL: (url: string, base: string) => new URL(url, base).href,
      });
      source = new Function(
        "sdk",
        '"use strict";\n' + data.script + "\n;return source;",
      )(sdk);
      for (const method of [
        "search",
        "getBook",
        "getChapters",
        "getChapter",
      ] as const)
        if (typeof source?.[method] !== "function")
          throw new Error(`缺少方法：${method}`);
      channel.postMessage({ kind: "ready" });
    } catch (error) {
      channel.postMessage({
        kind: "initError",
        error: (error as Error).message,
      });
    }
  } else if (data.kind === "response") {
    const request = requests.get(data.id);
    if (!request) return;
    requests.delete(data.id);
    data.error
      ? request.reject(new Error(data.error))
      : request.resolve(data.text);
  } else if (data.kind === "call") {
    try {
      channel.postMessage({
        kind: "result",
        id: data.id,
        result: await source[data.method as SourceMethod](...data.args),
      });
    } catch (error) {
      channel.postMessage({
        kind: "result",
        id: data.id,
        error: (error as Error).message,
      });
    }
  }
};
