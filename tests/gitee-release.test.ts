// @vitest-environment node
import { describe, it, expect, vi } from "vitest";
import { createHash } from "node:crypto";
// @ts-ignore Node release tooling is deliberately plain JavaScript.
import { publishGitee, verifyPublicFile } from "../scripts/lib/gitee-release.mjs";
const repository = "test-owner/zijian", tag = "v0.3.11";
function file(name: string, text: string) {
  const bytes = Buffer.from(text);
  return { name, bytes, size: bytes.length, sha256: createHash("sha256").update(bytes).digest("hex") };
}
const files = [file("zijian-0.3.11.apk", "signed-apk"), file("SHA256SUMS", "checksum"), file("update.json", "manifest")];
function server({ existing = false, published = false, corrupt = "", failUpload = "", empty = false, privateRepo = false } = {}) {
  let created = existing, stable = published;
  const attachments = existing ? files.map(({ name }) => ({ name })) : [];
  const mutations: string[] = [];
  const fetcher = vi.fn(async (url: string, options: RequestInit = {}) => {
    const method = options.method || "GET";
    if (url === `https://gitee.com/api/v5/repos/${repository}`) return Response.json({ private: privateRepo, default_branch: empty ? null : "main" });
    if (url.includes("/releases/download/")) {
      expect(options.headers).toBeUndefined();
      const name = url.split("/").at(-1)!;
      const uploaded = attachments.some((item) => item.name === name);
      const bytes = name === corrupt ? Buffer.from("broken") : files.find((item) => item.name === name)?.bytes;
      return uploaded && bytes ? new Response(bytes) : new Response(null, { status: 404 });
    }
    const release = () => ({ id: 1, tag_name: tag, prerelease: !stable });
    if (method !== "GET") {
      if (url.endsWith("/contents/README.md")) {
        const body = JSON.parse(options.body as string);
        expect(body.branch).toBe("main"); expect(Buffer.from(body.content, "base64").toString()).toContain(repository);
        mutations.push("initialize"); return Response.json({ commit: { sha: "initial-commit" } });
      }
      expect((options.headers as Record<string, string>).Authorization).toBe("Bearer private-token");
      if (url.endsWith("/releases") && method === "POST") { created = true; mutations.push("create"); expect(JSON.parse(options.body as string).prerelease).toBe(true); return Response.json(release()); }
      if (url.endsWith("/attach_files") && method === "POST") {
        const name = ((options.body as FormData).get("file") as File).name;
        mutations.push(`upload:${name}`);
        if (name === failUpload) return new Response(null, { status: 500 });
        attachments.push({ name }); return Response.json({ name });
      }
      if (method === "PATCH") { stable = true; mutations.push("publish"); return Response.json(release()); }
    }
    if (url.includes("/attach_files?")) return Response.json(attachments);
    return created ? Response.json(release()) : new Response(null, { status: 404 });
  });
  return { fetcher, mutations };
}
const options = { repository, tag, targetCommitish: "main", notes: "版本说明", token: "private-token", files };
describe("Gitee release publication", () => {
  it("uploads APK before manifest and only publishes after anonymous byte verification", async () => {
    const { fetcher, mutations } = server();
    expect(await publishGitee(options, fetcher)).toContain(`/releases/tag/${tag}`);
    expect(mutations).toEqual(["create", ...files.map((item) => `upload:${item.name}`), "publish"]);
    const finalOptions = fetcher.mock.calls.at(-1)![1]; expect(finalOptions?.headers).toBeUndefined();
  });
  it("initializes a new empty public repository before creating a release", async () => {
    const { fetcher, mutations } = server({ empty: true });
    await publishGitee(options, fetcher);
    expect(mutations.slice(0, 2)).toEqual(["initialize", "create"]);
  });
  it("refuses to publish into a private repository", async () => {
    const { fetcher, mutations } = server({ privateRepo: true });
    await expect(publishGitee(options, fetcher)).rejects.toThrow("必须公开");
    expect(mutations).toEqual([]);
  });
  it("can resume a staged release without duplicating attachments", async () => {
    const { fetcher, mutations } = server({ existing: true });
    await publishGitee(options, fetcher); expect(mutations).toEqual(["publish"]);
  });
  it("verifies an existing public release without overwriting it", async () => {
    const { fetcher, mutations } = server({ existing: true, published: true });
    await publishGitee(options, fetcher); expect(mutations).toEqual([]);
  });
  it("leaves incomplete uploads as prereleases", async () => {
    const { fetcher, mutations } = server({ failUpload: "update.json" });
    await expect(publishGitee(options, fetcher)).rejects.toThrow("HTTP 500");
    expect(mutations).not.toContain("publish");
  });
  it("refuses to publish if downloaded bytes do not match the original artifacts", async () => {
    const { fetcher, mutations } = server({ corrupt: "zijian-0.3.11.apk" });
    await expect(publishGitee(options, fetcher)).rejects.toThrow("校验失败");
    expect(mutations).not.toContain("publish");
  });
  it("does not send credentials to attachment downloads or redirects", async () => {
    const fetcher = vi.fn(async (_url: string, init: RequestInit) => {
      expect(init.headers).toBeUndefined();
      return fetcher.mock.calls.length === 1 ? new Response(null, { status: 302, headers: { Location: "https://foruda.gitee.com/file?token=signed" } }) : new Response(files[0]!.bytes);
    });
    await verifyPublicFile(`https://gitee.com/${repository}/releases/download/${tag}/zijian-0.3.11.apk`, files[0], fetcher);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it("rejects login pages and unrelated redirect domains", async () => {
    await expect(verifyPublicFile("https://gitee.com/file", files[0], async () => new Response("<html>Sign in</html>"))).rejects.toThrow();
    const fetcher = vi.fn(async () => new Response(null, { status: 302, headers: { Location: "https://evil.test/file" } }));
    await expect(verifyPublicFile("https://gitee.com/file", files[0], fetcher)).rejects.toThrow("域名");
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});
