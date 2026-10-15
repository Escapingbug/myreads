import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, writeFile, readFile, rm, chmod } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
const execute = promisify(execFile);

// curl negotiates HTTP/2 where available and has one explicit upload deadline.
// Authorization is read from a private temporary file, never command arguments.
export function giteeTransport({ fetcher = fetch, timeoutMs = 15 * 60_000, allowHttp = false } = {}) {
  return async function transport(url, options = {}) {
    if (!(options.body instanceof FormData)) return fetcher(url, options);
    const parsed = new URL(url);
    if (parsed.protocol !== 'https:' && !(allowHttp && parsed.protocol === 'http:')) throw new Error('Gitee 上传必须使用 HTTPS');
    const file = options.body.get('file');
    if (!(file instanceof Blob) || !/^[A-Za-z0-9._-]+$/.test(file.name)) throw new Error('Gitee 上传文件无效');
    const directory = await mkdtemp(join(tmpdir(), 'myreads-gitee-'));
    try {
      await chmod(directory, 0o700);
      const input = join(directory, file.name), headers = join(directory, 'headers'), output = join(directory, 'response');
      await writeFile(input, Buffer.from(await file.arrayBuffer()), { mode: 0o600 });
      await writeFile(headers, Object.entries(options.headers || {}).map(([name, value]) => `${name}: ${value}`).join('\n') + '\n', { mode: 0o600 });
      await writeFile(output, '', { mode: 0o600 });
      const args = ['--silent', '--show-error', '--proto', allowHttp ? '=http,https' : '=https', '--connect-timeout', '30',
        '--max-time', String(timeoutMs / 1000), '--max-filesize', '1048576', '--request', 'POST', '--header', '@' + headers,
        '--form', `file=@${input};filename=${file.name}`, '--output', output, '--write-out', '%{http_code} %{size_upload} %{time_total}', url];
      let stats;
      try { stats = (await execute('curl', args, { maxBuffer: 1024 * 1024 })).stdout.trim(); }
      catch (error) {
        const metrics = /^\d{3} [\d.]+ [\d.]+$/.test(error.stdout?.trim() || '') ? error.stdout.trim() : '无传输统计';
        throw new Error(`Gitee 文件上传失败（curl ${error.code === 28 ? '上传超时' : error.code || '连接中断'}；HTTP/发送字节/耗时：${metrics}）`);
      }
      if (!/^\d{3} [\d.]+ [\d.]+$/.test(stats)) throw new Error('Gitee 上传状态无效');
      const [status, sent, elapsed] = stats.split(' ');
      console.log(`Gitee 上传结果：HTTP ${status}，发送 ${sent} bytes，耗时 ${elapsed}s`);
      const code = Number(status);
      return new Response([204, 205, 304].includes(code) ? null : await readFile(output), { status: code });
    } finally { await rm(directory, { recursive: true, force: true }); }
  };
}
