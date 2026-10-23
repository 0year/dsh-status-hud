#!/usr/bin/env node
'use strict';
// QQ bridge：把手机 HUD App 收到的 QQ 通知转成回复，再交回 App（RemoteInput 直回）。
//
// 数据流：
//   手机 App --POST /qq/event--> 状态服务(8790) <--GET /qq/events-- 本进程
//   本进程 --POST /qq/reply--> 状态服务 <--GET /qq/replies-- 手机 App
//
// 用法：
//   node qq-bridge/bridge.js                 # echo 模式（不调模型，回复「收到：…」）
//   QQ_BRIDGE_MODE=dsh node qq-bridge/bridge.js   # 用 dsh headless 生成回复
//   node qq-bridge/bridge.js --once          # 处理一轮就退出（自测用）
const http = require('node:http');
const { spawn } = require('node:child_process');
const fs = require('node:fs');

const SERVER = (process.env.HUD_SERVER || 'http://127.0.0.1:8790').replace(/\/+$/, '');
const MODE = (process.env.QQ_BRIDGE_MODE || 'llm').toLowerCase(); // llm | echo | dsh
const API_URL = process.env.QQ_BRIDGE_API_URL || 'https://api.deepseek.com/anthropic/v1/messages';
const API_MODEL = process.env.QQ_BRIDGE_MODEL || 'deepseek-flash';
const API_KEY_PATH = process.env.QQ_BRIDGE_KEY_PATH || '/root/projects/.dsh/.credentials.yaml';
const DSH_BIN = process.env.DSH_BIN || '/opt/dshapp/runtime/node_modules/@deepseek-ai/dsh/lib/bin.js';
const CONFIG_FILE = process.env.QQ_BRIDGE_CONFIG || require('node:path').join(__dirname, 'config.json');
let fileConfig = {};
try { fileConfig = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8')); } catch (e) { /* ignore */ }
const ALLOW = (((process.env.QQ_BRIDGE_ALLOW || '') ? process.env.QQ_BRIDGE_ALLOW.split(',') : ((fileConfig.whitelist && fileConfig.whitelist.names) || [])).map(s => String(s).trim()).filter(Boolean));
const GROUPS = (process.env.QQ_BRIDGE_GROUPS || '').split(',').map(s => s.trim()).filter(Boolean);
const REPLY_MAX = Number(process.env.QQ_BRIDGE_REPLY_MAX || 300);
const DSH_TIMEOUT = Number(process.env.QQ_BRIDGE_DSH_TIMEOUT_MS || 120000);
const POLL_MS = Number(process.env.QQ_BRIDGE_POLL_MS || 1200);
const LOG = '/tmp/qq-bridge.log';
const CURSOR_FILE = process.env.QQ_BRIDGE_CURSOR_FILE || '/tmp/qq-bridge-cursor';
const PILOT_BIN = process.env.QQ_BRIDGE_PILOT_BIN || '/opt/pilot/bin/pilot';
const QQ_PKG = process.env.QQ_BRIDGE_QQ_PKG || 'com.tencent.mobileqq';
const UI_REPLY = (process.env.QQ_BRIDGE_UI || '1') !== '0';
const RELAY = (process.env.QQ_BRIDGE_MODE || 'llm').toLowerCase() === 'relay';
const HOST_PKG = process.env.QQ_BRIDGE_HOST_PKG || 'com.dshbox.app';
const SHELL_URL = process.env.QQ_BRIDGE_SHELL_URL || 'http://127.0.0.1:8791/shell';

/** 息屏自动回复：唤醒设备（亮度压到最低避免刺眼）、发完再息屏。 */

/** 读屏幕状态：Awake / Dozing / Asleep。 */
async function screenState() {
  const r = await shellExec("dumpsys power | grep -m1 'mWakefulness='");
  const m = /mWakefulness=(\w+)/.exec(r.out || '');
  return m ? m[1] : 'Unknown';
}

/** 需要的话唤醒屏幕（含亮度暂存/调暗/清锁屏），返回 {woke, brightness, mode}。 */
async function ensureAwake() {
  const state = await screenState();
  if (state === 'Awake') return { woke: false };
  const info = { woke: true, brightness: null, mode: null };
  try {
    const b = await shellExec('settings get system screen_brightness');
    const m = await shellExec('settings get system screen_brightness_mode');
    info.brightness = String(b.out || '').trim();
    info.mode = String(m.out || '').trim();
    // 先调暗再唤醒，避免半夜亮屏刺眼
    await shellExec('settings put system screen_brightness_mode 0');
    await shellExec('settings put system screen_brightness 1');
  } catch (e) { /* ignore */ }
  await shellExec('input keyevent 224'); // WAKEUP
  await sleep(800);
  await shellExec('wm dismiss-keyguard');
  await sleep(300);
  for (let i = 0; i < 3; i++) {
    if ((await screenState()) === 'Awake') break;
    await shellExec('input keyevent 224');
    await sleep(500);
  }
  return info;
}

/** 发完把屏幕关回去，并恢复亮度设置。 */
async function restoreSleep(info) {
  if (!info || !info.woke) return;
  try {
    await shellExec('input keyevent 223'); // SLEEP
    await sleep(250);
    if (info.mode !== null && info.mode !== '') await shellExec('settings put system screen_brightness_mode ' + info.mode);
    if (info.brightness !== null && /^\d+$/.test(info.brightness)) await shellExec('settings put system screen_brightness ' + info.brightness);
  } catch (e) { /* ignore */ }
}

/** 通过 Shell Helper（Shizuku uid 2000）执行 shell，免任何审批。 */
async function shellExec(cmd) {
  try {
    const r = await fetch(SHELL_URL, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ cmd }) });
    const d = await r.json();
    return { ok: !!d.ok, out: d.out || '' };
  } catch (e) { return { ok: false, out: String(e && e.message) }; }
}
const ONCE = process.argv.includes('--once');

function log(msg) {
  const line = new Date().toISOString() + ' ' + msg;
  try { fs.appendFileSync(LOG, line + '\n'); } catch (e) { /* ignore */ }
  console.log(line);
}

function httpJson(method, path, payload) {
  return new Promise((resolve, reject) => {
    const body = payload === undefined ? null : JSON.stringify(payload);
    const u = new URL(SERVER + path);
    const req = http.request({ hostname: u.hostname, port: u.port || 80, path: u.pathname + u.search, method,
      headers: body ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) } : {} }, (res) => {
      let d = '';
      res.on('data', c => { d += c; });
      res.on('end', () => { try { resolve(JSON.parse(d || '{}')); } catch (e) { reject(e); } });
    });
    req.on('error', reject);
    req.setTimeout(15000, () => req.destroy(new Error('timeout')));
    if (body) req.write(body);
    req.end();
  });
}

// ---- 回复生成 ----
function buildPrompt(ev) {
  return '你正在通过手机 QQ 替主人自动回复消息。对方（' + (ev.title || '对方') + '）发来：' +
    '「' + (ev.text || '') + '」。请直接用简短自然的中文回复（不超过 120 字），不要使用 markdown，' +
    '不要解释你是谁，直接给回复内容。';
}

function readApiKey() {
  if (process.env.QQ_BRIDGE_API_KEY) return process.env.QQ_BRIDGE_API_KEY;
  try {
    const txt = fs.readFileSync(API_KEY_PATH, 'utf8');
    const m = txt.match(/DEEPSEEK_API_KEY:\s*([^\s]+)/);
    return m ? m[1] : '';
  } catch (e) { return ''; }
}

// 直接调 DeepSeek（Anthropic Messages 兼容端点），不依赖 dsh 会话存储
async function callLlm(ev) {
  const key = readApiKey();
  if (!key) return { ok: false, text: '', error: 'no-api-key' };
  const system = '你是主人手机上的 AI 助理，现在通过 QQ 替主人自动回复消息。' +
    '请用简短自然的中文口语回复（不超过 120 字），不要使用 markdown，不要暴露你是自动程序；' +
    '如果不确定或涉及敏感事项，回复「我转告主人一下」。';
  const user = '对方（' + (ev.title || '对方') + (ev.convTitle ? '，群「' + ev.convTitle + '」' : '') +
    '）发来：「' + (ev.text || '') + '」';
  try {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 60000);
    const res = await fetch(API_URL, {
      method: 'POST',
      headers: { 'x-api-key': key, 'anthropic-version': '2023-06-01', 'content-type': 'application/json' },
      body: JSON.stringify({ model: API_MODEL, max_tokens: 400, system, messages: [{ role: 'user', content: user }] }),
      signal: ctl.signal,
    });
    clearTimeout(timer);
    if (!res.ok) return { ok: false, text: '', error: 'http ' + res.status };
    const j = await res.json();
    const parts = (j.content || []).filter(b => b && b.type === 'text' && b.text).map(b => b.text);
    const text = parts.join('').trim();
    return text ? { ok: true, text } : { ok: false, text: '', error: 'empty' };
  } catch (e) {
    return { ok: false, text: '', error: String(e && e.message) };
  }
}

// ---- Pilot UI 自动化：打开 QQ 找到会话并发送 ----
const { execFile } = require('node:child_process');

function pilotCall(capability, args, timeoutMs) {
  return new Promise((resolve) => {
    const argv = [PILOT_BIN, 'call', capability, '--json', JSON.stringify(args || {})];
    execFile(argv[0], argv.slice(1), { timeout: timeoutMs || 15000, maxBuffer: 8 * 1024 * 1024 }, (err, stdout) => {
      let data = null;
      try { data = JSON.parse(stdout); } catch (e) { /* ignore */ }
      resolve({ ok: !err && data && data.ok === true, data, error: err ? String(err.message) : (data && !data.ok ? data.reason : '') });
    });
  });
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

/** 用 UI 自动化把 reply 发到与 ev 对应的人的 QQ 会话里。 */

/** 打开目标聊天：群聊绕开 contentIntent（会跳错会话），私聊可优先用通知 PendingIntent。 */
async function openChatFlow(name, kind, log2) {
  let pkg = '';
  const opened = kind === 'group'
    ? { ok: false, error: 'skip-for-group' }
    : await openViaNotification({ title: name });
  log2('open path: ' + (kind === 'group' ? 'am-start(group)' : JSON.stringify(opened)));
  if (opened.ok) {
    await sleep(1100);
    const s0 = await pilotCall('ui.snapshot', {}, 8000);
    pkg = (s0.data && s0.data.data && s0.data.data.package) || '';
  }
  for (let i = 0; i < 3 && pkg !== QQ_PKG; i++) {
    const sh = await shellExec('am start -n com.tencent.mobileqq/.activity.SplashActivity');
    log2('shell am start qq ' + JSON.stringify(sh.ok));
    await sleep(1000);
    const snap = await pilotCall('ui.snapshot', {}, 10000);
    pkg = (snap.data && snap.data.data && snap.data.data.package) || '';
    if (pkg === QQ_PKG) break;
  }
  if (pkg !== QQ_PKG) return { ok: false, error: 'qq-not-foreground' };

  let snap = await pilotCall('ui.snapshot', {}, 12000);
  let nodes = (snap.data && snap.data.data && snap.data.data.nodes) || [];
  const isTarget = () => {
    const titleNode = nodes.find(n => (n.text || '').trim() === name && (n.top || 0) < 400);
    const input = nodes.find(n => (n.class || '').includes('EditText'));
    return !!(titleNode && input);
  };
  const hasSearch = () => nodes.some(n => (n.text || '').trim() === '搜索');

  for (let attempt = 0; attempt < 4 && !isTarget(); attempt++) {
    if (hasSearch()) {
      const searchNode = nodes.find(n => (n.text || '').trim() === '搜索');
      await pilotCall('ui.click', { nodeId: searchNode.nodeId }, 8000);
      await sleep(700);
      snap = await pilotCall('ui.snapshot', {}, 10000);
      nodes = (snap.data && snap.data.data && snap.data.data.nodes) || [];
      const edit = nodes.find(n => (n.class || '').includes('EditText'));
      if (!edit) { log2('no-search-input'); break; }
      await pilotCall('ui.setValue', { nodeId: edit.nodeId, text: name }, 8000);
      await sleep(1100);
      snap = await pilotCall('ui.snapshot', {}, 10000);
      nodes = (snap.data && snap.data.data && snap.data.data.nodes) || [];
      const hit = nodes.find(n => (n.text || '').trim() === name)
        || nodes.find(n => (n.text || '').includes(name) && (n.class || '').includes('TextView'));
      if (!hit) { log2('no-search-result'); break; }
      await pilotCall('ui.click', { nodeId: hit.nodeId }, 8000);
      await sleep(1200);
    } else {
      await pilotCall('ui.key', { key: 'back' }, 5000);
      await sleep(700);
    }
    snap = await pilotCall('ui.snapshot', {}, 12000);
    nodes = (snap.data && snap.data.data && snap.data.data.nodes) || [];
  }
  if (!isTarget()) return { ok: false, error: 'chat-not-found' };
  return { ok: true };
}

/** 在已打开的聊天页里按分割规则发送一段文本。 */
async function sendSegments(name, replyText, log2) {
  const segments = Array.isArray(replyText) ? replyText : splitMessage(replyText);
  if (!segments.length) return { ok: false, error: 'empty-message', sent: 0 };
  log2('本条回复切成 ' + segments.length + ' 条: ' + JSON.stringify(segments.map(x => x.slice(0, 20))));
  let sent = 0;
  for (let si = 0; si < segments.length; si++) {
    const seg = segments[si];
    let snapS = await pilotCall('ui.snapshot', {}, 10000);
    let nodesS = (snapS.data && snapS.data.data && snapS.data.data.nodes) || [];
    let inputS = nodesS.find(n => (n.class || '').includes('EditText'));
    for (let retry = 0; retry < 2 && !inputS; retry++) {
      await sleep(700);
      snapS = await pilotCall('ui.snapshot', {}, 10000);
      nodesS = (snapS.data && snapS.data.data && snapS.data.data.nodes) || [];
      inputS = nodesS.find(n => (n.class || '').includes('EditText'));
    }
    if (!inputS) { log2('第 ' + (si + 1) + ' 条找不到输入框，跳过'); continue; }
    await pilotCall('ui.setValue', { nodeId: inputS.nodeId, text: seg }, 8000);
    await sleep(350);
    snapS = await pilotCall('ui.snapshot', {}, 10000);
    nodesS = (snapS.data && snapS.data.data && snapS.data.data.nodes) || [];
    const sendBtn = nodesS.find(n => (n.text || '').trim() === '发送');
    if (!sendBtn) { log2('第 ' + (si + 1) + ' 条找不到发送按钮，跳过'); continue; }
    await pilotCall('ui.click', { nodeId: sendBtn.nodeId }, 8000);
    sent++;
    const delay = replyDelayMs(seg);
    log2('第 ' + (si + 1) + '/' + segments.length + ' 条已发（' + [...seg].length + ' 字，延迟 ' + delay + 'ms）');
    await sleep(delay);
  }
  return { ok: sent > 0, sent, error: sent ? '' : 'no-segment-sent' };
}

/** 切回发送前的前台 App（或 DSHBox / 桌面）。 */
async function restoreHost(prevPkg, log2) {
  let restored = false;
  if (prevPkg === QQ_PKG) return { restored: true };
  if (prevPkg === HOST_PKG || !prevPkg) {
    const sh = await shellExec('monkey -p ' + HOST_PKG + ' -c android.intent.category.LAUNCHER 1');
    if (sh.ok) { await sleep(600); restored = true; }
  }
  if (!restored) {
    try {
      const raw = await new Promise((resolve) => {
        execFile(PILOT_BIN, ['home'], { timeout: 8000 }, (err, stdout) => {
          let ok = !err;
          try { const j = JSON.parse(stdout); ok = ok && j.ok === true; } catch (e) { /* ignore */ }
          resolve(ok);
        });
      });
      restored = raw;
    } catch (e) { /* ignore */ }
  }
  if (!restored && prevPkg && prevPkg !== HOST_PKG && prevPkg !== 'com.miui.home') {
    const r = await pilotCall('app.launch', { package: prevPkg }, 8000);
    restored = r.ok;
  }
  if (!restored) {
    try { await pilotCall('ui.key', { key: 'home' }, 5000); } catch (e) { /* ignore */ }
  }
  return { restored };
}

/** 批量投递：同一个人/群的多条消息只打开一次 QQ，连续发完再切回。 */
async function deliverUiBatch(title, texts, kind) {
  const log2 = (m) => log('[ui] ' + m);
  const t0 = Date.now();
  let prevPkg = '';
  try {
    const before = await pilotCall('ui.snapshot', {}, 6000);
    prevPkg = (before.data && before.data.data && before.data.data.package) || '';
  } catch (e) { /* ignore */ }
  const name = String(title || '').replace(/\s*\(\d+条新消息\)\s*$/, '').trim();
  if (!name) return { ok: false, error: 'no-sender-name' };
  const open = await openChatFlow(name, kind || 'private', log2);
  if (!open.ok) {
    await restoreHost(prevPkg, log2);
    log2('打开会话失败（' + open.error + '），耗时 ' + (Date.now() - t0) + 'ms');
    return { ok: false, error: open.error };
  }
  let sentTotal = 0, failedBatches = 0;
  for (const text of texts) {
    const r = await sendSegments(name, text, log2);
    sentTotal += r.sent || 0;
    if (!r.ok) failedBatches++;
  }
  const rh = await restoreHost(prevPkg, log2);
  log2('total ' + (Date.now() - t0) + 'ms, sent=' + sentTotal + ', restored=' + rh.restored + ' prev=' + prevPkg);
  return { ok: sentTotal > 0 && failedBatches < texts.length, sent: sentTotal, error: sentTotal ? '' : 'no-message-sent' };
}

/** 分条规则：先按句末标点切句；一句话超过 12 个汉字时，按逗号再切并去掉该逗号。 */
function splitMessage(text) {
  const raw = String(text || '').replace(/\r/g, '').trim();
  if (!raw) return [];
  const len = (s) => [...s].length;
  const sentences = raw.split(/(?<=[。！？!?…～~])|\n+/).map(x => x.trim()).filter(Boolean);
  const out = [];
  for (const sentence of sentences) {
    const s = sentence.replace(/^[\s。！？!?…～~]+/, '').replace(/[\s。！？!?…～~]+$/, '').trim();
    if (!s) continue;
    if (len(s) <= 12) { out.push(s); continue; }
    // 超过 12 字：按逗号等读点切，去掉分隔的标点
    const pieces = s.split(/[，,；;、：:]/).map(x => x.trim()).filter(Boolean);
    for (const piece of pieces) out.push(piece);
  }
  return out.slice(0, 10);
}

/** 按字符长度估算的真人打字/阅读延迟（近似成比例）。 */
function replyDelayMs(text) {
  const n = [...String(text || '')].length;
  return Math.min(350 + n * 130, 4000) + Math.floor(Math.random() * 250);
}

async function replyViaUi(ev, replyText) {
  const log2 = (m) => log('[ui] ' + m);
  // 0) 记录当前前台 App（回复完切回去，避免 QQ 留前台导致后续没有通知）
  const t0 = Date.now();
  let prevPkg = '';
  try {
    const before = await pilotCall('ui.snapshot', {}, 6000);
    prevPkg = (before.data && before.data.data && before.data.data.package) || '';
  } catch (e) { /* ignore */ }
  const name = String(ev.title || '').replace(/\s*\(\d+条新消息\)\s*$/, '').trim();
  if (!name) return { ok: false, error: 'no-sender-name' };

  // 1) 优先用通知的 contentIntent 打开对应聊天（MIUI 会拦后台 app.launch）
  let pkg = '';
  const opened = await openViaNotification(ev);
  log2('open-by-notification ' + JSON.stringify(opened));
  await sleep(1200);
  {
    const snap0 = await pilotCall('ui.snapshot', {}, 8000);
    pkg = (snap0.data && snap0.data.data && snap0.data.data.package) || '';
  }
  // 1b) 兜底：用 Shell Helper 拉起 QQ（am start，不受 MIUI 后台启动限制）
  for (let i = 0; i < 3 && pkg !== QQ_PKG; i++) {
    const sh = await shellExec('am start -n com.tencent.mobileqq/.activity.SplashActivity');
    log2('shell am start qq ' + JSON.stringify(sh.ok));
    await sleep(900);
    const snap = await pilotCall('ui.snapshot', {}, 10000);
    pkg = (snap.data && snap.data.data && snap.data.data.package) || '';
    if (pkg === QQ_PKG) break;
  }
  if (pkg !== QQ_PKG) return { ok: false, error: 'qq-not-foreground' };

  // 2) 在 QQ 里找到目标会话：优先直接判定当前页
  let snap = await pilotCall('ui.snapshot', {}, 12000);
  let nodes = (snap.data && snap.data.data && snap.data.data.nodes) || [];
  const isChatWithTarget = () => {
    const titleNode = nodes.find(n => (n.text || '').trim() === name && (n.top || 0) < 400);
    const input = nodes.find(n => (n.class || '').includes('EditText'));
    return !!(titleNode && input);
  };
  const hasSearch = () => nodes.some(n => (n.text || '').trim() === '搜索');

  for (let attempt = 0; attempt < 3 && !isChatWithTarget(); attempt++) {
    if (hasSearch()) {
      // 列表页：搜索联系人
      const searchNode = nodes.find(n => (n.text || '').trim() === '搜索');
      await pilotCall('ui.click', { nodeId: searchNode.nodeId }, 8000);
      await sleep(600);
      snap = await pilotCall('ui.snapshot', {}, 10000);
      nodes = (snap.data && snap.data.data && snap.data.data.nodes) || [];
      const edit = nodes.find(n => (n.class || '').includes('EditText'));
      if (!edit) { log2('no-search-input'); break; }
      await pilotCall('ui.setValue', { nodeId: edit.nodeId, text: name }, 8000);
      await sleep(900);
      snap = await pilotCall('ui.snapshot', {}, 10000);
      nodes = (snap.data && snap.data.data && snap.data.data.nodes) || [];
      const hit = nodes.find(n => (n.text || '').trim() === name)
        || nodes.find(n => (n.text || '').includes(name) && (n.class || '').includes('TextView'));
      if (!hit) { log2('no-search-result'); break; }
      await pilotCall('ui.click', { nodeId: hit.nodeId }, 8000);
      await sleep(1100);
    } else {
      // 其它页面：返回上一级再试
      await pilotCall('ui.key', { key: 'back' }, 5000);
      await sleep(600);
    }
    snap = await pilotCall('ui.snapshot', {}, 12000);
    nodes = (snap.data && snap.data.data && snap.data.data.nodes) || [];
  }
  if (!isChatWithTarget()) return { ok: false, error: 'chat-not-found' };

  // 3) 聊天页：按分割队列逐条输入并发送
  const segments = Array.isArray(replyText) ? replyText : splitMessage(replyText);
  if (!segments.length) return { ok: false, error: 'empty-message' };
  log2('本条回复切成 ' + segments.length + ' 条: ' + JSON.stringify(segments.map(x => x.slice(0, 20))));
  let sent = 0;
  for (let si = 0; si < segments.length; si++) {
    const seg = segments[si];
    let snapS = await pilotCall('ui.snapshot', {}, 10000);
    let nodesS = (snapS.data && snapS.data.data && snapS.data.data.nodes) || [];
    let inputS = nodesS.find(n => (n.class || '').includes('EditText'));
    // 偶尔输入框会失焦，重新找聊天页
    for (let retry = 0; retry < 2 && !inputS; retry++) {
      await sleep(600);
      snapS = await pilotCall('ui.snapshot', {}, 10000);
      nodesS = (snapS.data && snapS.data.data && snapS.data.data.nodes) || [];
      inputS = nodesS.find(n => (n.class || '').includes('EditText'));
    }
    if (!inputS) { log2('第 ' + (si + 1) + ' 条找不到输入框，跳过'); continue; }
    await pilotCall('ui.setValue', { nodeId: inputS.nodeId, text: seg }, 8000);
    await sleep(350);
    snapS = await pilotCall('ui.snapshot', {}, 10000);
    nodesS = (snapS.data && snapS.data.data && snapS.data.data.nodes) || [];
    const sendBtn = nodesS.find(n => (n.text || '').trim() === '发送');
    if (!sendBtn) { log2('第 ' + (si + 1) + ' 条找不到发送按钮，跳过'); continue; }
    await pilotCall('ui.click', { nodeId: sendBtn.nodeId }, 8000);
    sent++;
    const delay = replyDelayMs(seg);
    log2('第 ' + (si + 1) + '/' + segments.length + ' 条已发（' + [...seg].length + ' 字，延迟 ' + delay + 'ms）');
    await sleep(delay);
  }
  if (!sent) return { ok: false, error: 'no-segment-sent' };

  // 4) 切回之前的 App（或回桌面）——QQ 留前台会让后续消息不再产生通知
  let restored = false;
  if (prevPkg === QQ_PKG) {
    restored = true; // 用户本来就在 QQ，不做打扰
  } else {
    // a0) Shell Helper 直接拉起宿主（最稳，免审批）
    if (prevPkg === HOST_PKG || !prevPkg) {
      const sh = await shellExec('monkey -p ' + HOST_PKG + ' -c android.intent.category.LAUNCHER 1');
      if (sh.ok) { await sleep(600); restored = true; }
    }
    // a) pilot home（宿主已在前台或允许后台启动时有效）
    try {
      const raw = await new Promise((resolve) => {
        execFile(PILOT_BIN, ['home'], { timeout: 8000 }, (err, stdout) => {
          let ok = !err;
          try { const j = JSON.parse(stdout); ok = ok && j.ok === true; } catch (e) { /* ignore */ }
          resolve(ok);
        });
      });
      restored = raw;
    } catch (e) { /* ignore */ }
    // b) 最近任务里点 DSHBox 卡片（不受后台启动限制）
    if (!restored) {
      try {
        await pilotCall('ui.key', { key: 'recents' }, 5000);
        await sleep(900);
        const snapR = await pilotCall('ui.snapshot', {}, 10000);
        const nodesR = (snapR.data && snapR.data.data && snapR.data.data.nodes) || [];
        const card = nodesR.find(n => (n.text || '').trim() === 'DSHBox');
        if (card) {
          const cx = Math.round(((card.left || 0) + (card.right || 0)) / 2);
          const cy = Math.round(((card.top || 0) + (card.bottom || 0)) / 2);
          await pilotCall('ui.tap', { x: cx, y: cy }, 6000);
          await sleep(900);
          const snap2 = await pilotCall('ui.snapshot', {}, 8000);
          restored = ((snap2.data && snap2.data.data && snap2.data.data.package) === HOST_PKG);
        }
      } catch (e) { /* ignore */ }
    }
    // c) 再兜底：回桌面
    if (!restored) {
      try { await pilotCall('ui.key', { key: 'home' }, 5000); } catch (e) { /* ignore */ }
    }
  }
  await sleep(200);
  log2('total ' + (Date.now() - t0) + 'ms, restored=' + restored + ' prev=' + prevPkg);
  return { ok: true, error: '' };
}

let uiResultCursor = 0;

/** 让手机 App 用通知自带的 contentIntent 打开对应聊天（绕过 MIUI 后台启动限制）。 */
async function openViaNotification(ev) {
  try {
    const posted = await httpJson('POST', '/qq/ui-cmd', { action: 'open', key: ev.key, title: String(ev.title || '').replace(/\s*\(\d+条新消息\)\s*$/, '').trim() });
    const want = posted && posted.id;
    if (!want) return { ok: false, error: 'cmd-rejected' };
    for (let i = 0; i < 12; i++) {
      await sleep(300);
      const d = await httpJson('GET', '/qq/ui-results?since=' + uiResultCursor);
      for (const r of (d.results || [])) {
        uiResultCursor = Math.max(uiResultCursor, r.id || 0);
        if (r.id >= want) return { ok: r.ok === true, error: r.error || '' };
      }
    }
    return { ok: false, error: 'open-timeout' };
  } catch (e) {
    return { ok: false, error: String(e && e.message) };
  }
}

function callDsh(prompt) {
  return new Promise((resolve) => {
    let out = '';
    let err = '';
    let child;
    try {
      child = spawn(process.execPath, [DSH_BIN, '--profile', 'headless', prompt], {
        stdio: ['ignore', 'pipe', 'pipe'],
        env: { ...process.env },
      });
    } catch (e) {
      return resolve({ ok: false, text: '', error: String(e && e.message) });
    }
    const timer = setTimeout(() => {
      try { child.kill('SIGKILL'); } catch (e) { /* ignore */ }
    }, DSH_TIMEOUT);
    child.stdout.on('data', c => { out += c; });
    child.stderr.on('data', c => { err += c; });
    child.on('error', (e) => { clearTimeout(timer); resolve({ ok: false, text: '', error: String(e && e.message) }); });
    child.on('close', (code) => {
      clearTimeout(timer);
      const text = out.trim();
      if (code === 0 && text) resolve({ ok: true, text });
      else resolve({ ok: false, text: '', error: 'exit=' + code + ' ' + err.slice(-300) });
    });
  });
}

async function genReply(ev) {
  if (MODE === 'llm' || MODE === 'api') {
    const r = await callLlm(ev);
    if (r.ok) return { ok: true, text: r.text.slice(0, REPLY_MAX) };
    log('llm 失败: ' + r.error);
    return { ok: false, text: '（我这会儿有点忙，稍后回复你）' };
  }
  if (MODE === 'echo' || MODE === 'test') {
    return { ok: true, text: '【自动回复】收到：' + String(ev.text || '').slice(0, 100) };
  }
  const r = await callDsh(buildPrompt(ev));
  if (!r.ok) {
    log('dsh 失败: ' + r.error);
    return { ok: false, text: '（DSH 暂时无法回答，稍后再试）' };
  }
  return { ok: true, text: r.text.slice(0, REPLY_MAX) };
}

// ---- 主循环 ----
let cursor = (() => { try { return Number(fs.readFileSync(CURSOR_FILE, 'utf8')) || 0; } catch (e) { return 0; } })();
const seen = new Set();
const recentText = new Map(); // sender|text -> ts

async function tick() {
  if (RELAY) return; // relay 模式：只负责发插件给的消息
  let data;
  try {
    data = await httpJson('GET', '/qq/events?since=' + cursor);
  } catch (e) {
    return; // 服务未起，等下一轮
  }
  for (const ev of data.events || []) {
    cursor = Math.max(cursor, ev.id || 0);
    try { fs.writeFileSync(CURSOR_FILE, String(cursor)); } catch (e) { /* ignore */ }
    if (!ev.key || !ev.text) continue;
    const dedupe = ev.key + '|' + ev.text;
    if (seen.has(dedupe)) continue;
    seen.add(dedupe);
    if (seen.size > 500) seen.clear();
    const textKey = (ev.title || '') + '|' + (ev.text || '');
    const lastSame = recentText.get(textKey) || 0;
    if (Date.now() - lastSame < 90000) { log('重复消息，跳过: ' + (ev.title || '')); continue; }
    recentText.set(textKey, Date.now());
    if (recentText.size > 300) recentText.clear();
    // 白名单校验（UI 通道也遵守）
    if (ev.canReply === false && !(UI_REPLY && ev.pkg === QQ_PKG)) {
      log('不可直回，跳过: ' + (ev.title || '') + ' / ' + (ev.text || '').slice(0, 40));
      continue;
    }
    if (ALLOW.length && !ALLOW.includes(ev.title)) { log('不在白名单，跳过: ' + ev.title); continue; }
    if (ev.convTitle && !GROUPS.includes(ev.convTitle)) { log('群消息跳过: ' + ev.convTitle + ' / ' + ev.text.slice(0, 40)); continue; }
    log('收到: [' + (ev.convTitle || '私聊') + '] ' + (ev.title || '') + ': ' + String(ev.text).slice(0, 80));
    const reply = await genReply(ev);
    if (!reply.text) continue;
    if (ev.canReply === false) {
      // QQ 没有通知快捷回复：用 UI 自动化发送
      log('走 UI 自动化回复: ' + reply.text.slice(0, 60));
      const r = await replyViaUi(ev, reply.text);
      log((r.ok ? 'UI 回复成功 #' : 'UI 回复失败 #') + ev.id + (r.error ? (' (' + r.error + ')') : ''));
      try { await httpJson('POST', '/qq/reply-result', { id: ev.id, key: ev.key, ok: r.ok, error: r.error, channel: 'ui' }); } catch (e) { /* ignore */ }
    } else {
      try {
        await httpJson('POST', '/qq/reply', { key: ev.key, text: reply.text, eventId: ev.id });
        log('已发回复 #' + ev.id + ': ' + reply.text.slice(0, 80));
      } catch (e) {
        log('回复投递失败: ' + (e && e.message));
      }
    }
  }
}

async function resultsLoop() {
  let rc = 0;
  for (;;) {
    try {
      const d = await httpJson('GET', '/qq/results?since=' + rc);
      for (const r of d.results || []) { rc = Math.max(rc, r.id || 0); log('App 回执: ' + JSON.stringify(r).slice(0, 200)); }
    } catch (e) { /* ignore */ }
    await new Promise(r => setTimeout(r, 3000));
  }
}

let relayCursor = (() => { try { return Number(require('node:fs').readFileSync('/tmp/qq-bridge-relay-cursor', 'utf8')) || 0; } catch (e) { return 0; } })();
let relayFirst = true;

/** relay 模式：插件/DSH 会话把回复放进 /qq/ui-sends，这里负责逐条打开 QQ 发送。 */
async function relayLoop() {
  for (;;) {
    try {
      const d = await httpJson('GET', '/qq/ui-sends?since=' + relayCursor);
      if (relayFirst) {
        relayFirst = false;
        if (relayCursor === 0) { relayCursor = Number(d.cursor) || 0; try { require('node:fs').writeFileSync('/tmp/qq-bridge-relay-cursor', String(relayCursor)); } catch (e) { /* ignore */ } continue; }
      }
      const items = d.sends || [];
      let i = 0;
      while (i < items.length) {
        const head = items[i];
        const title = head.title || 'Myself';
        const kind = head.kind || 'private';
        const batch = [head];
        const texts = [String(head.text)];
        let j = i + 1;
        while (j < items.length && batch.length < 6 && (items[j].title || 'Myself') === title) {
          batch.push(items[j]); texts.push(String(items[j].text)); j++;
        }
        const lastId = batch[batch.length - 1].id;
        log('relay 批量 #' + head.id + '~#' + lastId + ' -> ' + title + '（' + batch.length + ' 条，' + kind + '）');
        let wake = null;
        let r = { ok: false, error: '' };
        try {
          wake = await ensureAwake();
          if (wake.woke) log('relay 已唤醒屏幕');
          r = await deliverUiBatch(title, texts, kind);
        } catch (e) {
          r = { ok: false, error: String(e && e.message) };
        } finally {
          try { await restoreSleep(wake); } catch (e) { /* ignore */ }
        }
        if (r.ok) {
          relayCursor = Math.max(relayCursor, lastId || 0);
          try { require('node:fs').writeFileSync('/tmp/qq-bridge-relay-cursor', String(relayCursor)); } catch (e) { /* ignore */ }
          log('relay 发送成功 #' + head.id + '~#' + lastId);
          for (const it of batch) {
            try { await httpJson('POST', '/qq/reply-result', { id: it.id, key: 'ui-send', ok: true, error: '', channel: 'relay' }); } catch (e) { /* ignore */ }
          }
        } else {
          const tries = (head.tries || 0) + 1;
          if (tries >= 3) {
            relayCursor = Math.max(relayCursor, lastId || 0);
            try { require('node:fs').writeFileSync('/tmp/qq-bridge-relay-cursor', String(relayCursor)); } catch (e) { /* ignore */ }
            log('relay 发送失败 #' + head.id + '~#' + lastId + '（重试 ' + tries + ' 次后放弃）: ' + r.error);
            for (const it of batch) {
              try { await httpJson('POST', '/qq/reply-result', { id: it.id, key: 'ui-send', ok: false, error: r.error, channel: 'relay' }); } catch (e) { /* ignore */ }
            }
          } else {
            log('relay 发送失败 #' + head.id + '（第 ' + tries + ' 次，稍后重试）: ' + r.error);
            await sleep(3000);
            break;
          }
        }
        i = j;
      }
    } catch (e) { /* ignore */ }
    await new Promise(r => setTimeout(r, 1200));
  }
}

(async () => {
  log('QQ bridge 启动 mode=' + MODE + ' server=' + SERVER + (ONCE ? ' (single run)' : ''));
  if (RELAY) {
    await relayLoop();
    return;
  }
  if (ONCE) {
    await tick();
    await new Promise(r => setTimeout(r, 800));
    try { const st = await httpJson('GET', '/qq/stats'); log('stats: ' + JSON.stringify(st).slice(0, 600)); } catch (e) { /* ignore */ }
    process.exit(0);
  }
  resultsLoop();
  for (;;) {
    try { await tick(); } catch (e) { log('tick error: ' + (e && e.message)); }
    await new Promise(r => setTimeout(r, POLL_MS));
  }
})();
