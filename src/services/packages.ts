import { unzipSync, strFromU8 } from "fflate";
import type { SourceManifest, SourcePackage } from "../types";
const MAX_PACKAGE = 512 * 1024;
export function validatePackage(value: unknown): SourcePackage {
  const pack = value as Partial<SourcePackage> | null;
  const manifest = pack?.manifest as Partial<SourceManifest> | undefined;
  if (!manifest || typeof pack?.script !== "string")
    throw new Error("书源包需要包含 manifest 和 script");
  if (!/^[a-z0-9][a-z0-9.-]{1,63}$/.test(manifest.id ?? ""))
    throw new Error("书源 ID 格式无效");
  for (const key of ["name", "version", "description"] as const) {
    if (typeof manifest[key] !== "string" || manifest[key]!.length > 1000)
      throw new Error(`书源 ${key} 字段无效`);
  }
  if (!manifest.name?.trim() || !manifest.version?.trim())
    throw new Error("书源名称和版本不能为空");
  if (manifest.apiVersion !== 1)
    throw new Error("此书源的接口版本不受支持，目前支持 API 1");
  if (
    !Array.isArray(manifest.domains) ||
    manifest.domains.length > 16 ||
    !manifest.domains.every(
      (domain) =>
        typeof domain === "string" &&
        /^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z][a-z0-9-]*$/.test(
          domain,
        ) &&
        !domain.endsWith(".local") &&
        !domain.endsWith(".localhost"),
    )
  )
    throw new Error("书源需声明有效的域名列表，不支持通配符或本机地址");
  if (
    !pack.script.trim() ||
    new TextEncoder().encode(pack.script).length > MAX_PACKAGE
  )
    throw new Error("脚本为空或超过 512 KB");
  return { manifest: manifest as SourceManifest, script: pack.script };
}
export function decodePackage(bytes: Uint8Array, name = ""): SourcePackage {
  if (bytes.length > MAX_PACKAGE) throw new Error("书源包不能超过 512 KB");
  if (name.endsWith(".zip") || (bytes[0] === 0x50 && bytes[1] === 0x4b)) {
    // Check ZIP central directory sizes before decompression to reject oversized entries.
    let declaredSize = 0,
      count = 0;
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    for (let i = 0; i + 46 <= bytes.length; i++) {
      if (view.getUint32(i, true) === 0x02014b50) {
        declaredSize += view.getUint32(i + 24, true);
        count++;
        if (declaredSize > MAX_PACKAGE || count > 4)
          throw new Error("书源解压后的内容过大");
      }
    }
    if (!count) throw new Error("ZIP 书源包目录无效");
    const files = unzipSync(bytes, {
      filter: (file) =>
        ["manifest.json", "source.js"].includes(file.name) &&
        file.originalSize <= MAX_PACKAGE,
    });
    if (!files["manifest.json"] || !files["source.js"])
      throw new Error("ZIP 根目录需要 manifest.json 和 source.js");
    return validatePackage({
      manifest: JSON.parse(strFromU8(files["manifest.json"])),
      script: strFromU8(files["source.js"]),
    });
  }
  try {
    return validatePackage(JSON.parse(strFromU8(bytes)));
  } catch (error) {
    if (error instanceof SyntaxError)
      throw new Error("无法解析书源包，请选择 JSON 或 ZIP 格式");
    throw error;
  }
}
export function checkRequestUrl(value: string, domains: string[]): URL {
  const url = new URL(value);
  if (
    url.protocol !== "https:" ||
    url.username ||
    url.password ||
    (url.port && url.port !== "443")
  )
    throw new Error("书源只允许访问标准 HTTPS 地址");
  if (!domains.includes(url.hostname))
    throw new Error(`书源未声明访问域名：${url.hostname}`);
  return url;
}
