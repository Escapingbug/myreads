// @vitest-environment node
import { describe, it, expect } from "vitest";
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import catalog from "../android/app/src/main/assets/tts/catalog.json";

describe("bundled model dependencies", () => {
  it("provides the GitHub-hosted VAD locally with exactly the pinned catalog bytes", async () => {
    const vad = catalog.files.find((item) => item.path.startsWith("Silero-VAD/"))!;
    expect(vad.asset).toBe("tts/silero_vad_16k_op15.onnx");
    const bytes = await readFile(new URL(`../android/app/src/main/assets/${vad.asset}`, import.meta.url));
    expect(bytes.length).toBe(vad.size);
    expect(createHash("sha256").update(bytes).digest("hex")).toBe(vad.sha256);
  });
  it("leaves no required model file dependent on a GitHub-only network download", () => {
    expect(catalog.files).toHaveLength(13);
    for (const entry of catalog.files) expect(Boolean(entry.asset) || entry.url.startsWith("https://huggingface.co/")).toBe(true);
  });
});
