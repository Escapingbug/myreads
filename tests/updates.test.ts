import { beforeEach, describe, expect, it, vi } from "vitest";
const mocks = vi.hoisted(() => ({
  config: { githubRepository: "escapingbug/myreads", giteeRepository: "" },
  get: vi.fn(), prefs: new Map<string, string>(),
  native: { getStatus: vi.fn(), download: vi.fn(), install: vi.fn(), pause: vi.fn(), addListener: vi.fn() },
}));
vi.mock("../android/app/src/main/assets/distribution.json", () => ({ default: mocks.config }));
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
  vi.resetModules(); vi.clearAllMocks(); mocks.prefs.clear(); mocks.config.giteeRepository = "";
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
    await app.downloadUpdate(); expect(mocks.native.download).toHaveBeenCalledWith({ sourcePreference: "auto" });
  });
});

function dualFixture(giteeCode = 5, githubCode = 5) {
  mocks.config.giteeRepository = "test-owner/zijian";
  const github = fixture(), gitee = fixture();
  github.manifest.versionCode = githubCode; gitee.manifest.versionCode = giteeCode;
  const giteeRelease = { tag_name: "v0.3.0", prerelease: false, body: "Gitee 版本说明" };
  mocks.get.mockImplementation(async ({ url }: { url: string }) => ({ status: 200,
    data: url.includes("api.github.com") ? github.release : url.includes("gitee.com/api/") ? [giteeRelease]
      : url.includes("gitee.com/") ? gitee.manifest : github.manifest }));
  return { github, gitee, giteeRelease };
}
describe("dual-channel updates", () => {
  it("prefers Gitee for identical artifacts and gives native download both verified URLs", async () => {
    dualFixture();
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(true);
    expect(app.updates.latest?.source).toBe("gitee");
    expect(app.updates.latest?.urls).toEqual([
      "https://gitee.com/test-owner/zijian/releases/download/v0.3.0/zijian-0.3.0.apk",
      fixture().release.assets[1]!.browser_download_url,
    ]);
    await app.downloadUpdate();
    expect(mocks.native.download).toHaveBeenCalledWith(expect.objectContaining({ sourcePreference: "auto", urls: app.updates.latest!.urls }));
  });
  it("uses a newer GitHub version without falling back to the stale Gitee version", async () => {
    const data = dualFixture(); data.github.manifest.versionCode = 6;
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(true);
    expect(app.updates.latest?.source).toBe("github"); expect(app.updates.latest?.versionCode).toBe(6);
    expect(app.updates.latest?.urls).toHaveLength(1);
  });
  it("can check Gitee without any successful request to GitHub", async () => {
    dualFixture(); const implementation = mocks.get.getMockImplementation()!;
    mocks.get.mockImplementation((options) => options.url.includes("github") ? Promise.reject(new Error("GitHub 无法连接")) : implementation(options));
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(true);
    expect(app.updates.latest?.source).toBe("gitee"); expect(app.updates.error).toBe("");
    expect(app.updates.latest?.urls).toHaveLength(1);
  });
  it("uses GitHub when Gitee is rate limited", async () => {
    dualFixture(); const implementation = mocks.get.getMockImplementation()!;
    mocks.get.mockImplementation((options) => options.url.includes("gitee.com") ? Promise.resolve({ status: 429 }) : implementation(options));
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(true);
    expect(app.updates.latest?.source).toBe("github"); expect(app.updates.error).toBe("");
  });
  it("does not claim fully up to date if the other channel could not be checked", async () => {
    dualFixture(); const implementation = mocks.get.getMockImplementation()!;
    const info = await mocks.native.getStatus(); mocks.native.getStatus.mockResolvedValue({ ...info, versionCode: 5 });
    mocks.get.mockImplementation((options) => options.url.includes("gitee.com") ? Promise.reject(new Error("离线")) : implementation(options));
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(true);
    expect(app.updates.latest).toBeUndefined(); expect(app.updates.message).toContain("部分渠道");
    expect(app.updates.lastCheck).toBe(0);
  });
  it("rejects inconsistent artifacts instead of combining same-version mirrors", async () => {
    const data = dualFixture(); data.gitee.manifest.sha256 = "b".repeat(64);
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(true);
    expect(app.updates.latest).toBeUndefined(); expect(app.updates.error).toContain("不一致");
  });
  it("honors and saves a manually pinned source for checks and downloads", async () => {
    dualFixture();
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.setSource("github");
    expect(mocks.get.mock.calls.every(([options]) => !options.url.includes("gitee.com"))).toBe(true);
    await app.downloadUpdate();
    expect(mocks.native.download).toHaveBeenCalledWith(expect.objectContaining({ sourcePreference: "github", urls: [fixture().release.assets[1]!.browser_download_url] }));
    expect(JSON.parse(mocks.prefs.get("app-updates")!).source).toBe("github");
  });
  it("bounds an unresponsive channel and still returns the working channel", async () => {
    vi.useFakeTimers();
    try {
      dualFixture(); const implementation = mocks.get.getMockImplementation()!;
      mocks.get.mockImplementation((options) => options.url.includes("gitee.com") ? new Promise(() => {}) : implementation(options));
      const app = await import("../src/services/updates"); await app.initializeUpdates();
      const check = app.checkUpdates(true); await vi.advanceTimersByTimeAsync(8000); await check;
      expect(app.updates.latest?.source).toBe("github"); expect(app.updates.checking).toBe(false);
    } finally { vi.useRealTimers(); }
  });
  it("filters Gitee prereleases and chooses the newest tag even if an old release was edited", async () => {
    const data = dualFixture(); const implementation = mocks.get.getMockImplementation()!;
    mocks.get.mockImplementation((options) => options.url.includes("gitee.com/api/") ? Promise.resolve({ status: 200, data: [
      { ...data.giteeRelease, tag_name: "v0.2.0" }, { ...data.giteeRelease, tag_name: "v0.9.0", prerelease: true }, data.giteeRelease,
    ] }) : implementation(options));
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(true);
    expect(app.updates.latest?.source).toBe("gitee");
    expect(mocks.get.mock.calls.some(([options]) => options.url.includes("/v0.2.0/"))).toBe(false);
  });
  it("never accepts a manifest-provided URL to an unrelated repository", async () => {
    const data = dualFixture();
    const app = await import("../src/services/updates");
    const parsed = app.parseUpdate(data.giteeRelease, { ...data.gitee.manifest, url: "https://evil.example/update.apk" }, "gitee");
    expect(parsed.url).toMatch(/^https:\/\/gitee.com\/test-owner\/zijian\//);
  });
  it("preserves a validated update when all channels become unavailable", async () => {
    dualFixture();
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(true);
    const previous = app.updates.latest; mocks.get.mockRejectedValue(new Error("网络不可用"));
    await app.checkUpdates(true); expect(app.updates.latest).toEqual(previous); expect(app.updates.error).toContain("网络不可用");
  });
  it("keeps a known newer release when that channel goes offline instead of downgrading to the stale mirror", async () => {
    const data = dualFixture(5, 6);
    const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(true);
    expect(app.updates.latest?.versionCode).toBe(6);
    const implementation = mocks.get.getMockImplementation()!;
    mocks.get.mockImplementation((options) => options.url.includes("github") ? Promise.reject(new Error("离线")) : implementation(options));
    await app.checkUpdates(true);
    expect(app.updates.latest?.versionCode).toBe(6); expect(app.updates.latest?.source).toBe("github");
    expect(app.updates.message).toContain("保留已确认");
    expect(data.gitee.manifest.versionCode).toBe(5);
  });
  it("resumes a newer paused APK instead of replacing it with an older source's candidate", async () => {
    dualFixture(); const app = await import("../src/services/updates"); await app.initializeUpdates(); await app.checkUpdates(true);
    app.updates.download = { phase: "paused", downloaded: 1024, total: 60_000_000, versionName: "0.3.1", versionCode: 6, error: "" };
    await app.downloadUpdate();
    expect(mocks.native.download).toHaveBeenCalledWith({ sourcePreference: "auto" });
  });

});
