// @vitest-environment node
import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import catalog from "../android/app/src/main/assets/tts/catalog.json";

describe("Audio8 production model", () => {
  it("pins the auditioned mobile graphs and synthetic reference, excluding the unused encoder", () => {
    expect(catalog.id).toBe("audio8-realistic-air-int8-dcf47703-v1");
    const revision = catalog.revision["litert-community/Audio8-TTS-Realistic-0.6b"];
    expect(revision).toBe("dcf477032785f2dda4af08c89a0de8509e511c6f");
    expect(catalog.files).toHaveLength(7);
    expect(new Set(catalog.files.map(file => file.path)).size).toBe(7);
    for (const file of catalog.files) {
      if (file.asset) {
        const bytes = readFileSync(`android/app/src/main/assets/${file.asset}`);
        expect(bytes.length).toBe(file.size);
        expect(createHash("sha256").update(bytes).digest("hex")).toBe(file.sha256);
      } else expect(file.url).toContain(`/${revision}/`);
      expect(file.path).not.toMatch(/(encoder|\.mnn|\.onnx)/);
      expect(file.sha256).toMatch(/^[0-9a-f]{64}$/);
    }
    expect(catalog.files.reduce((total, file) => total + file.size, 0)).toBe(1_161_767_479);
    expect(catalog.voices.map(v => v.id)).toEqual(["audio8-zh"]);
  });
});
