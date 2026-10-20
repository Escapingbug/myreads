import { beforeEach, describe, expect, it, vi } from "vitest";
import type { InstalledSource } from "../src/types";
const mocks = vi.hoisted(() => ({ call: vi.fn(), dispose: vi.fn(), values: new Map<string, any>(), put: vi.fn() }));
vi.mock("../src/services/storage", () => ({ storage: {
  get: async (key: string) => mocks.values.get(key),
  put: async (key: string, value: unknown) => { mocks.put(key, value); mocks.values.set(key, value); },
} }));
vi.mock("../src/services/runtime", async (original) => ({ ...await original<any>(), SourceRuntime: class {
  call = mocks.call; dispose = mocks.dispose;
} }));
import { loadRecommendations, nextRecommendation } from "../src/services/recommendations";
const source = { manifest: { id: "test", name: "测试", version: "1.1", capabilities: ["recommend"] }, enabled: true } as InstalledSource;
const items = [{ id: "one", title: "第一本", author: "甲" }, { id: "two", title: "第二本", author: "乙" }];
beforeEach(() => { mocks.values.clear(); vi.clearAllMocks(); mocks.call.mockReset(); mocks.call.mockResolvedValue({ items }); });
describe("推荐书单及离线恢复", () => {
  it("请求推荐入口并去重，结果和时间保存到本机", async () => {
    mocks.call.mockResolvedValue({ items: [...items, items[0]] });
    const result = await loadRecommendations([source]);
    expect(mocks.call).toHaveBeenCalledWith("recommend", [], undefined);
    expect(result.items.map((entry) => entry.book.id)).toEqual(["one", "two"]);
    expect(mocks.values.get("recommendations:test").items).toHaveLength(2);
    expect(mocks.dispose).toHaveBeenCalledOnce();
  });
  it("新鲜缓存无需联网，过期缓存在网络失败时仍然可浏览", async () => {
    await loadRecommendations([source]); mocks.call.mockClear();
    expect((await loadRecommendations([source])).cached).toBe(true); expect(mocks.call).not.toHaveBeenCalled();
    mocks.values.get("recommendations:test").updatedAt = 1; mocks.call.mockRejectedValue(new Error("离线"));
    const result = await loadRecommendations([source]); expect(result.cached).toBe(true); expect(result.items).toHaveLength(2);
  });
  it("损坏缓存不会阻止获取，禁用书源不会被推荐", async () => {
    mocks.values.set("recommendations:test", { version: "1.1", updatedAt: 1, items: [null] });
    expect((await loadRecommendations([source])).items).toHaveLength(2);
    mocks.call.mockClear(); await expect(loadRecommendations([{ ...source, enabled: false }])).rejects.toThrow("尚不支持推荐");
    expect(mocks.call).not.toHaveBeenCalled();
  });
  it("旧接口书源没有推荐能力时不发送不存在的方法", async () => {
    await expect(loadRecommendations([{ ...source, manifest: { ...source.manifest, capabilities: undefined } }])).rejects.toThrow("更新书源");
    expect(mocks.call).not.toHaveBeenCalled();
  });
  it("推荐取消不会回退缓存或写入迟到结果", async () => {
    let finish!: (page: unknown) => void; mocks.call.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    const controller = new AbortController(); const result = loadRecommendations([source], controller.signal);
    await vi.waitFor(() => expect(finish).toBeTypeOf("function")); controller.abort(); finish({ items });
    await expect(result).rejects.toMatchObject({ name: "AbortError" }); expect(mocks.put).not.toHaveBeenCalled();
  });
  it("优先真实书源，演示回退保留明确的来源名", async () => {
    const demo = { ...source, manifest: { ...source.manifest, id: "io.myreads.demo", name: "原创演示" } };
    const result = await loadRecommendations([demo, source]); expect(result.sourceName).toBe("测试");
    mocks.values.clear(); mocks.call.mockRejectedValueOnce(new Error("离线"));
    expect((await loadRecommendations([demo, source])).sourceName).toBe("原创演示");
  });
  it("换一本会避开当前书，重进继续浏览，全部看过后循环而不留空", async () => {
    const list = items.map((book) => ({ book, sourceId: "test" }));
    const first = await nextRecommendation(list); expect(first?.book.id).toBe("one");
    const second = await nextRecommendation(list, first); expect(second?.book.id).toBe("two");
    const restarted = await nextRecommendation(list); expect(restarted?.book.id).toBe("two");
    const cycled = await nextRecommendation(list, second); expect(cycled?.book.id).toBe("one");
    expect(await nextRecommendation([], undefined)).toBeUndefined();
  });
});
