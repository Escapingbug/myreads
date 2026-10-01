<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { Capacitor, type PluginListenerHandle } from "@capacitor/core";
import { App as NativeApp } from "@capacitor/app";
import {
  ArrowDownToLine,
  ArrowLeft,
  ArrowRight,
  BookOpen,
  Check,
  CheckCircle2,
  ChevronRight,
  Download,
  FileUp,
  Library,
  LoaderCircle,
  Pause,
  Play,
  Plus,
  Search,
  Settings2,
  Trash2,
  X,
  Headphones,
  Square,
} from "lucide-vue-next";
import BookCover from "./components/BookCover.vue";
import Reader from "./components/Reader.vue";
import ListeningSettings from "./components/ListeningSettings.vue";
import AppUpdate from "./components/AppUpdate.vue";
import { updates, initializeUpdates, checkUpdates, refreshUpdates } from "./services/updates";
import { tts, listeningMessage, initializeTts, refreshTts, ttsAction, controlListening } from "./services/tts";
import type {
  Book,
  InstalledSource,
  Page,
  SourceBook,
  SourcePackage,
} from "./types";
import {
  addDownload,
  collectBook,
  initialize,
  installSource,
  pauseAll,
  pauseBook,
  removeBook,
  resumeBook,
  state,
  toggleSource,
  uninstallSource,
  type PreparedBook,
} from "./services/library";
import { SourceRuntime } from "./services/runtime";
import { decodePackage } from "./services/packages";
import { downloadPackage } from "./services/import";

type View = "shelf" | "search" | "downloads" | "sources" | "listen" | "settings";
const view = ref<View>("shelf");
const bootError = ref("");
const booting = ref(false);
const notice = ref("");
const readerBook = ref<Book>();
const reader = ref<InstanceType<typeof Reader>>();
const keyword = ref("");
const sourceId = ref("");
const results = ref<SourceBook[]>([]);
const searchBusy = ref(false);
const searched = ref(false);
const searchError = ref("");
const cursor = ref<string>();
const detail = ref<{
  source: InstalledSource;
  ref: SourceBook;
  prepared?: PreparedBook;
  error?: string;
}>();
const detailBusy = ref(false);
const downloadBusy = ref(false);
const pendingPackage = ref<SourcePackage>();
const importBusy = ref(false);
const packageUrl = ref("");
const fileInput = ref<HTMLInputElement>();
const confirmation = ref<{
  title: string;
  message: string;
  label: string;
  action: () => Promise<unknown>;
}>();
const actionBusy = ref(false);
const filter = ref<"all" | "ready">("all");
const enabledSources = computed(() =>
  state.sources.filter((source) => source.enabled),
);
const selectedSource = computed(() =>
  enabledSources.value.find((source) => source.manifest.id === sourceId.value),
);
const readyBooks = computed(() =>
  state.books.filter((book) => book.state === "ready"),
);
const visibleBooks = computed(() =>
  filter.value === "ready" ? readyBooks.value : state.books,
);
const activeCount = computed(
  () =>
    state.books.filter(
      (book) => book.state === "queued" || book.state === "downloading",
    ).length,
);
const recentBook = computed(
  () =>
    [...state.books]
      .filter((book) => book.progress && book.downloaded.length)
      .sort(
        (a, b) => (b.progress?.updatedAt ?? 0) - (a.progress?.updatedAt ?? 0),
      )[0],
);
const existingDetail = computed(() =>
  state.books.find(
    (book) =>
      book.sourceId === detail.value?.source.manifest.id &&
      book.id === detail.value?.ref.id,
  ),
);
const statusName: Record<Book["state"], string> = {
  queued: "等待下载",
  downloading: "下载中",
  paused: "已暂停",
  error: "下载未完成",
  ready: "已完整下载",
};
let searchRuntime: SourceRuntime | undefined;
let searchController: AbortController | undefined;
let detailController: AbortController | undefined;
let importController: AbortController | undefined;
let noticeTimer: ReturnType<typeof setTimeout> | undefined;
let listeners: PluginListenerHandle[] = [];
let searchGeneration = 0;
let detailGeneration = 0;

watch(
  enabledSources,
  (sources) => {
    if (!sources.some((source) => source.manifest.id === sourceId.value))
      sourceId.value = sources[0]?.manifest.id ?? "";
  },
  { immediate: true },
);
watch(sourceId, () => resetSearch());
watch(
  () => state.error,
  (message) => {
    if (message) notify(`本地保存失败：${message}`);
  },
);
function notify(message: string) {
  notice.value = message;
  if (noticeTimer) clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => {
    notice.value = "";
  }, 5500);
}
async function perform(action: () => Promise<unknown>) {
  try {
    await action();
  } catch (error) {
    notify((error as Error).message || "操作失败，请重试");
  }
}
async function boot() {
  if (booting.value) return;
  booting.value = true;
  bootError.value = "";
  try {
    await initialize();
  } catch (error) {
    bootError.value = (error as Error).message;
  } finally {
    booting.value = false;
  }
}
function navigate(next: View) {
  closeDetail();
  if (view.value === "search" && next !== "search") cancelSearch();
  view.value = next;
  window.scrollTo({ top: 0 });
}
function cancelSearch() {
  ++searchGeneration;
  searchController?.abort();
  searchRuntime?.dispose();
  searchRuntime = undefined;
  searchBusy.value = false;
}
function resetSearch() {
  cancelSearch();
  results.value = [];
  searched.value = false;
  searchError.value = "";
  cursor.value = undefined;
}
async function searchBooks(more = false) {
  if (!selectedSource.value || searchBusy.value) return;
  if (!keyword.value.trim()) {
    notify("请输入书名或作者");
    return;
  }
  if (more && !cursor.value) return;
  cancelSearch();
  const generation = searchGeneration;
  const source = selectedSource.value;
  const controller = new AbortController();
  searchController = controller;
  const runtime = new SourceRuntime(source);
  searchRuntime = runtime;
  searchBusy.value = true;
  searched.value = true;
  searchError.value = "";
  if (!more) {
    results.value = [];
    cursor.value = undefined;
  }
  try {
    const page = await runtime.call<Page<SourceBook>>(
      "search",
      [keyword.value.trim(), more ? cursor.value : undefined],
      controller.signal,
    );
    if (generation !== searchGeneration) return;
    const next = more ? [...results.value] : [];
    const seen = new Set(next.map((book) => book.id));
    for (const book of page.items)
      if (!seen.has(book.id)) {
        next.push(book);
        seen.add(book.id);
      }
    if (more && page.nextCursor === cursor.value)
      throw new Error("书源分页未前进，请重新搜索");
    results.value = next;
    cursor.value = page.nextCursor;
  } catch (error) {
    if (!controller.signal.aborted)
      searchError.value = (error as Error).message;
  } finally {
    runtime.dispose();
    if (generation === searchGeneration) {
      searchRuntime = undefined;
      searchBusy.value = false;
    }
  }
}
async function openDetail(book: SourceBook) {
  const source = selectedSource.value;
  if (!source) return;
  closeDetail();
  const generation = detailGeneration;
  detail.value = { source, ref: book };
  detailBusy.value = true;
  const controller = new AbortController();
  detailController = controller;
  try {
    const prepared = await collectBook(source, book, controller.signal);
    if (generation === detailGeneration && detail.value)
      detail.value.prepared = prepared;
  } catch (error) {
    if (
      !controller.signal.aborted &&
      detail.value &&
      generation === detailGeneration
    )
      detail.value.error = (error as Error).message;
  } finally {
    if (generation === detailGeneration) detailBusy.value = false;
  }
}
function closeDetail() {
  ++detailGeneration;
  detailController?.abort();
  detail.value = undefined;
  detailBusy.value = false;
}
async function startDownload() {
  const current = detail.value;
  if (!current?.prepared || downloadBusy.value) return;
  downloadBusy.value = true;
  try {
    await addDownload(current.source, current.ref, current.prepared);
    closeDetail();
    navigate("downloads");
    notify("已加入下载，完成后可离线阅读");
  } catch (error) {
    notify((error as Error).message);
  } finally {
    downloadBusy.value = false;
  }
}
function openBook(book: Book) {
  if (!book.downloaded.length) {
    navigate("downloads");
    return;
  }
  readerBook.value = book;
}
async function closeReader() {
  await perform(async () => {
    await reader.value?.persist();
  });
  readerBook.value = undefined;
}
function progress(book: Book) {
  return book.chapters.length
    ? Math.round((book.downloaded.length / book.chapters.length) * 100)
    : 0;
}
function readingLabel(book: Book) {
  const index = book.chapters.findIndex(
    (chapter) => chapter.id === book.progress?.chapterId,
  );
  return index >= 0 ? `读至第 ${index + 1} 章` : "尚未开始阅读";
}
function deleteBook(book: Book) {
  confirmation.value = {
    title: `删除《${book.title}》？`,
    message: "将移除本机上的章节文件和阅读记录，需要阅读时可重新下载。",
    label: "删除书籍",
    action: async () => {
      if (tts.playback.bookId === book.localId) await controlListening("stop");
      await removeBook(book);
    },
  };
}
function deleteSource(source: InstalledSource) {
  confirmation.value = {
    title: `卸载「${source.manifest.name}」？`,
    message: "已下载书籍仍可离线阅读，已有下载任务保留书源快照。",
    label: "卸载书源",
    action: () => uninstallSource(source.manifest.id),
  };
}
async function confirmAction() {
  if (!confirmation.value || actionBusy.value) return;
  actionBusy.value = true;
  try {
    await confirmation.value.action();
    confirmation.value = undefined;
  } catch (error) {
    notify((error as Error).message);
  } finally {
    actionBusy.value = false;
  }
}
async function choosePackage(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  importBusy.value = true;
  try {
    if (file.size > 512 * 1024) throw new Error("书源包不能超过 512 KB");
    pendingPackage.value = decodePackage(
      new Uint8Array(await file.arrayBuffer()),
      file.name,
    );
  } catch (error) {
    notify((error as Error).message);
  } finally {
    input.value = "";
    importBusy.value = false;
  }
}
async function importUrl() {
  if (importBusy.value || !packageUrl.value.trim()) return;
  importBusy.value = true;
  const controller = new AbortController();
  importController = controller;
  const timeout = setTimeout(() => controller.abort(), 25000);
  try {
    pendingPackage.value = await downloadPackage(
      packageUrl.value.trim(),
      controller.signal,
    );
  } catch (error) {
    notify(
      controller.signal.aborted
        ? "书源包下载已取消或超时"
        : (error as Error).message,
    );
  } finally {
    clearTimeout(timeout);
    importBusy.value = false;
  }
}
async function approvePackage() {
  if (!pendingPackage.value || importBusy.value) return;
  importBusy.value = true;
  try {
    await installSource(pendingPackage.value);
    pendingPackage.value = undefined;
    packageUrl.value = "";
    notify("书源已安装");
  } catch (error) {
    notify((error as Error).message);
  } finally {
    importBusy.value = false;
  }
}
function back() {
  if (readerBook.value) reader.value?.handleBack();
  else if (confirmation.value && !actionBusy.value)
    confirmation.value = undefined;
  else if (pendingPackage.value && !importBusy.value)
    pendingPackage.value = undefined;
  else if (detail.value) closeDetail();
  else if (view.value !== "shelf") navigate("shelf");
  else if (Capacitor.isNativePlatform()) void NativeApp.exitApp();
}
function keyboard(event: KeyboardEvent) {
  if (event.key === "Escape" && !readerBook.value) back();
}
onMounted(async () => {
  window.addEventListener("keydown", keyboard);
  await boot();
  await initializeTts().catch((error: Error) => notify(error.message));
  void initializeUpdates().then(() => checkUpdates()).catch((error: Error) => { updates.error = error.message; });
  if (Capacitor.isNativePlatform()) {
    listeners.push(await NativeApp.addListener("backButton", back));
    listeners.push(
      await NativeApp.addListener("appStateChange", ({ isActive }) => {
        if (isActive) void refreshTts().catch((error: Error) => notify(error.message));
        if (isActive) void initializeUpdates().then(async () => { await refreshUpdates(); await checkUpdates(); }).catch((error: Error) => { updates.error = error.message; });
        if (!isActive) {
          cancelSearch();
          closeDetail();
          importController?.abort();
          void perform(async () => {
            await reader.value?.persist();
            await pauseAll();
          });
        }
      }),
    );
  }
});
onBeforeUnmount(() => {
  cancelSearch();
  closeDetail();
  importController?.abort();
  if (noticeTimer) clearTimeout(noticeTimer);
  window.removeEventListener("keydown", keyboard);
  for (const listener of listeners) void listener.remove();
});
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <a class="brand" href="#" @click.prevent="navigate('shelf')"
        ><span class="brand-symbol"
          ><BookOpen :size="23" :stroke-width="1.5" /></span
        ><span>纸间<small>把故事留在身边</small></span></a
      >
      <button
        class="icon-button header-settings"
        aria-label="设置"
        @click="navigate('settings')"
      >
        <Settings2 :size="21" />
      </button>
    </header>
    <main v-if="!state.initialized" class="boot-screen">
      <BookOpen :size="42" :stroke-width="1.2" />
      <h1>{{ bootError ? "暂时无法打开书架" : "正在整理你的书架" }}</h1>
      <p>{{ bootError || "初始化本地存储，请稍候…" }}</p>
      <button v-if="bootError" class="button" :disabled="booting" @click="boot">
        重新尝试</button
      ><LoaderCircle v-else class="spin" :size="22" />
    </main>
    <main v-else class="main-content">
      <button v-if="view !== 'settings' && (updates.latest || updates.download.phase === 'ready')" class="update-banner" @click="navigate('settings')"><Download :size="17" /><span>{{ updates.download.phase === 'ready' ? '新版本已下载，点击安装' : `纸间 ${updates.latest?.versionName} 已发布` }}</span><ChevronRight :size="16" /></button>
      <template v-if="view === 'shelf'">
        <div class="page-heading">
          <div>
            <p class="eyebrow">YOUR PERSONAL LIBRARY</p>
            <h1>我的书架<span class="heading-dot">.</span></h1>
            <p class="subtitle">
              {{
                state.books.length
                  ? `${state.books.length} 本藏书，${readyBooks.length} 本已完整下载`
                  : "下载一本好书，留一段安静的时间。"
              }}
            </p>
          </div>
          <button class="button compact" @click="navigate('search')">
            <Plus :size="18" /> 找书
          </button>
        </div>
        <section v-if="recentBook" class="continue-card">
          <BookCover
            :title="recentBook.title"
            :author="recentBook.author"
            small
          />
          <div class="continue-info">
            <span class="eyebrow">接着上次的故事</span>
            <h2>{{ recentBook.title }}</h2>
            <p>{{ readingLabel(recentBook) }}</p>
            <button class="text-button" @click="openBook(recentBook)">
              继续阅读 <ArrowRight :size="17" />
            </button>
          </div>
          <span class="continue-ornament" aria-hidden="true">阅</span>
        </section>
        <div v-if="state.books.length" class="collection-heading">
          <div class="segmented">
            <button
              :class="{ active: filter === 'all' }"
              @click="filter = 'all'"
            >
              全部藏书 <span>{{ state.books.length }}</span></button
            ><button
              :class="{ active: filter === 'ready' }"
              @click="filter = 'ready'"
            >
              已下载 <span>{{ readyBooks.length }}</span>
            </button>
          </div>
          <span class="muted collection-note">保存在本机</span>
        </div>
        <div v-if="visibleBooks.length" class="shelf-grid">
          <article
            v-for="book in visibleBooks"
            :key="book.localId"
            class="shelf-book"
          >
            <button
              class="cover-button"
              :aria-label="`打开${book.title}`"
              @click="openBook(book)"
            >
              <BookCover :title="book.title" :author="book.author" /><span
                class="cover-status"
                :class="{ ready: book.state === 'ready' }"
                ><Check v-if="book.state === 'ready'" :size="12" /><Download
                  v-else
                  :size="12"
                />{{
                  book.state === "ready" ? "离线可读" : `${progress(book)}%`
                }}</span
              >
            </button>
            <div class="book-caption">
              <h2>{{ book.title }}</h2>
              <p>{{ book.author || "佚名" }}</p>
              <span>{{
                book.state === "ready"
                  ? readingLabel(book)
                  : statusName[book.state]
              }}</span
              ><button
                class="book-remove icon-button"
                :aria-label="`删除${book.title}`"
                @click="deleteBook(book)"
              >
                <Trash2 :size="15" />
              </button>
            </div>
          </article>
        </div>
        <section v-else class="empty-state shelf-empty">
          <div class="empty-illustration">
            <span></span><BookOpen :size="54" :stroke-width="1.1" /><i></i>
          </div>
          <p class="eyebrow">A LITTLE SPACE FOR STORIES</p>
          <h2>
            {{
              filter === "ready" ? "好故事，正在路上" : "你的故事，从这里开始"
            }}
          </h2>
          <p>
            {{
              filter === "ready"
                ? "书籍下载完成后，会出现在这里。"
                : "按书名或作者找书，下载后就能随时离线阅读。"
            }}
          </p>
          <button
            class="button"
            @click="navigate(filter === 'ready' ? 'downloads' : 'search')"
          >
            {{ filter === "ready" ? "查看下载" : "寻找第一本书"
            }}<ArrowRight :size="18" />
          </button>
          <div class="empty-footnote">
            <CheckCircle2 :size="15" /> 本地保存 · 安心离线阅读
          </div>
        </section>
      </template>
      <template v-else-if="view === 'search'">
        <div class="page-heading">
          <div>
            <p class="eyebrow">FIND YOUR NEXT STORY</p>
            <h1>寻找好故事<span class="heading-dot">.</span></h1>
            <p class="subtitle">先下载，再把故事慢慢读完。</p>
          </div>
        </div>
        <form class="search-form" @submit.prevent="searchBooks()">
          <Search :size="21" /><input
            v-model="keyword"
            type="search"
            placeholder="书名或作者"
            aria-label="书名或作者"
            :disabled="!enabledSources.length"
          /><button
            class="button"
            type="submit"
            :disabled="searchBusy || !selectedSource"
          >
            <LoaderCircle v-if="searchBusy" class="spin" :size="17" />{{
              searchBusy ? "搜索中" : "搜索"
            }}
          </button>
        </form>
        <div class="source-selector">
          <label for="search-source">搜索书源</label
          ><select id="search-source" v-model="sourceId" :disabled="searchBusy">
            <option
              v-for="source in enabledSources"
              :key="source.manifest.id"
              :value="source.manifest.id"
            >
              {{ source.manifest.name }}
            </option></select
          ><button class="text-button" @click="navigate('sources')">
            管理 <ChevronRight :size="14" />
          </button>
        </div>
        <div v-if="!enabledSources.length" class="inline-message">
          还没有启用的书源。<button
            class="text-button"
            @click="navigate('sources')"
          >
            添加书源 <ArrowRight :size="16" />
          </button>
        </div>
        <div v-if="searchError" class="error-card" role="alert">
          <p>{{ searchError }}</p>
          <button class="text-button" @click="searchBooks()">重试</button>
        </div>
        <div v-if="results.length" class="result-heading">
          <h2>搜索结果</h2>
          <span class="muted"
            >{{ results.length }} 本 · {{ selectedSource?.manifest.name }}</span
          >
        </div>
        <div v-if="results.length" class="results-list">
          <button
            v-for="book in results"
            :key="book.id"
            class="result-card"
            @click="openDetail(book)"
          >
            <BookCover :title="book.title" :author="book.author" small />
            <div>
              <h2>{{ book.title }}</h2>
              <p class="result-author">
                {{ book.author || "佚名"
                }}<span v-if="book.category"> · {{ book.category }}</span>
              </p>
              <p class="result-description">
                {{ book.description || "打开详情，查看本书的章节目录。" }}
              </p>
              <span class="result-link"
                >查看详情 <ArrowRight :size="14"
              /></span>
            </div>
            <ChevronRight class="result-chevron" :size="19" />
          </button>
        </div>
        <button
          v-if="cursor && results.length"
          class="button secondary load-more"
          :disabled="searchBusy"
          @click="searchBooks(true)"
        >
          {{ searchBusy ? "正在加载…" : "加载更多" }}
        </button>
        <section
          v-if="
            !results.length &&
            !searchBusy &&
            !searchError &&
            enabledSources.length
          "
          class="empty-state search-empty"
        >
          <Search :size="38" :stroke-width="1.1" />
          <h2>
            {{ searched ? "暂时没有找到这本书" : "下一本，会是什么故事？" }}
          </h2>
          <p>
            {{
              searched
                ? "换个关键词，或试试其他书源。"
                : selectedSource?.manifest.id === "io.myreads.demo"
                  ? "试试「山间来信」「夜行列车」或「小院四季」。"
                  : "输入书名或作者，搜索当前书源。"
            }}
          </p>
          <div
            v-if="
              !searched && selectedSource?.manifest.id === 'io.myreads.demo'
            "
            class="suggestions"
          >
            <button
              v-for="word in ['山间来信', '夜行列车', '小院四季']"
              :key="word"
              @click="
                keyword = word;
                searchBooks();
              "
            >
              {{ word }}
            </button>
          </div>
        </section>
        <div v-if="searchBusy && !results.length" class="loading-state">
          <LoaderCircle class="spin" :size="24" />
          <p>正在书源中寻找…</p>
          <button class="text-button" @click="cancelSearch">取消搜索</button>
        </div>
      </template>
      <template v-else-if="view === 'downloads'">
        <div class="page-heading">
          <div>
            <p class="eyebrow">BRING STORIES HOME</p>
            <h1>下载管理<span class="heading-dot">.</span></h1>
            <p class="subtitle">
              {{
                activeCount
                  ? `${activeCount} 本书正在下载或等待，离开应用后自动暂停。`
                  : "整本下载到本机，阅读时无需网络。"
              }}
            </p>
          </div>
        </div>
        <div v-if="activeCount" class="downloads-summary">
          <LoaderCircle class="spin" :size="17" /><span>逐章保存，随时暂停</span
          ><button class="text-button" @click="perform(pauseAll)">
            全部暂停
          </button>
        </div>
        <div v-if="state.books.length" class="download-list">
          <article
            v-for="book in state.books"
            :key="book.localId"
            class="download-card"
          >
            <BookCover :title="book.title" :author="book.author" small />
            <div class="download-info">
              <div class="download-title">
                <h2>{{ book.title }}</h2>
                <button
                  class="icon-button"
                  :aria-label="`删除${book.title}`"
                  @click="deleteBook(book)"
                >
                  <Trash2 :size="17" />
                </button>
              </div>
              <p class="muted">
                {{ book.sourceName }} · {{ book.author || "佚名" }}
              </p>
              <div class="progress-heading">
                <span
                  :class="{
                    'status-error': book.state === 'error',
                    'status-ready': book.state === 'ready',
                  }"
                  >{{ statusName[book.state] }}</span
                ><span
                  >{{ book.downloaded.length }} /
                  {{ book.chapters.length }} 章</span
                >
              </div>
              <progress
                :value="book.downloaded.length"
                :max="book.chapters.length"
                :aria-label="`${book.title}下载进度`"
              ></progress>
              <p v-if="book.error" class="download-error">{{ book.error }}</p>
              <div class="download-actions">
                <button
                  v-if="book.state === 'downloading' || book.state === 'queued'"
                  class="button secondary compact"
                  @click="perform(() => pauseBook(book))"
                >
                  <Pause :size="15" /> 暂停</button
                ><button
                  v-else-if="book.state !== 'ready'"
                  class="button secondary compact"
                  @click="perform(() => resumeBook(book))"
                >
                  <Play :size="15" />{{
                    book.state === "error" ? "重试未完成章节" : "继续下载"
                  }}</button
                ><button
                  v-if="book.downloaded.length"
                  class="button compact"
                  @click="openBook(book)"
                >
                  <BookOpen :size="15" />{{
                    book.state === "ready" ? "开始阅读" : "阅读已下载章节"
                  }}
                </button>
              </div>
            </div>
          </article>
        </div>
        <section v-else class="empty-state">
          <ArrowDownToLine :size="42" :stroke-width="1.1" />
          <h2>还没有下载任务</h2>
          <p>找到想读的书，在详情页下载整本。</p>
          <button class="button" @click="navigate('search')">
            去找书 <ArrowRight :size="18" />
          </button>
        </section>
      </template>
      <template v-else-if="view === 'listen'">
        <div class="page-heading">
          <div><p class="eyebrow">STORIES, IN YOUR EARS</p><h1>听书<span class="heading-dot">.</span></h1>
          <p class="subtitle">下载声音，把故事听进日常。</p></div>
        </div>
        <ListeningSettings />
        <p class="source-note">下载模型后，打开已保存的小说，点击阅读器顶部的耳机开始听书。</p>
      </template>
      <template v-else-if="view === 'sources'">
        <div class="page-heading">
          <div>
            <p class="eyebrow">YOUR SOURCES, YOUR CHOICE</p>
            <h1>书源管理<span class="heading-dot">.</span></h1>
            <p class="subtitle">连接故事的入口，已下载的书始终留在本机。</p>
          </div>
        </div>
        <section class="import-card">
          <div class="section-heading">
            <h2>添加书源</h2>
            <span class="muted">ZIP / JSON · 最大 512 KB</span>
          </div>
          <p>导入书源包，或粘贴书源包的 HTTPS 下载地址。</p>
          <input
            ref="fileInput"
            class="visually-hidden"
            type="file"
            accept=".zip,.json,.booksource,application/zip,application/json"
            @change="choosePackage"
          /><button
            class="button secondary"
            :disabled="importBusy"
            @click="fileInput?.click()"
          >
            <FileUp :size="18" /> 选择书源文件
          </button>
          <form class="url-form" @submit.prevent="importUrl">
            <input
              v-model="packageUrl"
              type="url"
              placeholder="https://example.com/source.zip"
              aria-label="书源包下载地址"
              required
            /><button
              class="button compact"
              type="submit"
              :disabled="importBusy"
            >
              <LoaderCircle
                v-if="importBusy"
                class="spin"
                :size="16"
              /><Download v-else :size="16" /> 获取
            </button>
          </form>
        </section>
        <div class="section-heading sources-heading">
          <h2>已安装书源</h2>
          <span class="muted">{{ state.sources.length }} 个</span>
        </div>
        <div class="sources-list">
          <article
            v-for="source in state.sources"
            :key="source.manifest.id"
            class="source-card"
          >
            <div class="source-card-heading">
              <span class="source-symbol"><Library :size="21" /></span>
              <div>
                <h3>{{ source.manifest.name }}</h3>
                <span class="muted"
                  >v{{ source.manifest.version }} ·
                  {{ source.manifest.author || "未知作者" }}</span
                >
              </div>
              <button
                class="switch"
                role="switch"
                :aria-label="`启用${source.manifest.name}`"
                :aria-checked="source.enabled"
                :class="{ on: source.enabled }"
                @click="perform(() => toggleSource(source))"
              >
                <span></span>
              </button>
            </div>
            <p>{{ source.manifest.description }}</p>
            <div class="source-footer">
              <span>{{
                source.manifest.domains.length
                  ? source.manifest.domains.join(" · ")
                  : "本地演示，无需网络"
              }}</span
              ><button class="text-button danger" @click="deleteSource(source)">
                卸载
              </button>
            </div>
          </article>
        </div>
        <p v-if="!state.sources.length" class="inline-message">
          暂无书源，导入一个书源包开始找书。
        </p>
        <p class="source-note">
          书源包含可执行脚本，请选择可信来源。启用后按其声明的域名访问网站。
        </p>
      </template>
      <template v-else-if="view === 'settings'">
        <div class="page-heading"><div><p class="eyebrow">MAKE YOURSELF AT HOME</p><h1>设置<span class="heading-dot">.</span></h1><p class="subtitle">照顾好阅读，也保持应用常新。</p></div></div>
        <AppUpdate />
        <div class="settings-links"><button @click="navigate('sources')"><Library :size="19" /><span>管理书源</span><ChevronRight :size="17" /></button><button @click="navigate('listen')"><Headphones :size="19" /><span>听书模型与声音</span><ChevronRight :size="17" /></button></div>
      </template>
    </main>
    <div v-if="tts.playback.bookId && !['idle', 'completed'].includes(tts.playback.phase) && !readerBook" class="listen-mini-player" role="status">
      <button class="listen-mini-title" @click="state.books.find(book => book.localId === tts.playback.bookId) && openBook(state.books.find(book => book.localId === tts.playback.bookId)!)">
        <Headphones :size="20" /><span><strong>{{ tts.playback.title }}</strong><small>{{ listeningMessage() }}</small></span>
      </button>
      <button v-if="tts.playback.phase !== 'error'" class="icon-button" :aria-label="tts.playback.phase === 'paused' ? '继续听书' : '暂停听书'" @click="ttsAction(() => controlListening(tts.playback.phase === 'paused' ? 'resume' : 'pause'))"><Play v-if="tts.playback.phase === 'paused'" :size="19" /><Pause v-else :size="19" /></button>
      <button class="icon-button" aria-label="停止听书" @click="ttsAction(() => controlListening('stop'))"><Square :size="17" /></button>
    </div>
    <nav v-if="state.initialized" class="bottom-nav" aria-label="主导航">
      <button :class="{ active: view === 'shelf' }" @click="navigate('shelf')">
        <BookOpen :size="22" :stroke-width="1.7" /><span>书架</span></button
      ><button
        :class="{ active: view === 'search' }"
        @click="navigate('search')"
      >
        <Search :size="22" :stroke-width="1.7" /><span>找书</span></button
      ><button
        :class="{ active: view === 'downloads' }"
        @click="navigate('downloads')"
      >
        <span class="nav-icon"
          ><ArrowDownToLine :size="22" :stroke-width="1.7" /><i
            v-if="activeCount"
            >{{ activeCount }}</i
          ></span
        ><span>下载</span></button
      ><button
        :class="{ active: view === 'sources' }"
        @click="navigate('sources')"
      >
        <Library :size="22" :stroke-width="1.7" /><span>书源</span>
      </button>
      <button :class="{ active: view === 'listen' }" @click="navigate('listen')"><Headphones :size="22" :stroke-width="1.7" /><span>听书</span></button>
    </nav>
    <div v-if="detail" class="sheet-backdrop" @click.self="closeDetail">
      <section
        class="detail-sheet"
        role="dialog"
        aria-modal="true"
        aria-label="书籍详情"
      >
        <div class="sheet-heading">
          <span class="eyebrow">BOOK DETAILS</span
          ><button
            class="icon-button"
            aria-label="关闭详情"
            @click="closeDetail"
          >
            <X :size="21" />
          </button>
        </div>
        <div class="detail-book">
          <BookCover
            :title="detail.prepared?.info.title || detail.ref.title"
            :author="detail.ref.author"
            small
          />
          <div>
            <span class="detail-category">{{
              detail.prepared?.info.category || "小说"
            }}</span>
            <h2>{{ detail.prepared?.info.title || detail.ref.title }}</h2>
            <p>
              {{ detail.prepared?.info.author || detail.ref.author || "佚名" }}
            </p>
            <span class="muted"
              >{{ detail.source.manifest.name
              }}<template v-if="detail.prepared">
                · {{ detail.prepared.chapters.length }} 章</template
              ></span
            >
          </div>
        </div>
        <p class="detail-description">
          {{
            detail.prepared?.info.description ||
            detail.ref.description ||
            "这本书暂无简介。"
          }}
        </p>
        <div v-if="detailBusy" class="inline-message">
          <LoaderCircle class="spin" :size="17" /> 正在获取完整目录…
        </div>
        <div v-else-if="detail.error" class="error-card">
          <p>{{ detail.error }}</p>
          <button class="text-button" @click="openDetail(detail.ref)">
            重试
          </button>
        </div>
        <div v-if="detail.prepared" class="detail-catalogue">
          <div class="section-heading">
            <h3>章节预览</h3>
            <span class="muted">{{
              detail.prepared.info.status ||
              "共 " + detail.prepared.chapters.length + " 章"
            }}</span>
          </div>
          <p
            v-for="chapter in detail.prepared.chapters.slice(0, 3)"
            :key="chapter.id"
          >
            {{ chapter.title }}
          </p>
          <span v-if="detail.prepared.chapters.length > 3" class="muted"
            >以及其他 {{ detail.prepared.chapters.length - 3 }} 章</span
          >
        </div>
        <div class="detail-download">
          <button
            v-if="existingDetail"
            class="button full"
            @click="
              closeDetail();
              navigate('downloads');
            "
          >
            已在书架 · 查看下载 <ArrowRight :size="18" /></button
          ><button
            v-else
            class="button full"
            :disabled="!detail.prepared || downloadBusy"
            @click="startDownload"
          >
            <Download :size="18" />{{
              downloadBusy ? "正在加入下载…" : "下载整本到本机"
            }}
          </button>
          <p>章节以书源提供的目录为准，下载完成后可离线阅读。</p>
        </div>
      </section>
    </div>
    <div
      v-if="pendingPackage"
      class="sheet-backdrop"
      @click.self="!importBusy && (pendingPackage = undefined)"
    >
      <section
        class="confirm-sheet"
        role="dialog"
        aria-modal="true"
        aria-label="确认安装书源"
      >
        <div class="sheet-heading">
          <h2>安装书源</h2>
          <button
            class="icon-button"
            aria-label="取消安装"
            :disabled="importBusy"
            @click="pendingPackage = undefined"
          >
            <X :size="20" />
          </button>
        </div>
        <h3>
          {{ pendingPackage.manifest.name }}
          <span class="muted">v{{ pendingPackage.manifest.version }}</span>
        </h3>
        <p>{{ pendingPackage.manifest.description }}</p>
        <div class="permissions">
          <strong>声明访问的域名</strong
          ><span
            v-for="domain in pendingPackage.manifest.domains"
            :key="domain"
            >{{ domain }}</span
          ><span v-if="!pendingPackage.manifest.domains.length"
            >无网络访问</span
          >
        </div>
        <p class="source-note">
          这是可执行的书源脚本，请确认来源可信。{{
            state.sources.some(
              (source) => source.manifest.id === pendingPackage?.manifest.id,
            )
              ? "此操作将替换同名书源，已有下载仍使用原快照。"
              : ""
          }}
        </p>
        <button
          class="button full"
          :disabled="importBusy"
          @click="approvePackage"
        >
          {{ importBusy ? "正在安装…" : "确认安装" }}
        </button>
      </section>
    </div>
    <div
      v-if="confirmation"
      class="sheet-backdrop"
      @click.self="!actionBusy && (confirmation = undefined)"
    >
      <section
        class="confirm-sheet"
        role="dialog"
        aria-modal="true"
        :aria-label="confirmation.title"
      >
        <h2>{{ confirmation.title }}</h2>
        <p>{{ confirmation.message }}</p>
        <div class="confirmation-actions">
          <button
            class="button secondary"
            :disabled="actionBusy"
            @click="confirmation = undefined"
          >
            取消</button
          ><button
            class="button destructive"
            :disabled="actionBusy"
            @click="confirmAction"
          >
            {{ actionBusy ? "处理中…" : confirmation.label }}
          </button>
        </div>
      </section>
    </div>
    <Reader
      v-if="readerBook"
      ref="reader"
      :book="readerBook"
      @close="closeReader"
      @error="notify"
    />
    <div v-if="notice" class="toast" role="status">
      {{ notice
      }}<button aria-label="关闭提示" @click="notice = ''">
        <X :size="16" />
      </button>
    </div>
  </div>
</template>
