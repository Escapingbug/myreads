import { Capacitor, CapacitorHttp } from "@capacitor/core";
import { checkRequestUrl, decodePackage } from "./packages";

export async function downloadPackage(address: string, signal: AbortSignal) {
  const url = new URL(address);
  checkRequestUrl(address, [url.hostname]);
  let bytes: Uint8Array;
  if (Capacitor.isNativePlatform()) {
    const response = await CapacitorHttp.request({
      url: url.href,
      method: "GET",
      responseType: "arraybuffer",
      disableRedirects: true,
      connectTimeout: 15000,
      readTimeout: 15000,
    });
    signal.throwIfAborted();
    if (response.status < 200 || response.status >= 300)
      throw new Error(`书源包请求失败：HTTP ${response.status}`);
    bytes =
      typeof response.data === "string"
        ? Uint8Array.from(atob(response.data), (c) => c.charCodeAt(0))
        : new TextEncoder().encode(JSON.stringify(response.data));
  } else if (import.meta.env.DEV) {
    const response = await fetch("/__source/request", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        url: url.href,
        domains: [url.hostname],
        format: "base64",
      }),
      signal,
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error ?? "书源包下载失败");
    bytes = Uint8Array.from(atob(result.base64), (c) => c.charCodeAt(0));
  } else {
    const response = await fetch(url, {
      signal,
      redirect: "error",
      credentials: "omit",
    });
    if (!response.ok)
      throw new Error(`书源包请求失败：HTTP ${response.status}`);
    bytes = new Uint8Array(await response.arrayBuffer());
  }
  signal.throwIfAborted();
  return decodePackage(bytes, url.pathname);
}
