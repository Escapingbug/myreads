import type {
  SourcePackage,
  SourceMethod,
  RequestOptions,
  SourceBook,
  ChapterRef,
  ChapterContent,
  Page,
} from "../types";
import { sourceRequest } from "./http";

export class SourceRuntime {
  private worker: Worker;
  private serial = 0;
  private calls = new Map<
    number,
    {
      resolve: (value: unknown) => void;
      reject: (error: Error) => void;
      timer: ReturnType<typeof setTimeout>;
    }
  >();
  private controllers = new Set<AbortController>();
  private ready: Promise<void>;
  private readyResolve!: () => void;
  private readyReject!: (error: Error) => void;
  private lastRequest = 0;
  private requestTail = Promise.resolve();
  private disposed = false;
  private pendingRequests = 0;
  constructor(readonly pack: SourcePackage) {
    this.ready = new Promise((resolve, reject) => {
      this.readyResolve = resolve;
      this.readyReject = reject;
    });
    this.worker = new Worker(new URL("./source.worker.ts", import.meta.url), {
      type: "module",
    });
    this.worker.onmessage = this.onMessage;
    this.worker.onerror = () =>
      this.dispose(new Error("书源脚本运行失败，请检查或更新书源"));
    const timer = setTimeout(
      () => this.dispose(new Error("书源启动超时")),
      8000,
    );
    void this.ready.then(
      () => clearTimeout(timer),
      () => clearTimeout(timer),
    );
    this.send({ kind: "init", script: pack.script });
  }
  private send(data: Record<string, unknown>) {
    if (!this.disposed)
      this.worker.postMessage(JSON.parse(JSON.stringify(data)));
  }
  private onMessage = ({ data }: MessageEvent) => {
    if (this.disposed) return;
    if (data.kind === "ready") this.readyResolve();
    else if (data.kind === "initError") this.dispose(new Error(data.error));
    else if (data.kind === "result") {
      const call = this.calls.get(data.id);
      if (!call) return;
      clearTimeout(call.timer);
      this.calls.delete(data.id);
      data.error
        ? call.reject(new Error(data.error))
        : call.resolve(data.result);
    } else if (data.kind === "request") {
      if (++this.pendingRequests > 20) {
        this.dispose(new Error("书源同时请求过多，已停止"));
        return;
      }
      this.requestTail = this.requestTail.then(async () => {
        if (this.disposed) return;
        const controller = new AbortController();
        this.controllers.add(controller);
        try {
          const delay = Math.max(0, 350 - (Date.now() - this.lastRequest));
          if (delay) await new Promise((resolve) => setTimeout(resolve, delay));
          if (this.disposed) return;
          this.lastRequest = Date.now();
          const text = await sourceRequest(
            data.options as RequestOptions,
            this.pack.manifest.domains,
            controller.signal,
          );
          this.send({ kind: "response", id: data.id, text });
        } catch (error) {
          this.send({
            kind: "response",
            id: data.id,
            error: (error as Error).message,
          });
        } finally {
          this.controllers.delete(controller);
          this.pendingRequests--;
        }
      });
    }
  };
  async call<T>(
    method: SourceMethod,
    args: unknown[],
    signal?: AbortSignal,
  ): Promise<T> {
    signal?.throwIfAborted();
    const cancelReady = () =>
      this.dispose(new DOMException("操作已取消", "AbortError"));
    signal?.addEventListener("abort", cancelReady, { once: true });
    try {
      await this.ready;
    } finally {
      signal?.removeEventListener("abort", cancelReady);
    }
    if (this.disposed) throw new Error("书源运行环境已关闭");
    signal?.throwIfAborted();
    const result = await new Promise<unknown>((resolve, reject) => {
      const id = ++this.serial;
      const finish = (error?: Error, value?: unknown) => {
        signal?.removeEventListener("abort", abort);
        error ? reject(error) : resolve(value);
      };
      const abort = () =>
        this.dispose(new DOMException("操作已取消", "AbortError"));
      const timer = setTimeout(
        () => this.dispose(new Error("书源执行超时，请重试")),
        60000,
      );
      this.calls.set(id, {
        resolve: (value) => finish(undefined, value),
        reject: (error) => finish(error),
        timer,
      });
      signal?.addEventListener("abort", abort, { once: true });
      this.send({ kind: "call", id, method, args });
    });
    return validateResult(method, result) as T;
  }
  dispose(error: Error = new Error("书源运行环境已关闭")) {
    if (this.disposed) return;
    this.disposed = true;
    this.readyReject(error);
    this.worker.terminate();
    for (const controller of this.controllers) controller.abort();
    for (const call of this.calls.values()) {
      clearTimeout(call.timer);
      call.reject(error);
    }
    this.calls.clear();
  }
}
function validText(value: unknown, limit: number): value is string {
  return (
    typeof value === "string" &&
    value.trim().length > 0 &&
    value.length <= limit
  );
}
function book(value: unknown): SourceBook {
  const item = value as SourceBook;
  if (
    !item ||
    !validText(item.id, 2048) ||
    !validText(item.title, 300) ||
    typeof item.author !== "string"
  )
    throw new Error("书源返回的书籍信息格式不正确");
  return {
    id: item.id,
    title: item.title,
    author: item.author.slice(0, 200),
    description: item.description?.slice(0, 20000),
    category: item.category?.slice(0, 100),
    status: item.status?.slice(0, 100),
    url: item.url,
    cover: item.cover,
  };
}
export function validateResult(method: SourceMethod, value: unknown): unknown {
  if (method === "getBook") return book(value);
  if (method === "getChapter") {
    const chapter = value as ChapterContent;
    if (
      !chapter ||
      !Array.isArray(chapter.paragraphs) ||
      chapter.paragraphs.length > 10000 ||
      !chapter.paragraphs.every(
        (p) => typeof p === "string" && p.length <= 100000,
      )
    )
      throw new Error("书源返回的正文格式不正确");
    const paragraphs = chapter.paragraphs.map((p) => p.trim()).filter(Boolean);
    if (!paragraphs.length) throw new Error("章节正文为空，未保存此章节");
    if (paragraphs.join("").length > 2_000_000) throw new Error("章节正文过大");
    return {
      title:
        typeof chapter.title === "string"
          ? chapter.title.slice(0, 300)
          : undefined,
      paragraphs,
    };
  }
  const page = value as Page<unknown>;
  if (
    !page ||
    !Array.isArray(page.items) ||
    page.items.length > 50000 ||
    (page.nextCursor !== undefined && !validText(page.nextCursor, 2048))
  )
    throw new Error("书源返回的列表格式不正确");
  return {
    nextCursor: page.nextCursor,
    items:
      method === "search"
        ? page.items.map(book)
        : page.items.map((value) => {
            const chapter = value as ChapterRef;
            if (
              !chapter ||
              !validText(chapter.id, 2048) ||
              !validText(chapter.title, 300)
            )
              throw new Error("书源返回的目录格式不正确");
            return { id: chapter.id, title: chapter.title, url: chapter.url };
          }),
  };
}
