const BASE = 'https://www.quanben.io';
const text = node => node?.textContent?.trim() || '';
const absolute = path => new URL(path, BASE).href;
const get = async (url, referer) => sdk.request({ url, headers: referer ? { Referer: referer } : {} });
function bookCards(doc) {
  return [...doc.querySelectorAll('.list2[itemscope]')].map(node => {
    const link = node.querySelector('h3 a');
    if (!link) return null;
    const url = absolute(link.getAttribute('href'));
    return { id: url, url, title: text(link), author: text(node.querySelector('[itemprop="author"]')), description: text(node.querySelector('[itemprop="description"]')), cover: node.querySelector('img')?.src };
  }).filter(Boolean);
}
const source = {
  async search(keyword, cursor) {
    const url = cursor || `${BASE}/index.php?c=book&a=search&keywords=${encodeURIComponent(keyword)}`;
    const doc = sdk.parseHTML(await get(url));
    const next = [...doc.querySelectorAll('a')].find(a => text(a) === '下一页');
    return { items: bookCards(doc), nextCursor: next ? absolute(next.getAttribute('href')) : undefined };
  },
  async getBook(ref) {
    const url = ref.url || ref.id;
    const doc = sdk.parseHTML(await get(url));
    const node = doc.querySelector('[itemtype="http://schema.org/Book"]');
    if (!node) throw Error('书籍页面结构已变化');
    const status = [...node.querySelectorAll('p')].find(p => text(p).startsWith('状态'));
    return { id: url, url, title: text(node.querySelector('h3 [itemprop="name"]')), author: text(node.querySelector('[itemprop="author"]')), description: text(node.querySelector('[itemprop="description"]')), category: text(node.querySelector('[itemprop="category"]')), status: text(status).replace(/^状态[:：]\s*/, ''), cover: node.querySelector('img')?.getAttribute('src') };
  },
  async getChapters(ref) {
    const url = new URL('list.html', ref.url || ref.id).href;
    const html = await get(url);
    const doc = sdk.parseHTML(html);
    const detail = doc.querySelector('#detail');
    if (detail) {
      const bookId = html.match(/load_more\(['"]([^'"]+)['"]\)/)?.[1];
      const callback = html.match(/var callback\s*=\s*['"]([^'"]+)['"]/)?.[1];
      const chars = html.match(/staticchars\s*=\s*['"]([^'"]+)['"]/)?.[1];
      if (!bookId || !callback || !chars || chars.length !== 62) throw Error('目录展开规则已变化，请更新书源');
      // Same parameter encoding used by the site's public load_more button.
      const randomChar = () => chars[Math.floor(Math.random() * chars.length)];
      const encoded = [...callback].map(c => randomChar() + (chars.includes(c) ? chars[(chars.indexOf(c) + 3) % 62] : c) + randomChar()).join('');
      const endpoint = `${BASE}/index.php?c=book&a=list.jsonp&callback=${encodeURIComponent(callback)}&book_id=${encodeURIComponent(bookId)}&b=${encodeURIComponent(encoded)}`;
      const response = await get(endpoint, url);
      const match = response.match(/^\s*[a-zA-Z_$][\w$]*\s*\(([\s\S]*)\);?\s*$/);
      if (!match) throw Error('无法展开完整目录：' + response.slice(0,80));
      const data = JSON.parse(match[1]);
      if (typeof data.content !== 'string') throw Error('目录响应格式已变化');
      const parsed = sdk.parseHTML(data.content);
      detail.replaceChildren(...parsed.body.childNodes);
    }
    const seen = new Set();
    const items = [...doc.querySelectorAll('ul.list3 a')].map(a => {
      const url = absolute(a.getAttribute('href')); return { id: url, url, title: text(a) };
    }).filter(item => { if (seen.has(item.id)) return false; seen.add(item.id); return true; });
    if (!items.length) throw Error('没有找到章节目录');
    return { items };
  },
  async getChapter(ref) {
    const doc = sdk.parseHTML(await get(ref.url || ref.id));
    const content = doc.querySelector('#content');
    if (!content) throw Error('正文页面结构已变化');
    content.querySelectorAll('script, style, #ad').forEach(node => node.remove());
    return { title: text(doc.querySelector('h1')), paragraphs: [...content.querySelectorAll('p')].map(text).filter(Boolean) };
  }
};
