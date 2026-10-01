import { Capacitor, CapacitorHttp, registerPlugin, type PluginListenerHandle } from "@capacitor/core";
import { Preferences } from "@capacitor/preferences";
import { reactive } from "vue";
import { version } from "../../package.json";

export const releasesUrl = "https://github.com/escapingbug/myreads/releases";
const apiUrl = "https://api.github.com/repos/escapingbug/myreads/releases/latest";
const day = 24 * 60 * 60 * 1000;
export interface UpdateCandidate {
  versionName: string; versionCode: number; size: number; sha256: string; url: string; notes: string;
}
export interface UpdateDownload {
  phase: "idle" | "downloading" | "verifying" | "paused" | "ready" | "error";
  downloaded: number; total: number; versionName: string; error: string;
}
interface UpdatePlugin {
  getStatus(): Promise<{ versionName: string; versionCode: number; canInstall: boolean; download: UpdateDownload }>;
  download(options?: UpdateCandidate): Promise<void>;
  pause(): Promise<void>;
  install(): Promise<{ permissionRequired: boolean }>;
  addListener(event: "updateState", listener: (value: UpdateDownload) => void): Promise<PluginListenerHandle>;
}
const native = registerPlugin<UpdatePlugin>("AppUpdate");
export const updates = reactive({
  supported: Capacitor.getPlatform() === "android",
  initialized: false, checking: false, busy: false, automatic: true,
  versionName: version, versionCode: 0, canInstall: false,
  lastCheck: 0, error: "", message: "", latest: undefined as UpdateCandidate | undefined,
  download: { phase: "idle", downloaded: 0, total: 0, versionName: "", error: "" } as UpdateDownload,
});
let initialization: Promise<void> | undefined;
let listener: PluginListenerHandle | undefined;
let cached: { release: unknown; manifest: unknown } | undefined;
let lastAttempt = 0;
type Json = Record<string, any>;
function object(value: unknown): Json {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("发布信息格式不正确");
  return value as Json;
}
function asset(release: Json, name: string) {
  if (!Array.isArray(release.assets)) throw new Error("发布信息缺少安装包");
  const items = release.assets.filter((item: Json) => item.name === name && item.state === "uploaded");
  if (items.length !== 1) throw new Error(`此版本缺少 ${name}，请稍后重试`);
  const item = object(items[0]);
  const path = `/escapingbug/myreads/releases/download/${release.tag_name}/${name}`;
  const url = new URL(item.browser_download_url);
  if (url.protocol !== "https:" || url.hostname !== "github.com" || url.port || url.username || url.password || url.search || url.hash || url.pathname.toLowerCase() !== path.toLowerCase())
    throw new Error("更新文件不属于纸间的发布仓库");
  return item;
}
export function manifestUrl(value: unknown): string {
  const release = object(value);
  if (release.draft !== false || release.prerelease !== false || !/^v\d+\.\d+\.\d+$/.test(release.tag_name)) throw new Error("暂时没有可用的正式版本");
  const file = asset(release, "update.json");
  if (!Number.isSafeInteger(file.size) || file.size <= 0 || file.size > 16384) throw new Error("更新清单大小无效");
  return file.browser_download_url;
}
export function parseUpdate(releaseValue: unknown, manifestValue: unknown): UpdateCandidate {
  manifestUrl(releaseValue);
  const release = object(releaseValue), manifest = object(manifestValue);
  const versionName = release.tag_name.slice(1);
  const apk = asset(release, `zijian-${versionName}.apk`);
  if (manifest.schemaVersion !== 1 || manifest.packageName !== "io.myreads.app" || manifest.versionName !== versionName
      || manifest.apkName !== apk.name || !Number.isSafeInteger(manifest.versionCode) || manifest.versionCode <= 0 || manifest.versionCode > 2147483647
      || !Number.isSafeInteger(manifest.size) || manifest.size < 1024 * 1024 || manifest.size > 300 * 1024 * 1024
      || manifest.size !== apk.size || typeof manifest.sha256 !== "string" || !/^[a-f0-9]{64}$/.test(manifest.sha256)
      || (apk.digest != null && apk.digest !== `sha256:${manifest.sha256}`))
    throw new Error("更新清单与安装包不一致，无法下载");
  return { versionName, versionCode: manifest.versionCode, size: manifest.size, sha256: manifest.sha256,
    url: apk.browser_download_url, notes: typeof release.body === "string" ? release.body.slice(0, 10000) : "" };
}
async function request(url: string, api = false): Promise<unknown | undefined> {
  const response = await CapacitorHttp.get({ url, headers: api ? { Accept: "application/vnd.github+json", "X-GitHub-Api-Version": "2026-03-10" } : {},
    responseType: "json", connectTimeout: 15000, readTimeout: 20000 });
  if (api && response.status === 404) return undefined;
  if (response.status === 403 || response.status === 429) throw new Error("GitHub 请求受限，请稍后重试或打开发布页面");
  if (response.status !== 200) throw new Error(`无法检查更新（HTTP ${response.status}），请稍后重试`);
  if (JSON.stringify(response.data).length > (api ? 1000000 : 16384)) throw new Error("发布信息过大");
  return typeof response.data === "string" ? JSON.parse(response.data) : response.data;
}
async function save() {
  await Preferences.set({ key: "app-updates", value: JSON.stringify({ automatic: updates.automatic, lastCheck: updates.lastCheck, lastAttempt, cached }) });
}
export async function refreshUpdates() {
  if (!updates.supported) return;
  const status = await native.getStatus();
  Object.assign(updates, status);
  if (updates.latest && updates.latest.versionCode <= status.versionCode) updates.latest = undefined;
}
export async function initializeUpdates() {
  if (initialization) return initialization;
  initialization = (async () => {
    if (!updates.supported) { updates.initialized = true; return; }
    const saved = await Preferences.get({ key: "app-updates" });
    if (saved.value) {
      try {
        const data = JSON.parse(saved.value);
        updates.automatic = data.automatic !== false;
        updates.lastCheck = Number.isFinite(data.lastCheck) ? data.lastCheck : 0;
        lastAttempt = Number.isFinite(data.lastAttempt) ? data.lastAttempt : 0;
        if (data.cached) { updates.latest = parseUpdate(data.cached.release, data.cached.manifest); cached = data.cached; }
      } catch { /* Corrupt cached metadata is discarded; the next check reloads it. */ }
    }
    listener = await native.addListener("updateState", (value) => { updates.download = value; });
    try { await refreshUpdates(); updates.initialized = true; }
    catch (error) { await listener.remove(); listener = undefined; throw error; }
  })();
  try { await initialization; } catch (error) { initialization = undefined; throw error; }
}
export async function checkUpdates(manual = false) {
  if (!updates.supported || updates.checking) return;
  if (!manual && (!updates.automatic || Date.now() - updates.lastCheck < day || Date.now() - lastAttempt < 60 * 60 * 1000)) return;
  updates.checking = true; updates.error = ""; updates.message = "";
  try {
    await refreshUpdates(); lastAttempt = Date.now(); await save();
    const release = await request(apiUrl, true);
    if (release) {
      const manifest = await request(manifestUrl(release));
      const candidate = parseUpdate(release, manifest);
      cached = { release, manifest };
      updates.latest = candidate.versionCode > updates.versionCode ? candidate : undefined;
      if (manual) updates.message = updates.latest ? "发现新版本，可以下载更新" : "已经是最新版本";
    } else { updates.latest = undefined; cached = undefined; if (manual) updates.message = "仓库暂未发布版本"; }
    updates.lastCheck = Date.now(); await save();
  } catch (error) { updates.error = (error as Error).message || "无法检查更新，请检查网络"; }
  finally { updates.checking = false; }
}
export async function setAutomatic(value: boolean) { updates.automatic = value; await save(); if (value) await checkUpdates(); }
export async function updateAction(action: () => Promise<unknown>) {
  if (updates.busy) return;
  updates.busy = true; updates.error = ""; updates.message = "";
  try { await action(); await refreshUpdates(); } catch (error) { updates.error = (error as Error).message; }
  finally { updates.busy = false; }
}
export async function downloadUpdate() {
  if (updates.latest && updates.latest.versionCode > updates.versionCode) await native.download({ ...updates.latest });
  else if (["paused", "error"].includes(updates.download.phase) && updates.download.versionName) await native.download();
  else throw new Error("请先检查更新");
}
export async function pauseUpdate() { await native.pause(); }
export async function installUpdate() {
  const result = await native.install();
  if (result.permissionRequired) updates.message = "允许纸间安装应用后，返回这里点击「安装更新」";
}
