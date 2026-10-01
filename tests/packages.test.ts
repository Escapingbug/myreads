import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { zipSync, strToU8 } from "fflate";
import { checkRequestUrl, decodePackage } from "../src/services/packages";
import { validateResult } from "../src/services/runtime";

describe("书源包与数据边界", () => {
  it("JSON 和 ZIP 导入得到相同书源", () => {
    const json = decodePackage(readFileSync("public/sources/demo.booksource"));
    const zip = decodePackage(
      readFileSync("public/sources/demo.zip"),
      "demo.zip",
    );
    expect(zip).toEqual(json);
  });
  it("拒绝缺少脚本或超出大小的包", () => {
    expect(() =>
      decodePackage(zipSync({ "manifest.json": strToU8("{}") }), "source.zip"),
    ).toThrow("source.js");
    expect(() => decodePackage(new Uint8Array(512 * 1024 + 1))).toThrow(
      "512 KB",
    );
  });
  it("限制书源请求到声明的标准 HTTPS 域名", () => {
    expect(
      checkRequestUrl("https://www.quanben.io/n/book/", ["www.quanben.io"])
        .hostname,
    ).toBe("www.quanben.io");
    expect(() =>
      checkRequestUrl("https://example.com/", ["www.quanben.io"]),
    ).toThrow("未声明");
    expect(() =>
      checkRequestUrl("http://www.quanben.io/", ["www.quanben.io"]),
    ).toThrow("HTTPS");
    expect(() =>
      checkRequestUrl("https://www.quanben.io:8443/", ["www.quanben.io"]),
    ).toThrow("HTTPS");
  });
  it("不把空正文或损坏目录记为成功", () => {
    expect(() =>
      validateResult("getChapter", { paragraphs: [" ", "\n"] }),
    ).toThrow("正文为空");
    expect(() =>
      validateResult("getChapters", { items: [{ id: "1", title: "" }] }),
    ).toThrow("目录");
    expect(
      validateResult("getChapter", { paragraphs: ["  正文  ", ""] }),
    ).toEqual({ title: undefined, paragraphs: ["正文"] });
  });
});
