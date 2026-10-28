// CCA Haiku: projects, files, settings and the agent loop. The host bridge (HaikuHost) owns storage, keys and HTTPS;
// this page builds the requests and reads the replies. Model output is shown as escaped text, never as HTML.
'use strict';
window.Haiku = (() => {
  const VERSION = '0.0.2';
  const MAX_ROUNDS = 16;
  const S = { tab: 'chat', project: '', projects: [], files: [], keys: {}, models: { anthropic: [], openai: [] },
    turns: [], running: false, cancel: false, live: null, error: '', sheet: null };
  const prefs = (() => { try { return JSON.parse(localStorage.getItem('haiku.prefs') || '{}'); } catch (_) { return {}; } })();
  const savePrefs = () => { try { localStorage.setItem('haiku.prefs', JSON.stringify(prefs)); } catch (_) { /* optional */ } };

  // ---- host bridge: one call, one reply through Haiku.receive
  let serial = 0;
  const waiting = new Map();
  function host(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = 'c' + (++serial);
      waiting.set(id, { resolve, reject });
      window.HaikuHost.call(JSON.stringify({ id, method, params }));
    });
  }
  function receive(text) {
    const m = JSON.parse(text);
    const w = waiting.get(m.id);
    if (!w) return;
    waiting.delete(m.id);
    if (m.ok) w.resolve(m.result); else w.reject(new Error(m.error || '操作失敗'));
  }

  // ---- DOM helpers
  function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(props || {})) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'value') el.value = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
    for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid instanceof Node ? kid : String(kid));
    return el;
  }
  const $ = s => document.querySelector(s);
  function toast(text) {
    const t = h('div', { class: 'toast', role: 'status' }, text);
    document.body.append(t);
    setTimeout(() => t.remove(), 2600);
  }
  // Model text is escaped first; only fenced code, inline code and paragraphs get structure.
  function rich(text) {
    const box = h('div', { class: 'body' });
    const parts = String(text || '').split(/```[a-zA-Z0-9_-]*\n?/);
    parts.forEach((part, i) => {
      if (i % 2) { box.append(h('pre', {}, part.replace(/\n$/, ''))); return; }
      for (const para of part.split(/\n{2,}/)) {
        if (!para.trim()) continue;
        const p = h('p');
        para.split(/(`[^`\n]+`)/).forEach(bit => {
          if (/^`[^`]+`$/.test(bit)) p.append(h('code', {}, bit.slice(1, -1)));
          else bit.split('\n').forEach((line, j) => { if (j) p.append(h('br')); p.append(line); });
        });
        box.append(p);
      }
    });
    return box;
  }
  const size = n => n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB';
  const tokens = n => n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n || 0);

  // ---- providers and models (IDs only ever come from the provider's own list)
  const PROVIDERS = { anthropic: 'Claude（Anthropic API）', openai: 'GPT（OpenAI API）' };
  const generation = id => (id.match(/\d+/g) || []).map(Number);
  const newer = (a, b) => { const x = generation(a), y = generation(b);
    for (let i = 0; i < Math.max(x.length, y.length); i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0);
    return false; };
  function chatModels(provider, body) {
    let list = [];
    try { list = JSON.parse(body).data || []; } catch (_) { return []; }
    const out = list.map(m => ({ id: m.id, name: m.display_name || m.id })).filter(m => typeof m.id === 'string');
    if (provider === 'anthropic') return out;
    // OpenAI lists every model kind; keep the ones that take chat messages with tools.
    return out.filter(m => /^(gpt|chatgpt|o\d)/.test(m.id) &&
      !/(embed|whisper|tts|dall-e|image|audio|realtime|moderation|search|transcribe|instruct)/.test(m.id));
  }
  // Haiku's default is the fast small tier of the list (haiku, mini), newest first; the user can pick any listed model.
  function defaultModel(provider) {
    const list = S.models[provider];
    const small = list.filter(m => (provider === 'anthropic' ? /haiku/ : /mini/).test(m.id));
    const pool = small.length ? small : list;
    return pool.reduce((best, m) => (!best || newer(m.id, best.id) ? m : best), null)?.id || '';
  }
  const provider = () => prefs.provider || 'anthropic';
  const model = () => prefs.model?.[provider()] || defaultModel(provider());
  async function loadModels(p, quiet) {
    if (!S.keys[p]) return;
    try {
      const r = await host('models.list', { provider: p });
      if (r.status >= 400) throw new Error(apiError(r));
      S.models[p] = chatModels(p, r.body);
      if (prefs.model?.[p] && !S.models[p].some(m => m.id === prefs.model[p])) { delete prefs.model[p]; savePrefs(); }
    } catch (e) { if (!quiet) toast('無法取得模型清單：' + e.message); }
    render();
  }
  function apiError(r) {
    try { const e = JSON.parse(r.body).error; return `${r.status} ${e?.message || e?.type || ''}`.trim(); } catch (_) { return `HTTP ${r.status}`; }
  }

  // ---- agent tools: the project folder only, through the host
  const TOOLS = [
    { name: 'list_files', description: '列出專案中的所有檔案與大小。', schema: { type: 'object', properties: {}, required: [] } },
    { name: 'read_file', description: '讀取專案中的一個文字檔。path 是專案內的相對路徑，例如 index.html。',
      schema: { type: 'object', properties: { path: { type: 'string' } }, required: ['path'] } },
    { name: 'write_file', description: '建立或覆寫專案中的一個文字檔，content 是完整的檔案內容。',
      schema: { type: 'object', properties: { path: { type: 'string' }, content: { type: 'string' } }, required: ['path', 'content'] } },
    { name: 'delete_file', description: '刪除專案中的一個檔案。',
      schema: { type: 'object', properties: { path: { type: 'string' } }, required: ['path'] } },
  ];
  const system = () => `你是 CCA Haiku，在使用者的 Android 手機上幫忙寫網頁專案（HTML、CSS、JavaScript）。目前的專案是「${S.project}」，入口是 index.html。` +
    '用工具讀寫專案裡的檔案；使用者會在手機上用「預覽」開啟 index.html。版面以手機為主、按鈕要好點。' +
    '只修改需要的檔案，寫入時給完整檔案內容。你無法執行程式或開啟網頁，不要宣稱看過執行結果。' +
    '最後用台灣繁體中文簡短說明改了什麼，以及預覽時要看哪裡。';
  async function runTool(name, input, changed) {
    const path = String(input?.path || '');
    if (name === 'list_files') return (await host('files.list', { project: S.project })).map(f => `${f.path} (${f.size} bytes)`).join('\n') || '（空的專案）';
    if (name === 'read_file') { const text = await host('files.read', { project: S.project, path }); return text.length > 60000 ? text.slice(0, 60000) + '\n…（檔案太長，已截斷）' : text; }
    if (name === 'write_file') { await host('files.write', { project: S.project, path, text: String(input?.content ?? '') }); changed.add(path); return '已寫入 ' + path; }
    if (name === 'delete_file') { await host('files.delete', { project: S.project, path }); changed.add(path); return '已刪除 ' + path; }
    throw new Error('沒有這個工具 ' + name);
  }

  // Earlier turns travel as plain request/answer pairs, so the user can switch provider mid-project.
  function history() {
    return S.turns.filter(t => t.answer).slice(-8).flatMap(t => [{ role: 'user', content: t.user }, { role: 'assistant', content: t.answer }]);
  }
  async function ask(p, messages) {
    const body = p === 'anthropic'
      ? { model: model(), max_tokens: 8192, system: system(), messages,
          tools: TOOLS.map(t => ({ name: t.name, description: t.description, input_schema: t.schema })) }
      : { model: model(), messages: [{ role: 'system', content: system() }, ...messages],
          tools: TOOLS.map(t => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.schema } })) };
    const r = await host('model.send', { provider: p, body: JSON.stringify(body) });
    if (r.status >= 400) throw new Error(apiError(r));
    return JSON.parse(r.body);
  }

  async function send(text) {
    if (S.running || !text.trim()) return;
    const p = provider();
    if (!S.keys[p]) { openSettings(); toast('先設定 ' + PROVIDERS[p] + ' 的 API 金鑰'); return; }
    if (!model()) { await loadModels(p); if (!model()) { toast('沒有可用的模型，請到設定重新整理清單'); return; } }
    const turn = { user: text.trim(), answer: '', tools: [], files: [], at: Date.now(), provider: p, model: model(), input: 0, output: 0 };
    S.running = true; S.cancel = false; S.error = ''; S.live = turn;
    render();
    const changed = new Set();
    try {
      turn.checkpoint = await host('checkpoint.take', { project: S.project });
      const messages = [...history(), { role: 'user', content: turn.user }];
      for (let round = 0; round < MAX_ROUNDS && !S.cancel; round++) {
        const data = await ask(p, messages);
        if (S.cancel) break;
        let calls = [];
        if (p === 'anthropic') {
          turn.input += data.usage?.input_tokens || 0; turn.output += data.usage?.output_tokens || 0;
          const text = (data.content || []).filter(b => b.type === 'text').map(b => b.text).join('\n\n');
          if (text) turn.answer = text;
          calls = (data.content || []).filter(b => b.type === 'tool_use').map(b => ({ id: b.id, name: b.name, input: b.input }));
          messages.push({ role: 'assistant', content: data.content || [] });
        } else {
          const msg = data.choices?.[0]?.message || {};
          turn.input += data.usage?.prompt_tokens || 0; turn.output += data.usage?.completion_tokens || 0;
          if (msg.content) turn.answer = msg.content;
          calls = (msg.tool_calls || []).map(c => { let input = {}; try { input = JSON.parse(c.function?.arguments || '{}'); } catch (_) { /* reported below */ }
            return { id: c.id, name: c.function?.name, input }; });
          messages.push({ role: 'assistant', content: msg.content || null, ...(msg.tool_calls ? { tool_calls: msg.tool_calls } : {}) });
        }
        if (!calls.length) break;
        const results = [];
        for (const call of calls) {
          let output, failed = false;
          try { output = await runTool(call.name, call.input, changed); } catch (e) { output = e.message; failed = true; }
          turn.tools.push({ name: call.name, path: call.input?.path || '', failed, note: failed ? output : '' });
          render();
          results.push({ call, output, failed });
        }
        if (p === 'anthropic') messages.push({ role: 'user', content: results.map(r => ({ type: 'tool_result', tool_use_id: r.call.id, content: r.output, is_error: r.failed })) });
        else for (const r of results) messages.push({ role: 'tool', tool_call_id: r.call.id, content: r.output });
        if (round === MAX_ROUNDS - 1) turn.answer += (turn.answer ? '\n\n' : '') + '（已達每輪 ' + MAX_ROUNDS + ' 次工具呼叫的上限，先停在這裡。）';
      }
      if (S.cancel) turn.answer += (turn.answer ? '\n\n' : '') + '（已停止。）';
    } catch (e) {
      S.error = e.message;
      turn.answer = turn.answer || '';
      turn.failed = true;
    }
    turn.files = [...changed];
    S.turns.push(turn);
    S.running = false; S.live = null;
    await saveChat();
    if (changed.size) await loadFiles();
    render();
  }
  async function saveChat() {
    try { await host('chat.write', { project: S.project, text: JSON.stringify({ version: 1, turns: S.turns.slice(-200) }) }); }
    catch (e) { toast('對話沒有存成功：' + e.message); }
  }
  async function undo(turn) {
    if (!confirm('把專案檔案還原到這一輪開始之前？之後各輪做的修改也會一起還原。')) return;
    try {
      await host('checkpoint.restore', { project: S.project, checkpoint: turn.checkpoint });
      turn.undone = true;
      await saveChat(); await loadFiles(); toast('已還原到這一輪之前');
    } catch (e) { toast('還原失敗：' + e.message); }
    render();
  }

  // ---- projects and files
  async function loadProjects() { S.projects = await host('projects.list'); }
  async function loadFiles() { S.files = S.project ? await host('files.list', { project: S.project }) : []; }
  async function open(name) {
    S.project = name; prefs.project = name; savePrefs();
    S.turns = [];
    try { const saved = await host('chat.read', { project: name }); S.turns = saved ? JSON.parse(saved).turns || [] : []; } catch (_) { S.turns = []; }
    await loadFiles();
    render();
  }
  async function create(name) {
    try { await host('projects.create', { name: name.trim() }); await loadProjects(); await open(name.trim()); closeSheet(); toast('已建立「' + name.trim() + '」'); }
    catch (e) { toast(e.message); }
  }

  // ---- rendering
  function render() {
    const app = $('#app');
    app.replaceChildren(
      h('header', {},
        h('span', { class: 'brand' }, 'Haiku', h('small', {}, VERSION)),
        h('button', { class: 'project-btn', onclick: openProjects, title: '切換專案' }, S.project || '選擇或建立專案'),
        h('button', { class: 'icon-btn primary', disabled: !S.project, onclick: () => host('preview.open', { project: S.project }).catch(e => toast(e.message)) }, '預覽'),
        h('button', { class: 'icon-btn', onclick: openSettings, 'aria-label': '設定' }, '設定')),
      h('main', { id: 'main' }, S.tab === 'files' ? filesView() : chatView()),
      // replaceChildren, unlike h(), would print a null as text.
      ...(S.tab === 'chat' && S.project ? [composer()] : []),
      h('nav', { class: 'tabs', role: 'tablist' },
        h('button', { role: 'tab', 'aria-selected': String(S.tab === 'chat'), onclick: () => { S.tab = 'chat'; render(); } }, '對話'),
        h('button', { role: 'tab', 'aria-selected': String(S.tab === 'files'), onclick: () => { S.tab = 'files'; render(); } }, `檔案 ${S.files.length || ''}`)));
    if (S.sheet) app.append(S.sheet());
    const main = $('#main');
    if (S.tab === 'chat') main.scrollTop = main.scrollHeight;
  }
  function welcome() {
    const keyed = S.keys.anthropic || S.keys.openai;
    return h('div', { class: 'card' },
      h('strong', {}, '在手機上輕鬆寫網頁'),
      h('p', { class: 'hint' }, '專案存在這支手機裡，Haiku 直接修改檔案，按「預覽」立刻看到結果。這不是遠端操作電腦。'),
      h('ol', { class: 'steps' },
        h('li', {}, keyed ? '已設定 API 金鑰' : h('button', { class: 'btn', onclick: openSettings }, '設定 API 金鑰')),
        h('li', {}, S.project ? `專案：${S.project}` : h('button', { class: 'btn', onclick: openProjects }, '建立專案')),
        h('li', {}, '告訴 Haiku 你想做什麼，例如「做一個記帳小工具」')));
  }
  function chatView() {
    const out = [];
    if (!S.project || !S.turns.length) out.push(welcome());
    for (const t of [...S.turns, ...(S.live ? [S.live] : [])]) {
      out.push(h('div', { class: 'msg user' }, h('div', { class: 'bubble' }, t.user)));
      const ai = h('div', { class: 'msg ai' });
      for (const tool of t.tools) ai.append(h('div', { class: 'tool' + (tool.failed ? ' error' : '') },
        `${{ list_files: '列出檔案', read_file: '讀取', write_file: '寫入', delete_file: '刪除' }[tool.name] || tool.name} ${tool.path}${tool.failed ? ' · ' + tool.note : ''}`));
      if (t.answer) ai.append(rich(t.answer));
      if (t === S.live) ai.append(h('div', { class: 'thinking', role: 'status' }, 'Haiku 正在處理…'));
      else ai.append(h('div', { class: 'turn' },
        h('div', { class: 'files' }, `${t.files.length ? '修改了 ' + t.files.join('、') : '沒有修改檔案'} · ${t.model} · 輸入 ${tokens(t.input)} · 輸出 ${tokens(t.output)}`),
        t.files.length && !t.undone ? h('div', { class: 'row' },
          h('button', { class: 'btn', onclick: () => host('preview.open', { project: S.project }).catch(e => toast(e.message)) }, '預覽'),
          h('button', { class: 'btn', onclick: () => undo(t) }, '還原這一輪')) : null,
        t.undone ? h('div', { class: 'hint' }, '已還原到這一輪之前') : null));
      out.push(ai);
    }
    if (S.error) out.push(h('div', { class: 'error-box', role: 'alert' }, S.error));
    return out;
  }
  function composer() {
    // Background updates re-render the page; the draft survives them.
    const box = h('textarea', { rows: 1, placeholder: '告訴 Haiku 要做什麼…', 'aria-label': '訊息', disabled: S.running,
      oninput: e => { S.draft = e.target.value; } }, S.draft || '');
    const go = () => { const text = box.value; box.value = ''; S.draft = ''; send(text); };
    return h('div', { class: 'composer' }, box,
      S.running ? h('button', { class: 'btn', onclick: () => { S.cancel = true; toast('這次回覆完成後停止'); } }, '停止')
        : h('button', { class: 'btn primary', onclick: go }, '送出'));
  }
  function filesView() {
    if (!S.project) return welcome();
    return [
      h('div', { class: 'file-list' }, S.files.length ? S.files.map(f => h('button', { class: 'file', onclick: () => edit(f.path) },
        h('span', {}, f.path), h('span', { class: 'size' }, size(f.size)))) : h('p', { class: 'hint', style: 'padding:12px' }, '還沒有檔案。')),
      h('div', { class: 'row' },
        h('button', { class: 'btn', onclick: () => { const p = prompt('新檔案的路徑，例如 about.html'); if (p) edit(p.trim(), true); } }, '新增檔案'),
        h('button', { class: 'btn', onclick: () => host('export.zip', { project: S.project }).then(at => toast('已匯出到 ' + at)).catch(e => toast(e.message)) }, '匯出 ZIP')),
    ];
  }
  async function edit(path, fresh) {
    let text = '';
    if (!fresh) { try { text = await host('files.read', { project: S.project, path }); } catch (e) { toast(e.message); return; } }
    const area = h('textarea', { class: 'editor', spellcheck: 'false', autocapitalize: 'off', 'aria-label': path }, text);
    S.sheet = () => h('div', { class: 'sheet-back', onclick: e => { if (e.target === e.currentTarget) closeSheet(); } },
      h('div', { class: 'sheet', role: 'dialog', 'aria-label': path },
        h('h2', {}, path), area,
        h('div', { class: 'row', style: 'margin-top:12px' },
          h('button', { class: 'btn primary', onclick: async () => { try { await host('files.write', { project: S.project, path, text: area.value }); await loadFiles(); closeSheet(); toast('已儲存 ' + path); } catch (e) { toast(e.message); } } }, '儲存'),
          fresh ? null : h('button', { class: 'btn danger', onclick: async () => { if (!confirm('刪除 ' + path + '？')) return; try { await host('files.delete', { project: S.project, path }); await loadFiles(); closeSheet(); } catch (e) { toast(e.message); } } }, '刪除'),
          h('button', { class: 'btn', onclick: closeSheet }, '取消'))));
    render();
  }
  function openProjects() {
    const name = h('input', { placeholder: '新專案名稱', 'aria-label': '新專案名稱', maxlength: 40 });
    S.sheet = () => h('div', { class: 'sheet-back', onclick: e => { if (e.target === e.currentTarget) closeSheet(); } },
      h('div', { class: 'sheet', role: 'dialog', 'aria-label': '專案' },
        h('h2', {}, '專案'),
        ...S.projects.map(p => h('button', { class: 'choice' + (p.name === S.project ? ' on' : ''), onclick: () => { open(p.name); closeSheet(); } },
          h('span', {}, p.name), h('span', { class: 'hint' }, `${p.files} 個檔案`))),
        h('div', { class: 'field' }, name),
        h('div', { class: 'row' },
          h('button', { class: 'btn primary', onclick: () => name.value.trim() && create(name.value) }, '建立'),
          S.project ? h('button', { class: 'btn danger', onclick: async () => {
            if (!confirm(`刪除專案「${S.project}」與它的所有檔案？無法復原。`)) return;
            try { await host('projects.delete', { project: S.project }); S.project = ''; await loadProjects(); closeSheet();
              if (S.projects[0]) await open(S.projects[0].name); else { S.files = []; S.turns = []; render(); } } catch (e) { toast(e.message); }
          } }, '刪除目前專案') : null)));
    render();
  }
  function openSettings() {
    const p = provider();
    const key = h('input', { type: 'password', autocomplete: 'off', placeholder: S.keys[p] ? '已設定；輸入新的金鑰可取代' : '貼上 API 金鑰', 'aria-label': 'API 金鑰' });
    const models = S.models[p];
    S.sheet = () => h('div', { class: 'sheet-back', onclick: e => { if (e.target === e.currentTarget) closeSheet(); } },
      h('div', { class: 'sheet', role: 'dialog', 'aria-label': '設定' },
        h('h2', {}, '設定'),
        h('div', { class: 'field' }, h('label', {}, 'AI 供應商'),
          ...Object.entries(PROVIDERS).map(([id, label]) => h('button', { class: 'choice' + (id === p ? ' on' : ''),
            onclick: () => { prefs.provider = id; savePrefs(); loadModels(id, true); openSettings(); } },
          h('span', {}, label), S.keys[id] ? h('span', { class: 'tag' }, '已設定金鑰') : h('span', { class: 'hint' }, '未設定')))),
        h('div', { class: 'field' }, h('label', {}, 'API 金鑰'), key,
          h('p', { class: 'hint' }, 'API 依 Token 計費，和 Claude／ChatGPT 的訂閱分開。金鑰用 Android Keystore 加密存在這支手機，不會傳給畫面，只在呼叫供應商時加上。'),
          h('div', { class: 'row' },
            h('button', { class: 'btn primary', onclick: async () => {
              try { await host('keys.set', { provider: p, key: key.value }); key.value = ''; S.keys = await host('keys.status'); toast('金鑰已加密保存'); await loadModels(p); openSettings(); }
              catch (e) { toast(e.message); } } }, '儲存金鑰'),
            S.keys[p] ? h('button', { class: 'btn danger', onclick: async () => { await host('keys.clear', { provider: p }); S.keys = await host('keys.status'); S.models[p] = []; openSettings(); } }, '移除金鑰') : null)),
        h('div', { class: 'field' }, h('label', {}, '模型'),
          models.length ? h('select', { 'aria-label': '模型', onchange: e => { prefs.model = { ...(prefs.model || {}), [p]: e.target.value }; savePrefs(); } },
            models.map(m => h('option', { value: m.id, selected: m.id === model() || null }, m.name + (m.id === defaultModel(p) ? '（預設）' : ''))))
            : h('p', { class: 'hint' }, S.keys[p] ? '還沒取得模型清單。' : '設定金鑰後，從供應商取得可用的模型。'),
          h('p', { class: 'hint' }, 'Haiku 預設選清單裡最快的小模型；只列出供應商回報的模型，不猜測名稱。'),
          S.keys[p] ? h('button', { class: 'btn', onclick: () => loadModels(p).then(openSettings) }, '重新整理清單') : null),
        h('p', { class: 'hint' }, `CCA Haiku ${VERSION} · 只在 Android 上使用 · 專案與對話存在這支手機。`),
        h('button', { class: 'btn', onclick: closeSheet }, '完成')));
    render();
  }
  function closeSheet() { S.sheet = null; render(); }

  async function start() {
    try {
      S.keys = await host('keys.status');
      await loadProjects();
      const first = S.projects.find(p => p.name === prefs.project) || S.projects[0];
      if (first) await open(first.name); else render();
      for (const p of Object.keys(PROVIDERS)) loadModels(p, true);
    } catch (e) { S.error = e.message; render(); }
  }
  document.addEventListener('DOMContentLoaded', start);
  return { receive, previewClosed: () => loadFiles().then(render), state: S };
})();
