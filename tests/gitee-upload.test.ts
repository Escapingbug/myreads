// @vitest-environment node
import { describe, it, expect, vi } from "vitest";
import { createServer } from "node:http";
import type { IncomingMessage, ServerResponse } from "node:http";
// @ts-ignore Plain Node publication tooling.
import { giteeTransport } from "../scripts/lib/gitee-upload.mjs";
async function withServer(handler: (request: IncomingMessage, response: ServerResponse) => void, run: (url: string) => Promise<void>) {
  const server = createServer(handler);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as { port: number }).port;
  try { await run(`http://127.0.0.1:${port}/upload`); }
  finally { server.closeAllConnections(); await new Promise<void>((resolve) => server.close(() => resolve())); }
}
function options() {
  const form = new FormData(); form.set("file", new Blob(["signed-apk-bytes"]), "zijian-0.3.11.apk");
  return { method: "POST", headers: { Authorization: "Bearer test-token" }, body: form };
}
describe("Gitee multipart transport", () => {
  it("sends complete multipart bytes with content length and authorization", async () => {
    await withServer(async (req, res) => {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const bytes = Buffer.concat(chunks);
      expect(Number(req.headers["content-length"])).toBe(bytes.length);
      expect(req.headers.authorization).toBe("Bearer test-token");
      expect(req.headers["content-type"]).toContain("multipart/form-data; boundary=");
      expect(bytes.toString()).toContain('filename="zijian-0.3.11.apk"');
      expect(bytes.toString()).toContain("signed-apk-bytes");
      res.writeHead(201, { "Content-Type": "application/json" }); res.end(JSON.stringify({ id: 1 }));
    }, async (url) => {
      const result = await giteeTransport({ allowHttp: true })(url, options());
      expect(result.status).toBe(201); expect(await result.json()).toEqual({ id: 1 });
    });
  });
  it("enforces the upload timeout even if the server never responds", async () => {
    await withServer((req) => req.resume(), async (url) => {
      await expect(giteeTransport({ allowHttp: true, timeoutMs: 60 })(url, options())).rejects.toThrow("上传超时");
    });
  });
  it("does not follow redirects carrying upload credentials", async () => {
    await withServer((req, res) => { req.resume(); res.writeHead(302, { Location: "https://unrelated.test" }); res.end(); }, async (url) => {
      const result = await giteeTransport({ allowHttp: true })(url, options()); expect(result.status).toBe(302);
    });
  });
  it("keeps metadata requests on the original fetch implementation", async () => {
    const fetcher = vi.fn(async () => Response.json({ id: 1 }));
    await giteeTransport({ fetcher })("https://gitee.com/api/v5/user", { headers: { Accept: "application/json" } });
    expect(fetcher).toHaveBeenCalledOnce();
  });
});
