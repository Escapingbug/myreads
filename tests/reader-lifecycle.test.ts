import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, nextTick, type App } from "vue";
import Reader from "../src/components/Reader.vue";
import { checkpointReader, clearReaderSession, readReaderSession } from "../src/services/reader-session";
import type { Book } from "../src/types";

const mocks = vi.hoisted(() => ({ chapter: vi.fn(), save: vi.fn() }));
vi.mock("../src/services/storage", () => ({ storage: { chapter: mocks.chapter } }));
vi.mock("../src/services/library", () => ({
  state: { settings: { theme: "paper", fontSize: 20, lineHeight: 1.9 } },
  saveProgress: mocks.save, saveSettings: vi.fn(),
}));
vi.mock("../src/services/tts", () => ({
  tts: { playback: { phase: "idle" }, model: { phase: "missing" } }, listeningMessage: () => "",
  ttsAction: vi.fn(), startListening: vi.fn(), controlListening: vi.fn(), syncListeningProgress: vi.fn(),
}));
vi.mock("../src/components/ListeningSettings.vue", () => ({ default: { template: "<div />" } }));
const book = {
  localId: "local", title: "测试书", author: "甲", chapters: [{ id: "a", title: "第一章" }, { id: "b", title: "第二章" }],
  downloaded: ["a", "b"], progress: { chapterId: "a", paragraph: 0, offset: 0, updatedAt: 1 },
} as Book;
let app: App | undefined, host: HTMLDivElement, reader: any, height: number;
const flush = async () => { await Promise.resolve(); await nextTick(); await Promise.resolve(); };
function mount() {
  app = createApp(Reader, { book }); reader = app.mount(host);
}
function geometry() {
  const root = host.querySelector<HTMLElement>(".reader-scroll")!;
  Object.defineProperties(root, { clientHeight: { get: () => height }, scrollHeight: { value: 2000 } });
  root.getBoundingClientRect = () => ({ top: 0 } as DOMRect);
  host.querySelectorAll<HTMLElement>("[data-paragraph]").forEach((p, i) => {
    Object.defineProperty(p, "offsetHeight", { value: 200 });
    p.getBoundingClientRect = () => ({ top: 100 + i * 200 - root.scrollTop, bottom: 300 + i * 200 - root.scrollTop } as DOMRect);
  });
  return root;
}
beforeEach(() => {
  vi.useFakeTimers(); vi.clearAllMocks();
  const values = new Map<string, string>();
  vi.stubGlobal("localStorage", {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  });
  clearReaderSession(); height = 600;
  vi.spyOn(document, "hidden", "get").mockReturnValue(false);
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => setTimeout(() => callback(0), 16));
  vi.stubGlobal("cancelAnimationFrame", clearTimeout);
  mocks.chapter.mockResolvedValue({ paragraphs: ["第一段", "第二段", "第三段", "第四段"] });
  mocks.save.mockResolvedValue(undefined);
  host = document.createElement("div"); document.body.append(host);
});
afterEach(() => { app?.unmount(); host.remove(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });

describe("阅读器锁屏恢复", () => {
  it("后台视口归零的滚动事件不会覆盖进度，恢复时保留章节正文和段内位置", async () => {
    mount(); await flush(); const root = geometry(); await reader.resume(); await vi.advanceTimersByTimeAsync(16);
    root.scrollTop = 450; root.dispatchEvent(new Event("scroll"));
    expect(readReaderSession()).toMatchObject({ chapterId: "a", paragraph: 2, offset: .25 });
    await reader.suspend(); height = 0; root.scrollTop = 0; root.dispatchEvent(new Event("scroll"));
    expect(mocks.save).toHaveBeenLastCalledWith(book, "a", 2, .25);
    height = 600; await reader.resume(); await vi.advanceTimersByTimeAsync(16);
    expect(root.scrollTop).toBe(450);
    expect(mocks.chapter).toHaveBeenCalledTimes(1);
    expect(host.querySelectorAll("[data-paragraph]")).toHaveLength(4);
    expect(readReaderSession()).toMatchObject({ paragraph: 2, offset: .25 });
  });
  it("渲染器重建时使用同步检查点，即使数据库保存尚未开始", async () => {
    checkpointReader(book, "b", { paragraph: 2, offset: .5 });
    mount(); await flush(); const root = geometry();
    await reader.resume(); await vi.advanceTimersByTimeAsync(16);
    expect(mocks.chapter).toHaveBeenCalledWith("local", 1);
    expect(root.scrollTop).toBe(500);
    expect(host.querySelector("h1")?.textContent).toBe("第二章");
  });
  it("快速解锁再锁屏取消待执行的恢复，不会用隐藏布局重写检查点", async () => {
    mount(); await flush(); const root = geometry(); await reader.resume(); await vi.advanceTimersByTimeAsync(16); root.scrollTop = 300;
    root.dispatchEvent(new Event("scroll")); await reader.suspend(); await reader.resume();
    await reader.suspend(); height = 0; root.scrollTop = 0;
    await vi.advanceTimersByTimeAsync(32);
    expect(readReaderSession()).toMatchObject({ paragraph: 1, offset: .5 });
  });
  it("后台完成章节读取后仍保留待恢复位置", async () => {
    let resolve!: (value: unknown) => void;
    mocks.chapter.mockReturnValue(new Promise((done) => { resolve = done; }));
    checkpointReader(book, "b", { paragraph: 1, offset: .5 }); mount(); await reader.suspend();
    resolve({ paragraphs: ["一", "二", "三"] }); await flush(); const root = geometry();
    await reader.resume(); await vi.advanceTimersByTimeAsync(16);
    expect(root.scrollTop).toBe(300);
  });
  it("章节读取失败可以原地重试，不必离开阅读页", async () => {
    mocks.chapter.mockRejectedValueOnce(new Error("读取暂时失败")); mount(); await flush();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("读取暂时失败");
    host.querySelector<HTMLButtonElement>('[role="alert"] button')!.click(); await flush();
    expect(host.querySelectorAll("[data-paragraph]")).toHaveLength(4);
  });
  it("document 可见性事件保存并恢复段落，不依赖原生回调", async () => {
    mount(); await flush(); const root = geometry(); await reader.resume(); await vi.advanceTimersByTimeAsync(16); root.scrollTop = 250; root.dispatchEvent(new Event("scroll"));
    vi.spyOn(document, "hidden", "get").mockReturnValue(true);
    document.dispatchEvent(new Event("visibilitychange")); await flush();
    root.scrollTop = 0; root.dispatchEvent(new Event("scroll"));
    expect(readReaderSession()).toMatchObject({ paragraph: 1, offset: .25 });
    vi.spyOn(document, "hidden", "get").mockReturnValue(false);
    document.dispatchEvent(new Event("visibilitychange")); await flush(); await vi.advanceTimersByTimeAsync(16);
    expect(root.scrollTop).toBe(250);
  });
  it("Web Storage 不可用时仍能读取章节和保存数据库进度", async () => {
    vi.stubGlobal("localStorage", { getItem() { throw new Error("denied"); }, setItem() { throw new Error("denied"); } });
    mount(); await flush(); const root = geometry(); await reader.resume(); await vi.advanceTimersByTimeAsync(16); root.scrollTop = 250; root.dispatchEvent(new Event("scroll"));
    await reader.suspend(); expect(mocks.save).toHaveBeenLastCalledWith(book, "a", 1, .25);
    expect(host.querySelectorAll("[data-paragraph]")).toHaveLength(4);
  });
  it("损坏的检查点不阻止打开已有阅读进度", async () => {
    localStorage.setItem("myreads:reader-session", JSON.stringify({ bookId: "local", chapterId: "b", paragraph: -1, offset: 99 }));
    mount(); await flush(); expect(mocks.chapter).toHaveBeenCalledWith("local", 0);
  });
});
