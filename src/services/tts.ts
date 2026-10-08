import { Capacitor, registerPlugin, type PluginListenerHandle } from "@capacitor/core";
import { reactive } from "vue";
import type { Book } from "../types";
import { state, saveProgress } from "./library";
import catalog from "../../android/app/src/main/assets/tts/catalog.json";

export interface ModelStatus {
  id: string;
  phase: "missing" | "downloading" | "verifying" | "paused" | "ready" | "error";
  downloaded: number;
  total: number;
  file: string;
  error: string;
}
export type PreparationMode = "auto" | "stream" | "chapter";
export interface PlaybackStatus {
  phase: "idle" | "loading" | "buffering" | "playing" | "paused" | "completed" | "error";
  bookId?: string;
  title?: string;
  chapter?: number;
  paragraph?: number;
  chapterTitle?: string;
  text?: string;
  voice?: string;
  speed?: number;
  positionMs?: number;
  updatedAt?: number;
  error?: string;
  preparation?: "" | "buffer" | "chapter";
  preparingChapterTitle?: string;
  preparedUnits?: number;
  totalUnits?: number;
  bufferedSeconds?: number;
}
interface TtsPlugin {
  getStatus(): Promise<{ model: ModelStatus; playback: PlaybackStatus }>;
  downloadModel(options: { mirror: boolean }): Promise<void>;
  pauseDownload(): Promise<void>;
  removeModel(): Promise<void>;
  play(options: {
    bookId: string; title: string; chapter: number; paragraph: number; voice: string; speed: number; mode: PreparationMode;
    chapters: { id: string; title: string; downloaded: boolean }[];
  }): Promise<void>;
  control(options: { action: "pause" | "resume" | "stop" | "speed" | "pauseForPrompt" | "resumeAfterPrompt"; speed?: number; bookId?: string; intent?: number }): Promise<unknown>;
  addListener(event: "modelState", callback: (status: ModelStatus) => void): Promise<PluginListenerHandle>;
  addListener(event: "playbackState", callback: (status: PlaybackStatus) => void): Promise<PluginListenerHandle>;
}
const native = registerPlugin<TtsPlugin>("ZijianTts");
export const voices = [
  { id: "Junhao", name: "俊豪", style: "男声" },
  { id: "Zhiming", name: "志明", style: "男声" },
  { id: "Weiguo", name: "卫国", style: "男声" },
  { id: "Xiaoyu", name: "小雨", style: "女声" },
  { id: "Yuewen", name: "悦文", style: "女声" },
  { id: "Lingyu", name: "灵羽", style: "女声" },
] as const;
export const tts = reactive({
  supported: Capacitor.getPlatform() === "android",
  initialized: false,
  busy: false,
  error: "",
  voice: "Junhao",
  speed: 1,
  mirror: false,
  mode: "auto" as PreparationMode,
  model: {
    id: catalog.id, phase: "missing", downloaded: 0,
    total: catalog.files.reduce((sum, file) => sum + file.size, 0), file: "", error: "",
  } as ModelStatus,
  playback: { phase: "idle" } as PlaybackStatus,
});
let initialization: Promise<void> | undefined;
let listeners: PluginListenerHandle[] = [];
let progressKey = "";
function synchronizeProgress(playback: PlaybackStatus) {
  const book = state.books.find((item) => item.localId === playback.bookId);
  const chapter = book?.chapters[playback.chapter ?? -1];
  const stamp = playback.updatedAt ?? 0;
  if (!book || !chapter || !book.downloaded.includes(chapter.id) || !stamp) return;
  const key = `${book.localId}:${chapter.id}:${playback.paragraph}:${stamp}`;
  if (key === progressKey) return;
  if (playback.phase === "idle" && stamp <= (book.progress?.updatedAt ?? 0)) return;
  progressKey = key;
  void saveProgress(book, chapter.id, playback.paragraph ?? 0, 0).catch((error: Error) => { tts.error = error.message; });
}
function playbackChanged(status: PlaybackStatus) {
  tts.playback = status;
  synchronizeProgress(status);
}
export function syncListeningProgress() { synchronizeProgress(tts.playback); }
export async function initializeTts() {
  if (initialization) return initialization;
  initialization = (async () => {
    const preferences = await import("@capacitor/preferences");
    try {
      const saved = await preferences.Preferences.get({ key: "tts-options" });
      if (saved.value) {
        const values = JSON.parse(saved.value);
        if (voices.some((voice) => voice.id === values.voice)) tts.voice = values.voice;
        if (typeof values.speed === "number" && Number.isFinite(values.speed)) tts.speed = Math.max(0.5, Math.min(2, values.speed));
        tts.mirror = values.mirror === true;
        if (["auto", "stream", "chapter"].includes(values.mode)) tts.mode = values.mode;
      }
      if (tts.supported) {
        listeners.push(await native.addListener("modelState", (status) => { tts.model = status; }));
        listeners.push(await native.addListener("playbackState", playbackChanged));
        await refreshTts();
      }
      tts.initialized = true;
    } catch (error) {
      for (const handle of listeners) await handle.remove();
      listeners = [];
      throw error;
    }
  })();
  try { await initialization; } catch (error) { initialization = undefined; throw error; }
}
export async function refreshTts() {
  if (!tts.supported) return;
  const status = await native.getStatus();
  tts.model = status.model;
  playbackChanged(status.playback);
}
export async function saveTtsOptions() {
  const { Preferences } = await import("@capacitor/preferences");
  await Preferences.set({ key: "tts-options", value: JSON.stringify({ voice: tts.voice, speed: tts.speed, mirror: tts.mirror, mode: tts.mode }) });
  if (tts.supported && tts.playback.phase !== "idle") await native.control({ action: "speed", speed: tts.speed });
}
export async function ttsAction(action: () => Promise<unknown>) {
  if (tts.busy) return;
  tts.busy = true; tts.error = "";
  try { await action(); } catch (error) { tts.error = (error as Error).message || "听书操作未完成，请重试"; }
  finally { tts.busy = false; }
}
export async function downloadModel() {
  if (!tts.supported) throw new Error("请在 Android App 中下载听书模型");
  await saveTtsOptions();
  await native.downloadModel({ mirror: tts.mirror });
  await refreshTts();
}
export async function pauseModelDownload() { await native.pauseDownload(); }
export async function removeModel() { await native.removeModel(); await refreshTts(); }
export async function startListening(book: Book, chapter: number, paragraph: number) {
  if (!tts.supported) throw new Error("本地听书支持 Android App");
  if (tts.model.phase !== "ready") throw new Error("请先下载听书模型");
  progressKey = "";
  await native.play({
    bookId: book.localId, title: book.title, chapter, paragraph,
    voice: tts.voice, speed: tts.speed, mode: tts.mode,
    chapters: book.chapters.map((item) => ({ id: item.id, title: item.title, downloaded: book.downloaded.includes(item.id) })),
  });
}
export async function controlListening(action: "pause" | "resume" | "stop") {
  await native.control({ action });
  await refreshTts();
}
export interface PromptResumeToken { bookId: string; intent: number }
export async function pauseForAnnouncement(): Promise<PromptResumeToken | undefined> {
  const token = await native.control({ action: "pauseForPrompt" }) as PromptResumeToken | undefined;
  await refreshTts(); return token;
}
export async function resumeAfterAnnouncement(token: PromptResumeToken) {
  const result = await native.control({ action: "resumeAfterPrompt", ...token }) as { resumed?: boolean } | undefined;
  await refreshTts(); return result?.resumed === true;
}
export function formatModelSize(bytes: number) { return `${(bytes / 1024 / 1024).toFixed(1)} MiB`; }

export function listeningMessage(status: PlaybackStatus = tts.playback): string {
  if (status.error) return status.error;
  if (status.phase === "paused") return "听书已暂停";
  if (status.phase === "playing") return status.chapterTitle || "正在朗读";
  if (status.preparation === "chapter") {
    const progress = status.totalUnits ? `（${status.preparedUnits ?? 0}/${status.totalUnits}）` : "";
    const chapter = status.chapterTitle && status.preparingChapterTitle && status.chapterTitle !== status.preparingChapterTitle ? "下一章" : "本章";
    return `正在准备${chapter}${progress}，完成后开始播放`;
  }
  if (status.phase === "loading") return "正在加载声音…";
  if (status.phase === "buffering") return "正在准备声音…";
  return status.chapterTitle || "";
}
