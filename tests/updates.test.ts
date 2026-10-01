import { beforeEach, describe, expect, it, vi } from "vitest";
const mocks = vi.hoisted(() => ({
  get: vi.fn(), prefs: new Map<string, string>(),
  native: { getStatus: vi.fn(), download: vi.fn(), install: vi.fn(), pause: vi.fn(), addListener: vi.fn() },
}));
vi.mock("@capacitor/core", () => ({ Capacitor: { getPlatform: () => "android" }, CapacitorHttp: { get: mocks.get }, registerPlugin: () => mocks.native }));
vi.mock("@capacitor/preferences", () => ({ Preferences: {
  get: async ({ key }: { key: string }) => ({ value: mocks.prefs.get(key) ?? null }),
  set: async ({ key, value }: { key: string; value: string }) => { mocks.prefs.set(key, value); },
} }));
const sha256 = "a".repeat(64);
function fixture() {
  const prefix = "https://github.com/Escapingbug/myreads/releases/download/v0.3.0/";
  return {
    release: { tag_name: "v0.3.0", draft: false, prerelease: false, body: "应用内更新", assets: [
      { name: "update.json", state: "uploaded", size: 350, browser_download_url: prefix + "update.json" },
      { name: "zijian-0.3.0.apk", state: "uploaded", size: 60_000_000, digest: `sha256:${sha256}`, browser_download_url: prefix + "zijian-0.3.0.apk" },
    ] },
    manifest: { schemaVersion: 1, packageName: "io.myreads.app", versionName: "0.3.0", versionCode: 5, apkName: "zijian-0.3.0.apk", size: 60_000_000, sha256 },
  };
}
beforeEach(() => {
  vi.resetModules(); vi.clearAllMocks(); mocks.prefs.clear();
  mocks.native.getStatus.mockResolvedValue({ versionName: "0.2.1", versionCode: 3, canInstall: false,
    download: { phase: "idle", downloaded: 0, total: 0, versionName: "", error: "" } });
  mocks.native.addListener.mockResolvedValue({ remove: vi.fn() });
  mocks.native.download.mockResolvedValue(undefined); mocks.native.pause.mockResolvedValue(undefined);
  const data = fixture(); mocks.get.mockImplementation(async ({ url }: { url: string }) => ({ status: 200, data: url.includes("api.github.com") ? data.release : data.manifest }));
});
describe("GitHub release updates", () => {
  it("initializes once and automatically checks without downloading", async () => {
    const app = await import("../src/services/updates");
    await Promise.all([app.initializeUpdates(), app.initializeUpdates()]); await app.checkUpdates(); await app.checkUpdates();
    expect(mocks.native.addListener).toHaveBeenCalledTimes(1); expect(mocks.get).toHaveBeenCalledTimes(2);
    expect(app.updates.latest?.versionCode).toBe(5); expect(mocks.native.download).not.toHaveBeenCalled();
  });
  it("honors automatic-check preference while allowing manual checks", async () => {
    mocks.prefs.set("app-updates", JSON.stringify({ automatic: false }));
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates();
    expect(mocks.get).not.toHaveBeenCalled(); await app.checkUpdates(true); expect(mocks.get).toHaveBeenCalledTimes(2);
  });
  it("compares Android versionCode and hides an already installed release", async () => {
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(true);
    const info = await mocks.native.getStatus(); mocks.native.getStatus.mockResolvedValue({ ...info, versionCode: 5, versionName: "0.3.0" });
    await app.refreshUpdates(); expect(app.updates.latest).toBeUndefined();
    await app.checkUpdates(true); expect(app.updates.message).toBe("已经是最新版本");
  });
  it("does not advertise an older release or compare version numbers as strings", async () => {
    const info = await mocks.native.getStatus(); mocks.native.getStatus.mockResolvedValue({ ...info, versionCode: 20, versionName: "0.10.0" });
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(true);
    expect(app.updates.latest).toBeUndefined(); expect(mocks.native.download).not.toHaveBeenCalled();
  });
  it("validates official repository, hash, size, version and package", async () => {
    const app = await import("../src/services/updates");
    const valid = fixture(); expect(app.parseUpdate(valid.release, valid.manifest).sha256).toBe(sha256);
    for (const change of [ { packageName: "other.app" }, { versionName: "0.4.0" }, { versionCode: 0 }, { size: 59_000_000 }, { sha256: "b".repeat(64) } ]) {
      expect(() => app.parseUpdate(valid.release, { ...valid.manifest, ...change })).toThrow();
    }
    for (const url of ["https://github.com/other/myreads/releases/download/v0.3.0/zijian-0.3.0.apk", valid.release.assets[1]!.browser_download_url + "?x=1", "http://github.com/Escapingbug/myreads/releases/download/v0.3.0/zijian-0.3.0.apk"]) {
      const data = fixture(); data.release.assets[1]!.browser_download_url = url;
      expect(() => app.parseUpdate(data.release, data.manifest)).toThrow();
    }
  });
  it("rejects drafts, prereleases, incomplete and duplicate assets", async () => {
    const app = await import("../src/services/updates");
    for (const change of [{ draft: true }, { prerelease: true }, { tag_name: "v0.3.0-beta" }, { assets: [] }]) {
      const data = fixture(); expect(() => app.parseUpdate({ ...data.release, ...change }, data.manifest)).toThrow();
    }
    const data = fixture(); data.release.assets.push(data.release.assets[1]!);
    expect(() => app.parseUpdate(data.release, data.manifest)).toThrow();
  });
  it("keeps a cached update available offline and throttles failed automatic requests", async () => {
    const data = fixture(); mocks.prefs.set("app-updates", JSON.stringify({ cached: data }));
    mocks.get.mockRejectedValue(new Error("网络不可用"));
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(); await app.checkUpdates();
    expect(mocks.get).toHaveBeenCalledTimes(1); expect(app.updates.latest?.versionCode).toBe(5); expect(app.updates.error).toBe("网络不可用");
    await app.checkUpdates(true); expect(mocks.get).toHaveBeenCalledTimes(2);
  });
  it("handles no releases without error or downloading", async () => {
    mocks.get.mockResolvedValue({ status: 404 });
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(true);
    expect(app.updates.latest).toBeUndefined(); expect(app.updates.error).toBe(""); expect(app.updates.message).toContain("暂未发布");
  });
  it("downloads only on user action and explains installer permission", async () => {
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(true);
    await app.downloadUpdate(); expect(mocks.native.download).toHaveBeenCalledWith(expect.objectContaining({ versionCode: 5, sha256 }));
    mocks.native.install.mockResolvedValue({ permissionRequired: true }); await app.installUpdate();
    expect(app.updates.message).toContain("允许纸间安装应用");
  });
  it("resumes a persisted native download even if frontend release cache was cleared", async () => {
    const app = await import("../src/services/updates"); await app.initializeUpdates();
    app.updates.download = { phase: "paused", downloaded: 1024, total: 60_000_000, versionName: "0.3.0", error: "" };
    await app.downloadUpdate(); expect(mocks.native.download).toHaveBeenCalledWith();
  });
});
