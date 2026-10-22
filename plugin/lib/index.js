'use strict';

// DSH 插件：DSH 启动时自动拉起「DSH 状态条」服务（127.0.0.1:8790）。
// 只负责进程生命周期，不注册工具、不依赖 dsh-tools —— 加载失败也绝不能连累 DSH 启动。

const { spawn } = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');

const name = 'dsh-status-hud';

function apply(ctx) {
  let child = null;
  try {
    const script = path.join(__dirname, '..', 'status-server.js');
    if (!fs.existsSync(script)) {
      console.warn('[status-hud] 找不到 status-server.js，跳过');
      return;
    }
    child = spawn(process.execPath, [script], {
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
  } catch (e) {
    console.warn('[status-hud] 初始化异常（已忽略）:', e && e.message ? e.message : e);
    return;
  }
  if (typeof ctx.on === 'function') {
    ctx.on('dispose', () => {
      try {
        if (child && !child.killed) child.kill('SIGTERM');
      } catch (e) { /* ignore */ }
    });
  }
}

module.exports = { name, apply };
