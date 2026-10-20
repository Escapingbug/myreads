<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { Book, SourceBook, ReadingHistory } from "../types";
import { state, addDownload, resumeBook, cancelCatalogue, prepareListeningBook } from "../services/library";
import { storage } from "../services/storage";
import { searchSource, searchSourceId } from "../services/search";
import { tts, startListening, controlListening, saveTtsOptions, listeningMessage } from "../services/tts";
import { accessibility, announce, cancelAnnouncements, recognizeSearch, cancelRecognition, rememberSearch, disableAccessibility } from "../services/accessibility";
import { DirectGestureController } from "../services/accessibility-input";
import { SourceRuntime } from "../services/runtime";
import { fitAccessibleLabels } from "../services/accessibility-layout";
import { loadRecommendations, nextRecommendation, rememberRecommendationDetails, type Recommendation } from "../services/recommendations";

type Screen = "home" | "recommendations" | "advanced" | "playerOptions" | "books" | "find" | "query" | "results" | "searches" | "player" | "more" | "chapters" | "speed" | "exit" | "help";
interface Entry { book: SourceBook; sourceId: string; history?: ReadingHistory }
const root = ref<HTMLElement>();
let layoutObserver: ResizeObserver | undefined, layoutFrame: number | undefined;
function fitLayout() {
  if (layoutFrame !== undefined) cancelAnimationFrame(layoutFrame);
  layoutFrame = requestAnimationFrame(() => {
    layoutFrame = undefined;
    if (root.value) fitAccessibleLabels(root.value);
  });
}
const screen = ref<Screen>("home"), listLabel = ref("我的书架");
const recommendations = ref<Recommendation[]>([]), recommended = ref<Recommendation>();
const recommendationError = ref(""), recommendationStatus = ref("");
const preparingTitle = ref(""), simpleError = ref("");
const lastListening = ref<Recommendation>();
let recommendationController: AbortController | undefined, listeningController: AbortController | undefined;
const entries = ref<Entry[]>([]), index = ref(0), chapterIndex = ref(0), searchIndex = ref(0);
const query = ref("");
const selectedSourceId = searchSourceId;
const searchError = ref(""), searchedWord = ref(""), searchedSource = ref("");
const searchPending = ref(false);
const resultPage = ref(0);
const resultEntries = computed(() => entries.value.slice(resultPage.value * 2, resultPage.value * 2 + 2));
const hasMoreResults = computed(() => (resultPage.value + 1) * 2 < entries.value.length || !!nextCursor.value);
const busy = ref(false), message = ref(""), selectedAction = ref("");
const playingBook = computed(() => state.books.find((book) => book.localId === tts.playback.bookId));
const item = computed(() => entries.value[index.value]);
const localBook = computed(() => item.value && state.books.find((book) => book.id === item.value!.book.id && book.sourceId === item.value!.sourceId));
const currentBook = computed(() => screen.value === "player" || screen.value === "speed" ? playingBook.value : localBook.value);
const sources = computed(() => state.sources.filter((source) => source.enabled));
const source = computed(() => sources.value.find((source) => source.manifest.id === selectedSourceId.value) ?? sources.value[0]);
const recent = computed(() => [...state.history].sort((a, b) => b.updatedAt - a.updatedAt)[0]);
const playbackIntended = computed(() => accessibility.interruptedPlayback || ["playing", "loading", "buffering"].includes(tts.playback.phase));
const title = computed(() => ({ home: "语音听书", recommendations: "听听推荐", advanced: "更多功能", playerOptions: "播放设置", books: listLabel.value, find: "搜索书籍", query: "确认搜索", results: "搜索结果", searches: "最近搜索", player: "正在听书", more: "更多操作", chapters: "选择章节", speed: "播放速度", exit: "退出无障碍模式", help: "操作帮助" })[screen.value]);
const chapter = computed(() => localBook.value?.chapters[chapterIndex.value]);
const summary = computed(() => {
  if (preparingTitle.value) return `正在准备《${preparingTitle.value}》，准备好就开始听。不用操作下载，可以换一本或返回取消。`;
  if (screen.value === "home") return simpleError.value || (lastListening.value ? `上次在听《${lastListening.value.book.title}》。点继续上次接着听，想换个故事就点听听推荐。` : "不知道听什么？点听听推荐，感兴趣就听，不喜欢就换一本。");
  if (screen.value === "recommendations") return recommendationError.value || simpleError.value || (recommended.value
    ? `${recommended.value.book.title}，${recommended.value.book.author || "作者未知"}。${recommended.value.book.description || "点听简介了解这本书，或者直接开始听。"}` : "正在找推荐书籍，请稍候。");
  if (screen.value === "advanced") return "书架、搜索和播放设置都在这里。不需要这些功能时，返回就能继续听书或看推荐。";
  if (screen.value === "playerOptions") return "可以换段、选章节或调整语速，返回继续听书。";
  if (screen.value === "books" || screen.value === "more") return bookSummary();
  if (screen.value === "player") return `${tts.playback.title || lastListening.value?.book.title || "没有正在听的书"}。${listeningMessage()}。`;
  if (screen.value === "find" || screen.value === "query") return searchError.value || (searchPending.value ? `正在搜索：${query.value}，可以返回取消。` : "输入书名或作者，点击搜索。也可以点击语音输入，说完后检查文字再搜索。");
  if (screen.value === "results") return searchError.value || `“${searchedWord.value}” · ${searchedSource.value}。${entries.value.length ? `已找到 ${entries.value.length} 本，点击书名查看。` : "没有找到相关书籍，请修改关键词或选择其他书源。"}`;
  if (screen.value === "searches") return accessibility.searches.length ? `第${searchIndex.value + 1}项，共${accessibility.searches.length}项。${accessibility.searches[searchIndex.value]}` : "还没有搜索记录";
  if (screen.value === "chapters") return chapter.value ? `第${chapterIndex.value + 1}章。${chapter.value.title}。${localBook.value?.downloaded.includes(chapter.value.id) ? "已下载" : "尚未下载"}` : "没有可用章节";
  if (screen.value === "speed") return `当前语速 ${tts.speed} 倍。`;
  if (screen.value === "help") return "进入后会继续上次的书。想换个故事就点换一本，推荐会介绍书名和简介。喜欢就点就听这本，不喜欢再换一本。搜索、书架和章节在更多功能里。";
  return "单击确认退出后恢复普通界面，书籍和阅读进度保留。下次仍可由协助者在设置中开启。";
});
const primaryLabel = computed(() => {
  if (!item.value) return "重听当前状态";
  if (!localBook.value) return item.value.history ? "重新下载这本" : "下载这本";
  const book = localBook.value;
  const target = book.chapters.find((entry) => entry.id === book.progress?.chapterId) ?? book.chapters[0];
  if (target && book.downloaded.includes(target.id)) return "听这本";
  return ["downloading", "queued"].includes(book.state) ? "听下载状态" : "继续下载";
});
let controller: AbortController | undefined;
const nextCursor = ref<string>();
let operation = 0;
let touchInput = false, pointerId: number | undefined, pauseOnToggle: boolean | undefined;
let helpReturn: Screen = "home", exitReturn: Screen = "home";
let advancedReturn: Screen = "home";
let chapterReturn: Screen = "books";
const screenInstructions = computed(() => {
  if (screen.value === "home") return "点继续上次接着听，点听听推荐换个故事。其他操作在更多功能里。";
  if (screen.value === "recommendations") return "感兴趣点就听这本，不喜欢点换一本。点听简介可以听完整介绍。";
  if (screen.value === "books") return `左滑下一本，右滑上一本。单击中间${primaryLabel.value}，长按听简介。`;
  if (screen.value === "searches") return "左右滑动切换搜索记录，单击中间使用当前搜索。";
  if (screen.value === "chapters") return "左右滑动切换章节，单击中间从本章开始听。";
  if (screen.value === "player") return "点中间暂停或继续。不想听这本就点换一本。搜索、书架和章节在更多功能里。";
  if (screen.value === "results") return `${resultEntries.value.map((entry) => `${entry.book.title}，${entry.book.author || "作者未知"}`).join("。")}。点击书名查看，下一页查看更多，也可以左右滑动翻页。返回可修改关键词或书源。`;
  if (screen.value === "find" || screen.value === "query") return "点击搜索框输入书名或作者，再点击搜索。语音输入是可选的，识别后可以修改文字。";
  return "";
});
const gestureHint = computed(() => screen.value === "books" ? "左滑下一本 · 右滑上一本 · 长按简介"
  : screen.value === "recommendations" ? recommendationStatus.value || "喜欢就听这本 · 不喜欢换一本"
  : screen.value === "player" ? "点暂停或继续 · 换一本听新故事"
  : ["searches", "chapters"].includes(screen.value) ? "左右滑动切换 · 单击执行 · 长按说明"
  : "单击执行 · 长按听说明");
function bookSummary() {
  if (!item.value) return `${listLabel.value}里还没有书，可以返回找新书。`;
  const book = item.value.book, local = localBook.value, history = item.value.history;
  const progressChapter = local?.chapters.find((chapter) => chapter.id === local.progress?.chapterId);
  const progress = progressChapter ? `上次读听到${progressChapter.title}` : history ? `上次读听到${history.chapterTitle}` : "尚未读听";
  const time = history ? `，${new Date(history.updatedAt).toLocaleDateString("zh-CN")}读听过` : "";
  return `${book.title}，${book.author || "作者未知"}。第${index.value + 1}本，共${entries.value.length}本。${progress}${time}。${local ? `已下载${local.downloaded.length}章` : history ? "本地内容已移除，可以重新下载" : "可以听简介或下载"}。`;
}
function say(text: string, resume = false) { message.value = text; return announce(text, resume); }
function clearSearch() {
  ++operation; controller?.abort(); recommendationController?.abort(); listeningController?.abort();
  preparingTitle.value = ""; searchPending.value = false; busy.value = false; cancelCatalogue();
}
function go(next: Screen) {
  gestures.reset(); selectedAction.value = ""; screen.value = next; message.value = "";
  void say(`${title.value}。${summary.value}。${screenInstructions.value}`);
}
function showBooks(kind: "shelf" | "history" | "unread") {
  clearSearch(); nextCursor.value = undefined; index.value = 0;
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
  catch (error) {
    if (token === operation) {
      simpleError.value = (error as Error).message || "操作未完成，请重试";
      void say(simpleError.value);
    }
  }
  finally { if (token === operation) { busy.value = false; preparingTitle.value = ""; } }
}
async function startBook(book: Book, target?: number, paragraph?: number) {
  const token = operation;
  const ch = target ?? Math.max(0, book.chapters.findIndex((chapter) => chapter.id === book.progress?.chapterId));
  if (!book.downloaded.includes(book.chapters[ch]?.id ?? "")) { await say("要听的章节尚未下载，请先继续下载。"); return; }
  message.value = "正在准备声音，可以返回取消等待。";
  if (token !== operation) return;
  if (target === undefined && tts.playback.bookId === book.localId && ["playing", "loading", "buffering"].includes(tts.playback.phase)) { /* Already playing: keep the native position. */ }
  else if (target === undefined && tts.playback.bookId === book.localId && tts.playback.phase === "paused") await controlListening("resume");
  else await startListening(book, ch, paragraph ?? book.progress?.paragraph ?? 0);
  if (token === operation) {
    lastListening.value = { sourceId: book.sourceId, book: { id: book.id, title: book.title, author: book.author, description: book.description, url: book.url } };
    void storage.put("settings:last-listening", lastListening.value).catch(() => {});
    gestures.reset(); selectedAction.value = ""; screen.value = "player"; message.value = ""; simpleError.value = "";
  }
}
async function presentRecommendation(token: number, current?: Recommendation) {
  const next = await nextRecommendation(recommendations.value, current);
  if (token !== operation) return;
  recommended.value = next;
  if (next) {
    if (!next.book.description) void describeRecommendation(next, token);
    else void say(`${next.book.title}，${next.book.author || "作者未知"}。${next.book.description.slice(0, 500)}。感兴趣点就听这本，不喜欢点换一本。`);
  }
}
async function describeRecommendation(entry: Recommendation, token: number) {
  let book = entry.book;
  if (!book.description) {
    const source = sources.value.find((source) => source.manifest.id === entry.sourceId);
    if (source) {
      recommendationController?.abort();
      const current = new AbortController(); recommendationController = current;
      const runtime = new SourceRuntime(source);
      const timeout = setTimeout(() => runtime.dispose(new Error("简介暂不可用")), 8000);
      try { book = await runtime.call<SourceBook>("getBook", [book], current.signal); }
      catch { /* The user can still listen or move on when details are offline. */ }
      finally { clearTimeout(timeout); runtime.dispose(); }
    }
  }
  if (token !== operation || recommended.value?.book.id !== entry.book.id) return;
  entry.book = book;
  void rememberRecommendationDetails(entry);
  void say(`${book.title}，${book.author || "作者未知"}。${book.description?.slice(0, 2000) || "暂时没有简介，可以直接听听正文。"}。喜欢点就听这本，不喜欢点换一本。`);
}
async function showRecommendations(change = false) {
  const previous = screen.value === "recommendations" ? recommended.value : lastListening.value;
  clearSearch(); const token = operation;
  simpleError.value = ""; recommendationError.value = "";
  screen.value = "recommendations";
  gestures.reset();
  if (["playing", "loading", "buffering"].includes(tts.playback.phase)) {
    await controlListening("pause"); if (token !== operation) return;
  }
  if (recommendations.value.length) { await presentRecommendation(token, change ? previous : undefined); return; }
  const current = new AbortController(); recommendationController = current;
  busy.value = true;
  try {
    const list = await loadRecommendations(sources.value, current.signal);
    if (token !== operation) return;
    recommendations.value = list.items;
    recommendationStatus.value = `${list.sourceName}${list.cached ? " · 已保存的推荐" : " · 推荐书籍"}`;
    await presentRecommendation(token, change ? previous : undefined);
  } catch (error) {
    if (token === operation && !current.signal.aborted) {
      recommendationError.value = `${(error as Error).message}。可以重试，或在更多功能里选书。`;
      void say(recommendationError.value);
    }
  } finally { if (token === operation) busy.value = false; }
}
async function listenEntry(entry: Recommendation) {
  const token = operation;
  listeningController = new AbortController();
  const signal = listeningController.signal;
  let book = state.books.find((book) => book.id === entry.book.id && book.sourceId === entry.sourceId);
  const chapter = book?.chapters.find((chapter) => chapter.id === book?.progress?.chapterId) ?? book?.chapters[0];
  if (!book || !chapter || !book.downloaded.includes(chapter.id)) {
    const installed = sources.value.find((source) => source.manifest.id === entry.sourceId);
    if (!installed) throw new Error("原书源暂不可用，请换一本或请协助者在更多功能中启用书源");
    preparingTitle.value = entry.book.title;
    void say(`正在准备${entry.book.title}，准备好就开始听。可以换一本或返回取消。`);
    book = await prepareListeningBook(installed, entry.book, signal);
  }
  if (token !== operation || signal.aborted) return;
  preparingTitle.value = "";
  await cancelAnnouncements();
  if (token !== operation || signal.aborted) return;
  await startBook(book);
}
function continueLast() {
  const entry = playingBook.value && !["completed", "error"].includes(tts.playback.phase)
    ? { sourceId: playingBook.value.sourceId, book: playingBook.value }
    : lastListening.value ?? (recent.value && { sourceId: recent.value.sourceId, book: recent.value.book });
  return entry ? run(() => listenEntry(entry)) : showRecommendations();
}
async function enterSimpleMode() {
  const token = operation;
  try {
    const saved = await storage.get<Recommendation>("settings:last-listening");
    if (token !== operation) return;
    if (saved?.sourceId && saved.book?.id && saved.book?.title) lastListening.value = saved;
    else if (recent.value && state.books.some((book) => book.id === recent.value!.book.id && book.sourceId === recent.value!.sourceId))
      lastListening.value = { book: recent.value.book, sourceId: recent.value.sourceId };
  } catch { /* Existing local history is still available. */ }
  if (token !== operation) return;
  if (!accessibility.active || document.hidden) return;
  if (tts.playback.phase === "completed" && playingBook.value && tts.playback.chapter === playingBook.value.chapters.length - 1) {
    await showRecommendations(true); return;
  }
  if (lastListening.value || playingBook.value) await continueLast();
  else await showRecommendations();
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
  const word = more ? searchedWord.value : query.value.trim();
  if (!word) { searchError.value = "请输入书名或作者"; void say(searchError.value); return; }
  const token = operation; controller?.abort();
  const current = new AbortController(); controller = current;
  searchPending.value = true;
  searchError.value = "";
  if (!more) {
    searchedWord.value = word; searchedSource.value = installed.manifest.name;
    entries.value = []; index.value = 0; resultPage.value = 0; nextCursor.value = undefined;
  }
  root.value?.querySelector<HTMLInputElement>("input")?.blur();
  // Search must not wait for local speech synthesis or history persistence.
  void say(more ? "正在获取更多结果。" : `正在搜索${word}。`);
  try {
    const page = await searchSource(installed, word, more ? nextCursor.value : undefined, current.signal);
    if (token !== operation) return;
    const fresh = page.items.filter((book) => !more || !entries.value.some((entry) => entry.sourceId === installed.manifest.id && entry.book.id === book.id));
    const oldCount = more ? entries.value.length : 0;
    entries.value = [...(more ? entries.value : []), ...fresh.map((book) => ({ book, sourceId: installed.manifest.id }))];
    index.value = more ? Math.min(oldCount, Math.max(0, entries.value.length - 1)) : 0;
    nextCursor.value = page.nextCursor; listLabel.value = "搜索结果";
    if (more) resultPage.value = Math.floor(index.value / 2);
    go("results");
    void rememberSearch(word).catch(() => {});
  } catch (error) {
    if (token !== operation || current.signal.aborted) return;
    searchError.value = (error as Error).message || "搜索未完成，请重试";
    void say(searchError.value);
  } finally { if (controller === current) { controller = undefined; searchPending.value = false; } }
}
function chooseResult(result: number) {
  const target = resultPage.value * 2 + result;
  if (!entries.value[target] || busy.value) return;
  index.value = target; go("books");
}
async function moveResults(delta: number) {
  if (busy.value) return;
  const next = resultPage.value + delta;
  if (next < 0) return;
  if (next * 2 >= entries.value.length) {
    if (nextCursor.value) await run(() => search(true));
  } else { resultPage.value = next; void say(`第${next + 1}页。${resultEntries.value.map((entry) => `${entry.book.title}，${entry.book.author || "作者未知"}`).join("。")}`); }
}
async function dictate() {
  const token = operation;
  searchError.value = "";
  try {
    const text = await recognizeSearch();
    if (token === operation) { query.value = text; go("query"); }
  } catch (error) {
    if (token !== operation) return;
    searchError.value = `${(error as Error).message || "语音输入未完成"}，可以在搜索框直接输入。`;
    void say(searchError.value);
  }
}
async function moveBook(delta: number) {
  if (busy.value) { await say("正在处理，请稍候或返回。"); return; }
  const next = index.value + delta;
  if (next < 0) { await say("已经是第一本。"); return; }
  if (next >= entries.value.length) {
    if (nextCursor.value) { await run(() => search(true)); return; }
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
  else if (screen.value === "exit") go(exitReturn);
  else if (screen.value === "more") go("books");
  else if (screen.value === "chapters") go(chapterReturn);
  else if (screen.value === "speed") go("playerOptions");
  else if (screen.value === "playerOptions") go("player");
  else if (screen.value === "advanced") go(advancedReturn);
  else if (screen.value === "books" && listLabel.value === "搜索结果") { resultPage.value = Math.floor(index.value / 2); go("results"); }
  else if (["query", "results", "searches"].includes(screen.value)) go("find");
  else go("home");
}
defineExpose({ back });
const actions: Record<string, () => void | Promise<void>> = {
  repeat: () => say(summary.value, screen.value === "player"),
  continue: continueLast,
  recommendations: () => showRecommendations(),
  another: () => showRecommendations(true),
  listenRecommended: () => recommended.value ? run(() => listenEntry(recommended.value!)) : showRecommendations(),
  recommendationDescription: () => run(async () => { if (recommended.value) await describeRecommendation(recommended.value, operation); }),
  advanced: () => { advancedReturn = screen.value; clearSearch(); go("advanced"); },
  playerOptions: () => { clearSearch(); go("playerOptions"); },
  playerChapters: () => {
    const book = playingBook.value;
    if (!book) return say("还没有正在听的书，返回看看推荐吧。");
    entries.value = [{ sourceId: book.sourceId, book }]; index.value = 0;
    chapterReturn = "playerOptions";
    chapterIndex.value = tts.playback.chapter ?? 0; go("chapters");
  },
  history: () => showBooks("history"), shelf: () => showBooks("shelf"), unread: () => showBooks("unread"),
  find: () => { clearSearch(); nextCursor.value = undefined; searchError.value = ""; go("find"); },
  resultPrevious: () => moveResults(-1), resultNext: () => moveResults(1),
  previous: () => moveBook(-1), next: () => moveBook(1), choose: () => run(chooseBook),
  more: () => go("more"), back,
  description: () => say(item.value?.book.description?.slice(0, 2000) || "这本书没有简介。"),
  chapters: () => { chapterReturn = "books"; chapterIndex.value = Math.max(0, localBook.value?.chapters.findIndex((chapter) => chapter.id === localBook.value?.progress?.chapterId) ?? 0); go("chapters"); },
  chapterPrevious: () => { if (chapterIndex.value > 0) chapterIndex.value--; return say(summary.value); },
  chapterNext: () => { if (chapterIndex.value < (localBook.value?.chapters.length ?? 0) - 1) chapterIndex.value++; return say(summary.value); },
  chapterPlay: () => run(async () => { if (localBook.value) await startBook(localBook.value, chapterIndex.value, 0); else await say("请先下载这本书。"); }),
  dictate: () => run(dictate), search: () => run(() => search()),
  searches: () => { searchIndex.value = 0; go("searches"); },
  searchPrevious: () => { if (searchIndex.value > 0) searchIndex.value--; return say(summary.value); },
  searchNext: () => { if (searchIndex.value < accessibility.searches.length - 1) searchIndex.value++; return say(summary.value); },
  reuseSearch: () => { query.value = accessibility.searches[searchIndex.value] ?? ""; if (query.value) go("query"); else return say("还没有搜索记录。"); },
  toggle: () => run(async () => {
    const pause = pauseOnToggle ?? playbackIntended.value; pauseOnToggle = undefined;
    if (!playingBook.value) { await say("请先从书架选择一本书。"); return; }
    if (pause) { await controlListening("pause"); message.value = "已暂停，进度已保存。"; }
    else if (tts.playback.phase === "paused") { await controlListening("resume"); message.value = "正在继续播放这本书。"; }
    else await startBook(playingBook.value);
  }),
  paragraphPrevious: () => run(() => moveParagraph(-1)), paragraphNext: () => run(() => moveParagraph(1)),
  speed: () => go("speed"),
  slower: () => run(async () => { tts.speed = Math.max(0.5, tts.speed - 0.25); await saveTtsOptions(); await say(summary.value); }),
  faster: () => run(async () => { tts.speed = Math.min(2, tts.speed + 0.25); await saveTtsOptions(); await say(summary.value); }),
  exit: () => {
    if (screen.value !== "exit") exitReturn = screen.value;
    clearSearch(); void cancelRecognition(); go("exit");
  }, cancelExit: back,
  confirmExit: () => run(disableAccessibility),
  help: () => { if (screen.value !== "help") helpReturn = screen.value; go("help"); },
  instructions: () => say("每次进入会接着上次的书继续听。想换个故事，点换一本。推荐会说书名和简介，喜欢点就听这本，应用会自动准备好开始播放；不喜欢再点换一本。不需要搜索，也不用操作书架。搜索、书架、章节和语速在更多功能里。单击执行，长按听说明，底部返回和帮助，右上角退出。"),
};
function targetAt(event: PointerEvent) {
  const target = document.elementFromPoint?.(event.clientX, event.clientY) ?? event.target;
  return target instanceof Element ? target.closest<HTMLElement>("[data-action]")?.dataset.action ?? "" : "";
}
function nativeControl(target: EventTarget | null) {
  return target instanceof Element && !!target.closest("[data-native-control]");
}
function element(id: string) { return root.value?.querySelector<HTMLElement>(`[data-action="${id}"]`); }
function describe(id: string) {
  selectedAction.value = id;
  navigator.vibrate?.(35);
  if (id.startsWith("result-")) {
    const entry = resultEntries.value[Number(id.slice(7))];
    if (entry) void say(`${entry.book.title}，${entry.book.author || "作者未知"}。${entry.book.description || "暂无简介"}。单击查看这本书。`);
    return;
  }
  if (screen.value === "books" && (id === "choose" || id === "repeat")) {
    void say(`${item.value?.book.title ?? listLabel.value}。${item.value?.book.description?.slice(0, 2000) || "这本书没有简介。"}`); return;
  }
  const label = element(id)?.dataset.speech ?? element(id)?.textContent ?? "";
  void say(`这是${label.trim()}按钮，单击执行。`, screen.value === "player" || screen.value === "speed");
}
function activate(id: string) {
  navigator.vibrate?.(15);
  if (id === "toggle") pauseOnToggle = playbackIntended.value;
  const token = operation;
  void cancelAnnouncements().then(() => {
    if (token !== operation) return;
    if (id.startsWith("result-")) chooseResult(Number(id.slice(7)));
    else return actions[id]?.();
  });
}
function swipe(delta: -1 | 1) {
  if (screen.value === "recommendations") { if (delta > 0) void showRecommendations(true); else void actions.recommendationDescription?.(); return; }
  const id = screen.value === "results" ? delta > 0 ? "resultNext" : "resultPrevious"
    : screen.value === "books" ? delta > 0 ? "next" : "previous"
    : screen.value === "player" ? delta > 0 ? "paragraphNext" : "paragraphPrevious"
    : screen.value === "searches" ? delta > 0 ? "searchNext" : "searchPrevious"
    : screen.value === "chapters" ? delta > 0 ? "chapterNext" : "chapterPrevious" : "";
  if (id) activate(id);
}
const gestures = new DirectGestureController(describe, activate, swipe);
function pointerDown(event: PointerEvent) {
  if (nativeControl(event.target)) { gestures.reset(); pointerId = undefined; return; }
  touchInput = event.pointerType !== "mouse";
  if (event.pointerType === "mouse") return;
  if (!event.isPrimary) { gestures.reset(); pointerId = undefined; return; }
  pointerId = event.pointerId; selectedAction.value = ""; root.value?.setPointerCapture(event.pointerId);
  gestures.down(targetAt(event), event.clientX, event.clientY, performance.now());
}
function pointerMove(event: PointerEvent) {
  if (event.pointerId !== pointerId) return;
  gestures.move(event.clientX, event.clientY);
}
function pointerUp(event: PointerEvent) {
  if (event.pointerId !== pointerId) return;
  pointerId = undefined;
  gestures.up(targetAt(event), event.clientX, event.clientY, performance.now());
}
function pointerCancel(event: PointerEvent) {
  if (event.pointerId === pointerId) { gestures.reset(); pointerId = undefined; selectedAction.value = ""; }
}
function click(event: MouseEvent) {
  if (nativeControl(event.target)) return;
  event.preventDefault();
  if (event.detail === 0 || !touchInput) {
    const id = (event.target as HTMLElement).closest<HTMLElement>("[data-action]")?.dataset.action;
    if (id) activate(id);
  }
}
watch(screen, () => { gestures.reset(); pointerId = undefined; fitLayout(); }, { flush: "post" });
watch([resultEntries, primaryLabel], fitLayout, { flush: "post" });
watch(selectedSourceId, () => {
  clearSearch(); nextCursor.value = undefined; searchError.value = "";
}, { flush: "sync" });
watch(sources, (value) => {
  if (!value.some((entry) => entry.manifest.id === selectedSourceId.value)) selectedSourceId.value = value[0]?.manifest.id ?? "";
}, { immediate: true });
watch(selectedAction, async (id) => {
  await nextTick();
  root.value?.querySelectorAll<HTMLElement>("[data-action]").forEach((button) => button.classList.toggle("selected", button.dataset.action === id));
});
watch(() => tts.playback.error, (error) => { if (error) void say(error); });
watch(() => state.error, (error) => { if (error) void say(`本地保存失败：${error}`); });
watch(() => accessibility.active, (active) => {
  if (!active) { clearSearch(); gestures.reset(); }
  else if (screen.value !== "player") void say(`${title.value}。${summary.value}。${screenInstructions.value}`);
});
onMounted(() => {
  if (typeof ResizeObserver !== "undefined" && root.value) {
    layoutObserver = new ResizeObserver(fitLayout);
    layoutObserver.observe(root.value);
  }
  window.addEventListener("resize", fitLayout);
  fitLayout();
  void enterSimpleMode();
});
onBeforeUnmount(() => {
  layoutObserver?.disconnect();
  window.removeEventListener("resize", fitLayout);
  if (layoutFrame !== undefined) cancelAnimationFrame(layoutFrame);
  gestures.reset(); clearSearch(); void cancelAnnouncements(); void cancelRecognition();
});
</script>

<template>
  <main ref="root" class="accessible-mode" :class="{ 'is-speaking': accessibility.speaking, 'a11y-search-screen': screen === 'find' || screen === 'query', 'a11y-results-screen': screen === 'results' }"
    @pointerdown="pointerDown" @pointermove="pointerMove" @pointerup="pointerUp" @pointercancel="pointerCancel" @lostpointercapture="pointerCancel" @contextmenu="!nativeControl($event.target) && $event.preventDefault()" @click="click">
    <header class="a11y-header">
      <h1>{{ title }}</h1>
      <button class="a11y-exit-shortcut" data-action="exit" data-speech="退出无障碍模式，协助者可直接单击">
        <span>退出无障碍模式</span><small>协助者单击此处</small>
      </button>
    </header>
    <button class="a11y-summary" data-action="repeat" data-speech="重听当前书籍和状态" :class="{ selected: selectedAction === 'repeat' }" :aria-label="summary">
      <span class="a11y-summary-text">{{ summary }}</span>
    </button>
    <div class="a11y-controls">
      <template v-if="screen === 'home'">
        <button class="a11y-primary" data-action="continue">{{ lastListening || playingBook ? '继续上次' : '听听推荐' }}</button>
        <button data-action="recommendations">听听推荐</button>
        <button data-action="advanced">更多功能</button>
      </template>
      <template v-else-if="screen === 'recommendations'">
        <button class="a11y-primary" data-action="listenRecommended" :disabled="busy">{{ preparingTitle ? '正在准备…' : recommended ? '就听这本' : '重试推荐' }}</button>
        <div class="a11y-row"><button data-action="recommendationDescription" :disabled="busy || !recommended">听简介</button><button data-action="another">换一本</button></div>
        <button data-action="advanced">更多功能</button>
      </template>
      <template v-else-if="screen === 'advanced'">
        <button data-action="find">搜索书籍</button>
        <div class="a11y-row"><button data-action="shelf">我的书架</button><button data-action="history">最近读听</button></div>
        <div class="a11y-row"><button data-action="playerOptions">播放设置</button><button data-action="continue">继续上次</button></div>
      </template>
      <template v-else-if="screen === 'playerOptions'">
        <div class="a11y-row"><button data-action="paragraphPrevious">上一段</button><button data-action="paragraphNext">下一段</button></div>
        <button data-action="playerChapters">选择章节</button>
        <button data-action="speed">调整语速</button>
      </template>
      <template v-else-if="screen === 'books'">
        <button class="a11y-primary" data-action="choose">{{ primaryLabel }}</button>
        <div class="a11y-row"><button data-action="previous">上一本</button><button data-action="next">下一本</button></div>
        <button data-action="more">更多：简介与章节</button>
      </template>
      <template v-else-if="screen === 'more'">
        <button class="a11y-primary" data-action="description">听简介</button>
        <button data-action="chapters">选择章节</button>
        <button data-action="choose">{{ primaryLabel }}</button>
      </template>
      <template v-else-if="screen === 'find' || screen === 'query'">
        <form class="a11y-search-form" data-native-control @submit.prevent="actions.search()">
          <label for="a11y-search-query">书名或作者</label>
          <div class="a11y-search-input-row">
            <input id="a11y-search-query" v-model="query" type="search" placeholder="输入书名或作者" enterkeyhint="search" maxlength="200" :disabled="busy" />
            <button type="submit" :disabled="busy || !sources.length">{{ searchPending ? '搜索中…' : '搜索' }}</button>
          </div>
          <div class="a11y-search-source-row">
            <label for="a11y-search-source">书源</label>
            <select id="a11y-search-source" v-model="selectedSourceId" :disabled="busy || !sources.length">
              <option v-if="!sources.length" value="">没有启用的书源</option>
              <option v-for="entry in sources" :key="entry.manifest.id" :value="entry.manifest.id">{{ entry.manifest.name }}</option>
            </select>
            <button type="button" :disabled="busy" @click="actions.dictate()">语音输入</button>
          </div>
          <p v-if="searchError" class="a11y-search-error" role="alert">{{ searchError }}</p>
          <p v-else class="a11y-search-help" role="status">{{ accessibility.recording ? '正在听，请说书名或作者…' : screen === 'query' ? '识别文字已填入，可修改后点击搜索。' : !sources.length ? '请先退出此模式，在书源页启用书源。' : '输入后点击搜索，语音输入也可以修改。' }}</p>
        </form>
        <div class="a11y-row a11y-search-shortcuts"><button data-action="searches">最近搜索</button><button data-action="unread">未听过的书</button></div>
      </template>
      <template v-else-if="screen === 'results'">
        <template v-if="entries.length">
          <button v-for="(entry, i) in resultEntries" :key="entry.book.id" class="a11y-result-card" :data-action="`result-${i}`" :aria-label="`${entry.book.title}，${entry.book.author || '作者未知'}，点击查看`">
            <strong>{{ entry.book.title }}</strong>
            <span>{{ entry.book.author || '作者未知' }}</span>
            <small>查看书籍</small>
          </button>
          <div v-if="resultEntries.length === 1" class="a11y-results-note">{{ nextCursor ? '下一页查看更多结果' : '已显示全部结果' }}</div>
          <div class="a11y-row"><button data-action="resultPrevious" :disabled="busy || resultPage === 0">上一页</button><button data-action="resultNext" :disabled="busy || !hasMoreResults">{{ busy ? '加载中…' : '下一页' }}</button></div>
        </template>
        <template v-else><p class="a11y-empty-results">没有找到相关书籍</p><button class="a11y-primary" data-action="back">修改关键词或书源</button></template>
      </template>
      <template v-else-if="screen === 'searches'">
        <button class="a11y-primary" data-action="reuseSearch">使用这个搜索</button>
        <div class="a11y-row"><button data-action="searchPrevious">上一项</button><button data-action="searchNext">下一项</button></div>
      </template>
      <template v-else-if="screen === 'chapters'">
        <button class="a11y-primary" data-action="chapterPlay">从本章开始听</button>
        <div class="a11y-row"><button data-action="chapterPrevious">上一章</button><button data-action="chapterNext">下一章</button></div>
      </template>
      <template v-else-if="screen === 'player'">
        <button class="a11y-primary" data-action="toggle">{{ playbackIntended ? '暂停播放' : tts.playback.phase === 'paused' ? '继续播放' : '重新开始' }}</button>
        <button data-action="another">换一本</button>
        <button data-action="advanced">更多功能</button>
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
      </template>
    </div>
    <div class="a11y-feedback" role="status">{{ accessibility.recording ? '正在听，请说书名或作者…' : preparingTitle ? '准备好自动开听 · 可换一本' : busy ? '正在处理，可返回取消等待' : simpleError || searchError || accessibility.error || (screen === 'results' ? `第 ${resultPage + 1} 页 · 点击书名查看` : gestureHint) }}</div>
    <footer class="a11y-row a11y-footer"><button data-action="back">返回</button><button data-action="help">操作帮助</button></footer>
  </main>
</template>
