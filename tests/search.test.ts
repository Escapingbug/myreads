import { beforeEach, describe, expect, it, vi } from "vitest";
import { searchSource } from "../src/services/search";
import type { InstalledSource } from "../src/types";
const mocks = vi.hoisted(() => ({ call: vi.fn(), dispose: vi.fn(), create: vi.fn() }));
vi.mock("../src/services/runtime", () => ({ SourceRuntime: class {
  constructor(source: unknown) { mocks.create(source); }
  call = mocks.call; dispose = mocks.dispose;
} }));
const source = { manifest: { id: "same-source" } } as InstalledSource;
beforeEach(() => { vi.clearAllMocks(); mocks.call.mockReset(); });
describe("普通与无障碍界面共用搜索", () => {
  it("保留书源结果顺序、内容和游标，同页同 ID 只显示一次", async () => {
    const first = { id: "a", title: "甲", author: "作者甲" }, second = { id: "b", title: "乙", author: "作者乙" };
    mocks.call.mockResolvedValue({ items: [first, first, second], nextCursor: "p2" });
    const result = await searchSource(source, "  作者甲  ");
    expect(mocks.create).toHaveBeenCalledWith(source);
    expect(mocks.call).toHaveBeenCalledWith("search", ["作者甲", undefined], undefined);
    expect(result).toEqual({ items: [first, second], nextCursor: "p2" });
    expect(mocks.dispose).toHaveBeenCalledOnce();
  });
  it("返回取消会立即释放执行环境，迟到结果不会改变取消状态", async () => {
    let resolve!: (value: unknown) => void;
    mocks.call.mockImplementation(() => new Promise((done) => { resolve = done; }));
    const controller = new AbortController(), pending = searchSource(source, "甲", undefined, controller.signal);
    controller.abort(); expect(mocks.dispose).toHaveBeenCalledWith(expect.objectContaining({ name: "AbortError" }));
    resolve({ items: [] }); await expect(pending).rejects.toMatchObject({ name: "AbortError" });
  });
  it("已取消的搜索不创建书源执行环境", async () => {
    const controller = new AbortController(); controller.abort();
    await expect(searchSource(source, "甲", undefined, controller.signal)).rejects.toMatchObject({ name: "AbortError" });
    expect(mocks.create).not.toHaveBeenCalled();
  });
  it("书源返回重复游标时报错并释放环境", async () => {
    mocks.call.mockResolvedValue({ items: [], nextCursor: "p2" });
    await expect(searchSource(source, "甲", "p2")).rejects.toThrow("书源分页未前进");
    expect(mocks.dispose).toHaveBeenCalledOnce();
  });
});
