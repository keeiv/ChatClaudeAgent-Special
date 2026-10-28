// CCA Haiku's editor page at phone size in Edge, against the scripted host (android/haiku/web/mock-host.js).
// Serves android/haiku/web itself; needs Playwright and Edge. The Android host is covered by build.py --test.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');

const web = path.resolve(__dirname, '..', 'android', 'haiku', 'web');
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8' };

(async () => {
  const server = http.createServer((req, res) => {
    const file = path.join(web, decodeURIComponent(new URL(req.url, 'http://x').pathname).replace(/^\/+/, '') || 'index.html');
    if (!file.startsWith(web)) { res.writeHead(403).end(); return; }
    fs.readFile(file, (err, data) => { if (err) res.writeHead(404).end(); else res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'text/plain' }).end(data); });
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}/index.html`;
  const checks = [], errors = [];
  const check = (value, label) => { assert.ok(value, label); checks.push(label); };
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const phone = { viewport: { width: 412, height: 915 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'zh-TW' };
  try {
    // ---- first run: no key, no project
    let context = await browser.newContext(phone);
    let page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    page.on('dialog', d => d.accept());
    await page.goto(base);
    await page.getByText('在手機上輕鬆寫網頁').waitFor();
    check(await page.getByText('這不是遠端操作電腦', { exact: false }).isVisible(), 'the first screen says what Haiku is and is not');
    await page.getByRole('button', { name: '設定 API 金鑰' }).click();
    await page.getByRole('dialog', { name: '設定' }).waitFor();
    check(await page.getByText('和 Claude／ChatGPT 的訂閱分開', { exact: false }).isVisible(), 'API billing is stated before a key is entered');
    await page.getByLabel('API 金鑰').fill('sk-test-key-1234567890');
    await page.getByRole('button', { name: '儲存金鑰' }).click();
    await page.getByRole('combobox', { name: '模型' }).waitFor();
    const options = await page.getByRole('combobox', { name: '模型' }).locator('option').allInnerTexts();
    check(options.some(o => o === 'Claude Haiku 5.5（預設）'), 'the newest Haiku model of the list is the default');
    check(await page.evaluate(() => !document.body.innerHTML.includes('sk-test-key')), 'the key is not kept in the page');
    await page.getByRole('button', { name: 'GPT（OpenAI API）' }).click();
    await page.getByLabel('API 金鑰').fill('sk-test-openai-123456');
    await page.getByRole('button', { name: '儲存金鑰' }).click();
    await page.getByRole('combobox', { name: '模型' }).waitFor();
    const gpt = await page.getByRole('combobox', { name: '模型' }).locator('option').allInnerTexts();
    check(gpt.length === 2 && gpt.some(o => o.startsWith('gpt-6-mini')) && !gpt.some(o => /embedding|whisper/.test(o)),
      'only chat models from the provider list are offered');
    await page.getByRole('button', { name: 'Claude（Anthropic API）' }).click();
    await page.getByRole('button', { name: '完成' }).click();
    await page.getByRole('button', { name: '選擇或建立專案' }).click();
    await page.getByLabel('新專案名稱').fill('記帳小工具');
    await page.getByRole('button', { name: '建立', exact: true }).click();
    await page.getByRole('button', { name: '記帳小工具' }).waitFor();
    check(await page.getByRole('tab', { name: /檔案 3/ }).isVisible(), 'a new project starts with three files');

    // ---- a turn: the model writes a file through the host, the reply is plain text
    await page.getByLabel('訊息').fill('做一個記帳小工具 <img src=x onerror=alert(1)>');
    await page.getByRole('button', { name: '送出' }).click();
    await page.getByText('修改了 index.html', { exact: false }).waitFor({ timeout: 10000 });
    const request = await page.evaluate(() => JSON.parse(window.mockSent.find(m => m.method === 'model.send').params.body));
    check(request.model === 'claude-haiku-5-5' && request.tools.map(t => t.name).join() === 'list_files,read_file,write_file,delete_file',
      'the request uses the chosen model and only the four project tools');
    check(await page.locator('.msg.user .bubble').first().innerText().then(t => t.includes('<img src=x')) && await page.locator('.msg img').count() === 0,
      'user and model text are shown as text, never as HTML');
    check(await page.locator('.msg.ai code').first().innerText() === 'index.html', 'inline code in the reply is formatted');
    check(await page.getByText('寫入 index.html').isVisible(), 'the file operation is listed in the turn');
    await page.screenshot({ path: 'build/haiku-chat.png' });
    await page.getByRole('button', { name: '預覽' }).last().click();
    await page.waitForFunction(() => window.mockPreview === '記帳小工具');
    check(true, 'preview opens the current project');
    await page.getByRole('button', { name: '還原這一輪' }).click();
    await page.locator('#main').getByText('已還原到這一輪之前').waitFor();
    const restored = await page.evaluate(() => new Promise(resolve => {
      const id = 'probe'; const original = window.Haiku.receive;
      window.Haiku.receive = text => { const m = JSON.parse(text); if (m.id === id) { window.Haiku.receive = original; resolve(m.result); } else original(text); };
      window.HaikuHost.call(JSON.stringify({ id, method: 'files.read', params: { project: '記帳小工具', path: 'index.html' } }));
    }));
    check(!restored.includes('id="add"'), 'undo puts the project files back as they were before the turn');

    // ---- files tab: edit a file and add one
    await page.getByRole('tab', { name: /檔案/ }).click();
    check(await page.evaluate(() => [...document.querySelector('#app').childNodes].every(n => n.nodeType === Node.ELEMENT_NODE)),
      'the files tab leaves no stray text (a skipped composer once printed "null")');
    await page.getByRole('button', { name: /style\.css/ }).click();
    await page.getByRole('textbox', { name: 'style.css' }).fill('body { color: teal; }\n');
    await page.getByRole('button', { name: '儲存' }).click();
    await page.getByRole('status').getByText('已儲存 style.css').waitFor();
    check(true, 'a file can be edited by hand');
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'no horizontal overflow at phone width');
    await page.screenshot({ path: 'build/haiku-files.png' });
    await context.close();

    // ---- an existing project in the dark scheme
    context = await browser.newContext({ ...phone, colorScheme: 'dark' });
    page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(base + '#keyed,project');
    await page.getByRole('button', { name: '記帳小工具' }).waitFor();
    check(await page.evaluate(() => getComputedStyle(document.body).backgroundColor) === 'rgb(20, 22, 24)', 'the dark scheme follows the phone');
    await page.screenshot({ path: 'build/haiku-dark.png' });
    await context.close();
    check(errors.length === 0, 'no page errors: ' + errors.join('; '));
    console.log(`${checks.length} Haiku browser checks passed`);
  } finally {
    await browser.close();
    server.close();
  }
})().catch(e => { console.error(e); process.exitCode = 1; });
