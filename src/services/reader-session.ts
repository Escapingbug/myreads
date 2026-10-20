import type { Book } from "../types";

const key = "myreads:reader-session";
export interface ReaderSession {
  bookId: string;
  chapterId: string;
  paragraph: number;
  offset: number;
}

// Synchronous checkpoint: renderer termination can happen before an async
// database write or the background lifecycle callback finishes.
export function readReaderSession(): ReaderSession | undefined {
  try {
    const value = JSON.parse(localStorage.getItem(key) ?? "null");
    if (value && typeof value.bookId === "string" && typeof value.chapterId === "string"
      && Number.isInteger(value.paragraph) && value.paragraph >= 0
      && Number.isFinite(value.offset) && value.offset >= 0 && value.offset <= 1) return value;
  } catch { /* Reading must still work when Web Storage is unavailable. */ }
}
export function checkpointReader(book: Book, chapterId: string, position: { paragraph: number; offset: number }) {
  try { localStorage.setItem(key, JSON.stringify({ bookId: book.localId, chapterId, ...position })); } catch { /* Best effort. */ }
}
export function clearReaderSession() {
  try { localStorage.removeItem(key); } catch { /* Best effort. */ }
}
