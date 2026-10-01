import { Capacitor, CapacitorHttp } from "@capacitor/core";
import { checkRequestUrl } from "./packages";
import type { RequestOptions } from "../types";
export async function sourceRequest(
  options: RequestOptions,
  domains: string[],
  signal?: AbortSignal,
): Promise<string> {
  const url = checkRequestUrl(options.url, domains);
  const method = options.method ?? "GET";
  if (!["GET", "POST"].includes(method)) throw new Error("不支持此请求方法");
  const headers: Record<string, string> = {};
  for (const [key, value] of Object.entries(options.headers ?? {})) {
    if (
      !["accept", "accept-language", "content-type", "referer"].includes(
        key.toLowerCase(),
      )
    )
      throw new Error(`不支持请求头：${key}`);
    if (typeof value !== "string" || /[\r\n]/.test(value))
      throw new Error("请求头无效");
    if (key.toLowerCase() === "referer") checkRequestUrl(value, domains);
    headers[key] = value;
  }
  signal?.throwIfAborted();
  let text: string;
  if (Capacitor.isNativePlatform()) {
    const result = await CapacitorHttp.request({
      url: url.href,
      method,
      headers,
      data: options.body,
      responseType: "text",
      connectTimeout: 15000,
      readTimeout: 25000,
      disableRedirects: true,
    });
    signal?.throwIfAborted();
    if (result.status < 200 || result.status >= 300)
      throw new Error(`请求失败：HTTP ${result.status}（不自动跟随重定向）`);
    text =
      typeof result.data === "string"
        ? result.data
        : JSON.stringify(result.data);
  } else if (import.meta.env.DEV) {
    const response = await fetch("/__source/request", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...options, headers, domains }),
      signal,
    });
    const result = await response.json();
    if (!response.ok)
      throw new Error(result.error ?? `请求失败：HTTP ${response.status}`);
    text = result.text;
  } else {
    const response = await fetch(url.href, {
      method,
      headers,
      body: options.body,
      signal,
      redirect: "error",
      credentials: "omit",
    });
    if (!response.ok) throw new Error(`请求失败：HTTP ${response.status}`);
    text = await response.text();
  }
  if (new TextEncoder().encode(text).length > 8 * 1024 * 1024)
    throw new Error("书源单次响应超过 8 MB");
  return text;
}
