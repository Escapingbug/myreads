import type { Plugin } from 'vite';
import { lookup } from 'node:dns/promises';
import https from 'node:https';
import { isIP } from 'node:net';
import { Buffer } from 'node:buffer';

export function isPublicAddress(address: string): boolean {
  if (isIP(address) === 4) {
    const [a,b] = address.split('.').map(Number);
    return !(a === 0 || a === 10 || a === 127 || a >= 224 || (a === 169 && b === 254) || (a === 172 && b! >= 16 && b! <= 31) || (a === 192 && b === 168) || (a === 100 && b! >= 64 && b! <= 127) || (a === 198 && (b === 18 || b === 19)));
  }
  if (isIP(address) === 6) {
    const lower = address.toLowerCase();
    // Only ordinary global unicast; reject mapped IPv4, loopback and local scopes.
    return /^[23]/.test(lower) && !lower.startsWith('2001:db8:');
  }
  return false;
}
async function fetchText(url: URL, headers: Record<string,string>, method: string, body?: string) {
  const addresses = await lookup(url.hostname, { all: true });
  if (!addresses.length || !addresses.every(a => isPublicAddress(a.address))) throw new Error('开发代理只允许访问公共网站');
  const selected = addresses.find(a => a.family === 4) ?? addresses[0]!;
  return new Promise<Buffer>((resolve, reject) => {
    const request = https.request(url, {
      method, headers: { ...headers, 'accept-encoding': 'identity' },
      // Pin the address we validated rather than doing a second DNS resolution.
      lookup: (_host, options, callback) => options.all
        ? callback(null, [{ address: selected.address, family: selected.family }])
        : callback(null, selected.address, selected.family),
    }, response => {
      if (!response.statusCode || response.statusCode < 200 || response.statusCode >= 300) { response.resume(); reject(new Error(`HTTP ${response.statusCode}（不自动跟随重定向）`)); return; }
      const chunks: Buffer[] = []; let size = 0;
      response.on('data', chunk => { size += chunk.length; if (size > 8 * 1024 * 1024) response.destroy(new Error('响应超过 8 MB')); else chunks.push(chunk); });
      response.on('end', () => resolve(Buffer.concat(chunks)));
      response.on('error', reject);
    });
    const timer = setTimeout(() => request.destroy(new Error('网站请求超时')), 25000);
    request.on('close', () => clearTimeout(timer)); request.on('error', reject);
    if (body) request.write(body);
    request.end();
  });
}
export function sourceProxy(): Plugin {
  return {
    name: 'local-source-preview',
    configureServer(server) {
      server.middlewares.use('/__source/request', async (request, response) => {
        response.setHeader('Content-Type', 'application/json; charset=utf-8');
        try {
          if (request.method !== 'POST') throw new Error('仅允许 POST');
          const host = request.headers.host ?? '';
          if (!/^(127\.0\.0\.1|localhost)(:\d+)?$/.test(host)) throw new Error('此代理仅用于本机开发');
          const origin = request.headers.origin;
          if (origin && origin !== `http://${host}`) throw new Error('请求来源不匹配');
          let input = '';
          for await (const chunk of request) { input += chunk; if (Buffer.byteLength(input) > 64 * 1024) throw new Error('请求内容过大'); }
          const options = JSON.parse(input);
          const url = new URL(options.url);
          if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443') || isIP(url.hostname) || !Array.isArray(options.domains) || !options.domains.includes(url.hostname)) throw new Error('目标地址无效或未声明');
          if (!['GET','POST'].includes(options.method ?? 'GET')) throw new Error('方法无效');
          const headers: Record<string,string> = {};
          for (const [key,value] of Object.entries(options.headers ?? {})) {
            if (!['accept','accept-language','referer','content-type'].includes(key.toLowerCase()) || typeof value !== 'string' || /[\r\n]/.test(value)) throw new Error('请求头无效');
            headers[key] = value;
          }
          const bytes = await fetchText(url, headers, options.method ?? 'GET', options.body);
          response.end(JSON.stringify(options.format === 'base64' ? { base64: bytes.toString('base64') } : { text: bytes.toString('utf8') }));
        } catch (error) { response.statusCode = 400; response.end(JSON.stringify({ error: (error as Error).message })); }
      });
    },
  };
}
