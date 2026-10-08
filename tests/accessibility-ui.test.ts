import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, type App } from "vue";
import AccessibleMode from "../src/components/AccessibleMode.vue";
const mocks = vi.hoisted(() => ({
  announce: vi.fn(), recognize: vi.fn(), search: vi.fn(), addDownload: vi.fn(),
  state: { books: [] as any[], history: [] as any[], sources: [] as any[], error: "" },
  mode: { enabled: true, active: true, searches: [] as string[], speaking: false, recording: false, interruptedPlayback: false },
}));
vi.mock("../src/services/accessibility", () => ({
  accessibility: mocks.mode, announce: mocks.announce, recognizeSearch: mocks.recognize,
  cancelAnnouncements: vi.fn().mockResolvedValue(undefined), cancelRecognition: vi.fn().mockResolvedValue(undefined),
  rememberSearch: vi.fn().mockResolvedValue(undefined), disableAccessibility: vi.fn().mockResolvedValue(undefined),
}));
vi.mock("../src/services/library", () => ({ state: mocks.state, addDownload: mocks.addDownload, resumeBook: vi.fn(), cancelCatalogue: vi.fn() }));
vi.mock("../src/services/storage", () => ({ storage: { chapter: vi.fn() } }));
vi.mock("../src/services/tts", () => ({
  tts: { playback: { phase: "idle" }, speed: 1 }, startListening: vi.fn(), controlListening: vi.fn(), saveTtsOptions: vi.fn(), listeningMessage: () => "",
}));
vi.mock("../src/services/runtime", () => ({ SourceRuntime: class { call = mocks.search; dispose() {} } }));
let app: App, host: HTMLDivElement;
const click = async (id: string) => {
  host.querySelector<HTMLButtonElement>(`[data-action="${id}"]`)!.click();
  await new Promise((resolve) => setTimeout(resolve, 0));
};
beforeEach(() => {
  vi.clearAllMocks(); mocks.announce.mockResolvedValue(undefined);
  mocks.state.books = [{ id: "one", title: "第一本", author: "甲", sourceId: "demo", localId: "local", chapters: [], downloaded: [], state: "ready" }];
  mocks.state.history = [{ book: { id: "old", title: "以前读过的书", author: "乙" }, sourceId: "demo", chapterTitle: "第十章", updatedAt: 1000 }];
  mocks.state.sources = [{ manifest: { id: "demo", name: "演示" }, enabled: true }];
  mocks.recognize.mockResolvedValue("三体");
  mocks.search.mockResolvedValue({ items: [{ id: "new", title: "三体", author: "刘慈欣" }] });
  host = document.createElement("div"); document.body.append(host); app = createApp(AccessibleMode); app.mount(host);
});
afterEach(() => { app.unmount(); host.remove(); delete (document as any).elementFromPoint; });
describe("语音模式日常选书流程", () => {
  it("触摸先播报完整按钮说明，确认后才进入书架", async () => {
    const root = host.querySelector<HTMLElement>("main")!;
    root.setPointerCapture = vi.fn();
    const shelf = host.querySelector<HTMLButtonElement>('[data-action="shelf"]')!;
    Object.defineProperty(document, "elementFromPoint", { configurable: true, value: () => shelf });
    const touch = (type: string) => {
      const event = new Event(type, { bubbles: true, cancelable: true });
      Object.assign(event, { pointerType: "touch", isPrimary: true, pointerId: 1, clientX: 20, clientY: 20 });
      root.dispatchEvent(event);
    };
    touch("pointerdown"); touch("pointerup");
    expect(mocks.announce).toHaveBeenCalledWith("这是我的书架按钮。", false);
    expect(host.querySelector("h1")!.textContent).toBe("语音听书");
    touch("pointerdown"); touch("pointerup");
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(host.querySelector("h1")!.textContent).toBe("我的书架");
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
