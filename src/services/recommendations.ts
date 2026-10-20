import type { InstalledSource, Page, SourceBook } from "../types";
import { SourceRuntime, validateResult } from "./runtime";
import { storage } from "./storage";

export interface Recommendation { book: SourceBook; sourceId: string }
export interface RecommendationList { items: Recommendation[]; cached: boolean; sourceName: string }
interface Cache { version: string; items: SourceBook[]; updatedAt: number }
const DAY = 24 * 60 * 60 * 1000;
export const recommendationKey = (entry: Recommendation) => JSON.stringify([entry.sourceId, entry.book.id]);
export async function rememberRecommendationDetails(entry: Recommendation) {
  const key = `recommendations:${entry.sourceId}`;
  try {
    const saved = await storage.get<Cache>(key);
    if (!saved || !Array.isArray(saved.items)) return;
    const index = saved.items.findIndex((book) => book.id === entry.book.id);
    if (index < 0) return;
    saved.items[index] = entry.book;
    await storage.put(key, saved);
  } catch { /* Descriptions are optional when storage is unavailable. */ }
}

export async function loadRecommendations(sources: InstalledSource[], signal?: AbortSignal, refresh = false): Promise<RecommendationList> {
  const supported = sources.filter((source) => source.enabled && source.manifest.capabilities?.includes("recommend"))
    .sort((a, b) => Number(a.manifest.id === "io.myreads.demo") - Number(b.manifest.id === "io.myreads.demo"));
  let failure = "当前书源尚不支持推荐，可在更多功能中更新书源或搜索书籍。";
  for (const source of supported) {
    signal?.throwIfAborted();
    const key = `recommendations:${source.manifest.id}`;
    let cache: Cache | undefined;
    try {
      const saved = await storage.get<Cache>(key);
      if (saved?.version === source.manifest.version && Number.isFinite(saved.updatedAt)) {
        const page = validateResult("recommend", { items: saved.items }) as Page<SourceBook>;
        if (page.items.length) cache = { ...saved, items: page.items };
      }
    } catch { /* A damaged cache must not prevent a new fetch. */ }
    signal?.throwIfAborted();
    const result = (items: SourceBook[], cached: boolean): RecommendationList => ({
      items: items.map((book) => ({ book, sourceId: source.manifest.id })), cached, sourceName: source.manifest.name,
    });
    if (!refresh && cache && Date.now() - cache.updatedAt < DAY) return result(cache.items, true);
    const runtime = new SourceRuntime(source);
    const abort = () => runtime.dispose(new DOMException("操作已取消", "AbortError"));
    signal?.addEventListener("abort", abort, { once: true });
    const timer = setTimeout(() => runtime.dispose(new Error("推荐书单加载超时")), 15000);
    try {
      const page = await runtime.call<Page<SourceBook>>("recommend", [], signal);
      signal?.throwIfAborted();
      const seen = new Set<string>();
      const items = page.items.filter((book) => !seen.has(book.id) && !!seen.add(book.id)).slice(0, 60);
      if (!items.length) throw new Error("书源暂时没有推荐书籍");
      await storage.put(key, { version: source.manifest.version, items, updatedAt: Date.now() }).catch(() => {});
      signal?.throwIfAborted();
      return result(items, false);
    } catch (error) {
      signal?.throwIfAborted();
      if (cache) return result(cache.items, true);
      failure = (error as Error).message;
    } finally { clearTimeout(timer); signal?.removeEventListener("abort", abort); runtime.dispose(); }
  }
  throw new Error(failure);
}

interface Choices { skipped: string[]; last?: string }
export async function nextRecommendation(items: Recommendation[], current?: Recommendation): Promise<Recommendation | undefined> {
  let saved: Choices | undefined;
  try { saved = await storage.get<Choices>("settings:recommendation-choices"); } catch { /* Best effort. */ }
  let skipped = Array.isArray(saved?.skipped) ? saved.skipped.filter((value) => typeof value === "string").slice(-100) : [];
  const currentKey = current && recommendationKey(current);
  if (currentKey && !skipped.includes(currentKey)) skipped.push(currentKey);
  const last = currentKey ?? saved?.last;
  const start = Math.max(0, items.findIndex((entry) => recommendationKey(entry) === last) + 1);
  const rotated = [...items.slice(start), ...items.slice(0, start)];
  let next = rotated.find((entry) => recommendationKey(entry) !== currentKey && !skipped.includes(recommendationKey(entry)));
  if (!next) {
    skipped = currentKey ? [currentKey] : [];
    next = rotated.find((entry) => recommendationKey(entry) !== currentKey) ?? rotated[0];
  }
  if (next) await storage.put("settings:recommendation-choices", { skipped: skipped.slice(-100), last: recommendationKey(next) }).catch(() => {});
  return next;
}
