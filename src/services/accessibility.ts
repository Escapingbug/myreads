import { Capacitor, registerPlugin } from "@capacitor/core";
import { Preferences } from "@capacitor/preferences";
import { reactive } from "vue";
import { tts, controlListening, refreshTts, pauseForAnnouncement, resumeAfterAnnouncement, type PromptResumeToken } from "./tts";

interface AccessibilityPlugin {
  enable(): Promise<void>;
  disable(): Promise<void>;
  status(): Promise<{ loaded: boolean; recognitionAvailable: boolean; microphoneGranted?: boolean }>;
  configureRecognition(): Promise<{ recognitionAvailable: boolean }>;
  speak(options: { text: string; voice: string }): Promise<{ cancelled?: boolean }>;
  stopSpeech(): Promise<void>;
  recognize(): Promise<{ text: string }>;
  stopRecognition(): Promise<void>;
}
const native = registerPlugin<AccessibilityPlugin>("ZijianAccessibility");
export const accessibility = reactive({
  enabled: false, preparing: false, error: "", speaking: false,
  recording: false, recognitionAvailable: false, active: true, searches: [] as string[],
  interruptedPlayback: false,
  microphoneGranted: false,
});
let generation = 0;
let resumeAfterSpeech = false;
let resumeToken: PromptResumeToken | undefined;
let pendingPause: Promise<PromptResumeToken | undefined> | undefined;
let switching: Promise<void> | undefined;
export async function initializeAccessibility() {
  const [saved, searches] = await Promise.all([
    Preferences.get({ key: "accessibility-enabled" }), Preferences.get({ key: "accessibility-searches" }),
  ]);
  try {
    const values: unknown = JSON.parse(searches.value ?? "[]");
    if (Array.isArray(values)) accessibility.searches = values.filter((item): item is string => typeof item === "string" && item.length > 0 && item.length <= 200).slice(0, 20);
  } catch { /* Keep an empty search history if old preferences are damaged. */ }
  if (saved.value === "true") await enableAccessibility();
}
export async function enableAccessibility() {
  if (switching) return switching;
  if (accessibility.enabled) return;
  switching = (async () => {
    accessibility.preparing = true; accessibility.error = "";
    try {
      if (Capacitor.getPlatform() !== "android") throw new Error("无障碍模式需要 Android App 和本地语音模型");
      await refreshTts();
      if (tts.model.phase !== "ready") throw new Error("请由协助者先在听书页下载并校验语音模型，再开启无障碍模式");
      if (["playing", "loading", "buffering"].includes(tts.playback.phase)) await controlListening("pause");
      await native.enable();
      const status = await native.status();
      if (!status.loaded) throw new Error("语音模型尚未加载，请重试");
      accessibility.recognitionAvailable = status.recognitionAvailable;
      accessibility.microphoneGranted = status.microphoneGranted === true;
      await Preferences.set({ key: "accessibility-enabled", value: "true" });
      accessibility.enabled = true;
    } catch (error) {
      await native.disable().catch(() => {});
      accessibility.error = (error as Error).message; throw error;
    } finally { accessibility.preparing = false; }
  })();
  try { await switching; } finally { switching = undefined; }
}
export async function disableAccessibility() {
  await cancelAnnouncements();
  await native.stopRecognition();
  await Preferences.set({ key: "accessibility-enabled", value: "false" });
  await native.disable();
  accessibility.enabled = false; accessibility.error = "";
}
export async function cancelAnnouncements() {
  ++generation; resumeAfterSpeech = false; accessibility.speaking = false;
  accessibility.interruptedPlayback = false;
  resumeToken = undefined;
  if (Capacitor.getPlatform() === "android") await native.stopSpeech();
}
export async function announce(text: string, resume = false) {
  if (!accessibility.enabled || !accessibility.active || accessibility.recording || !text.trim()) return;
  const token = ++generation;
  if (!resume) { resumeAfterSpeech = false; resumeToken = undefined; }
  else if (["playing", "buffering", "loading"].includes(tts.playback.phase)) resumeAfterSpeech = true;
  accessibility.interruptedPlayback = resumeAfterSpeech;
  accessibility.speaking = true;
  try {
    await native.stopSpeech();
    if (token !== generation) return;
    if (!pendingPause && ["playing", "buffering", "loading"].includes(tts.playback.phase)) {
      const paused = pauseForAnnouncement();
      pendingPause = paused;
      void paused.finally(() => { if (pendingPause === paused) pendingPause = undefined; }).catch(() => {});
    }
    if (pendingPause) {
      const paused = await pendingPause;
      if (resumeAfterSpeech && !resumeToken) resumeToken = paused;
      if (token !== generation) return;
    }
    if (token !== generation) return;
    const result = await native.speak({ text: text.slice(0, 4096), voice: tts.voice });
    if (token !== generation) return;
    if (result.cancelled) { resumeAfterSpeech = false; resumeToken = undefined; accessibility.interruptedPlayback = false; return; }
    if (!result.cancelled && resumeAfterSpeech && accessibility.active) {
      resumeAfterSpeech = false;
      if (resumeToken) await resumeAfterAnnouncement(resumeToken);
      resumeToken = undefined;
      accessibility.interruptedPlayback = false;
    }
  } catch (error) {
    if (token === generation) { accessibility.error = (error as Error).message; resumeAfterSpeech = false; resumeToken = undefined; accessibility.interruptedPlayback = false; }
  } finally { if (token === generation) accessibility.speaking = false; }
}
export async function recognizeSearch() {
  await announce("请在提示音后说出书名或作者。");
  await cancelAnnouncements();
  accessibility.recording = true;
  try {
    const result = await native.recognize();
    const text = result.text?.trim().slice(0, 200);
    if (!text) throw new Error("没有听清，请重新说书名或作者");
    return text;
  } finally { accessibility.recording = false; }
}
export async function cancelRecognition() { await native.stopRecognition(); accessibility.recording = false; }
export async function configureRecognition() {
  if (Capacitor.getPlatform() !== "android") throw new Error("请在 Android App 中配置语音搜索");
  const status = await native.configureRecognition();
  accessibility.microphoneGranted = true; accessibility.recognitionAvailable = status.recognitionAvailable;
  return status.recognitionAvailable;
}
export async function rememberSearch(text: string) {
  accessibility.searches = [text, ...accessibility.searches.filter((item) => item !== text)].slice(0, 20);
  await Preferences.set({ key: "accessibility-searches", value: JSON.stringify(accessibility.searches) });
}
