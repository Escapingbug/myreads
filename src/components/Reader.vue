<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from "vue";
import {
  ArrowLeft,
  List,
  SlidersHorizontal,
  ChevronLeft,
  ChevronRight,
  X,
  Check,
  Moon,
  Sun,
  BookOpen,
  Headphones,
  Pause,
  Play,
  Square,
} from "lucide-vue-next";
import type { Book, ChapterContent } from "../types";
import { storage } from "../services/storage";
import { state, saveProgress, saveSettings } from "../services/library";
import ListeningSettings from "./ListeningSettings.vue";
import { tts, ttsAction, startListening, controlListening, syncListeningProgress } from "../services/tts";
const props = defineProps<{ book: Book }>();
const emit = defineEmits<{ close: []; error: [message: string] }>();
const savedIndex = props.book.chapters.findIndex(
  (ch) =>
    ch.id === props.book.progress?.chapterId &&
    props.book.downloaded.includes(ch.id),
);
const initialIndex =
  savedIndex >= 0
    ? savedIndex
    : Math.max(
        0,
        props.book.chapters.findIndex((ch) =>
          props.book.downloaded.includes(ch.id),
        ),
      );
const index = ref(initialIndex);
const content = ref<ChapterContent>();
const loading = ref(true);
const failure = ref("");
const toolbar = ref(true);
const panel = ref<"toc" | "settings" | "listen" | null>(null);
const panelNames = { toc: "章节目录", settings: "阅读设置", listen: "听书" };
const listening = computed(() => tts.playback.bookId === props.book.localId && !["idle", "completed", "error"].includes(tts.playback.phase));
const scrollRoot = ref<HTMLElement>();
const percent = ref(0);
const chapter = computed(() => props.book.chapters[index.value]);
const settings = computed(() => state.settings);
let saveTimer: ReturnType<typeof setTimeout> | undefined;
let position = {
  paragraph: props.book.progress?.paragraph ?? 0,
  offset: props.book.progress?.offset ?? 0,
};
let loadSerial = 0;
let restoring = false;
async function loadChapter(next: number, restore = false, fromAudio = false) {
  if (next < 0 || next >= props.book.chapters.length) return;
  const ref = props.book.chapters[next]!;
  if (!props.book.downloaded.includes(ref.id)) {
    emit("error", "这一章尚未下载，请先完成下载。");
    return;
  }
  if (listening.value && !fromAudio) await controlListening("stop");
  if (saveTimer) {
    clearTimeout(saveTimer);
    saveTimer = undefined;
  }
  if (!fromAudio && !loading.value && content.value) {
    try {
      await persist();
    } catch (error) {
      emit("error", (error as Error).message);
      return;
    }
  }
  const serial = ++loadSerial;
  loading.value = true;
  content.value = undefined;
  failure.value = "";
  index.value = next;
  panel.value = null;
  try {
    const saved = await storage.chapter(props.book.localId, next);
    if (serial !== loadSerial) return;
    if (!saved)
      throw new Error("本地章节文件缺失，请在下载管理中重新下载这本书。");
    content.value = saved;
    if (fromAudio) position = { paragraph: tts.playback.paragraph ?? 0, offset: 0 };
    else if (!restore) position = { paragraph: 0, offset: 0 };
    restoring = true;
    loading.value = false;
    await nextTick();
    if (serial !== loadSerial) return;
    const root = scrollRoot.value;
    const paragraph = root?.querySelector<HTMLElement>(
      `[data-paragraph="${position.paragraph}"]`,
    );
    if (
      root &&
      paragraph &&
      (restore || fromAudio) &&
      (position.paragraph > 0 || position.offset > 0)
    )
      root.scrollTop =
        paragraph.getBoundingClientRect().top -
        root.getBoundingClientRect().top +
        root.scrollTop -
        100 +
        position.offset * paragraph.offsetHeight;
    else if (root) root.scrollTop = 0;
    restoring = false;
    onScroll();
  } catch (error) {
    if (serial === loadSerial) {
      failure.value = (error as Error).message;
      loading.value = false;
      restoring = false;
    }
  }
}
function onScroll() {
  const root = scrollRoot.value;
  if (!root || loading.value || restoring || !content.value) return;
  const boundary = root.getBoundingClientRect().top + 100;
  const paragraphs = [
    ...root.querySelectorAll<HTMLElement>("[data-paragraph]"),
  ];
  const first =
    paragraphs.find((p) => p.getBoundingClientRect().bottom > boundary) ??
    paragraphs.at(-1);
  if (first)
    position = {
      paragraph: Number(first.dataset.paragraph),
      offset: Math.max(
        0,
        Math.min(
          1,
          (boundary - first.getBoundingClientRect().top) /
            Math.max(1, first.offsetHeight),
        ),
      ),
    };
  percent.value =
    root.scrollHeight <= root.clientHeight
      ? 100
      : Math.min(
          100,
          Math.round(
            (root.scrollTop / (root.scrollHeight - root.clientHeight)) * 100,
          ),
        );
  if (saveTimer) clearTimeout(saveTimer);
  if (listening.value) return;
  saveTimer = setTimeout(() => {
    void persist().catch((error) => emit("error", (error as Error).message));
  }, 500);
}
async function persist() {
  if (listening.value) { syncListeningProgress(); return; }
  if (chapter.value && content.value)
    await saveProgress(
      props.book,
      chapter.value.id,
      position.paragraph,
      position.offset,
    );
}
async function updateSettings() {
  try {
    await saveSettings();
    await nextTick();
    const root = scrollRoot.value;
    const element = root?.querySelector<HTMLElement>(
      `[data-paragraph="${position.paragraph}"]`,
    );
    if (root && element)
      root.scrollTop =
        element.getBoundingClientRect().top -
        root.getBoundingClientRect().top +
        root.scrollTop -
        100 +
        position.offset * element.offsetHeight;
  } catch (error) {
    emit("error", (error as Error).message);
  }
}
function textTap() {
  if (!window.getSelection()?.toString()) toolbar.value = !toolbar.value;
}
function handleBack() {
  panel.value ? (panel.value = null) : emit("close");
}
function key(event: KeyboardEvent) {
  if (event.key === "Escape") handleBack();
}
async function listenHere() {
  await persist();
  await startListening(props.book, index.value, position.paragraph);
  panel.value = null;
}
watch(() => [tts.playback.bookId, tts.playback.chapter, tts.playback.paragraph], async () => {
  if (!listening.value || loading.value) return;
  const target = tts.playback.chapter ?? index.value;
  if (target !== index.value) await loadChapter(target, true, true);
  else {
    position = { paragraph: tts.playback.paragraph ?? 0, offset: 0 };
    const root = scrollRoot.value;
    const element = root?.querySelector<HTMLElement>(`[data-paragraph="${position.paragraph}"]`);
    if (root && element) root.scrollTo({ top: element.offsetTop - 100, behavior: "smooth" });
  }
});
defineExpose({ handleBack, persist });
onMounted(() => {
  document.body.style.overflow = "hidden";
  window.addEventListener("keydown", key);
  const audioIndex = listening.value ? tts.playback.chapter : undefined;
  void loadChapter(audioIndex ?? initialIndex, true, audioIndex !== undefined);
});
onBeforeUnmount(() => {
  ++loadSerial;
  document.body.style.overflow = "";
  window.removeEventListener("keydown", key);
  if (saveTimer) clearTimeout(saveTimer);
  void persist().catch((error) => emit("error", (error as Error).message));
});
</script>
<template>
  <section
    class="reader"
    :class="`theme-${settings.theme}`"
    aria-label="小说阅读器"
  >
    <header class="reader-toolbar" :class="{ hidden: !toolbar }">
      <button class="icon-button" aria-label="返回书架" @click="emit('close')">
        <ArrowLeft :size="22" />
      </button>
      <div>
        <strong>{{ book.title }}</strong
        ><span>{{ book.author }}</span>
      </div>
      <button
        class="icon-button"
        :class="{ 'listen-active': listening }"
        aria-label="听书"
        @click="panel = 'listen'"
      ><Headphones :size="21" /></button>
      <button
        class="icon-button"
        aria-label="阅读设置"
        @click="panel = 'settings'"
      >
        <SlidersHorizontal :size="21" />
      </button>
    </header>
    <div ref="scrollRoot" class="reader-scroll" @scroll.passive="onScroll">
      <article
        class="reader-article"
        :style="{
          fontSize: `${settings.fontSize}px`,
          lineHeight: settings.lineHeight,
        }"
        @click="textTap"
      >
        <div class="reader-eyebrow">
          {{ book.title }} · {{ index + 1 }} / {{ book.chapters.length }}
        </div>
        <h1>{{ chapter?.title }}</h1>
        <div v-if="loading" class="reader-placeholder">正在打开本地章节…</div>
        <div v-else-if="failure" class="reader-placeholder">{{ failure }}</div>
        <template v-else
          ><p
            v-for="(paragraph, i) in content?.paragraphs"
            :key="i"
            :data-paragraph="i"
            :class="{ 'speaking-paragraph': listening && tts.playback.chapter === index && tts.playback.paragraph === i }"
          >
            {{ paragraph }}
          </p></template
        >
        <div v-if="!loading && !failure" class="chapter-end" @click.stop>
          <span>— 本章结束 —</span>
          <button
            v-if="index < book.chapters.length - 1"
            class="button secondary"
            @click="loadChapter(index + 1)"
          >
            下一章 <ChevronRight :size="17" />
          </button>
          <span v-else>你已读到本书最后一章</span>
        </div>
      </article>
    </div>
    <div v-if="listening" class="reader-listen-bar" :class="{ hidden: !toolbar }">
      <Headphones :size="16" /><span>{{ { loading: '正在加载声音…', buffering: '正在准备下一段…', paused: '听书已暂停', playing: '正在朗读' }[tts.playback.phase as 'loading' | 'buffering' | 'paused' | 'playing'] }}</span>
      <button class="icon-button" :aria-label="tts.playback.phase === 'paused' ? '继续听书' : '暂停听书'" @click="ttsAction(() => controlListening(tts.playback.phase === 'paused' ? 'resume' : 'pause'))"><Play v-if="tts.playback.phase === 'paused'" :size="17" /><Pause v-else :size="17" /></button>
      <button class="icon-button" aria-label="停止听书" @click="ttsAction(() => controlListening('stop'))"><Square :size="15" /></button>
    </div>
    <footer class="reader-bottom" :class="{ hidden: !toolbar }">
      <button
        class="text-button"
        :disabled="index === 0 || loading"
        @click="loadChapter(index - 1)"
      >
        <ChevronLeft :size="18" /><span>上一章</span>
      </button>
      <button class="text-button" @click="panel = 'toc'">
        <List :size="19" /><span>目录</span>
      </button>
      <span class="reader-progress">本章 {{ percent }}%</span>
      <button
        class="text-button"
        :disabled="index === book.chapters.length - 1 || loading"
        @click="loadChapter(index + 1)"
      >
        <span>下一章</span><ChevronRight :size="18" />
      </button>
    </footer>
    <button
      v-if="!toolbar"
      class="reader-show-controls"
      aria-label="显示阅读操作"
      @click="toolbar = true"
    >
      <SlidersHorizontal :size="18" />
    </button>
    <div v-if="panel" class="sheet-backdrop" @click.self="panel = null">
      <section
        class="reader-sheet"
        role="dialog"
        aria-modal="true"
        :aria-label="panelNames[panel]"
      >
        <div class="sheet-heading">
          <h2>{{ panelNames[panel] }}</h2>
          <button
            class="icon-button"
            aria-label="关闭面板"
            @click="panel = null"
          >
            <X :size="20" />
          </button>
        </div>
        <template v-if="panel === 'settings'">
          <div class="setting-row">
            <label for="font-size">文字大小</label
            ><span>{{ settings.fontSize }} px</span>
          </div>
          <input
            id="font-size"
            v-model.number="state.settings.fontSize"
            type="range"
            min="16"
            max="30"
            step="1"
            @change="updateSettings"
          />
          <div class="setting-row">
            <label for="line-height">行间距</label
            ><span>{{ settings.lineHeight.toFixed(1) }}</span>
          </div>
          <input
            id="line-height"
            v-model.number="state.settings.lineHeight"
            type="range"
            min="1.5"
            max="2.5"
            step="0.1"
            @change="updateSettings"
          />
          <div class="setting-row"><span>阅读背景</span></div>
          <div class="theme-options">
            <button
              v-for="theme in ['paper', 'white', 'night'] as const"
              :key="theme"
              :class="[
                'theme-option',
                theme,
                { selected: settings.theme === theme },
              ]"
              @click="
                state.settings.theme = theme;
                updateSettings();
              "
            >
              <BookOpen v-if="theme === 'paper'" :size="18" /><Sun
                v-else-if="theme === 'white'"
                :size="18"
              /><Moon v-else :size="18" />
              {{ { paper: "纸色", white: "明亮", night: "夜读" }[theme]
              }}<Check v-if="settings.theme === theme" :size="15" />
            </button>
          </div>
        </template>
        <template v-else-if="panel === 'listen'">
          <div v-if="tts.model.phase === 'ready'" class="listen-start">
            <p>从当前段落开始朗读，自动接续已下载的下一章。</p>
            <button class="button" :disabled="tts.busy || loading || !!failure || !tts.supported" @click="ttsAction(listenHere)"><Headphones :size="18" />从这里开始听书</button>
          </div>
          <ListeningSettings />
        </template>
        <div v-else class="toc-list">
          <button
            v-for="(item, i) in book.chapters"
            :key="item.id"
            :class="{
              active: i === index,
              unavailable: !book.downloaded.includes(item.id),
            }"
            :disabled="!book.downloaded.includes(item.id)"
            @click="loadChapter(i)"
          >
            <span>{{ item.title }}</span
            ><Check v-if="i === index" :size="17" /><span
              v-else-if="!book.downloaded.includes(item.id)"
              class="muted"
              >未下载</span
            >
          </button>
        </div>
      </section>
    </div>
  </section>
</template>
