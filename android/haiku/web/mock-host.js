// A scripted stand-in for the Android host, for desktop development, tests and launch shots. Not packaged into
// the APK. Projects live in memory; the "model" answers in the provider's real reply format and writes a page.
'use strict';
(() => {
  const flags = location.hash.slice(1).split(',');
  const projects = new Map();
  const chats = new Map();
  const checkpoints = new Map();
  const keys = { anthropic: flags.includes('keyed'), openai: false };
  const sent = window.mockSent = [];
  let step = 0;
  const starter = name => new Map([
    ['index.html', `<!doctype html>\n<html lang="zh-Hant-TW">\n<head>\n  <meta charset="utf-8">\n  <title>${name}</title>\n  <link rel="stylesheet" href="style.css">\n</head>\n<body>\n  <main><h1>${name}</h1></main>\n  <script src="app.js"></script>\n</body>\n</html>\n`],
    ['style.css', 'body { margin: 0; font-family: system-ui, sans-serif; }\n'],
    ['app.js', '// 頁面載入後執行。\n'],
  ]);
  if (flags.includes('project')) projects.set('記帳小工具', starter('記帳小工具'));
  const files = name => { const p = projects.get(name); if (!p) throw new Error('找不到專案'); return p; };
  const safe = path => { if (!path || path.startsWith('/') || path.includes('..') || path.includes('\\') || path.startsWith('.haiku')) throw new Error('只能使用專案內的相對路徑'); return path; };
  const models = {
    anthropic: { data: [{ id: 'claude-sonnet-5-5', display_name: 'Claude Sonnet 5.5' }, { id: 'claude-haiku-5-5', display_name: 'Claude Haiku 5.5' },
      { id: 'claude-haiku-4-5', display_name: 'Claude Haiku 4.5' }, { id: 'claude-opus-5-5', display_name: 'Claude Opus 5.5' }] },
    openai: { data: [{ id: 'gpt-6-mini' }, { id: 'gpt-6' }, { id: 'text-embedding-3-small' }, { id: 'whisper-1' }] },
  };
  const page = request => `<!doctype html>\n<html lang="zh-Hant-TW">\n<head>\n  <meta charset="utf-8">\n  <meta name="viewport" content="width=device-width, initial-scale=1">\n  <title>記帳小工具</title>\n  <link rel="stylesheet" href="style.css">\n</head>\n<body>\n  <main>\n    <h1>記帳</h1>\n    <form id="add"><input id="item" placeholder="項目"><input id="amount" type="number" placeholder="金額"><button>加入</button></form>\n    <ul id="list"></ul>\n    <p>合計 <strong id="total">0</strong> 元</p>\n  </main>\n  <!-- ${request.replace(/--/g, '')} -->\n  <script src="app.js"></script>\n</body>\n</html>\n`;
  // Anthropic Messages replies: first write the page, then report.
  function anthropic(body) {
    const last = body.messages.at(-1);
    if (Array.isArray(last.content) && last.content.some(b => b.type === 'tool_result')) {
      return { content: [{ type: 'text', text: '做好了：`index.html` 加入記帳表單、清單與合計，`app.js` 會在加入項目時更新合計。\n\n按「預覽」，輸入項目與金額後按「加入」。' }],
        stop_reason: 'end_turn', usage: { input_tokens: 2410, output_tokens: 182 } };
    }
    return { content: [{ type: 'text', text: '我先改寫首頁。' },
      { type: 'tool_use', id: 'tool-' + (++step), name: 'write_file', input: { path: 'index.html', content: page(String(last.content)) } }],
      stop_reason: 'tool_use', usage: { input_tokens: 1980, output_tokens: 640 } };
  }
  function handle(method, p) {
    switch (method) {
      case 'projects.list': return [...projects.keys()].map(name => ({ name, files: files(name).size, updated: Date.now() }));
      case 'projects.create':
        if (projects.has(p.name)) throw new Error('已經有同名的專案');
        projects.set(p.name, starter(p.name)); return true;
      case 'projects.delete': projects.delete(p.project); return true;
      case 'files.list': return [...files(p.project)].sort().map(([path, text]) => ({ path, size: new Blob([text]).size }));
      case 'files.read': { const t = files(p.project).get(safe(p.path)); if (t == null) throw new Error('找不到檔案 ' + p.path); return t; }
      case 'files.write': files(p.project).set(safe(p.path), p.text); return true;
      case 'files.delete': if (!files(p.project).delete(safe(p.path))) throw new Error('找不到檔案 ' + p.path); return true;
      case 'chat.read': return chats.get(p.project) || '';
      case 'chat.write': chats.set(p.project, p.text); return true;
      case 'checkpoint.take': { const id = String(Date.now() + checkpoints.size); checkpoints.set(id, new Map(files(p.project))); return id; }
      case 'checkpoint.restore': { const saved = checkpoints.get(p.checkpoint); if (!saved) throw new Error('找不到這個還原點'); projects.set(p.project, new Map(saved)); return true; }
      case 'keys.status': return { ...keys };
      case 'keys.set': if (String(p.key).trim().length < 8) throw new Error('金鑰格式不正確'); keys[p.provider] = true; return true;
      case 'keys.clear': keys[p.provider] = false; return true;
      case 'models.list': return { status: 200, body: JSON.stringify(models[p.provider]) };
      case 'model.send': {
        const body = JSON.parse(p.body);
        if (p.provider !== 'anthropic') return { status: 200, body: JSON.stringify({ choices: [{ message: { content: '（模擬的 GPT 回覆）' } }], usage: { prompt_tokens: 900, completion_tokens: 40 } }) };
        return { status: 200, body: JSON.stringify(anthropic(body)) };
      }
      case 'preview.open': files(p.project); window.mockPreview = p.project; return true;
      case 'export.zip': return 'Download/CCA Haiku/' + p.project + '.zip';
      default: throw new Error('不支援的操作 ' + method);
    }
  }
  window.HaikuHost = {
    call(text) {
      const m = JSON.parse(text);
      sent.push(m);
      setTimeout(() => {
        let reply;
        try { reply = { id: m.id, ok: true, result: handle(m.method, m.params || {}) }; }
        catch (e) { reply = { id: m.id, ok: false, error: e.message }; }
        window.Haiku.receive(JSON.stringify(reply));
      }, m.method === 'model.send' ? 600 : 20);
    },
  };
})();
