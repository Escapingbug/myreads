import "fake-indexeddb/auto";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { storage } from "../src/services/storage";
import type { InstalledSource } from "../src/types";

const control = vi.hoisted(() => ({
  requests: [] as string[],
  fail: new Set<string>(),
  nextCursor: undefined as string | undefined,
}));
vi.mock("../src/services/runtime", () => ({
  SourceRuntime: class {
    async call(method: string, args: any[], signal?: AbortSignal) {
      signal?.throwIfAborted();
      if (method === "getBook") return args[0];
      if (method === "getChapters")
        return {
          items: Array.from({ length: 4 }, (_, i) => ({
            id: String(i),
            title: `第${i + 1}章`,
          })),
          nextCursor: control.nextCursor,
        };
      const chapter = args[0];
      control.requests.push(chapter.id);
      await new Promise<void>((resolve, reject) => {
        const finish = () => {
          signal?.removeEventListener("abort", abort);
          resolve();
        };
        const timer = setTimeout(finish, 20);
        const abort = () => {
          clearTimeout(timer);
          reject(new DOMException("取消", "AbortError"));
        };
        signal?.addEventListener("abort", abort, { once: true });
      });
      signal?.throwIfAborted();
      if (control.fail.has(chapter.id)) throw new Error("网络失败");
      return { paragraphs: [`章节${chapter.id}正文`] };
    }
    dispose() {}
  },
}));
import {
  addDownload,
  collectBook,
  initialize,
  installSource,
  pauseBook,
  pauseAll,
  removeBook,
  resumeBook,
  saveProgress,
  prepareListeningBook,
  state,
  uninstallSource,
} from "../src/services/library";
const source: InstalledSource = {
  manifest: {
    id: "io.test",
    name: "测试书源",
    version: "1",
    apiVersion: 1,
    description: "",
    domains: [],
  },
  script: "test",
  enabled: true,
  installedAt: 0,
};
const ref = { id: "book", title: "测试小说", author: "作者" };
const wait = (condition: () => boolean) =>
  vi.waitFor(() => expect(condition()).toBe(true), {
    timeout: 5000,
    interval: 10,
  });
beforeAll(async () => {
  await storage.init();
  await storage.put("settings:bootstrapped", true);
});
beforeEach(async () => {
  await pauseAll();
  await wait(() => !state.activeDownload);
  for (const book of [...state.books]) await removeBook(book);
  for (const entry of await storage.list<{ key: string }>("history:")) await storage.remove(`history:${entry.key}`);
  state.history = [];
  state.sources = [];
  control.requests = [];
  control.fail.clear();
  control.nextCursor = undefined;
  await installSource(source);
});
describe("下载、本地持久化与恢复", () => {
  it("一键开听只等待要听的章节，剩余章节继续下载", async () => {
    const book = await prepareListeningBook(source, ref, new AbortController().signal);
    expect(book.downloaded).toContain("0");
    expect(book.downloaded.length).toBeLessThan(4);
    expect(await storage.chapter(book.localId, 0)).toEqual({ paragraphs: ["章节0正文"] });
    await wait(() => book.state === "ready");
  });
  it("一键续听自动准备原来的章节，已有章节不重复请求", async () => {
    const book = await addDownload(source, ref); await wait(() => book.state === "ready");
    await saveProgress(book, "2", 3, 0); control.requests = [];
    expect(await prepareListeningBook(source, ref, new AbortController().signal)).toBe(book);
    expect(control.requests).toEqual([]);
    await removeBook(book); control.requests = [];
    const restored = await prepareListeningBook(source, ref, new AbortController().signal);
    expect(control.requests[0]).toBe("2"); expect(restored.downloaded).toContain("2");
    expect(restored.progress?.paragraph).toBe(3);
  });
  it("推荐准备期间取消会停止等待与当前下载，不会自动继续", async () => {
    const controller = new AbortController();
    const result = prepareListeningBook(source, ref, controller.signal);
    const checked = expect(result).rejects.toMatchObject({ name: "AbortError" });
    await wait(() => state.activeDownload !== ""); controller.abort(); await checked;
    await wait(() => state.activeDownload === "");
    expect(state.books[0]?.state).toBe("paused");
  });
  it("有其他下载时优先准备用户刚选中的书，再恢复原下载", async () => {
    const first = await addDownload(source, ref); await wait(() => state.activeDownload === first.localId);
    const picked = await prepareListeningBook(source, { ...ref, id: "recommended", title: "推荐书" }, new AbortController().signal);
    expect(picked.downloaded).toContain("0");
    await wait(() => first.state === "ready" && picked.state === "ready");
  });
  it("删除本地内容保留历史，重新下载优先恢复原章节", async () => {
    const book = await addDownload(source, ref);
    await wait(() => book.state === "ready");
    await saveProgress(book, "2", 7, 0.25);
    await removeBook(book);
    expect(state.history[0]).toMatchObject({ chapterId: "2", paragraph: 7, book: { title: "测试小说" } });
    await initialize();
    expect(state.history).toHaveLength(1);
    control.requests = [];
    const restored = await addDownload(source, ref);
    await wait(() => restored.state === "ready");
    expect(control.requests[0]).toBe("2");
    expect(restored.progress).toMatchObject({ chapterId: "2", paragraph: 7 });
    expect(await storage.chapter(restored.localId, 2)).toEqual({ paragraphs: ["章节2正文"] });
  });
  it("目录分页重复时停止，避免将不完整目录当成整本", async () => {
    control.nextCursor = "repeat";
    await expect(collectBook(source, ref)).rejects.toThrow("目录分页重复");
  });
  it("暂停后立即继续只补剩余章节，删除书源后仍能读本地文件", async () => {
    const book = await addDownload(source, ref);
    await wait(() => book.downloaded.length >= 1);
    await pauseBook(book);
    const completed = [...book.downloaded];
    await resumeBook(book);
    await wait(() => book.state === "ready");
    expect(book.downloaded).toHaveLength(4);
    for (const id of completed)
      expect(control.requests.filter((item) => item === id)).toHaveLength(1);
    await uninstallSource(source.manifest.id);
    expect(await storage.chapter(book.localId, 3)).toEqual({
      paragraphs: ["章节3正文"],
    });
    await saveProgress(book, "2", 7, 0.25);
    await initialize();
    const restored = state.books.find((item) => item.localId === book.localId)!;
    expect(restored.state).toBe("ready");
    expect(restored.progress).toMatchObject({
      chapterId: "2",
      paragraph: 7,
      offset: 0.25,
    });
  });
  it("失败章节可重试，已写入的章节不重复请求", async () => {
    control.fail.add("1");
    const book = await addDownload(source, ref);
    await wait(() => book.state === "error");
    expect(book.downloaded).toEqual(["0", "2", "3"]);
    expect(book.failed).toEqual(["1"]);
    control.fail.clear();
    control.requests = [];
    await resumeBook(book);
    await wait(() => book.state === "ready");
    expect(control.requests).toEqual(["1"]);
  });
  it("后台暂停整条队列，重新初始化不自动联网", async () => {
    const first = await addDownload(source, ref);
    const second = await addDownload(source, { ...ref, id: "second" });
    await pauseAll();
    await wait(() => !state.activeDownload);
    expect(first.state).toBe("paused");
    expect(second.state).toBe("paused");
    const count = control.requests.length;
    await initialize();
    expect(state.books.every((book) => book.state === "paused")).toBe(true);
    expect(control.requests).toHaveLength(count);
  });
});
