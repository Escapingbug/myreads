// @vitest-environment node
import { describe, it, expect } from "vitest";
import catalog from "../android/app/src/main/assets/tts/catalog.json";

describe("Bert-VITS2 model migration", () => {
  it("pins the auditioned bilingual weights, BERT and complete text frontend", () => {
    expect(catalog.id).toBe("bertvits2-mnn-bili22-a45fd766-v1");
    const revision = catalog.revision["Voine/Bert-VITS2-MNN"];
    expect(catalog.files).toHaveLength(19);
    expect(new Set(catalog.files.map(file => file.path)).size).toBe(19);
    for (const file of catalog.files) {
      expect(file.url).toContain(`/${revision}/`);
      expect(file.path).not.toMatch(/^(MOSS|Silero)/);
      expect(file.sha256).toMatch(/^[0-9a-f]{64}$/);
      expect(file.size).toBeGreaterThan(0);
    }
    expect(catalog.files.reduce((total, file) => total + file.size, 0)).toBeLessThan(90_000_000);
    expect(catalog.files.filter(file => file.path.startsWith("bv2_model/") && file.path.endsWith(".mnn"))).toHaveLength(6);
    expect(catalog.files.some(file => file.path === "bert/zh/tokenizer.json")).toBe(true);
    expect(catalog.files.some(file => file.path === "preprocess/en/cmudict.rep")).toBe(true);
  });
});
