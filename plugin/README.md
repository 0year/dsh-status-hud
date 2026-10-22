# @local/dsh-status-hud · DSH 自动推送插件

把 DSH agent 的活动**自动**推送到「DSH 状态条」（Android 悬浮条），并在 DSH 启动时拉起状态服务。
装好之后不需要 agent 手动调命令 —— 状态条会自己跟着 agent 的操作变化。

## 安装

把本目录复制到 DSH profile 的 `node_modules/@local/dsh-status-hud/`：

```bash
cp -r plugin/. <DSH_PROFILE>/node_modules/@local/dsh-status-hud/
```

并在 profile 的 `package.json` 里注册 bundle（`dsh.profile.bundles` 数组加一项）：

```json
{
  "dsh": {
    "profile": {
      "bundles": ["@local/dsh-status-hud"]
    }
  }
}
```

重启 DSH 生效（插件在启动时加载）。

## 行为

| DSH 事件 | 推送到状态条 |
|---|---|
| `turn/start` | 🤖 处理中… |
| `tool/call` | 🔧 <动作>（如 `执行命令（bash）`、`点击手机控件（phone_click）`） |
| `approval/asked` | ⏸ 等待你的确认 |
| `turn/end`（无活跃回合） | 空闲：暂时没有新的任务 |

- 高频事件做 **500ms 合并 + 去重**，不会刷屏；
- 插件加载失败只打印 warn，不影响 DSH 启动；
- 状态服务地址可用环境变量覆盖：`HUD_SERVER_URL`（默认 `http://127.0.0.1:8790`），服务端口 `HUD_PORT`。

## 手动控制（可选）

```bash
node status-server.js push "自定义状态"   # running
node status-server.js done "完成"         # done
node status-server.js idle               # idle
```
