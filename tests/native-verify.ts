// Dedicated verification build; Vite excludes this entry from the normal APK.
import { createApp } from "vue";
import App from "../src/App.vue";
import Reader from "../src/components/Reader.vue";
import "../src/style.css";
import {
  addDownload,
  collectBook,
  initialize,
  installSource,
  pauseBook,
  removeBook,
  resumeBook,
  saveProgress,
  state,
  uninstallSource,
} from "../src/services/library";
import { storage } from "../src/services/storage";
import { SourceRuntime } from "../src/services/runtime";
import type { ChapterContent, Page, SourceBook } from "../src/types";

const report: Record<string, unknown>[] = [];
function log(name: string, detail: unknown = true) {
  report.push({ name, detail });
  console.log("NATIVE_VERIFY " + JSON.stringify({ name, detail }));
}
function check(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}
async function wait(condition: () => boolean) {
  const deadline = Date.now() + 30000;
  while (!condition()) {
    if (Date.now() > deadline) throw new Error("等待下载超时");
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}
async function run() {
  await initialize();
  log("sqlite_initialized", state.sources.length);
  const previous = await storage.get<string>("verify:book");
  if (previous) {
    for (const sample of [...state.books].filter((book) =>
      book.title.endsWith("（三章验证样本）"),
    ))
      await removeBook(sample);
    const book = state.books.find((item) => item.localId === previous);
    check(book?.state === "ready", "重启后书籍未完整恢复");
    check(
      book.progress?.chapterId === book.chapters[1]?.id,
      "重启后阅读进度丢失",
    );
    check(
      (await storage.chapter(book.localId, 5))?.paragraphs.length,
      "重启后章节文件丢失",
    );
    log("restart_local_chapters_and_progress", {
      chapters: book.downloaded.length,
      progress: book.progress,
    });
    const root = document.createElement("div");
    root.id = "native-reader";
    document.body.appendChild(root);
    const app = createApp(Reader, {
      book,
      onClose: () => {
        app.unmount();
        createApp(App).mount("#app");
      },
      onError: (message: string) => {
        throw new Error(message);
      },
    });
    app.mount(root);
    await new Promise((resolve) => setTimeout(resolve, 500));
    const paragraphs = document.querySelectorAll(".reader-article p").length;
    check(paragraphs === 24, "离线阅读器没有渲染本地正文");
    log("offline_reader", { paragraphs, theme: state.settings.theme });
    console.log("NATIVE_VERIFY_SUCCESS " + JSON.stringify(report));
    return;
  }
  const demo = state.sources.find(
    (source) => source.manifest.id === "io.myreads.demo",
  );
  check(demo, "演示书源未初始化");
  const runtime = new SourceRuntime(demo);
  const found = await runtime.call<Page<SourceBook>>("search", ["山间来信"]);
  runtime.dispose();
  check(found.items.length === 1, "演示搜索失败");
  log("worker_search", found.items[0].title);
  const prepared = await collectBook(demo, found.items[0]);
  check(prepared.chapters.length === 6, "演示目录缺章");
  const book = await addDownload(demo, found.items[0], prepared);
  await wait(() => book.downloaded.length >= 1);
  await pauseBook(book);
  log("pause_download", book.downloaded.length);
  await resumeBook(book);
  await wait(() => book.state === "ready" || book.state === "error");
  check(book.state === "ready", book.error || "原生下载失败");
  for (let i = 0; i < 6; i++)
    check(
      (await storage.chapter(book.localId, i))?.paragraphs.length === 24,
      `第${i + 1}章文件缺失`,
    );
  log("filesystem_directory_data", { saved: 6, paragraphs: 24 });
  await uninstallSource(demo.manifest.id);
  check(
    (await storage.chapter(book.localId, 1))?.paragraphs.length,
    "卸载书源后无法读取本地正文",
  );
  log("read_without_source");
  await installSource(demo);
  await saveProgress(book, book.chapters[1].id, 7, 0.25);
  await storage.put("verify:book", book.localId);
  const online = state.sources.find(
    (source) => source.manifest.id === "io.quanben",
  );
  if (online) {
    const sourceRuntime = new SourceRuntime(online);
    try {
      const results = await sourceRuntime.call<Page<SourceBook>>("search", [
        "斗破苍穹",
      ]);
      check(results.items.length, "真实书源没有搜索结果");
      const actual = await collectBook(
        online,
        results.items.find((item) => item.title === "斗破苍穹") ??
          results.items[0],
      );
      check(actual.chapters.length > 1000, "真实完整目录疑似缺失");
      const first = await sourceRuntime.call<ChapterContent>("getChapter", [
        actual.chapters[0],
      ]);
      check(first.paragraphs.length, "真实第一章正文为空");
      // Save only three chapters to verify native network + persistent storage.
      const sample = await addDownload(online, actual.info, {
        info: actual.info,
        chapters: actual.chapters.slice(0, 3),
      });
      await wait(() => sample.state === "ready" || sample.state === "error");
      check(sample.state === "ready", sample.error || "真实章节保存失败");
      sample.title = actual.info.title + "（三章验证样本）";
      await storage.saveBook(sample);
      log("quanben_native_http", {
        title: actual.info.title,
        fullCatalogue: actual.chapters.length,
        savedSample: sample.downloaded.length,
        firstParagraphs: first.paragraphs.length,
      });
    } catch (error) {
      log("quanben_network_failure", String((error as Error).message));
    } finally {
      sourceRuntime.dispose();
    }
  }
  createApp(App).mount("#app");
  console.log("NATIVE_VERIFY_SUCCESS " + JSON.stringify(report));
}
run().catch((error) => {
  console.error("NATIVE_VERIFY_FAILED " + String(error?.stack || error));
  document.querySelector("#app")!.textContent =
    "原生验证失败：" + error.message;
});
