#!/usr/bin/env node
// DSH 状态服务：给 Android 状态条 / 桌面网页提供 agent 当前操作状态
// 用法：
//   node status-server.js                 # 启动服务，默认 127.0.0.1:8790
//   node status-server.js push "文本"     # 以 running 推送一条状态
//   node status-server.js done "文本"     # 完成状态
//   node status-server.js idle            # 隐藏状态条
const http = require('http');
const PORT = Number(process.env.HUD_PORT || 8790);
const HOST = process.env.HUD_HOST || '127.0.0.1';

let state = {
  state: 'idle',      // idle | running | done | blocked
  text: '',
  step: 0,
  total: 0,
  startedAt: 0,
  updatedAt: Date.now(),
};

function snapshot() {
  const elapsedSec = state.startedAt ? Math.round((Date.now() - state.startedAt) / 1000) : 0;
  return { ...state, elapsedSec, now: Date.now() };
}

function apply(patch) {
  const next = { ...state, ...patch, updatedAt: Date.now() };
  if ((patch.state === 'running') && !state.startedAt) next.startedAt = Date.now();
  if (patch.state === 'idle' || patch.state === 'done') next.startedAt = 0;
  state = next;
  return snapshot();
}

const HTML = `<!doctype html><html lang="zh"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH 操作状态</title>
<style>
 body{margin:0;background:#15151a;color:#eee;font:14px/1.5 -apple-system,system-ui,sans-serif}
 #wrap{position:fixed;inset:0;display:flex;flex-direction:column;justify-content:center;gap:18px;padding:24px}
 #bar{white-space:nowrap;overflow:hidden;border-radius:12px;padding:14px 18px;background:#26262e;font-size:18px}
 #bar span{display:inline-block;padding-left:100%;animation:slide 18s linear infinite}
 @keyframes slide{0%{transform:translateX(0)}100%{transform:translateX(-100%)}}
 .running #bar{background:#26262e;border-left:6px solid #5b8cff}
 .done #bar{background:#12351f;border-left:6px solid #35c46a}
 .blocked #bar{background:#3a2c12;border-left:6px solid #e0a73a}
 .idle #bar{background:#1d1d24;color:#777;border-left:6px solid #333}
 #meta{color:#8a8a96;font-size:13px}
 a{color:#5b8cff}
</style>
<div id="wrap"><div id="bar"><span id="txt">等待 DSH 状态…</span></div><div id="meta"></div>
<div><a href="/status">/status JSON</a> · 由 DSH 状态服务提供</div></div>
<script>
async function tick(){
  try{
    const r = await fetch('/status',{cache:'no-store'}); const s = await r.json();
    document.body.className = s.state;
    document.getElementById('txt').textContent =
      s.state==='idle' ? '空闲 · 等待 agent 操作' :
      (s.state==='done'?'✅ ':(s.state==='blocked'?'⏸ ':'🤖 ')) + (s.text||'');
    document.getElementById('meta').textContent =
      (s.state==='running'?'进行中 · ':'') + (s.elapsedSec?('已持续 '+s.elapsedSec+' 秒 · '):'') +
      (s.step?('第 '+s.step+(s.total?('/'+s.total):'')+' 步'):'');
  }catch(e){ document.getElementById('txt').textContent='未连接到状态服务'; document.body.className='idle'; }
}
setInterval(tick, 800); tick();
</script></html>`;

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  let body = '';
  req.on('data', c => { body += c; if (body.length > 1e5) req.destroy(); });
  req.on('end', () => {
    const send = (code, obj, type = 'application/json; charset=utf-8') => {
      res.writeHead(code, { 'content-type': type, 'cache-control': 'no-store' });
      res.end(typeof obj === 'string' ? obj : JSON.stringify(obj));
    };
    if (req.method === 'GET' && url.pathname === '/status') return send(200, snapshot());
    if (req.method === 'GET' && url.pathname === '/') return send(200, HTML, 'text/html; charset=utf-8');
    if (req.method === 'POST' && url.pathname === '/report') {
      try {
        const fs = require('node:fs');
        fs.appendFileSync('/tmp/hud-report.log', new Date().toISOString() + ' ' + (body || '') + '\n');
      } catch (e) { /* ignore */ }
      return send(200, { ok: true });
    }
    if (req.method === 'GET' && url.pathname === '/done') return send(200, apply({ state: 'done' }));
    if (req.method === 'GET' && url.pathname === '/idle') return send(200, apply({ state: 'idle' }));
    if (req.method === 'POST' && url.pathname === '/status') {
      let patch = {};
      try { patch = JSON.parse(body || '{}'); } catch (e) { return send(400, { error: 'bad json' }); }
      if (patch.state === undefined) patch.state = 'running';
      return send(200, apply(patch));
    }
    send(404, { error: 'not found' });
  });
});

// 命令行推送模式
const argv = process.argv.slice(2);
if (argv[0] === 'push' || argv[0] === 'done' || argv[0] === 'idle') {
  const kind = argv[0];
  const text = argv[1] || '';
  const payload = JSON.stringify(kind === 'idle' ? { state: 'idle' } :
    { state: kind === 'done' ? 'done' : 'running', text });
  const req = http.request({ host: HOST, port: PORT, path: '/status', method: 'POST',
    headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) } },
    r => { let d=''; r.on('data',c=>d+=c); r.on('end',()=>{ console.log(d); }); });
  req.on('error', e => { console.error('push failed:', e.message); process.exit(1); });
  req.write(payload); req.end();
} else {
  server.listen(PORT, HOST, () => {
    console.log(`DSH 状态服务: http://${HOST}:${PORT}/  (JSON: /status)`);
  });
}
