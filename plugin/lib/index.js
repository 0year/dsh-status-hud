'use strict';

// DSH 插件：把 DSH agent 的活动自动推送到「DSH 状态条」（Android 悬浮条）。
//
// 两件事：
//   1. 把状态服务（server/status-server.js，默认 127.0.0.1:8790）拉起来；
//   2. 监听 DSH 的会话事件，自动推送「正在做什么」：
//        turn/start   -> running  🤖 处理中…
//        tool/call    -> running  🔧 <工具名>  （常见工具映射成中文描述）
//        approval/asked -> blocked ⏸ 等待你的确认
//        turn/end     -> 没有活跃回合时恢复 idle（暂时没有新的任务）
//   可选：环境变量 HUD_SERVER_URL 指向别处（默认 http://127.0.0.1:8790）。
//
// 设计约束：
//   · 插件加载失败绝不能影响 DSH 启动 —— 全部 try/catch，失败只 warn；
//   · 事件高频（每步都有工具调用），推送做去重 + 节流，且不阻塞事件循环；
//   · 不注册工具、不 inject 任何服务，降低耦合。

const { spawn } = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');
const http = require('node:http');

const name = 'dsh-status-hud';

/** 工具名 -> 人类可读动作（没列到的直接用名字）。 */
const TOOL_LABELS = {
  bash: '执行命令',
  read: '读取文件',
  write: '写入文件',
  edit: '修改文件',
  grep: '搜索代码',
  glob: '查找文件',
  web_search: '搜索网页',
  web_fetch: '抓取网页',
  subagent: '子代理处理中',
  subagent_fork: '子代理处理中',
  todo_write: '更新任务清单',
  skill: '加载技能',
  phone_observe: '查看手机界面',
  phone_click: '点击手机控件',
  phone_setValue: '填写手机输入框',
  phone_scroll: '滚动手机界面',
  phone_node: '读取手机控件',
  phone_capture: '手机截图',
  phone_launch: '启动手机应用',
  phone_intent: '调用系统入口',
  phone_call: '调用手机能力',
  phone_ask: '向你提问',
  browser_navigate: '操作浏览器',
  browser_observe: '读取浏览器页面',
  browser_click: '点击浏览器元素',
  browser_type: '浏览器输入文字',
  present: '展示成果',
  create_goal: '设定目标',
  update_goal: '更新目标',
};

function serverUrl() {
  return (process.env.HUD_SERVER_URL || 'http://127.0.0.1:8790').replace(/\/+$/, '');
}

/** POST 一个状态到状态服务；不等待结果，失败静默。 */
function pushStatus(state, text, extra) {
  try {
    const body = JSON.stringify(Object.assign({ state, text: text || '' }, extra || {}));
    const url = new URL(serverUrl() + '/status');
    const req = http.request({
      hostname: url.hostname,
      port: url.port || 80,
      path: url.pathname,
      method: 'POST',
      headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) },
    }, (res) => { res.resume(); });
    req.on('error', () => {});
    req.setTimeout(800, () => req.destroy());
    req.write(body);
    req.end();
  } catch (e) { /* ignore */ }
}

/** 保证状态服务在跑（EADDRINUSE 时它自己会静默退出，不会重复占用）。 */
function ensureServer() {
  try {
    const script = path.join(__dirname, '..', 'status-server.js');
    if (!fs.existsSync(script)) {
      console.warn('[status-hud] 找不到 status-server.js，跳过服务启动');
      return null;
    }
    const child = spawn(process.execPath, [script], {
      stdio: 'ignore',
      detached: false,
      env: { ...process.env, HUD_PORT: process.env.HUD_PORT || '8790' },
    });
    child.on('error', (e) => {
      console.warn('[status-hud] 状态服务启动失败:', e && e.message ? e.message : e);
    });
    child.on('exit', (code, signal) => {
      if (code && code !== 0 && code !== 143) {
        console.warn('[status-hud] 状态服务退出 code=' + code + ' signal=' + signal);
      }
    });
    console.log('[status-hud] 状态服务已启动 pid=' + (child && child.pid));
    return child;
  } catch (e) {
    console.warn('[status-hud] 状态服务启动异常（已忽略）:', e && e.message ? e.message : e);
    return null;
  }
}

function apply(ctx) {
  let child = null;
  try {
    child = ensureServer();
  } catch (e) { /* ignore */ }

  // ---- 事件 -> 状态条 ----
  let activeTurns = 0;
  let lastPushed = '';      // 去重
  let lastAt = 0;           // 节流（ms）
  let pending = null;       // 合并窗口内的最后一次
  let timer = null;

  const MIN_INTERVAL = 500;

  function flush() {
    timer = null;
    const p = pending;
    pending = null;
    if (!p) return;
    const key = p.state + '|' + p.text;
    if (key === lastPushed) return;
    lastPushed = key;
    pushStatus(p.state, p.text, p.extra);
  }

  function push(state, text, extra) {
    pending = { state, text, extra };
    const now = Date.now();
    const wait = Math.max(0, MIN_INTERVAL - (now - lastAt));
    if (!timer) {
      timer = setTimeout(flush, wait);
      if (typeof timer.unref === 'function') timer.unref();
    }
    lastAt = now;
  }

  function onEvent(session, event) {
    try {
      if (!event || typeof event.type !== 'string') return;
      const data = event.data && typeof event.data === 'object' ? event.data : {};
      switch (event.type) {
        case 'turn/start':
          activeTurns++;
          push('running', '🤖 处理中…');
          break;
        case 'tool/call': {
          const raw = typeof data.name === 'string' ? data.name : '';
          const label = TOOL_LABELS[raw] || raw || '执行工具';
          push('running', '🔧 ' + label + (raw && TOOL_LABELS[raw] ? '（' + raw + '）' : ''));
          break;
        }
        case 'approval/asked':
          push('blocked', '⏸ 等待你的确认');
          break;
        case 'turn/end':
          activeTurns = Math.max(0, activeTurns - 1);
          if (activeTurns === 0) push('idle', '');
          break;
        default:
          break;
      }
    } catch (e) { /* ignore */ }
  }

  // 观察所有会话的追加事件；按需删掉自己挂的监听。
  const listeners = [];
  try {
    if (typeof ctx.on === 'function') {
      ctx.on('session/event', onEvent);
      listeners.push(['session/event', onEvent]);
    }
  } catch (e) {
    console.warn('[status-hud] 事件监听注册失败（已忽略）:', e && e.message ? e.message : e);
  }

  const dispose = () => {
    for (const [evt, fn] of listeners) {
      try { if (typeof ctx.off === 'function') ctx.off(evt, fn); } catch (e) { /* ignore */ }
    }
    try { if (timer) { clearTimeout(timer); timer = null; } } catch (e) { /* ignore */ }
    try { if (child && !child.killed) child.kill('SIGTERM'); } catch (e) { /* ignore */ }
  };

  if (typeof ctx.on === 'function') {
    try { ctx.on('dispose', dispose); } catch (e) { /* ignore */ }
  }
}

module.exports = { name, apply };
