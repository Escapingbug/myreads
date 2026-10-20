import { readFileSync } from "node:fs";
import { webcrypto } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";
import { parseHTML } from "../src/services/html";
import { validateResult } from "../src/services/runtime";
import { upgradeBundledSources } from "../src/services/bundled-sources";
import type { InstalledSource } from "../src/types";
afterEach(() => vi.unstubAllGlobals());
describe("内置推荐书源", () => {
  it("只取首页推荐区，补全作者与简介，忽略分类及重复链接", async () => {
    const request = vi.fn().mockResolvedValue(`<div class="box"><h2><span>推荐小说</span></h2><p class="links"><a href="/n/a/">甲书</a><a href="/n/b/">乙书</a><a href="/n/a/">甲书</a></p></div>
      <div class="list2" itemscope><h3><a href="/n/a/">甲书</a></h3><span itemprop="author">作者甲</span><p itemprop="description">甲的故事</p></div>
      <ul class="list"><li><a href="/n/b/">乙书</a><span class="author">作者乙</span></li><li><a href="/n/c/">分类中的书</a></li></ul>`);
    const script = readFileSync("public/sources/quanben.source.js", "utf8");
    const source = new Function("sdk", script + ";return source;")({ request, parseHTML });
    const result = validateResult("recommend", await source.recommend()) as any;
    expect(request).toHaveBeenCalledWith({ url: "https://www.quanben.io/", headers: {} });
    expect(result.items.map((book: any) => book.title)).toEqual(["甲书", "乙书"]);
    expect(result.items[0].description).toBe("甲的故事"); expect(result.items[1].author).toBe("作者乙");
  });
  it("网站结构失效时报错，不把空推荐缓存为成功", async () => {
    const source = new Function("sdk", readFileSync("public/sources/quanben.source.js", "utf8") + ";return source;")({ request: async () => "<p>结构变化</p>", parseHTML });
    await expect(source.recommend()).rejects.toThrow("推荐页面已变化");
  });
  it("只升级未改动的旧内置脚本，保留自定义和禁用设置", async () => {
    vi.stubGlobal("crypto", webcrypto);
    const pack = JSON.parse(readFileSync("public/sources/demo.booksource", "utf8"));
    const old = { ...pack, manifest: { ...pack.manifest, version: "1.0.0", capabilities: undefined }, script: pack.script.replace(/  async recommend\(\)[^\n]*\n/, ""), enabled: false, installedAt: 1 } as InstalledSource;
    const install = vi.fn(); vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => pack }));
    await upgradeBundledSources([old], install); expect(install).toHaveBeenCalledWith(pack);
    install.mockClear(); await upgradeBundledSources([{ ...old, script: old.script + "\n// 用户修改" }], install);
    expect(install).not.toHaveBeenCalled();
  });
});
