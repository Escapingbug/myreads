import { DOMParser } from "linkedom";

// linkedom does not place bare fragments in body like a browser DOMParser does.
export function parseHTML(html: string) {
  const document = /<html(?:\s|>)/i.test(html)
    ? html
    : `<!doctype html><html><head></head><body>${html}</body></html>`;
  return new DOMParser().parseFromString(document, "text/html");
}
