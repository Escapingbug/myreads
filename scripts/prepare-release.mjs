import { readFile, writeFile, mkdir, copyFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const pkg = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
const tag = process.argv[2] || `v${pkg.version}`;
if (!/^v\d+\.\d+\.\d+$/.test(tag) || tag !== `v${pkg.version}` || !Number.isSafeInteger(pkg.androidVersionCode) || pkg.androidVersionCode < 1)
  throw new Error('版本标签必须匹配 package.json，androidVersionCode 必须为递增整数');
const input = join(root, 'android/app/build/outputs/apk/release/app-release.apk');
const apk = await readFile(input);
const sha256 = createHash('sha256').update(apk).digest('hex');
const apkName = `zijian-${pkg.version}.apk`;
const manifest = { schemaVersion: 1, packageName: 'io.myreads.app', versionName: pkg.version,
  versionCode: pkg.androidVersionCode, apkName, size: apk.length, sha256 };
const output = join(root, 'release');
await mkdir(output, { recursive: true });
await copyFile(input, join(output, apkName));
await writeFile(join(output, 'update.json'), JSON.stringify(manifest, null, 2) + '\n');
await writeFile(join(output, 'SHA256SUMS'), `${sha256}  ${apkName}\n`);
console.log(`已准备 ${tag}：${apkName} / update.json / SHA256SUMS`);
