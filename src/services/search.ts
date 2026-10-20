import { ref } from "vue";
import type { InstalledSource, Page, SourceBook } from "../types";
import { SourceRuntime } from "./runtime";

// Both interfaces keep the same source when switching reading modes.
export const searchSourceId = ref("");

export async function searchSource(
  source: InstalledSource, keyword: string, cursor?: string, signal?: AbortSignal,
): Promise<Page<SourceBook>> {
  const word = keyword.trim();
  if (!word) throw new Error("请输入书名或作者");
  signal?.throwIfAborted();
  const runtime = new SourceRuntime(source);
  const abort = () => runtime.dispose(new DOMException("操作已取消", "AbortError"));
  signal?.addEventListener("abort", abort, { once: true });
  try {
    const page = await runtime.call<Page<SourceBook>>("search", [word, cursor], signal);
    signal?.throwIfAborted();
    if (cursor && page.nextCursor === cursor) throw new Error("书源分页未前进，请重新搜索");
    const seen = new Set<string>();
    return { ...page, items: page.items.filter((book) => {
      if (seen.has(book.id)) return false;
      seen.add(book.id); return true;
    }) };
  } finally { signal?.removeEventListener("abort", abort); runtime.dispose(); }
}
