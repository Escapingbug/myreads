import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Book } from "../src/types";
const mocks = vi.hoisted(() => ({
  callbacks: {} as Record<string, (status: unknown) => void>,
  native: {
    getStatus: vi.fn(), downloadModel: vi.fn(), pauseDownload: vi.fn(), removeModel: vi.fn(), play: vi.fn(), control: vi.fn(), addListener: vi.fn(),
  },
  books: [] as Book[], saveProgress: vi.fn(),
  preferences: { get: vi.fn(), set: vi.fn() },
}));
vi.mock("@capacitor/core", () => ({ Capacitor: { getPlatform: () => "android" }, registerPlugin: () => mocks.native }));
vi.mock("@capacitor/preferences", () => ({ Preferences: mocks.preferences }));
vi.mock("../src/services/library", () => ({ state: { books: mocks.books }, saveProgress: mocks.saveProgress }));
const model = { id: "moss-nano-onnx-v1", phase: "ready", total: 100, downloaded: 100, file: "", error: "" };
const book: Book = {
  localId: "test-book", id: "book", sourceId: "demo", sourceName: "demo", sourceVersion: "1", addedAt: 1,
  title: "山间来信", author: "演示", state: "ready", failed: [],
  chapters: [{ id: "one", title: "第一章" }, { id: "two", title: "第二章" }], downloaded: ["one"],
  progress: { chapterId: "one", paragraph: 0, offset: 0, updatedAt: 1000 },
};
beforeEach(() => {
  vi.resetModules(); vi.clearAllMocks(); mocks.books.splice(0, mocks.books.length, structuredClone(book));
  mocks.native.getStatus.mockResolvedValue({ model, playback: { phase: "idle" } });
  mocks.preferences.get.mockResolvedValue({ value: null }); mocks.preferences.set.mockResolvedValue(undefined);
  mocks.saveProgress.mockResolvedValue(undefined);
  mocks.native.addListener.mockImplementation(async (name: string, callback: (status: unknown) => void) => {
    mocks.callbacks[name] = callback;
    return { remove: vi.fn() };
  });
});
describe("local listening", () => {
  it("initialization queries status without downloading a model", async () => {
    const tts = await import("../src/services/tts");
    await Promise.all([tts.initializeTts(), tts.initializeTts()]);
    expect(mocks.native.getStatus).toHaveBeenCalledTimes(1);
    expect(mocks.native.downloadModel).not.toHaveBeenCalled();
    expect(mocks.native.addListener).toHaveBeenCalledTimes(2);
  });
  it("downloads only after an explicit action and forwards selected source", async () => {
    const tts = await import("../src/services/tts");
    await tts.initializeTts(); tts.tts.mirror = true;
    await tts.downloadModel();
    expect(mocks.native.downloadModel).toHaveBeenCalledWith({ mirror: true });
  });
  it("passes only committed download flags and preserves the chosen starting paragraph", async () => {
    const tts = await import("../src/services/tts");
    await tts.initializeTts(); await tts.startListening(book, 0, 7);
    expect(mocks.native.play).toHaveBeenCalledWith(expect.objectContaining({
      bookId: "test-book", chapter: 0, paragraph: 7, mode: "stream",
      chapters: [{ id: "one", title: "第一章", downloaded: true }, { id: "two", title: "第二章", downloaded: false }],
    }));
  });
  it("restores, saves and forwards the selected chapter preparation mode", async () => {
    mocks.preferences.get.mockResolvedValue({ value: JSON.stringify({ mode: "chapter", voice: "Xiaoyu" }) });
    const service = await import("../src/services/tts"); await service.initializeTts();
    expect(service.tts.mode).toBe("chapter");
    service.tts.mode = "stream"; await service.saveTtsOptions(); await service.startListening(book, 0, 0);
    expect(JSON.parse(mocks.preferences.set.mock.calls[0]![0].value).mode).toBe("stream");
    expect(mocks.native.play).toHaveBeenCalledWith(expect.objectContaining({ mode: "stream" }));
  });
  it("migrates saved automatic mode to streaming instead of waiting for a chapter", async () => {
    mocks.preferences.get.mockResolvedValue({ value: JSON.stringify({ mode: "auto", voice: "Xiaoyu", speed: 2 }) });
    const service = await import("../src/services/tts"); await service.initializeTts();
    expect(service.tts.mode).toBe("stream");
    await service.startListening(book, 0, 0);
    expect(mocks.native.play).toHaveBeenCalledWith(expect.objectContaining({ mode: "stream", voice: "Xiaoyu", speed: 2 }));
  });
  it("preparing the next chapter does not advance the actual reading position", async () => {
    const service = await import("../src/services/tts"); await service.initializeTts();
    const current = { phase: "playing", bookId: book.localId, chapter: 0, paragraph: 7, updatedAt: 2000 };
    mocks.callbacks.playbackState(current); mocks.saveProgress.mockClear();
    mocks.callbacks.playbackState({ ...current, preparation: "chapter", preparedUnits: 3, totalUnits: 20, preparingChapterTitle: "第二章" });
    expect(mocks.saveProgress).not.toHaveBeenCalled();
    expect(service.tts.playback.chapter).toBe(0);
  });
  it("shows chapter preparation progress and preserves pause feedback", async () => {
    const { listeningMessage } = await import("../src/services/tts");
    expect(listeningMessage({ phase: "buffering", preparation: "chapter", preparedUnits: 2, totalUnits: 8 })).toContain("2/8");
    expect(listeningMessage({ phase: "paused", preparation: "chapter", preparedUnits: 2, totalUnits: 8 })).toBe("听书已暂停");
    expect(listeningMessage({ phase: "buffering" })).toContain("首段");
    expect(listeningMessage({ phase: "buffering", text: "上一段正文" })).toContain("后续语音");
  });
  it("saves background listening position once and does not overwrite newer reading on restart", async () => {
    const tts = await import("../src/services/tts"); await tts.initializeTts();
    const update = { phase: "playing", bookId: "test-book", chapter: 0, paragraph: 7, updatedAt: 2000 };
    mocks.callbacks.playbackState(update); mocks.callbacks.playbackState(update);
    expect(mocks.saveProgress).toHaveBeenCalledTimes(1);
    expect(mocks.saveProgress).toHaveBeenCalledWith(mocks.books[0], "one", 7, 0);
    mocks.saveProgress.mockClear();
    mocks.books[0]!.progress!.updatedAt = 4000;
    mocks.callbacks.playbackState({ ...update, phase: "idle", paragraph: 1, updatedAt: 3000 });
    expect(mocks.saveProgress).not.toHaveBeenCalled();
  });
});
