import { createHash } from 'node:crypto';

const allowedHosts = new Set(['gitee.com', 'foruda.gitee.com']);
export function releaseFile(repository, tag, name) {
  return `https://gitee.com/${repository}/releases/download/${tag}/${name}`;
}
export async function verifyPublicFile(url, file, fetcher = fetch) {
  // No credentials: publishing is useful only if the installed app can download it.
  const signal = AbortSignal.timeout(180_000);
  for (let redirects = 0; redirects <= 6; redirects++) {
    const parsed = new URL(url);
    if (parsed.protocol !== 'https:' || !allowedHosts.has(parsed.hostname) || parsed.port || parsed.username || parsed.password || parsed.hash)
      throw new Error('Gitee 附件跳转到未获允许的下载域名');
    const response = await fetcher(url, { redirect: 'manual', signal });
    if ([301, 302, 303, 307, 308].includes(response.status)) {
      const location = response.headers.get('location');
      await response.body?.cancel();
      if (!location) throw new Error('Gitee 下载跳转缺少地址');
      url = new URL(location, url).href; continue;
    }
    if (response.status !== 200 || !response.body) {
      await response.body?.cancel();
      throw new Error(`Gitee 附件无法匿名下载（HTTP ${response.status}）`);
    }
    const hash = createHash('sha256'); let size = 0;
    for await (const chunk of response.body) {
      size += chunk.length;
      if (size > file.size) throw new Error(`Gitee 附件大小不符：${file.name}`);
      hash.update(chunk);
    }
    if (size !== file.size || hash.digest('hex') !== file.sha256) throw new Error(`Gitee 附件校验失败：${file.name}`);
    return;
  }
  throw new Error('Gitee 附件重定向次数过多');
}
export async function publishGitee({ repository, token, tag, targetCommitish, notes, files }, fetcher = fetch) {
  if (!/^[A-Za-z0-9_-]+\/[A-Za-z0-9_.-]+$/.test(repository) || !/^v\d+\.\d+\.\d+$/.test(tag)) throw new Error('Gitee 仓库或版本标签无效');
  if (!token) throw new Error('请在发布环境配置 GITEE_TOKEN');
  const base = `https://gitee.com/api/v5/repos/${repository}`;
  async function api(path, method = 'GET', body, missing = false) {
    const multipart = body instanceof FormData;
    const response = await fetcher(base + path, { method, redirect: 'error', signal: AbortSignal.timeout(multipart ? 15 * 60_000 : 180_000),
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/json', ...(body && !multipart ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? multipart ? body : JSON.stringify(body) : undefined });
    if (missing && response.status === 404) return undefined;
    // Never print a response body: it can contain credentials or signed URLs.
    if (!response.ok) throw new Error(`Gitee 发布接口失败：${method} ${path}（HTTP ${response.status}）`);
    return response.status === 204 ? undefined : response.json();
  }
  const project = await api('');
  if (project.private !== false && project.public !== true) throw new Error('Gitee 发布仓库必须公开');
  const branch = targetCommitish || project.default_branch || 'main';
  if (!project.default_branch) {
    const readme = `# 纸间 · myreads\n\nAndroid 小说阅读器，支持离线阅读和本地听书。\n\n[下载最新 APK](https://gitee.com/${repository}/releases/latest) · [GitHub 备用下载](https://github.com/escapingbug/myreads/releases/latest)\n\n此仓库用于国内下载与自动更新。源代码、版本标签与构建记录见 [GitHub](https://github.com/escapingbug/myreads)。\n`;
    await api('/contents/README.md', 'POST', { branch, message: 'Initialize domestic release repository', content: Buffer.from(readme).toString('base64') });
  }
  let release = await api(`/releases/tags/${tag}`, 'GET', undefined, true);
  if (!release) release = await api('/releases', 'POST', {
    tag_name: tag, target_commitish: branch, name: `纸间 ${tag}`, body: notes, prerelease: true,
  });
  if (!Number.isSafeInteger(release.id) || release.tag_name !== tag || typeof release.prerelease !== 'boolean') throw new Error('Gitee 返回的发行版信息无效');
  const attachments = await api(`/releases/${release.id}/attach_files?per_page=100&page=1&direction=desc`);
  if (!Array.isArray(attachments) || attachments.length >= 100) throw new Error('Gitee 附件列表无效或超出预期');
  for (const file of files) {
    const matching = attachments.filter((item) => item.name === file.name);
    if (matching.length > 1) throw new Error(`Gitee 有同名重复附件：${file.name}`);
    if (!matching.length) {
      if (!release.prerelease) throw new Error('已公开的 Gitee 发行版缺少附件，请人工检查；不会覆盖正式版本');
      console.log(`正在上传 ${file.name}（${file.size} bytes）`);
      const form = new FormData(); form.set('file', new Blob([file.bytes]), file.name);
      await api(`/releases/${release.id}/attach_files`, 'POST', form);
    }
    // Manifest last. Partial uploads remain prereleases and invisible
    // to the app. Re-running verifies existing bytes instead of duplicating them.
    console.log(`正在匿名校验 ${file.name}`);
    await verifyPublicFile(releaseFile(repository, tag, file.name), file, fetcher);
  }
  if (release.prerelease) await api(`/releases/${release.id}`, 'PATCH', { tag_name: tag, name: `纸间 ${tag}`, body: notes, prerelease: false });
  const publicRelease = await fetcher(`${base}/releases/tags/${tag}`, { redirect: 'error', signal: AbortSignal.timeout(15_000) });
  if (publicRelease.status !== 200) throw new Error('发布后无法匿名读取 Gitee 发行版');
  const published = await publicRelease.json();
  if (published.prerelease !== false || published.tag_name !== tag) throw new Error('Gitee 发行版尚未转为正式版本');
  return `https://gitee.com/${repository}/releases/tag/${tag}`;
}
