import { request as httpsRequest } from 'node:https';
import { randomBytes } from 'node:crypto';

// Native HTTPS avoids fetch/Undici's independent 300-second headers timeout.
// Metadata requests still use fetch; credentials never enter command arguments.
export function giteeTransport({ fetcher = fetch, request = httpsRequest, timeoutMs = 15 * 60_000 } = {}) {
  return async function transport(url, options = {}) {
    if (!(options.body instanceof FormData)) return fetcher(url, options);
    const file = options.body.get('file');
    if (!(file instanceof Blob) || !/^[A-Za-z0-9._-]+$/.test(file.name)) throw new Error('Gitee 上传文件无效');
    const bytes = Buffer.from(await file.arrayBuffer());
    const boundary = `myreads-${randomBytes(16).toString('hex')}`;
    const prefix = Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${file.name}"\r\nContent-Type: application/octet-stream\r\n\r\n`);
    const suffix = Buffer.from(`\r\n--${boundary}--\r\n`);
    return new Promise((resolve, reject) => {
      let timer;
      const pending = request(url, { method: 'POST', headers: { ...options.headers,
        'Content-Type': `multipart/form-data; boundary=${boundary}`, 'Content-Length': prefix.length + bytes.length + suffix.length,
      } }, (response) => {
        const chunks = []; let size = 0;
        response.on('data', (chunk) => {
          size += chunk.length;
          if (size > 1024 * 1024) pending.destroy(new Error('Gitee 上传响应过大'));
          else chunks.push(chunk);
        });
        response.on('error', reject);
        response.on('aborted', () => reject(new Error('Gitee 上传响应中断')));
        response.on('end', () => {
          clearTimeout(timer);
          resolve(new Response(Buffer.concat(chunks), { status: response.statusCode || 502 }));
        });
      });
      pending.on('error', reject);
      const cleanup = () => clearTimeout(timer);
      pending.on('close', cleanup);
      timer = setTimeout(() => pending.destroy(new Error('Gitee 文件上传超时')), timeoutMs);
      pending.write(prefix); pending.write(bytes); pending.end(suffix);
    });
  };
}
