import { beforeEach, describe, expect, it, vi } from "vitest";
const mocks = vi.hoisted(() => ({
  native: { enable: vi.fn(), disable: vi.fn(), status: vi.fn(), speak: vi.fn(), stopSpeech: vi.fn(), recognize: vi.fn(), stopRecognition: vi.fn() },
  preferences: { get: vi.fn(), set: vi.fn() },
  tts: { model: { phase: "ready" }, voice: "Junhao", playback: { phase: "idle" } },
  control: vi.fn(), refresh: vi.fn(), promptPause: vi.fn(), promptResume: vi.fn(),
}));
vi.mock("@capacitor/core", () => ({ Capacitor: { getPlatform: () => "android" }, registerPlugin: () => mocks.native }));
vi.mock("@capacitor/preferences", () => ({ Preferences: mocks.preferences }));
vi.mock("../src/services/tts", () => ({ tts: mocks.tts, controlListening: mocks.control, refreshTts: mocks.refresh, pauseForAnnouncement: mocks.promptPause, resumeAfterAnnouncement: mocks.promptResume }));
beforeEach(() => {
  vi.resetModules(); vi.clearAllMocks(); mocks.tts.model.phase = "ready"; mocks.tts.playback.phase = "idle";
  mocks.native.enable.mockResolvedValue(undefined); mocks.native.disable.mockResolvedValue(undefined);
  mocks.native.status.mockResolvedValue({ loaded: true, recognitionAvailable: true });
  mocks.native.stopSpeech.mockResolvedValue(undefined); mocks.native.speak.mockResolvedValue({ cancelled: false });
  mocks.preferences.get.mockResolvedValue({ value: null }); mocks.preferences.set.mockResolvedValue(undefined);
  mocks.control.mockImplementation(async (action: string) => { mocks.tts.playback.phase = action === "pause" ? "paused" : "playing"; });
  mocks.promptPause.mockImplementation(async () => { await mocks.control("pause"); return { bookId: "one", intent: 1 }; });
  mocks.promptResume.mockImplementation(async () => { await mocks.control("resume"); return true; });
});
describe("无障碍模式的模型与反馈生命周期", () => {
  it("模型缺失不能开启，也不能保存为已开启", async () => {
    mocks.tts.model.phase = "missing";
    const service = await import("../src/services/accessibility");
    await expect(service.enableAccessibility()).rejects.toThrow("先在听书页下载");
    expect(service.accessibility.enabled).toBe(false); expect(mocks.native.enable).not.toHaveBeenCalled();
    expect(mocks.preferences.set).not.toHaveBeenCalled();
  });
  it("必须真正加载成功才显示模式，并在重启恢复时再次加载", async () => {
    mocks.preferences.get.mockImplementation(async ({ key }: { key: string }) => ({ value: key === "accessibility-enabled" ? "true" : "[]" }));
    const service = await import("../src/services/accessibility");
    await service.initializeAccessibility();
    expect(mocks.native.enable).toHaveBeenCalledOnce(); expect(service.accessibility.enabled).toBe(true);
    await service.disableAccessibility(); expect(service.accessibility.enabled).toBe(false);
    expect(mocks.preferences.set).toHaveBeenLastCalledWith({ key: "accessibility-enabled", value: "false" });
  });
  it("加载失败保留普通界面", async () => {
    mocks.native.enable.mockRejectedValue(new Error("内存不足"));
    const service = await import("../src/services/accessibility");
    await expect(service.enableAccessibility()).rejects.toThrow("内存不足");
    expect(service.accessibility.enabled).toBe(false); expect(service.accessibility.preparing).toBe(false);
  });
  it("探索提示暂停正文，提示完成后恢复；用户取消则不会恢复", async () => {
    const service = await import("../src/services/accessibility"); await service.enableAccessibility();
    mocks.tts.playback.phase = "playing";
    await service.announce("暂停播放", true);
    expect(mocks.control.mock.calls.map((call) => call[0])).toEqual(["pause", "resume"]);
    let finish!: (value: { cancelled: boolean }) => void;
    mocks.native.speak.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    const pending = service.announce("下一段", true);
    await vi.waitFor(() => expect(finish).toBeTypeOf("function"));
    await service.cancelAnnouncements(); mocks.control.mockClear(); finish({ cancelled: false }); await pending;
    expect(mocks.control).not.toHaveBeenCalled();
  });
  it("录音之前等操作提示结束，录音期间不播报探索语音", async () => {
    const service = await import("../src/services/accessibility"); await service.enableAccessibility();
    let finish!: (value: { text: string }) => void;
    mocks.native.recognize.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    const pending = service.recognizeSearch();
    await vi.waitFor(() => expect(service.accessibility.recording).toBe(true));
    mocks.native.speak.mockClear(); await service.announce("操作帮助"); expect(mocks.native.speak).not.toHaveBeenCalled();
    finish({ text: " 三体 " }); expect(await pending).toBe("三体"); expect(service.accessibility.recording).toBe(false);
  });
  it("快速切换探索区域时共用正在进行的暂停，只有最新提示恢复正文", async () => {
    const service = await import("../src/services/accessibility"); await service.enableAccessibility();
    mocks.tts.playback.phase = "playing";
    let finish!: (value: { bookId: string; intent: number }) => void;
    mocks.promptPause.mockImplementation(() => {
      mocks.tts.playback.phase = "paused";
      return new Promise((resolve) => { finish = resolve; });
    });
    const first = service.announce("上一段", true);
    await vi.waitFor(() => expect(finish).toBeTypeOf("function"));
    const second = service.announce("下一段", true);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.native.speak).not.toHaveBeenCalled();
    finish({ bookId: "one", intent: 1 }); await Promise.all([first, second]);
    expect(mocks.promptPause).toHaveBeenCalledOnce();
    expect(mocks.native.speak).toHaveBeenCalledExactlyOnceWith({ text: "下一段", voice: "Junhao" });
    expect(mocks.promptResume).toHaveBeenCalledOnce();
  });
});
