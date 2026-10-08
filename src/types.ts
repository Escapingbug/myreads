export interface SourceManifest {
  id: string;
  name: string;
  version: string;
  apiVersion: 1;
  description: string;
  author?: string;
  domains: string[];
}
export interface SourcePackage {
  manifest: SourceManifest;
  script: string;
}
export interface InstalledSource extends SourcePackage {
  enabled: boolean;
  installedAt: number;
}
export interface SourceBook {
  id: string;
  title: string;
  author: string;
  description?: string;
  cover?: string;
  category?: string;
  status?: string;
  url?: string;
}
export interface ChapterRef {
  id: string;
  title: string;
  url?: string;
}
export interface Page<T> {
  items: T[];
  nextCursor?: string;
}
export interface ChapterContent {
  title?: string;
  paragraphs: string[];
}
export interface Book extends SourceBook {
  downloadPriority?: string;
  localId: string;
  sourceId: string;
  sourceName: string;
  sourceVersion: string;
  addedAt: number;
  chapters: ChapterRef[];
  downloaded: string[];
  state: "queued" | "downloading" | "paused" | "error" | "ready";
  error?: string;
  failed: string[];
  progress: {
    chapterId: string;
    paragraph: number;
    offset: number;
    updatedAt: number;
  } | null;
}
export interface ReadingHistory {
  key: string;
  book: SourceBook;
  sourceId: string;
  sourceName: string;
  chapterId: string;
  chapterTitle: string;
  chapterIndex: number;
  paragraph: number;
  updatedAt: number;
}
export interface ReaderSettings {
  fontSize: number;
  lineHeight: number;
  theme: "paper" | "white" | "night";
}
export type SourceMethod = "search" | "getBook" | "getChapters" | "getChapter";
export interface RequestOptions {
  url: string;
  method?: "GET" | "POST";
  headers?: Record<string, string>;
  body?: string;
}
