import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, type App } from "vue";
import AccessibleMode from "../src/components/AccessibleMode.vue";
const mocks = vi.hoisted(() => ({
  announce: vi.fn(), recognize: vi.fn(), search: vi.fn(), addDownload: vi.fn(), disable: vi.fn(), start: vi.fn(), control: vi.fn(),
  playback: { phase: "idle", bookId: "", title: "", chapter: 0, paragraph: 0 },
  state: { books: [] as any[], history: [] as any[], sources: [] as any[], error: "" },
  mode: { enabled: true, active: true, searches: [] as string[], speaking: false, recording: false, interruptedPlayback: false },
}));
vi.mock("../src/services/accessibility", () => ({
  accessibility: mocks.mode, announce: mocks.announce, recognizeSearch: mocks.recognize,
  cancelAnnouncements: vi.fn().mockResolvedValue(undefined), cancelRecognition: vi.fn().mockResolvedValue(undefined),
  rememberSearch: vi.fn().mockResolvedValue(undefined), disableAccessibility: mocks.disable,
}));
vi.mock("../src/services/library", () => ({ state: mocks.state, addDownload: mocks.addDownload, resumeBook: vi.fn(), cancelCatalogue: vi.fn() }));
vi.mock("../src/services/storage", () => ({ storage: { chapter: vi.fn().mockResolvedValue({ paragraphs: ["第一段", "第二段", "第三段"] }) } }));
vi.mock("../src/services/tts", async () => {
  const { reactive } = await import("vue"); mocks.playback = reactive(mocks.playback);
  return { tts: { playback: mocks.playback, speed: 1 }, startListening: mocks.start, controlListening: mocks.control, saveTtsOptions: vi.fn(), listeningMessage: () => "" };
});
vi.mock("../src/services/runtime", () => ({ SourceRuntime: class { call = mocks.search; dispose() {} } }));
let app: App, host: HTMLDivElement;
const click = async (id: string) => {
  host.querySelector<HTMLButtonElement>(`[data-action="${id}"]`)!.click();
  await new Promise((resolve) => setTimeout(resolve, 0));
};
function touch(type: string, id: string, x = 200, y = 200, primary = true, pointerId = 1) {
  const target = host.querySelector<HTMLElement>(`[data-action="${id}"]`)!;
  Object.defineProperty(document, "elementFromPoint", { configurable: true, value: () => target });
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.assign(event, { pointerType: "touch", isPrimary: primary, pointerId, clientX: x, clientY: y });
  target.dispatchEvent(event);
}
async function tap(id: string) {
  touch("pointerdown", id); touch("pointerup", id);
  host.querySelector<HTMLElement>(`[data-action="${id}"]`)?.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, detail: 1 }));
  await new Promise((resolve) => setTimeout(resolve, 0));
}
beforeEach(() => {
  vi.clearAllMocks(); mocks.announce.mockResolvedValue(undefined); mocks.disable.mockResolvedValue(undefined);
  mocks.start.mockImplementation(async (book, chapter, paragraph) => { Object.assign(mocks.playback, { phase: "playing", bookId: book.localId, title: book.title, chapter, paragraph }); });
  mocks.control.mockImplementation(async (action) => { mocks.playback.phase = action === "pause" ? "paused" : "playing"; });
  Object.assign(mocks.playback, { phase: "idle", bookId: "", title: "", chapter: 0, paragraph: 0 });
  mocks.mode.searches = [];
  mocks.state.books = [{ id: "one", title: "第一本", author: "甲", sourceId: "demo", localId: "local", chapters: [], downloaded: [], state: "ready" }];
  mocks.state.history = [{ book: { id: "old", title: "以前读过的书", author: "乙" }, sourceId: "demo", chapterTitle: "第十章", updatedAt: 1000 }];
  mocks.state.sources = [{ manifest: { id: "demo", name: "演示" }, enabled: true }];
  mocks.recognize.mockResolvedValue("三体");
  mocks.search.mockResolvedValue({ items: [{ id: "new", title: "三体", author: "刘慈欣" }] });
  host = document.createElement("div"); document.body.append(host); app = createApp(AccessibleMode); app.mount(host);
  host.querySelector<HTMLElement>("main")!.setPointerCapture = vi.fn();
});
afterEach(() => { app.unmount(); host.remove(); delete (document as any).elementFromPoint; vi.useRealTimers(); });
describe("语音模式日常选书流程", () => {
  it("协助者在书架单击退出并确认，无需触摸探索或等待提示语音", async () => {
    await click("shelf");
    const root = host.querySelector<HTMLElement>("main")!;
    root.setPointerCapture = vi.fn();
    mocks.announce.mockImplementation(() => new Promise<void>(() => {}));
    await tap("exit");
    expect(host.querySelector("h1")!.textContent).toBe("退出无障碍模式");
    expect(host.textContent).toContain("协助者单击此处");
    expect(mocks.disable).not.toHaveBeenCalled();
    await tap("cancelExit");
    expect(host.querySelector("h1")!.textContent).toBe("我的书架");
    await tap("exit"); await tap("confirmExit");
    expect(mocks.disable).toHaveBeenCalledOnce();
    expect(root.setPointerCapture).toHaveBeenCalled();
  });
  it("单击进入书架，无须先听按钮说明，兼容点击不会重复执行", async () => {
    await tap("shelf");
    expect(host.querySelector("h1")!.textContent).toBe("我的书架");
    expect(mocks.announce.mock.calls.filter(([text]) => text.startsWith("我的书架。"))).toHaveLength(1);
    expect(mocks.announce.mock.calls.some(([text]) => text.includes("这是我的书架按钮"))).toBe(false);
  });
  it("长按说明与书籍简介，松手不进入页面也不开始播放", async () => {
    vi.useFakeTimers();
    touch("pointerdown", "shelf"); await vi.advanceTimersByTimeAsync(650); touch("pointerup", "shelf");
    await vi.advanceTimersByTimeAsync(0);
    expect(mocks.announce).toHaveBeenCalledWith("这是我的书架按钮，单击执行。", false);
    expect(host.querySelector("h1")!.textContent).toBe("语音听书");
    vi.useRealTimers(); await click("shelf"); vi.useFakeTimers();
    touch("pointerdown", "choose"); await vi.advanceTimersByTimeAsync(650); touch("pointerup", "choose");
    await vi.advanceTimersByTimeAsync(0);
    expect(mocks.announce).toHaveBeenCalledWith("第一本。这本书没有简介。", false);
    expect(mocks.start).not.toHaveBeenCalled(); expect(host.querySelector("h1")!.textContent).toBe("我的书架");
  });
  it("书架和历史左右滑动播报当前书，不触发途中按钮或下载", async () => {
    mocks.state.books.push({ ...mocks.state.books[0], id: "two", title: "第二本", localId: "two" });
    await click("shelf");
    touch("pointerdown", "choose", 250); touch("pointermove", "more", 100); touch("pointerup", "more", 80);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(host.textContent).toContain("第二本"); expect(host.querySelector("h1")!.textContent).toBe("我的书架");
    touch("pointerdown", "choose", 80); touch("pointerup", "more", 250);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(host.textContent).toContain("第一本");
    await click("back");
    mocks.state.history.push({ ...mocks.state.history[0], book: { id: "older", title: "更早的书" }, updatedAt: 500 });
    await click("history"); touch("pointerdown", "choose", 250); touch("pointerup", "choose", 80);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(host.textContent).toContain("更早的书"); expect(mocks.addDownload).not.toHaveBeenCalled(); expect(mocks.start).not.toHaveBeenCalled();
  });
  it("系统取消和多指触摸取消长按与点击", async () => {
    vi.useFakeTimers();
    touch("pointerdown", "shelf"); touch("pointercancel", "shelf");
    await vi.advanceTimersByTimeAsync(650); touch("pointerup", "shelf");
    touch("pointerdown", "shelf"); touch("pointerdown", "find", 200, 200, false, 2);
    await vi.advanceTimersByTimeAsync(650); touch("pointerup", "shelf");
    expect(host.querySelector("h1")!.textContent).toBe("语音听书");
    expect(mocks.announce.mock.calls.some(([text]) => text.startsWith("这是"))).toBe(false);
  });
  it("播放单击暂停与继续不插入提示语音，滑动直接换段", async () => {
    Object.assign(mocks.state.books[0], { chapters: [{ id: "ch", title: "第一章" }], downloaded: ["ch"] });
    await click("shelf"); await tap("choose");
    expect(host.querySelector("h1")!.textContent).toBe("正在听书");
    mocks.announce.mockClear();
    await tap("toggle"); expect(mocks.control).toHaveBeenLastCalledWith("pause");
    await tap("toggle"); expect(mocks.control).toHaveBeenLastCalledWith("resume");
    touch("pointerdown", "toggle", 250); touch("pointerup", "speed", 80);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.start).toHaveBeenLastCalledWith(mocks.state.books[0], 0, 1);
    expect(mocks.announce).not.toHaveBeenCalled(); expect(host.querySelector("h1")!.textContent).toBe("正在听书");
  });
  it("搜索结果末尾左滑加载下一页，右滑可回到已有结果", async () => {
    mocks.search.mockResolvedValueOnce({ items: [{ id: "new", title: "第一条结果" }], nextCursor: "page2" });
    mocks.search.mockResolvedValueOnce({ items: [{ id: "next", title: "第二条结果" }] });
    await click("find"); await click("dictate"); await click("search");
    touch("pointerdown", "choose", 250); touch("pointerup", "choose", 80);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.search).toHaveBeenLastCalledWith("search", ["三体", "page2"], expect.any(AbortSignal));
    expect(host.textContent).toContain("第二条结果");
    touch("pointerdown", "choose", 80); touch("pointerup", "choose", 250);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(host.textContent).toContain("第一条结果"); expect(mocks.addDownload).not.toHaveBeenCalled();
  });
  it("书架与删除内容后的历史都能浏览，并通过固定返回回到首页", async () => {
    await click("shelf"); expect(host.textContent).toContain("第一本");
    await click("back"); await click("history");
    expect(host.textContent).toContain("以前读过的书"); expect(host.textContent).toContain("重新下载这本");
    await click("back"); expect(host.querySelector("h1")!.textContent).toBe("语音听书");
  });
  it("语音输入先确认再搜索，结果复用选书控件", async () => {
    await click("find"); await click("dictate");
    expect(host.querySelector("h1")!.textContent).toBe("确认搜索"); expect(mocks.search).not.toHaveBeenCalled();
    await click("search");
    expect(mocks.search).toHaveBeenCalledWith("search", ["三体", undefined], expect.any(AbortSignal));
    expect(host.querySelector("h1")!.textContent).toBe("搜索结果"); expect(host.textContent).toContain("刘慈欣");
    expect(host.querySelector('[data-action="next"]')).not.toBeNull();
  });
  it("返回后迟到的搜索结果不会将用户带回搜索页", async () => {
    let finish!: (value: unknown) => void;
    mocks.search.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    await click("find"); await click("dictate"); await click("search");
    await vi.waitFor(() => expect(finish).toBeTypeOf("function")); await click("back");
    finish({ items: [{ id: "late", title: "迟到的结果" }] });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(host.querySelector("h1")!.textContent).toBe("找新书"); expect(host.textContent).not.toContain("迟到的结果");
  });
  it("准备下载的提示尚未播完时返回，不会在后台开始下载", async () => {
    let finish!: () => void;
    mocks.announce.mockImplementation((text: string) => text.includes("正在获取章节")
      ? new Promise<void>((resolve) => { finish = resolve; }) : Promise.resolve());
    await click("history"); await click("choose");
    expect(finish).toBeTypeOf("function"); await click("back"); finish();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.addDownload).not.toHaveBeenCalled();
    expect(host.querySelector("h1")!.textContent).toBe("语音听书");
  });
});
