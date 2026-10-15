import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { unzipSync, strFromU8 } from 'fflate';
import { publishGitee } from './lib/gitee-release.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const config = JSON.parse(await readFile(join(root, 'android/app/src/main/assets/distribution.json'), 'utf8'));
const repository = config.giteeRepository;
if (!repository && process.argv.includes('--if-configured')) {
  console.log('尚未配置 Gitee 仓库，跳过同步发布。');
  process.exit(0);
}
if (!/^[A-Za-z0-9_-]+\/[A-Za-z0-9_.-]+$/.test(repository)) throw new Error('请先在 distribution.json 配置 giteeRepository（用户名/仓库名）并重新构建 APK');
const manifest = JSON.parse(await readFile(join(root, 'release/update.json'), 'utf8'));
const tag = process.argv.slice(2).find((arg) => !arg.startsWith('--')) || `v${manifest.versionName}`;
if (!/^v\d+\.\d+\.\d+$/.test(tag) || tag !== `v${manifest.versionName}` || manifest.packageName !== 'io.myreads.app'
    || manifest.schemaVersion !== 1 || !Number.isSafeInteger(manifest.versionCode) || manifest.versionCode < 1
    || manifest.apkName !== `zijian-${manifest.versionName}.apk` || !/^[a-f0-9]{64}$/.test(manifest.sha256)) throw new Error('发布清单与版本标签不符');
const notes = await readFile(join(root, `docs/releases/${manifest.versionName}.md`), 'utf8');
// The exact GitHub artifacts are reused, including the APK signature and hash.
const files = [];
for (const name of ['SHA256SUMS', manifest.apkName, 'update.json']) {
  const bytes = await readFile(join(root, 'release', name));
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  if (name === manifest.apkName) {
    if (sha256 !== manifest.sha256 || bytes.length !== manifest.size) throw new Error('APK 与更新清单不一致');
    const bundled = unzipSync(bytes, { filter: (entry) => entry.name === 'assets/distribution.json' })['assets/distribution.json'];
    if (!bundled || JSON.parse(strFromU8(bundled)).giteeRepository !== repository)
      throw new Error('APK 内的 Gitee 仓库配置不一致，请重新构建并准备发布资产');
  }
  if (name === 'SHA256SUMS' && bytes.toString('utf8') !== `${manifest.sha256}  ${manifest.apkName}\n`) throw new Error('SHA256SUMS 与清单不一致');
  files.push({ name, bytes, size: bytes.length, sha256 });
}
if (process.argv.includes('--dry-run')) {
  console.log(JSON.stringify({ repository, tag, files: files.map(({ bytes, ...file }) => file) }, null, 2));
} else {
  const url = await publishGitee({ repository, token: process.env.GITEE_TOKEN, tag,
    targetCommitish: process.env.GITEE_TARGET_BRANCH || undefined, notes, files });
  console.log(`Gitee 发布完成并通过匿名下载校验：${url}`);
}
