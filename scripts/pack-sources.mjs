import fs from 'node:fs';
import { zipSync, strToU8 } from 'fflate';
const manifests = {
  quanben: { id: 'io.quanben', name: '全本小说', version: '1.0.0', apiVersion: 1, author: '纸间', domains: ['www.quanben.io'], description: '搜索并下载全本网站的公开章节。目录以网站实际提供的列表为准，原站可能存在缺章。' },
  demo: { id: 'io.myreads.demo', name: '纸间演示', version: '1.0.0', apiVersion: 1, author: '纸间', domains: [], description: '三本原创演示短篇，无需网络，可用于验证下载和阅读。' }
};
for (const [name, manifest] of Object.entries(manifests)) {
  const script = fs.readFileSync(`public/sources/${name}.source.js`, 'utf8');
  fs.writeFileSync(`public/sources/${name}.booksource`, JSON.stringify({manifest, script}, null, 2));
  fs.writeFileSync(`public/sources/${name}.zip`, zipSync({'manifest.json': strToU8(JSON.stringify(manifest,null,2)), 'source.js': strToU8(script)}));
}
