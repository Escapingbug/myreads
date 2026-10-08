<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { Book, SourceBook, InstalledSource, ReadingHistory, Page } from "../types";
import { state, addDownload, resumeBook, cancelCatalogue } from "../services/library";
import { storage } from "../services/storage";
import { SourceRuntime } from "../services/runtime";
import { tts, startListening, controlListening, saveTtsOptions, listeningMessage } from "../services/tts";
import { accessibility, announce, cancelAnnouncements, recognizeSearch, cancelRecognition, rememberSearch, disableAccessibility } from "../services/accessibility";
import { ExplorationController } from "../services/accessibility-input";

type Screen = "home" | "books" | "find" | "query" | "searches" | "player" | "more" | "chapters" | "speed" | "exit" | "help";
interface Entry { book: SourceBook; sourceId: string; history?: ReadingHistory }
const root = ref<HTMLElement>();
const screen = ref<Screen>("home"), listLabel = ref("我的书架");
const entries = ref<Entry[]>([]), index = ref(0), chapterIndex = ref(0), searchIndex = ref(0);
const query = ref(""), selectedSourceId = ref("");
const busy = ref(false), message = ref(""), selectedAction = ref("");
const playingBook = computed(() => state.books.find((book) => book.localId === tts.playback.bookId));
const item = computed(() => entries.value[index.value]);
const localBook = computed(() => item.value && state.books.find((book) => book.id === item.value!.book.id && book.sourceId === item.value!.sourceId));
const currentBook = computed(() => screen.value === "player" || screen.value === "speed" ? playingBook.value : localBook.value);
const sources = computed(() => state.sources.filter((source) => source.enabled));
const source = computed(() => sources.value.find((source) => source.manifest.id === selectedSourceId.value) ?? sources.value[0]);
const recent = computed(() => [...state.history].sort((a, b) => b.updatedAt - a.updatedAt)[0]);
const playbackIntended = computed(() => accessibility.interruptedPlayback || ["playing", "loading", "buffering"].includes(tts.playback.phase));
const title = computed(() => ({ home: "语音听书", books: listLabel.value, find: "找新书", query: "确认搜索", searches: "最近搜索", player: "正在听书", more: "更多操作", chapters: "选择章节", speed: "播放速度", exit: "退出无障碍模式", help: "操作帮助" })[screen.value]);
const chapter = computed(() => localBook.value?.chapters[chapterIndex.value]);
const summary = computed(() => {
  if (screen.value === "home") return recent.value ? `上次读听：${recent.value.book.title}，${recent.value.chapterTitle}` : "选择书架中的书，或者找一本新书";
  if (screen.value === "books" || screen.value === "more") return bookSummary();
  if (screen.value === "player") return `${tts.playback.title ?? "没有正在听的书"}。${listeningMessage()}。`;
  if (screen.value === "find") return `当前书源：${source.value?.manifest.name ?? "没有启用的书源"}。说书名或作者，也可以浏览未听过的书。`;
  if (screen.value === "query") return `识别到：${query.value}。请确认搜索，或重新说。`;
  if (screen.value === "searches") return accessibility.searches.length ? `第${searchIndex.value + 1}项，共${accessibility.searches.length}项。${accessibility.searches[searchIndex.value]}` : "还没有搜索记录";
  if (screen.value === "chapters") return chapter.value ? `第${chapterIndex.value + 1}章。${chapter.value.title}。${localBook.value?.downloaded.includes(chapter.value.id) ? "已下载" : "尚未下载"}` : "没有可用章节";
  if (screen.value === "speed") return `当前语速 ${tts.speed} 倍。`;
  if (screen.value === "help") return "移动手指听按钮名称，抬手选中，任意位置双击执行。左下角返回，右下角帮助。";
  return "确认退出后恢复普通界面。下次仍可由协助者在设置中开启。";
});
const primaryLabel = computed(() => {
  if (!item.value) return "重听当前状态";
  if (!localBook.value) return item.value.history ? "重新下载这本" : "下载这本";
  const book = localBook.value;
  const target = book.chapters.find((entry) => entry.id === book.progress?.chapterId) ?? book.chapters[0];
  if (target && book.downloaded.includes(target.id)) return "听这本";
  return ["downloading", "queued"].includes(book.state) ? "听下载状态" : "继续下载";
});
let runtime: SourceRuntime | undefined, controller: AbortController | undefined, nextCursor: string | undefined;
let operation = 0;
let touchInput = false, pauseOnToggle: boolean | undefined;
let helpReturn: Screen = "home";
function bookSummary() {
  if (!item.value) return `${listLabel.value}里还没有书，可以返回找新书。`;
  const book = item.value.book, local = localBook.value, history = item.value.history;
  const progressChapter = local?.chapters.find((chapter) => chapter.id === local.progress?.chapterId);
  const progress = progressChapter ? `上次读听到${progressChapter.title}` : history ? `上次读听到${history.chapterTitle}` : "尚未读听";
  const time = history ? `，${new Date(history.updatedAt).toLocaleDateString("zh-CN")}读听过` : "";
  return `第${index.value + 1}本，当前列表共${entries.value.length}本。${book.title}，${book.author || "作者未知"}。${progress}${time}。${local ? `已下载${local.downloaded.length}章` : history ? "本地内容已移除，可以重新下载" : "可以听简介或下载"}。`;
}
function say(text: string, resume = false) { message.value = text; return announce(text, resume); }
function clearSearch() { ++operation; controller?.abort(); runtime?.dispose(); runtime = undefined; busy.value = false; cancelCatalogue(); }
function go(next: Screen) {
  explorer.reset(); selectedAction.value = ""; screen.value = next; message.value = "";
  void say(`${title.value}。${summary.value}`);
}
function showBooks(kind: "shelf" | "history" | "unread") {
  clearSearch(); nextCursor = undefined; index.value = 0;
  listLabel.value = kind === "history" ? "最近读听" : kind === "unread" ? "未听过的书" : "我的书架";
  entries.value = kind === "history"
    ? [...state.history].sort((a, b) => b.updatedAt - a.updatedAt).map((history) => ({ book: history.book, sourceId: history.sourceId, history }))
    : state.books.filter((book) => kind !== "unread" || !book.progress).map((book) => ({ book: { ...book }, sourceId: book.sourceId }));
  go("books");
}
async function run(action: () => Promise<void>) {
  if (busy.value) { await say("正在处理，可以返回取消等待。"); return; }
  const token = ++operation; busy.value = true;
  try { await cancelAnnouncements(); if (token === operation) await action(); }
  catch (error) { if (token === operation) await say((error as Error).message || "操作未完成，请重试"); }
  finally { if (token === operation) busy.value = false; }
}
async function startBook(book: Book, target?: number, paragraph?: number) {
  const token = operation;
  const ch = target ?? Math.max(0, book.chapters.findIndex((chapter) => chapter.id === book.progress?.chapterId));
  if (!book.downloaded.includes(book.chapters[ch]?.id ?? "")) { await say("要听的章节尚未下载，请先继续下载。"); return; }
  await say("正在准备声音，可以返回取消等待。");
  if (token !== operation) return;
  if (target === undefined && tts.playback.bookId === book.localId && tts.playback.phase === "paused") await controlListening("resume");
  else await startListening(book, ch, paragraph ?? book.progress?.paragraph ?? 0);
  if (token === operation) { explorer.reset(); selectedAction.value = ""; screen.value = "player"; message.value = ""; }
}
async function chooseBook() {
  const token = operation;
  if (!item.value) { await say(summary.value); return; }
  if (localBook.value) {
    const book = localBook.value;
    const ch = Math.max(0, book.chapters.findIndex((entry) => entry.id === book.progress?.chapterId));
    if (book.downloaded.includes(book.chapters[ch]?.id ?? "")) { await startBook(book); return; }
    if (["downloading", "queued"].includes(book.state)) { await say(`正在下载${book.title}，已下载${book.downloaded.length}章。`); return; }
    await resumeBook(book); await say("已继续下载，请稍后再选择听这本。"); return;
  }
  const installed = state.sources.find((entry) => entry.manifest.id === item.value!.sourceId);
  if (!installed) { await say("原书源已移除，请由协助者重新安装书源后下载。"); return; }
  await say("正在获取章节并下载，请稍候。");
  if (token !== operation) return;
  const book = await addDownload(installed, item.value.book);
  if (token === operation) await say(`已加入书架，正在下载${book.title}。历史书籍将先下载上次读听的章节。`);
}
async function search(more = false) {
  const installed = source.value;
  if (!installed) throw new Error("没有启用的书源，请由协助者先配置书源");
  const word = query.value.trim(); if (!word) throw new Error("请先说出书名或作者");
  const token = operation; controller?.abort(); runtime?.dispose();
  controller = new AbortController(); const current = new SourceRuntime(installed); runtime = current;
  await say(more ? "正在获取更多结果。" : `正在搜索${word}。`);
  if (token !== operation) return;
  try {
    const page = await current.call<Page<SourceBook>>("search", [word, more ? nextCursor : undefined], controller.signal);
    if (token !== operation) return;
    if (more && page.nextCursor && page.nextCursor === nextCursor) throw new Error("书源分页未前进，请换书源重试");
    const fresh = page.items.filter((book) => !more || !entries.value.some((entry) => entry.sourceId === installed.manifest.id && entry.book.id === book.id));
    const oldCount = more ? entries.value.length : 0;
    entries.value = [...(more ? entries.value : []), ...fresh.map((book) => ({ book, sourceId: installed.manifest.id }))];
    index.value = more ? Math.min(oldCount, Math.max(0, entries.value.length - 1)) : 0;
    nextCursor = page.nextCursor; listLabel.value = "搜索结果";
    await rememberSearch(word); go("books");
  } finally { current.dispose(); if (runtime === current) runtime = undefined; }
}
async function dictate() {
  const token = operation;
  const text = await recognizeSearch();
  if (token === operation) { query.value = text; go("query"); }
}
async function moveBook(delta: number) {
  if (busy.value) { await say("正在处理，请稍候或返回。"); return; }
  const next = index.value + delta;
  if (next < 0) { await say("已经是第一本。"); return; }
  if (next >= entries.value.length) {
    if (nextCursor) { await run(() => search(true)); return; }
    await say("已经是最后一本。"); return;
  }
  index.value = next; await say(bookSummary());
}
async function moveParagraph(delta: number) {
  const token = operation;
  const book = playingBook.value; if (!book) throw new Error("请先选择一本书");
  let ch = tts.playback.chapter ?? 0, p = (tts.playback.paragraph ?? 0) + delta;
  const content = await storage.chapter(book.localId, ch);
  if (token !== operation) return;
  if (!content) throw new Error("本地章节缺失，请重新下载");
  if (p < 0) {
    if (ch === 0) { await say("已经是第一段。", true); return; }
    ch--; const previous = await storage.chapter(book.localId, ch);
    if (token !== operation) return;
    if (!previous || !book.downloaded.includes(book.chapters[ch]!.id)) throw new Error("上一章尚未下载");
    p = Math.max(0, previous.paragraphs.length - 1);
  } else if (p >= content.paragraphs.length) { ch++; p = 0; }
  if (ch >= book.chapters.length) { await say("已经是最后一段。", true); return; }
  await startBook(book, ch, p);
}
function back() {
  clearSearch(); void cancelRecognition(); void cancelAnnouncements();
  if (screen.value === "home") { void say("当前已经是语音听书首页。"); return; }
  if (screen.value === "help") go(helpReturn);
  else if (["more", "chapters"].includes(screen.value)) go("books");
  else if (screen.value === "speed") go("player");
  else if (["query", "searches"].includes(screen.value)) go("find");
  else go("home");
}
defineExpose({ back });
const actions: Record<string, () => void | Promise<void>> = {
  repeat: () => say(summary.value, screen.value === "player"),
  continue: () => {
    if (playingBook.value && !["idle", "completed", "error"].includes(tts.playback.phase)) return run(() => startBook(playingBook.value!));
    if (!recent.value) return say("还没有读听记录，可以在书架选择一本书。");
    entries.value = [{ book: recent.value.book, sourceId: recent.value.sourceId, history: recent.value }]; index.value = 0; listLabel.value = "继续上次";
    if (localBook.value) return run(() => startBook(localBook.value!));
    go("books");
  },
  history: () => showBooks("history"), shelf: () => showBooks("shelf"), unread: () => showBooks("unread"),
  find: () => { clearSearch(); nextCursor = undefined; go("find"); },
  previous: () => moveBook(-1), next: () => moveBook(1), choose: () => run(chooseBook),
  more: () => go("more"), back,
  description: () => say(item.value?.book.description?.slice(0, 2000) || "这本书没有简介。"),
  chapters: () => { chapterIndex.value = Math.max(0, localBook.value?.chapters.findIndex((chapter) => chapter.id === localBook.value?.progress?.chapterId) ?? 0); go("chapters"); },
  chapterPrevious: () => { if (chapterIndex.value > 0) chapterIndex.value--; return say(summary.value); },
  chapterNext: () => { if (chapterIndex.value < (localBook.value?.chapters.length ?? 0) - 1) chapterIndex.value++; return say(summary.value); },
  chapterPlay: () => run(async () => { if (localBook.value) await startBook(localBook.value, chapterIndex.value, 0); else await say("请先下载这本书。"); }),
  dictate: () => run(dictate), search: () => run(() => search()),
  searches: () => { searchIndex.value = 0; go("searches"); },
  searchPrevious: () => { if (searchIndex.value > 0) searchIndex.value--; return say(summary.value); },
  searchNext: () => { if (searchIndex.value < accessibility.searches.length - 1) searchIndex.value++; return say(summary.value); },
  reuseSearch: () => { query.value = accessibility.searches[searchIndex.value] ?? ""; if (query.value) go("query"); else return say("还没有搜索记录。"); },
  source: () => { const i = sources.value.findIndex((entry) => entry.manifest.id === source.value?.manifest.id); selectedSourceId.value = sources.value[(i + 1) % Math.max(1, sources.value.length)]?.manifest.id ?? ""; return say(summary.value); },
  toggle: () => run(async () => {
    const pause = pauseOnToggle ?? playbackIntended.value; pauseOnToggle = undefined;
    if (!playingBook.value) { await say("请先从书架选择一本书。"); return; }
    if (pause) { await controlListening("pause"); await say("已暂停，进度已保存。"); }
    else if (tts.playback.phase === "paused") { const token = operation; await say("正在继续播放这本书。"); if (token === operation) await controlListening("resume"); }
    else await startBook(playingBook.value);
  }),
  paragraphPrevious: () => run(() => moveParagraph(-1)), paragraphNext: () => run(() => moveParagraph(1)),
  speed: () => go("speed"),
  slower: () => run(async () => { tts.speed = Math.max(0.5, tts.speed - 0.25); await saveTtsOptions(); await say(summary.value); }),
  faster: () => run(async () => { tts.speed = Math.min(2, tts.speed + 0.25); await saveTtsOptions(); await say(summary.value); }),
  exit: () => go("exit"), cancelExit: back,
  confirmExit: () => run(async () => { await say("正在退出无障碍模式。"); await disableAccessibility(); }),
  help: () => { if (screen.value !== "help") helpReturn = screen.value; go("help"); },
  instructions: () => say("移动手指听按钮名称，抬手选中。在屏幕任意位置点按两次执行。左下角返回，右下角帮助。书籍一次显示一本，上一本和下一本用于选择。"),
};
function targetAt(x: number, y: number) { return document.elementFromPoint(x, y)?.closest<HTMLElement>("[data-action]")?.dataset.action ?? ""; }
function element(id: string) { return root.value?.querySelector<HTMLElement>(`[data-action="${id}"]`); }
function describe(id: string) {
  selectedAction.value = id;
  const label = element(id)?.dataset.speech ?? element(id)?.textContent ?? "";
  navigator.vibrate?.(25); void say(`这是${label.trim()}按钮。`, screen.value === "player");
}
function activate(id: string) {
  if (id === "toggle") pauseOnToggle = playbackIntended.value;
  const token = operation;
  void cancelAnnouncements().then(() => { if (token === operation) return actions[id]?.(); });
}
const explorer = new ExplorationController(describe, activate);
function pointerDown(event: PointerEvent) {
  touchInput = event.pointerType !== "mouse";
  if (!event.isPrimary || event.pointerType === "mouse") return;
  event.preventDefault(); root.value?.setPointerCapture(event.pointerId);
  explorer.down(targetAt(event.clientX, event.clientY), event.clientX, event.clientY, performance.now());
}
function pointerMove(event: PointerEvent) {
  if (!event.isPrimary || event.pointerType === "mouse" || !event.buttons) return;
  explorer.move(targetAt(event.clientX, event.clientY), event.clientX, event.clientY);
}
function pointerUp(event: PointerEvent) {
  if (!event.isPrimary || event.pointerType === "mouse") return;
  event.preventDefault(); explorer.up(performance.now());
}
function click(event: MouseEvent) {
  if (event.detail === 0 || !touchInput) {
    const id = (event.target as HTMLElement).closest<HTMLElement>("[data-action]")?.dataset.action;
    if (id) activate(id);
  }
}
watch(screen, () => explorer.reset());
watch(selectedAction, async (id) => {
  await nextTick();
  root.value?.querySelectorAll<HTMLElement>("[data-action]").forEach((button) => button.classList.toggle("selected", button.dataset.action === id));
});
watch(() => tts.playback.error, (error) => { if (error) void say(error); });
watch(() => state.error, (error) => { if (error) void say(`本地保存失败：${error}`); });
watch(() => accessibility.active, (active) => { if (!active) back(); else void say(`${title.value}。${summary.value}`); });
onMounted(() => { void say(`语音听书首页。${summary.value}`); });
onBeforeUnmount(() => { explorer.reset(); clearSearch(); void cancelAnnouncements(); void cancelRecognition(); });
</script>

<template>
  <main ref="root" class="accessible-mode" :class="{ 'is-speaking': accessibility.speaking }"
    @pointerdown="pointerDown" @pointermove="pointerMove" @pointerup="pointerUp" @pointercancel="explorer.reset()" @click.prevent="click">
    <h1>{{ title }}</h1>
    <button class="a11y-summary" data-action="repeat" data-speech="重听当前书籍和状态" :class="{ selected: selectedAction === 'repeat' }">
      {{ summary }}
    </button>
    <div class="a11y-controls">
      <template v-if="screen === 'home'">
        <button class="a11y-primary" data-action="continue">继续上次</button>
        <div class="a11y-row"><button data-action="history">最近读听</button><button data-action="shelf">我的书架</button></div>
        <button data-action="find">找新书</button>
      </template>
      <template v-else-if="screen === 'books'">
        <div class="a11y-row"><button data-action="previous">上一本</button><button data-action="next">下一本</button></div>
        <button class="a11y-primary" data-action="choose">{{ primaryLabel }}</button>
        <button data-action="more">更多：简介与章节</button>
      </template>
      <template v-else-if="screen === 'more'">
        <button class="a11y-primary" data-action="description">听简介</button>
        <button data-action="chapters">选择章节</button>
        <button data-action="choose">{{ primaryLabel }}</button>
      </template>
      <template v-else-if="screen === 'find'">
        <button class="a11y-primary" data-action="dictate">说书名或作者</button>
        <div class="a11y-row"><button data-action="searches">最近搜索</button><button data-action="unread">未听过的书</button></div>
        <button data-action="source">换一个书源</button>
      </template>
      <template v-else-if="screen === 'query'">
        <button class="a11y-primary" data-action="search">确认并搜索</button>
        <button data-action="dictate">重新说一遍</button>
      </template>
      <template v-else-if="screen === 'searches'">
        <div class="a11y-row"><button data-action="searchPrevious">上一项</button><button data-action="searchNext">下一项</button></div>
        <button class="a11y-primary" data-action="reuseSearch">使用这个搜索</button>
      </template>
      <template v-else-if="screen === 'chapters'">
        <div class="a11y-row"><button data-action="chapterPrevious">上一章</button><button data-action="chapterNext">下一章</button></div>
        <button class="a11y-primary" data-action="chapterPlay">从本章开始听</button>
      </template>
      <template v-else-if="screen === 'player'">
        <button class="a11y-primary" data-action="toggle">{{ playbackIntended ? '暂停播放' : tts.playback.phase === 'paused' ? '继续播放' : '重新开始' }}</button>
        <div class="a11y-row"><button data-action="paragraphPrevious">上一段</button><button data-action="paragraphNext">下一段</button></div>
        <button data-action="speed">调整语速</button>
      </template>
      <template v-else-if="screen === 'speed'">
        <div class="a11y-row"><button data-action="slower">慢一点</button><button data-action="faster">快一点</button></div>
        <button class="a11y-primary" data-action="toggle">{{ playbackIntended ? '暂停播放' : '继续播放' }}</button>
      </template>
      <template v-else-if="screen === 'exit'">
        <button data-action="cancelExit">取消，留在此模式</button>
        <button class="a11y-primary" data-action="confirmExit">确认退出</button>
      </template>
      <template v-else-if="screen === 'help'">
        <button class="a11y-primary" data-action="instructions">重听操作说明</button>
        <button data-action="exit">退出此模式</button>
      </template>
    </div>
    <div class="a11y-feedback" role="status">{{ accessibility.recording ? '正在听，请说书名或作者…' : accessibility.speaking ? '正在准备或播报提示…' : busy ? '正在处理，可返回取消等待' : accessibility.error || message || '触摸听说明，双击执行' }}</div>
    <footer class="a11y-row"><button data-action="back">返回</button><button data-action="help">操作帮助</button></footer>
  </main>
</template>
