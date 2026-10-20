import type { InstalledSource, SourcePackage } from "../types";
import { validatePackage } from "./packages";

const legacy = [
  { id: "io.quanben", name: "quanben", sha: "aef30cec7076bad8758e1223cca4adab0e32e5eb60168229cd455901fff24b3e" },
  { id: "io.myreads.demo", name: "demo", sha: "306e5b7e5218a9b336e7b0f441ab497e945266e26d6234137fab40c3ac16e72a" },
];
// Only replace the exact original bundled script; preserve imported/custom edits
// and disabled sources. Existing download snapshots remain untouched.
export async function upgradeBundledSources(sources: InstalledSource[], install: (pack: SourcePackage) => Promise<void>) {
  for (const entry of legacy) {
    const source = sources.find((source) => source.manifest.id === entry.id && source.manifest.version === "1.0.0");
    if (!source) continue;
    try {
      const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(source.script));
      const hash = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
      if (hash !== entry.sha) continue;
      const response = await fetch(`/sources/${entry.name}.booksource`);
      if (!response.ok) continue;
      const pack = validatePackage(await response.json());
      if (pack.manifest.id === entry.id) await install(pack);
    } catch { /* A source upgrade must not prevent reading existing books. */ }
  }
}
