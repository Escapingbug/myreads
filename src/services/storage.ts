import { Capacitor } from "@capacitor/core";
import { Filesystem, Directory, Encoding } from "@capacitor/filesystem";
import {
  CapacitorSQLite,
  SQLiteConnection,
  type SQLiteDBConnection,
} from "@capacitor-community/sqlite";
import { openDB, type IDBPDatabase } from "idb";
import type {
  Book,
  ChapterContent,
  InstalledSource,
  ReaderSettings,
} from "../types";

export class LibraryStorage {
  private web?: IDBPDatabase;
  private sql?: SQLiteDBConnection;
  private connection = new SQLiteConnection(CapacitorSQLite);
  private initializing?: Promise<void>;
  private writes: Promise<unknown> = Promise.resolve();
  async init() {
    if (this.initializing) return this.initializing;
    this.initializing = this.open();
    try {
      await this.initializing;
    } catch (error) {
      this.initializing = undefined;
      throw error;
    }
  }
  private async open() {
    if (Capacitor.isNativePlatform()) {
      const exists = await this.connection.isConnection("myreads", false);
      this.sql = exists.result
        ? await this.connection.retrieveConnection("myreads", false)
        : await this.connection.createConnection(
            "myreads",
            false,
            "no-encryption",
            1,
            false,
          );
      await this.sql.open();
      await this.sql.execute(
        "CREATE TABLE IF NOT EXISTS records (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);",
      );
    } else {
      this.web = await openDB("myreads", 1, {
        upgrade(db) {
          db.createObjectStore("records");
        },
      });
      // Request persistence where supported. Availability remains browser-dependent.
      await navigator.storage?.persist?.().catch(() => false);
    }
  }
  async get<T>(key: string): Promise<T | undefined> {
    await this.writes;
    if (this.sql) {
      const result = await this.sql.query(
        "SELECT value FROM records WHERE key = ?;",
        [key],
      );
      return result.values?.[0]
        ? (JSON.parse(result.values[0].value) as T)
        : undefined;
    }
    return this.web!.get("records", key);
  }
  async put<T>(key: string, value: T) {
    const serialized = JSON.stringify(value);
    return this.enqueue(async () => {
      if (this.sql)
        await this.sql.run(
          "INSERT OR REPLACE INTO records (key, value) VALUES (?, ?);",
          [key, serialized],
        );
      else await this.web!.put("records", JSON.parse(serialized), key);
    });
  }
  async remove(key: string) {
    return this.enqueue(async () => {
      if (this.sql)
        await this.sql.run("DELETE FROM records WHERE key = ?;", [key]);
      else await this.web!.delete("records", key);
    });
  }
  private enqueue(task: () => Promise<unknown>) {
    const result = this.writes.then(task);
    this.writes = result.catch(() => undefined);
    return result;
  }
  async list<T>(prefix: string): Promise<T[]> {
    await this.writes;
    if (this.sql) {
      const result = await this.sql.query(
        "SELECT value FROM records WHERE key LIKE ?;",
        [`${prefix}%`],
      );
      return (result.values ?? []).map((row) => JSON.parse(row.value) as T);
    }
    const range = IDBKeyRange.bound(prefix, `${prefix}\uffff`);
    return this.web!.getAll("records", range);
  }
  books() {
    return this.list<Book>("book:");
  }
  sources() {
    return this.list<InstalledSource>("source:");
  }
  saveBook(book: Book) {
    return this.put(`book:${book.localId}`, book);
  }
  saveSource(source: InstalledSource) {
    return this.put(`source:${source.manifest.id}`, source);
  }
  private chapterPath(bookId: string, index: number) {
    if (
      !/^[a-zA-Z0-9-]+$/.test(bookId) ||
      !Number.isInteger(index) ||
      index < 0
    )
      throw new Error("章节路径无效");
    return `books/${bookId}/${index}.json`;
  }
  async saveChapter(bookId: string, index: number, content: ChapterContent) {
    const path = this.chapterPath(bookId, index);
    if (Capacitor.isNativePlatform()) {
      const temporary = `${path}.tmp`;
      await Filesystem.writeFile({
        path: temporary,
        directory: Directory.Data,
        data: JSON.stringify(content),
        encoding: Encoding.UTF8,
        recursive: true,
      });
      await Filesystem.rename({
        from: temporary,
        to: path,
        directory: Directory.Data,
        toDirectory: Directory.Data,
      });
    } else await this.put(`chapter:${bookId}:${index}`, content);
  }
  async chapter(
    bookId: string,
    index: number,
  ): Promise<ChapterContent | undefined> {
    if (Capacitor.isNativePlatform()) {
      try {
        const file = await Filesystem.readFile({
          path: this.chapterPath(bookId, index),
          directory: Directory.Data,
          encoding: Encoding.UTF8,
        });
        return JSON.parse(file.data as string);
      } catch (error) {
        if (
          /not exist|not found|ENOENT/i.test(String((error as Error).message))
        )
          return undefined;
        throw error;
      }
    }
    return this.get(`chapter:${bookId}:${index}`);
  }
  async deleteBook(book: Book) {
    if (Capacitor.isNativePlatform()) {
      try {
        await Filesystem.rmdir({
          path: `books/${book.localId}`,
          directory: Directory.Data,
          recursive: true,
        });
      } catch (error) {
        if (
          !/not exist|not found|ENOENT/i.test(String((error as Error).message))
        )
          throw error;
      }
    } else {
      const keys = await this.web!.getAllKeys(
        "records",
        IDBKeyRange.bound(
          `chapter:${book.localId}:`,
          `chapter:${book.localId}:\uffff`,
        ),
      );
      const tx = this.web!.transaction("records", "readwrite");
      for (const key of keys) await tx.store.delete(key);
      await tx.done;
    }
    await this.remove(`book:${book.localId}`);
  }
  settings() {
    return this.get<ReaderSettings>("settings:reader");
  }
}
export const storage = new LibraryStorage();
