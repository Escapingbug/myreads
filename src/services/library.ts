import { reactive } from "vue";
import type {
  Book,
  ChapterRef,
  InstalledSource,
  Page,
  ReaderSettings,
  SourceBook,
  SourcePackage,
  ChapterContent,
  ReadingHistory,
} from "../types";
import { storage } from "./storage";
import { SourceRuntime } from "./runtime";
import { validatePackage } from "./packages";

export const state = reactive({
  initialized: false,
  books: [] as Book[],
  history: [] as ReadingHistory[],
  sources: [] as InstalledSource[],
  settings: { fontSize: 20, lineHeight: 1.9, theme: "paper" } as ReaderSettings,
  activeDownload: "" as string,
  catalogueLoading: false,
  error: "",
});
export async function initialize() {
  await storage.init();
  state.books = (await storage.books()).sort((a, b) => b.addedAt - a.addedAt);
  state.sources = await storage.sources();
  state.history = await storage.list<ReadingHistory>("history:");
  for (const book of state.books) {
    const previous = state.history.find((item) => item.key === historyKey(book.sourceId, book.id));
    if (book.progress && (!previous || previous.updatedAt < book.progress.updatedAt)) await rememberHistory(book);
  }
  const settings = await storage.settings();
  if (settings) Object.assign(state.settings, settings);
  // An interrupted process resumes only on explicit user action.
  for (const book of state.books)
    if (book.state === "downloading" || book.state === "queued") {
      book.state = "paused";
      await storage.saveBook(book);
    }
  if (!(await storage.get("settings:bootstrapped"))) {
    for (const name of ["quanben", "demo"]) {
      const response = await fetch(`/sources/${name}.booksource`);
      if (!response.ok) throw new Error("内置书源初始化失败");
      await installSource(validatePackage(await response.json()));
    }
    await storage.put("settings:bootstrapped", true);
  }
  state.initialized = true;
}
export async function installSource(pack: SourcePackage) {
  const index = state.sources.findIndex(
    (source) => source.manifest.id === pack.manifest.id,
  );
  const previous = state.sources[index];
  const source: InstalledSource = {
    ...pack,
    enabled: previous?.enabled ?? true,
    installedAt: Date.now(),
  };
  await storage.saveSource(source);
  if (index >= 0) state.sources[index] = source;
  else state.sources.push(source);
}
export async function toggleSource(source: InstalledSource) {
  const enabled = !source.enabled;
  await storage.saveSource({ ...source, enabled });
  source.enabled = enabled;
}
export async function uninstallSource(id: string) {
  await storage.remove(`source:${id}`);
  state.sources = state.sources.filter((source) => source.manifest.id !== id);
}
export async function saveSettings() {
  await storage.put("settings:reader", state.settings);
}

let currentController: AbortController | null = null;
let currentRuntime: SourceRuntime | null = null;
let catalogueController: AbortController | null = null;

export interface PreparedBook {
  info: SourceBook;
  chapters: ChapterRef[];
}
export async function collectBook(
  source: SourcePackage,
  ref: SourceBook,
  signal?: AbortSignal,
): Promise<PreparedBook> {
  const runtime = new SourceRuntime(source);
  try {
    const info = await runtime.call<SourceBook>("getBook", [ref], signal);
    const chapters: ChapterRef[] = [];
    const seen = new Set<string>();
    const cursors = new Set<string>();
    let cursor: string | undefined;
    for (let pageNumber = 0; ; pageNumber++) {
      if (pageNumber >= 1000) throw new Error("目录分页数量异常，已停止");
      const page = await runtime.call<Page<ChapterRef>>(
        "getChapters",
        [info, cursor],
        signal,
      );
      for (const chapter of page.items)
        if (!seen.has(chapter.id)) {
          seen.add(chapter.id);
          chapters.push(chapter);
        }
      if (chapters.length > 50000) throw new Error("目录章节数量过大");
      if (!page.nextCursor) break;
      if (cursors.has(page.nextCursor))
        throw new Error("目录分页重复，无法确认完整目录");
      cursor = page.nextCursor;
      cursors.add(cursor);
    }
    if (!chapters.length) throw new Error("书籍没有可下载章节");
    signal?.throwIfAborted();
    return { info, chapters };
  } finally {
    runtime.dispose();
  }
}
export async function addDownload(
  source: InstalledSource,
  ref: SourceBook,
  prepared?: PreparedBook,
): Promise<Book> {
  const existing = state.books.find(
    (book) => book.sourceId === source.manifest.id && book.id === ref.id,
  );
  if (existing) return existing;
  if (state.catalogueLoading) throw new Error("正在获取另一部书的目录，请稍候");
  state.catalogueLoading = true;
  const controller = new AbortController();
  catalogueController = controller;
  try {
    const { info, chapters } =
      prepared ?? (await collectBook(source, ref, controller.signal));
    controller.signal.throwIfAborted();
    const book: Book = {
      ...info,
      localId: crypto.randomUUID(),
      sourceId: source.manifest.id,
      sourceName: source.manifest.name,
      sourceVersion: source.manifest.version,
      addedAt: Date.now(),
      chapters,
      downloaded: [],
      failed: [],
      state: "queued",
      progress: null,
    };
    const history = state.history.find((item) => item.key === historyKey(book.sourceId, book.id));
    if (history && chapters.some((chapter) => chapter.id === history.chapterId)) {
      book.progress = { chapterId: history.chapterId, paragraph: history.paragraph, offset: 0, updatedAt: history.updatedAt };
      book.downloadPriority = history.chapterId;
    }
    // Snapshot the package, so source updates/removal cannot break this task.
    await storage.put(`snapshot:${book.localId}`, source);
    await storage.saveBook(book);
    state.books.unshift(book);
    startPump();
    return state.books[0]!;
  } finally {
    catalogueController = null;
    state.catalogueLoading = false;
  }
}
export function cancelCatalogue() {
  catalogueController?.abort();
}
export async function resumeBook(book: Book) {
  if (
    book.state === "ready" ||
    book.state === "downloading" ||
    book.state === "queued"
  )
    return;
  while (state.activeDownload === book.localId)
    await new Promise((resolve) => setTimeout(resolve, 30));
  book.state = "queued";
  book.error = undefined;
  book.failed = [];
  await storage.saveBook(book);
  startPump();
}
export async function pauseBook(book: Book) {
  if (book.state !== "downloading" && book.state !== "queued") return;
  book.state = "paused";
  if (state.activeDownload === book.localId) {
    currentController?.abort();
    currentRuntime?.dispose();
  }
  await storage.saveBook(book);
}
export async function removeBook(book: Book) {
  if (book.state === "downloading" || book.state === "queued")
    await pauseBook(book);
  // Wait for the task to stop before deleting its files, preventing a late write.
  while (state.activeDownload === book.localId)
    await new Promise((resolve) => setTimeout(resolve, 30));
  await storage.deleteBook(book);
  await storage.remove(`snapshot:${book.localId}`);
  state.books = state.books.filter((item) => item.localId !== book.localId);
}
let pumping = false;
function startPump() {
  void pump().catch((error) => {
    state.error = (error as Error).message;
  });
}
export async function pauseAll() {
  cancelCatalogue();
  const pending = state.books.filter(
    (book) => book.state === "queued" || book.state === "downloading",
  );
  // Mark the whole queue first, so stopping one task cannot start the next.
  for (const book of pending) book.state = "paused";
  currentController?.abort();
  currentRuntime?.dispose();
  await Promise.all(pending.map((book) => storage.saveBook(book)));
}
async function pump() {
  if (pumping) return;
  pumping = true;
  try {
    let next: Book | undefined;
    while ((next = state.books.find((book) => book.state === "queued")))
      await downloadBook(next);
  } finally {
    pumping = false;
  }
}
async function downloadBook(book: Book) {
  const controller = new AbortController();
  currentController = controller;
  state.activeDownload = book.localId;
  let runtime: SourceRuntime | null = null;
  try {
    const pack = await storage.get<SourcePackage>(`snapshot:${book.localId}`);
    if (!pack) throw new Error("此下载缺少书源快照，请删除后重新下载");
    if (book.state === "paused") return;
    runtime = new SourceRuntime(pack);
    currentRuntime = runtime;
    book.state = "downloading";
    await storage.saveBook(book);
    const done = new Set(book.downloaded);
    const failed: string[] = [];
    let consecutiveFailures = 0;
    const indexes = book.chapters.map((_, index) => index);
    const priority = book.chapters.findIndex((chapter) => chapter.id === book.downloadPriority);
    if (priority > 0) indexes.unshift(...indexes.splice(priority, 1));
    for (const index of indexes) {
      controller.signal.throwIfAborted();
      const chapter = book.chapters[index]!;
      if (done.has(chapter.id)) continue;
      let error: Error | null = null;
      for (let attempt = 0; attempt < 2; attempt++) {
        try {
          const content = await runtime.call<ChapterContent>(
            "getChapter",
            [chapter],
            controller.signal,
          );
          controller.signal.throwIfAborted();
          await storage.saveChapter(book.localId, index, content);
          // Commit completed IDs only after the chapter data has been written.
          done.add(chapter.id);
          book.downloaded = [...done];
          await storage.saveBook(book);
          error = null;
          break;
        } catch (caught) {
          controller.signal.throwIfAborted();
          error = caught as Error;
          if (attempt === 0)
            await new Promise((resolve) => setTimeout(resolve, 600));
        }
      }
      if (error) {
        consecutiveFailures++;
        failed.push(chapter.id);
        book.failed = [...failed];
        book.error = `${chapter.title}：${error.message}`;
        await storage.saveBook(book);
      } else consecutiveFailures = 0;
      // Stop a failing source early rather than requesting hundreds of failing chapters.
      if (consecutiveFailures >= 3)
        throw new Error(
          "连续下载出现多次失败，已停止。请检查网络或更新书源后重试。",
        );
    }
    book.failed = failed;
    book.state = done.size === book.chapters.length ? "ready" : "error";
    if (book.state === "ready") book.error = undefined;
  } catch (error) {
    if (controller.signal.aborted) book.state = "paused";
    else {
      book.state = "error";
      book.error = (error as Error).message;
    }
  } finally {
    runtime?.dispose();
    currentRuntime = null;
    currentController = null;
    try {
      await storage.saveBook(book);
    } finally {
      state.activeDownload = "";
    }
  }
}
export async function saveProgress(
  book: Book,
  chapterId: string,
  paragraph: number,
  offset: number,
) {
  book.progress = { chapterId, paragraph, offset, updatedAt: Date.now() };
  await storage.saveBook(book);
  await rememberHistory(book);
}
export function historyKey(sourceId: string, bookId: string) { return JSON.stringify([sourceId, bookId]); }
async function rememberHistory(book: Book) {
  if (!book.progress) return;
  const chapterIndex = book.chapters.findIndex((chapter) => chapter.id === book.progress!.chapterId);
  if (chapterIndex < 0) return;
  const entry: ReadingHistory = {
    key: historyKey(book.sourceId, book.id),
    book: { id: book.id, title: book.title, author: book.author, description: book.description, category: book.category, url: book.url },
    sourceId: book.sourceId, sourceName: book.sourceName,
    chapterId: book.progress.chapterId, chapterTitle: book.chapters[chapterIndex]!.title,
    chapterIndex, paragraph: book.progress.paragraph, updatedAt: book.progress.updatedAt,
  };
  await storage.put(`history:${entry.key}`, entry);
  const index = state.history.findIndex((item) => item.key === entry.key);
  if (index >= 0) state.history[index] = entry; else state.history.push(entry);
}
