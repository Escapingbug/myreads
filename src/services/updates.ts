import { Capacitor, CapacitorHttp, registerPlugin, type PluginListenerHandle } from "@capacitor/core";
import { Preferences } from "@capacitor/preferences";
import { reactive } from "vue";
import { version } from "../../package.json";
import { channelFor, releaseFile, updateChannels, sourceLabels, type UpdateChannel, type UpdateSource, type UpdateSourcePreference } from "./update-channels";
export { updateChannels, sourceLabels } from "./update-channels";

export const releasesUrl = channelFor("github").releasesUrl;
const day = 24 * 60 * 60 * 1000;
export interface UpdateCandidate {
  versionName: string; versionCode: number; size: number; sha256: string; url: string; urls: string[]; notes: string; source: UpdateSource;
}
export interface UpdateDownload {
  phase: "idle" | "downloading" | "verifying" | "paused" | "ready" | "error";
  downloaded: number; total: number; versionName: string; versionCode?: number; error: string; source?: UpdateSource;
}
interface UpdatePlugin {
  getStatus(): Promise<{ versionName: string; versionCode: number; canInstall: boolean; download: UpdateDownload }>;
  download(options?: (UpdateCandidate & { sourcePreference?: UpdateSourcePreference }) | { sourcePreference: UpdateSourcePreference }): Promise<void>;
  pause(): Promise<void>;
  install(): Promise<{ permissionRequired: boolean }>;
  addListener(event: "updateState", listener: (value: UpdateDownload) => void): Promise<PluginListenerHandle>;
}
const native = registerPlugin<UpdatePlugin>("AppUpdate");
export const updates = reactive({
  supported: Capacitor.getPlatform() === "android",
  initialized: false, checking: false, busy: false, automatic: true, source: "auto" as UpdateSourcePreference,
  versionName: version, versionCode: 0, canInstall: false,
  lastCheck: 0, error: "", message: "", latest: undefined as UpdateCandidate | undefined,
  download: { phase: "idle", downloaded: 0, total: 0, versionName: "", error: "" } as UpdateDownload,
});
let initialization: Promise<void> | undefined;
let listener: PluginListenerHandle | undefined;
interface CachedRelease { source: UpdateSource; release: unknown; manifest: unknown }
let cached: CachedRelease[] = [];
let lastAttempt = 0;
type Json = Record<string, any>;
function object(value: unknown): Json {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("发布信息格式不正确");
  return value as Json;
}
function official(value: unknown, source: UpdateSource): Json {
  const release = object(value);
  if (release.prerelease !== false || (source === "github" ? release.draft !== false : release.draft === true)
      || typeof release.tag_name !== "string" || !/^v\d+\.\d+\.\d+$/.test(release.tag_name)) throw new Error("暂时没有可用的正式版本");
  return release;
}
function asset(release: Json, name: string) {
  if (!Array.isArray(release.assets)) throw new Error("发布信息缺少安装包");
  const items = release.assets.filter((item: Json) => item.name === name && item.state === "uploaded");
  if (items.length !== 1) throw new Error(`此版本缺少 ${name}，请稍后重试`);
  const item = object(items[0]);
  const expected = new URL(releaseFile(channelFor("github"), release.tag_name, name));
  const url = new URL(item.browser_download_url);
  if (url.protocol !== "https:" || url.hostname !== expected.hostname || url.port || url.username || url.password || url.search || url.hash
      || url.pathname.toLowerCase() !== expected.pathname.toLowerCase()) throw new Error("更新文件不属于纸间的发布仓库");
  return item;
}
export function manifestUrl(value: unknown, source: UpdateSource = "github"): string {
  const release = official(value, source);
  if (source === "gitee") return releaseFile(channelFor(source), release.tag_name, "update.json");
  const file = asset(release, "update.json");
  if (!Number.isSafeInteger(file.size) || file.size <= 0 || file.size > 16384) throw new Error("更新清单大小无效");
  return file.browser_download_url;
}
export function parseUpdate(releaseValue: unknown, manifestValue: unknown, source: UpdateSource = "github"): UpdateCandidate {
  manifestUrl(releaseValue, source);
  const release = object(releaseValue), manifest = object(manifestValue);
  const versionName = release.tag_name.slice(1);
  const name = `zijian-${versionName}.apk`;
  const apk = source === "github" ? asset(release, name) : undefined;
  if (manifest.schemaVersion !== 1 || manifest.packageName !== "io.myreads.app" || manifest.versionName !== versionName
      || manifest.apkName !== name || !Number.isSafeInteger(manifest.versionCode) || manifest.versionCode <= 0 || manifest.versionCode > 2147483647
      || !Number.isSafeInteger(manifest.size) || manifest.size < 1024 * 1024 || manifest.size > 300 * 1024 * 1024
      || typeof manifest.sha256 !== "string" || !/^[a-f0-9]{64}$/.test(manifest.sha256)
      || (apk && (manifest.size !== apk.size || (apk.digest != null && apk.digest !== `sha256:${manifest.sha256}`))))
    throw new Error("更新清单与安装包不一致，无法下载");
  const url = apk ? apk.browser_download_url : releaseFile(channelFor(source), release.tag_name, name);
  return { versionName, versionCode: manifest.versionCode, size: manifest.size, sha256: manifest.sha256,
    url, urls: [url], source, notes: typeof release.body === "string" ? release.body.slice(0, 10000) : "" };
}
export function selectUpdate(candidates: UpdateCandidate[], preference: UpdateSourcePreference): UpdateCandidate | undefined {
  const available = candidates.filter((item) => preference === "auto" || item.source === preference)
    .sort((a, b) => b.versionCode - a.versionCode || (a.source === b.source ? 0 : a.source === "gitee" ? -1 : 1));
  const selected = available[0];
  if (!selected) return undefined;
  const sameVersion = available.filter((item) => item.versionCode === selected.versionCode);
  if (sameVersion.some((item) => item.versionName !== selected.versionName || item.size !== selected.size || item.sha256 !== selected.sha256))
    throw new Error("两个渠道的同版本安装包不一致，已停止更新，请稍后重试");
  return { ...selected, urls: [...new Set(sameVersion.map((item) => item.url))] };
}
async function request(url: string, source: UpdateSource, api = false): Promise<unknown | undefined> {
  const response = await CapacitorHttp.get({ url, headers: api && source === "github" ? { Accept: "application/vnd.github+json", "X-GitHub-Api-Version": "2026-03-10" } : {},
    responseType: "json", connectTimeout: 4000, readTimeout: 4000 });
  if (api && source === "github" && response.status === 404) return undefined;
  if (response.status === 403 || response.status === 429) throw new Error(`${sourceLabels[source]} 请求受限，请稍后重试或切换来源`);
  if (response.status !== 200) throw new Error(`无法检查 ${sourceLabels[source]} 更新（HTTP ${response.status}）`);
  if (JSON.stringify(response.data).length > (api ? 1000000 : 16384)) throw new Error("发布信息过大");
  return typeof response.data === "string" ? JSON.parse(response.data) : response.data;
}
async function loadChannel(channel: UpdateChannel): Promise<CachedRelease | undefined> {
  let release = await request(channel.apiUrl, channel.source, true);
  if (channel.source === "gitee") {
    if (!Array.isArray(release)) throw new Error("Gitee 发布列表格式不正确");
    const stable = release.filter((value) => { try { official(value, "gitee"); return true; } catch { return false; } });
    // Gitee lists by last update, which can put a republished old tag first.
    stable.sort((a, b) => {
      const left = a.tag_name.slice(1).split(".").map(BigInt), right = b.tag_name.slice(1).split(".").map(BigInt);
      for (let i = 0; i < 3; i++) if (left[i] !== right[i]) return left[i] > right[i] ? -1 : 1;
      return 0;
    });
    release = stable[0];
  }
  if (!release) return undefined;
  const manifest = await request(manifestUrl(release, channel.source), channel.source);
  parseUpdate(release, manifest, channel.source);
  return { source: channel.source, release, manifest };
}
function bounded<T>(promise: Promise<T>, source: UpdateSource): Promise<T> {
  let timer: ReturnType<typeof setTimeout>;
  return Promise.race([promise, new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${sourceLabels[source]} 检查超时`)), 8000);
  })]).finally(() => clearTimeout(timer));
}
function candidates() { return cached.map((item) => parseUpdate(item.release, item.manifest, item.source)); }
function applyCached() {
  const candidate = selectUpdate(candidates(), updates.source);
  updates.latest = candidate && candidate.versionCode > updates.versionCode ? candidate : undefined;
}
async function save() {
  await Preferences.set({ key: "app-updates", value: JSON.stringify({ automatic: updates.automatic, source: updates.source, lastCheck: updates.lastCheck, lastAttempt, cached }) });
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
        if (data.source === "auto" || updateChannels.some((item) => item.source === data.source)) updates.source = data.source;
        updates.lastCheck = Number.isFinite(data.lastCheck) ? data.lastCheck : 0;
        lastAttempt = Number.isFinite(data.lastAttempt) ? data.lastAttempt : 0;
        const records = Array.isArray(data.cached) ? data.cached : data.cached ? [{ ...data.cached, source: "github" }] : [];
        cached = records.filter((item: CachedRelease) => {
          try { parseUpdate(item.release, item.manifest, item.source); return true; } catch { return false; }
        });
        applyCached();
      } catch { cached = []; updates.latest = undefined; updates.lastCheck = 0; lastAttempt = 0; }
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
    const channels = updateChannels.filter((item) => updates.source === "auto" || item.source === updates.source);
    const results = await Promise.allSettled(channels.map((channel) => bounded(loadChannel(channel), channel.source)));
    const successful: CachedRelease[] = [], errors: string[] = [];
    results.forEach((result) => {
      if (result.status === "fulfilled") { if (result.value) successful.push(result.value); }
      else errors.push(result.reason?.message || "更新渠道暂时不可用");
    });
    if (errors.length === results.length || (!successful.length && errors.length)) throw new Error(errors.join("；"));
    // Do not replace a known newer release with an older mirror just because
    // the newer channel is offline (especially while its APK is paused).
    const checkedSources = channels.filter((_, i) => results[i]?.status === "fulfilled").map((item) => item.source);
    const merged = [...successful, ...cached.filter((item) => !checkedSources.includes(item.source))];
    const fresh = selectUpdate(successful.map((item) => parseUpdate(item.release, item.manifest, item.source)), updates.source);
    const known = selectUpdate(merged.map((item) => parseUpdate(item.release, item.manifest, item.source)), updates.source);
    const fromCache = known && (!fresh || known.versionCode > fresh.versionCode);
    const candidate = fromCache ? known : fresh;
    cached = merged;
    updates.latest = candidate && candidate.versionCode > updates.versionCode ? candidate : undefined;
    if (manual) updates.message = updates.latest ? (fromCache ? "保留已确认的较新版本；该渠道暂时无法连接，可稍后重试" : "发现新版本，可以下载更新")
      : successful.length ? (errors.length ? "可用渠道未发现新版本；部分渠道暂时无法连接" : "已经是最新版本") : "仓库暂未发布版本";
    updates.lastCheck = errors.length ? 0 : Date.now(); await save();
  } catch (error) { updates.error = (error as Error).message || "无法检查更新，请检查网络"; }
  finally { updates.checking = false; }
}
export async function setSource(value: UpdateSourcePreference) {
  if (updates.checking || ["downloading", "verifying"].includes(updates.download.phase)) return;
  if (value !== "auto") channelFor(value);
  updates.source = value; updates.error = ""; updates.message = ""; updates.lastCheck = 0; lastAttempt = 0;
  applyCached(); await save(); await checkUpdates(true);
}
export async function setAutomatic(value: boolean) { updates.automatic = value; await save(); if (value) await checkUpdates(); }
export async function updateAction(action: () => Promise<unknown>) {
  if (updates.busy) return;
  updates.busy = true; updates.error = ""; updates.message = "";
  try { await action(); await refreshUpdates(); } catch (error) { updates.error = (error as Error).message; }
  finally { updates.busy = false; }
}
export async function downloadUpdate() {
  if (["paused", "error"].includes(updates.download.phase) && (updates.download.versionCode ?? 0) > (updates.latest?.versionCode ?? 0))
    await native.download({ sourcePreference: updates.source });
  else if (updates.latest && updates.latest.versionCode > updates.versionCode) await native.download({ ...updates.latest, sourcePreference: updates.source });
  else if (["paused", "error"].includes(updates.download.phase) && updates.download.versionName) await native.download({ sourcePreference: updates.source });
  else throw new Error("请先检查更新");
}
export async function pauseUpdate() { await native.pause(); }
export async function installUpdate() {
  const result = await native.install();
  if (result.permissionRequired) updates.message = "允许纸间安装应用后，返回这里点击「安装更新」";
}
