import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createApp, type App } from "vue";
import AccessibleMode from "../src/components/AccessibleMode.vue";
import { searchSource, searchSourceId } from "../src/services/search";
const mocks = vi.hoisted(() => ({
  loadRecommendations: vi.fn(), prepare: vi.fn(), get: vi.fn(), put: vi.fn(), remember: vi.fn(), createdSources: [] as any[], announce: vi.fn(), recognize: vi.fn(), search: vi.fn(), addDownload: vi.fn(), disable: vi.fn(), start: vi.fn(), control: vi.fn(),
  playback: { phase: "idle", bookId: "", title: "", chapter: 0, paragraph: 0 },
  state: { books: [] as any[], history: [] as any[], sources: [] as any[], error: "" },
  mode: { enabled: true, active: true, searches: [] as string[], speaking: false, recording: false, interruptedPlayback: false },
}));
vi.mock("../src/services/accessibility", () => ({
  accessibility: mocks.mode, announce: mocks.announce, recognizeSearch: mocks.recognize,
  cancelAnnouncements: vi.fn().mockResolvedValue(undefined), cancelRecognition: vi.fn().mockResolvedValue(undefined),
  rememberSearch: mocks.remember, disableAccessibility: mocks.disable,
}));
vi.mock("../src/services/library", async () => {
  const { reactive } = await import("vue"); mocks.state = reactive(mocks.state);
  return { state: mocks.state, addDownload: mocks.addDownload, resumeBook: vi.fn(), cancelCatalogue: vi.fn(), prepareListeningBook: mocks.prepare };
});
vi.mock("../src/services/storage", () => ({ storage: { get: mocks.get, put: mocks.put, chapter: vi.fn().mockResolvedValue({ paragraphs: ["第一段", "第二段", "第三段"] }) } }));
vi.mock("../src/services/tts", async () => {
  const { reactive } = await import("vue"); mocks.playback = reactive(mocks.playback);
  return { tts: { playback: mocks.playback, speed: 1 }, startListening: mocks.start, controlListening: mocks.control, saveTtsOptions: vi.fn(), listeningMessage: () => "" };
});
vi.mock("../src/services/runtime", () => ({ SourceRuntime: class { constructor(source: any) { mocks.createdSources.push(source); } call = mocks.search; dispose() {} } }));
vi.mock("../src/services/recommendations", () => ({
  loadRecommendations: mocks.loadRecommendations,
  rememberRecommendationDetails: vi.fn(),
  nextRecommendation: async (items: any[], current?: any) => items[(items.findIndex((entry) => entry.book.id === current?.book.id) + 1) % items.length],
}));
let app: App, host: HTMLDivElement;
const click = async (id: string) => {
  if (["shelf", "history", "find", "playerOptions"].includes(id) && !host.querySelector(`[data-action="${id}"]`)) await click("advanced");
  if (id === "search") host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  else if (id === "dictate") host.querySelector<HTMLButtonElement>(".a11y-search-source-row button")!.click();
  else host.querySelector<HTMLButtonElement>(`[data-action="${id}"]`)!.click();
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
  if (["shelf", "history", "find"].includes(id) && !host.querySelector(`[data-action="${id}"]`)) await click("advanced");
  touch("pointerdown", id); touch("pointerup", id);
  host.querySelector<HTMLElement>(`[data-action="${id}"]`)?.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, detail: 1 }));
  await new Promise((resolve) => setTimeout(resolve, 0));
}
beforeEach(async () => {
  vi.clearAllMocks(); searchSourceId.value = ""; mocks.createdSources = []; mocks.remember.mockResolvedValue(undefined); mocks.announce.mockResolvedValue(undefined); mocks.disable.mockResolvedValue(undefined);
  mocks.start.mockImplementation(async (book, chapter, paragraph) => { Object.assign(mocks.playback, { phase: "playing", bookId: book.localId, title: book.title, chapter, paragraph }); });
  mocks.control.mockImplementation(async (action) => { mocks.playback.phase = action === "pause" ? "paused" : "playing"; });
  Object.assign(mocks.playback, { phase: "idle", bookId: "", title: "", chapter: 0, paragraph: 0 });
  mocks.mode.searches = [];
  mocks.state.books = [{ id: "one", title: "第一本", author: "甲", sourceId: "demo", localId: "local", chapters: [], downloaded: [], state: "ready" }];
  mocks.state.history = [{ book: { id: "old", title: "以前读过的书", author: "乙" }, sourceId: "demo", chapterTitle: "第十章", updatedAt: 1000 }];
  mocks.state.sources = [{ manifest: { id: "demo", name: "演示" }, enabled: true }];
  mocks.recognize.mockResolvedValue("三体");
  mocks.search.mockResolvedValue({ items: [{ id: "new", title: "三体", author: "刘慈欣" }] });
  mocks.get.mockResolvedValue(undefined); mocks.put.mockResolvedValue(undefined);
  mocks.loadRecommendations.mockResolvedValue({ items: [
    { sourceId: "demo", book: { id: "rec1", title: "第一本推荐", author: "推荐作者", description: "在山中散步的故事。" } },
    { sourceId: "demo", book: { id: "rec2", title: "第二本推荐", author: "乙", description: "关于夜间火车的故事。" } },
  ], sourceName: "演示", cached: false });
  mocks.prepare.mockImplementation(async (_source, ref) => {
    const book = { ...ref, localId: "prepared", sourceId: "demo", chapters: [{ id: "p0", title: "第一章" }], downloaded: ["p0"], state: "downloading", progress: null };
    mocks.state.books.push(book); return book;
  });
  host = document.createElement("div"); document.body.append(host); app = createApp(AccessibleMode); app.mount(host);
  host.querySelector<HTMLElement>("main")!.setPointerCapture = vi.fn();
  await new Promise((resolve) => setTimeout(resolve, 0));
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
    await click("advanced"); vi.useFakeTimers();
    touch("pointerdown", "shelf"); await vi.advanceTimersByTimeAsync(650); touch("pointerup", "shelf");
    await vi.advanceTimersByTimeAsync(0);
    expect(mocks.announce).toHaveBeenCalledWith("这是我的书架按钮，单击执行。", false);
    expect(host.querySelector("h1")!.textContent).toBe("更多功能");
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
    await click("advanced"); vi.useFakeTimers();
    touch("pointerdown", "shelf"); touch("pointercancel", "shelf");
    await vi.advanceTimersByTimeAsync(650); touch("pointerup", "shelf");
    touch("pointerdown", "shelf"); touch("pointerdown", "find", 200, 200, false, 2);
    await vi.advanceTimersByTimeAsync(650); touch("pointerup", "shelf");
    expect(host.querySelector("h1")!.textContent).toBe("更多功能");
    expect(mocks.announce.mock.calls.some(([text]) => text.startsWith("这是"))).toBe(false);
  });
  it("播放单击暂停与继续不插入提示语音，滑动直接换段", async () => {
    Object.assign(mocks.state.books[0], { chapters: [{ id: "ch", title: "第一章" }], downloaded: ["ch"] });
    await click("shelf"); await tap("choose");
    expect(host.querySelector("h1")!.textContent).toBe("正在听书");
    mocks.announce.mockClear();
    await tap("toggle"); expect(mocks.control).toHaveBeenLastCalledWith("pause");
    await tap("toggle"); expect(mocks.control).toHaveBeenLastCalledWith("resume");
    touch("pointerdown", "toggle", 250); touch("pointerup", "another", 80);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.start).toHaveBeenLastCalledWith(mocks.state.books[0], 0, 1);
    expect(mocks.announce).not.toHaveBeenCalled(); expect(host.querySelector("h1")!.textContent).toBe("正在听书");
  });
  it("搜索结果点击下一页加载更多，上一页可回到已有结果", async () => {
    mocks.search.mockResolvedValueOnce({ items: [{ id: "new", title: "第一条结果" }], nextCursor: "page2" });
    mocks.search.mockResolvedValueOnce({ items: [{ id: "next", title: "第二条结果" }] });
    await click("find"); await click("dictate"); await click("search");
    await click("resultNext");
    expect(mocks.search).toHaveBeenLastCalledWith("search", ["三体", "page2"], expect.any(AbortSignal));
    expect(host.textContent).toContain("第二条结果");
    await click("resultPrevious");
    expect(host.textContent).toContain("第一条结果"); expect(mocks.addDownload).not.toHaveBeenCalled();
  });
  it("书架与删除内容后的历史都能浏览，并通过固定返回回到首页", async () => {
    await click("shelf"); expect(host.textContent).toContain("第一本");
    await click("back"); await click("history");
    expect(host.textContent).toContain("以前读过的书"); expect(host.textContent).toContain("重新下载这本");
    await click("back"); expect(host.querySelector("h1")!.textContent).toBe("语音听书");
  });
  it("语音输入先显示可修改文字，搜索结果显示书名作者和查看入口", async () => {
    await click("find"); await click("dictate");
    expect(host.querySelector("h1")!.textContent).toBe("确认搜索"); expect(mocks.search).not.toHaveBeenCalled();
    await click("search");
    expect(mocks.search).toHaveBeenCalledWith("search", ["三体", undefined], expect.any(AbortSignal));
    expect(host.querySelector("h1")!.textContent).toBe("搜索结果"); expect(host.textContent).toContain("刘慈欣");
    expect(host.querySelector('[data-action="result-0"]')?.textContent).toContain("三体");
    await click("result-0"); expect(host.querySelector('[data-action="choose"]')).not.toBeNull();
  });
  it("返回后迟到的搜索结果不会将用户带回搜索页", async () => {
    let finish!: (value: unknown) => void;
    mocks.search.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    await click("find"); await click("dictate"); await click("search");
    await vi.waitFor(() => expect(finish).toBeTypeOf("function")); await click("back");
    finish({ items: [{ id: "late", title: "迟到的结果" }] });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(host.querySelector("h1")!.textContent).toBe("搜索书籍"); expect(host.textContent).not.toContain("迟到的结果");
  });
  it("搜索框可以输入和提交，不拦截聚焦、选择书源及原生点击", async () => {
    await click("find");
    const input = host.querySelector<HTMLInputElement>('input[type="search"]')!;
    input.value = "  山间来信  "; input.dispatchEvent(new Event("input", { bubbles: true }));
    const event = new MouseEvent("click", { bubbles: true, cancelable: true }); input.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
    const touchEvent = new Event("pointerdown", { bubbles: true, cancelable: true });
    Object.assign(touchEvent, { pointerType: "touch", isPrimary: true, pointerId: 1 }); input.dispatchEvent(touchEvent);
    expect(host.querySelector<HTMLElement>("main")!.setPointerCapture).not.toHaveBeenCalled();
    await click("search");
    expect(mocks.search).toHaveBeenCalledWith("search", ["山间来信", undefined], expect.any(AbortSignal));
    expect(mocks.recognize).not.toHaveBeenCalled();
  });
  it("搜索不等待语音提示播完或历史写入，仍然显示返回结果", async () => {
    await click("find"); await click("dictate");
    mocks.announce.mockImplementation(() => new Promise<void>(() => {}));
    mocks.remember.mockRejectedValue(new Error("写入失败"));
    await click("search");
    expect(mocks.search).toHaveBeenCalledOnce();
    expect(host.querySelector('[data-action="result-0"]')?.textContent).toContain("三体");
  });
  it("语音识别的文字可以修改，再走与普通输入相同的书源与搜索接口", async () => {
    await click("find"); await click("dictate");
    const input = host.querySelector<HTMLInputElement>('input[type="search"]')!;
    expect(input.value).toBe("三体"); input.value = "刘慈欣"; input.dispatchEvent(new Event("input", { bubbles: true }));
    await click("search");
    expect(mocks.search).toHaveBeenLastCalledWith("search", ["刘慈欣", undefined], expect.any(AbortSignal));
    const voiceSource = mocks.createdSources.at(-1);
    const result = await searchSource(mocks.state.sources[0], " 刘慈欣 ");
    expect(mocks.createdSources.at(-1)).toBe(voiceSource);
    expect(result.items[0].title).toBe("三体");
    expect(mocks.search).toHaveBeenLastCalledWith("search", ["刘慈欣", undefined], undefined);
  });
  it("两种界面共用所选书源，并显示真实结果卡片", async () => {
    mocks.state.sources.push({ manifest: { id: "other", name: "另一书源" }, enabled: true });
    searchSourceId.value = "other"; await click("find");
    expect(host.querySelector<HTMLSelectElement>("select")!.value).toBe("other");
    await click("dictate"); await click("search");
    expect(mocks.createdSources.at(-1).manifest.id).toBe("other");
    expect(host.textContent).toContain("另一书源");
    expect(host.querySelector('[data-action="result-0"]')?.textContent).toContain("刘慈欣");
  });
  it("空结果可以返回修改关键词，失败显示可读错误并能重试", async () => {
    mocks.search.mockResolvedValueOnce({ items: [] });
    await click("find"); await click("dictate"); await click("search");
    expect(host.textContent).toContain("没有找到相关书籍");
    await click("back");
    mocks.search.mockRejectedValueOnce(new Error("书源连接超时")); await click("search");
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("书源连接超时");
    expect(host.querySelector<HTMLInputElement>("input")!.value).toBe("三体");
    await click("search"); expect(host.querySelector('[data-action="result-0"]')?.textContent).toContain("三体");
  });
  it("语音输入失败可直接改用键盘，不需要退出此模式", async () => {
    mocks.recognize.mockRejectedValueOnce(new Error("没有听清")); await click("find"); await click("dictate");
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("可以在搜索框直接输入");
    const input = host.querySelector<HTMLInputElement>("input")!; input.value = "夜行列车";
    input.dispatchEvent(new Event("input", { bubbles: true })); await click("search");
    expect(mocks.search).toHaveBeenLastCalledWith("search", ["夜行列车", undefined], expect.any(AbortSignal));
  });
  it("分页失败保留现有结果，重试仍使用原查询和游标", async () => {
    mocks.search.mockResolvedValueOnce({ items: [{ id: "a", title: "甲书" }], nextCursor: "p2" });
    await click("find"); await click("dictate"); await click("search");
    mocks.search.mockRejectedValueOnce(new Error("连接失败")); await click("resultNext");
    expect(host.querySelector('.a11y-result-card')?.textContent).toContain("甲书");
    expect(host.textContent).toContain("连接失败");
    await click("resultNext"); expect(mocks.search).toHaveBeenLastCalledWith("search", ["三体", "p2"], expect.any(AbortSignal));
  });
  it("结果按两本一页浏览，返回保留查询和已加载的列表", async () => {
    mocks.search.mockResolvedValueOnce({ items: [{ id: "a", title: "甲书" }, { id: "b", title: "乙书" }, { id: "c", title: "丙书" }] });
    await click("find"); await click("dictate"); await click("search");
    expect(host.querySelectorAll(".a11y-result-card")).toHaveLength(2);
    await click("resultNext"); expect(host.textContent).toContain("丙书");
    await click("result-0"); expect(host.querySelector('[data-action="choose"]')).not.toBeNull();
    await click("back"); expect(host.querySelector("h1")!.textContent).toBe("搜索结果"); expect(host.textContent).toContain("丙书");
    await click("back"); expect(host.querySelector<HTMLInputElement>("input")!.value).toBe("三体");
  });
  it("没有上次记录时直接介绍推荐，简单页面不出现书架或搜索", async () => {
    expect(host.querySelector("h1")!.textContent).toBe("听听推荐");
    expect(host.textContent).toContain("第一本推荐");
    expect(host.querySelector('[data-action="listenRecommended"]')?.textContent).toBe("就听这本");
    expect(host.querySelector('[data-action="find"]')).toBeNull();
    expect(host.querySelector('[data-action="shelf"]')).toBeNull();
    expect(mocks.announce.mock.calls.some(([text]) => text.includes("在山中散步"))).toBe(true);
  });
  it("不感兴趣换一本，喜欢后一次点击自动准备并开始听", async () => {
    await click("another"); expect(host.textContent).toContain("第二本推荐");
    expect(mocks.prepare).not.toHaveBeenCalled(); await click("listenRecommended");
    expect(mocks.prepare).toHaveBeenCalledWith(mocks.state.sources[0], expect.objectContaining({ id: "rec2" }), expect.any(AbortSignal));
    expect(mocks.start).toHaveBeenCalledWith(expect.objectContaining({ id: "rec2" }), 0, 0);
    expect(host.querySelector("h1")!.textContent).toBe("正在听书");
    expect(host.querySelector('[data-action="another"]')).not.toBeNull();
    expect(host.querySelector('[data-action="shelf"]')).toBeNull();
    expect(mocks.put).toHaveBeenCalledWith("settings:last-listening", expect.objectContaining({ book: expect.objectContaining({ id: "rec2" }) }));
  });
  it("准备过程中换一本，迟到的下载完成不会自动播放旧书", async () => {
    let finish!: (book: unknown) => void;
    mocks.prepare.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    await click("listenRecommended"); expect(host.textContent).toContain("正在准备");
    await click("another");
    const signal = mocks.prepare.mock.calls[0][2]; expect(signal.aborted).toBe(true);
    finish({ localId: "late", id: "rec1", sourceId: "demo", chapters: [{ id: "c" }], downloaded: ["c"] });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.start).not.toHaveBeenCalled(); expect(host.textContent).toContain("第二本推荐");
  });
  it("重新进入自动从上次段落继续，优先本地章节且无需书架操作", async () => {
    app.unmount(); host.innerHTML = "";
    const book = { ...mocks.state.books[0], id: "last", localId: "last-local", chapters: [{ id: "c0" }, { id: "c1" }], downloaded: ["c1"], progress: { chapterId: "c1", paragraph: 7 } };
    mocks.state.books.push(book);
    mocks.get.mockResolvedValue({ sourceId: "demo", book: { id: "last", title: "上次的书" } });
    app = createApp(AccessibleMode); app.mount(host); await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.start).toHaveBeenCalledWith(book, 1, 7);
    expect(mocks.prepare).not.toHaveBeenCalled(); expect(host.querySelector("h1")!.textContent).toBe("正在听书");
  });
  it("上次原生播放器暂停时重新进入只继续，不从头开始", async () => {
    app.unmount(); host.innerHTML = "";
    const book = { ...mocks.state.books[0], chapters: [{ id: "c" }], downloaded: ["c"], progress: { chapterId: "c", paragraph: 9 } };
    mocks.state.books = [book]; Object.assign(mocks.playback, { bookId: book.localId, phase: "paused", chapter: 0, paragraph: 9 });
    app = createApp(AccessibleMode); app.mount(host); await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.control).toHaveBeenCalledWith("resume"); expect(mocks.start).not.toHaveBeenCalled();
    expect(host.querySelector("h1")!.textContent).toBe("正在听书");
  });
  it("章节已听完时进入推荐，不自动重复最后一段", async () => {
    app.unmount(); host.innerHTML = "";
    const book = { ...mocks.state.books[0], chapters: [{ id: "c" }], downloaded: ["c"] };
    mocks.state.books = [book]; Object.assign(mocks.playback, { bookId: book.localId, phase: "completed", chapter: 0 });
    mocks.get.mockResolvedValue({ sourceId: "demo", book });
    app = createApp(AccessibleMode); app.mount(host); await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.start).not.toHaveBeenCalled(); expect(host.querySelector("h1")!.textContent).toBe("听听推荐");
  });
  it("准备书籍失败仍能直接换一本，不必理解下载状态", async () => {
    mocks.prepare.mockRejectedValueOnce(new Error("章节网络失败")); await click("listenRecommended");
    expect(host.textContent).toContain("章节网络失败");
    await click("another"); expect(host.textContent).toContain("第二本推荐");
    await click("listenRecommended"); expect(host.querySelector("h1")!.textContent).toBe("正在听书");
  });
  it("原生已经在播放时重新进入不重启段落，换一本先暂停旧书", async () => {
    app.unmount(); host.innerHTML = "";
    const book = { ...mocks.state.books[0], chapters: [{ id: "c" }], downloaded: ["c"], progress: null };
    mocks.state.books = [book]; Object.assign(mocks.playback, { bookId: book.localId, phase: "playing", chapter: 0, paragraph: 5 });
    app = createApp(AccessibleMode); app.mount(host); await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.start).not.toHaveBeenCalled();
    await click("another"); expect(mocks.control).toHaveBeenCalledWith("pause");
    expect(host.querySelector("h1")!.textContent).toBe("听听推荐");
  });
  it("推荐失败可重试，更多功能仍能打开搜索和书架", async () => {
    mocks.loadRecommendations.mockRejectedValueOnce(new Error("当前离线"));
    app.unmount(); host.innerHTML = ""; app = createApp(AccessibleMode); app.mount(host);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(host.textContent).toContain("当前离线");
    await click("listenRecommended"); expect(host.textContent).toContain("第一本推荐");
    await click("advanced"); expect(host.querySelector('[data-action="find"]')).not.toBeNull();
    expect(host.querySelector('[data-action="shelf"]')).not.toBeNull();
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
