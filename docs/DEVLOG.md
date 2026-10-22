# DSH 状态条（配套 Android App + 状态服务）

在其它 App 上方显示一条滚动状态条，实时告诉用户 DSH agent 正在做什么、什么时候结束。
解决「agent 在别的 App 前台操作时，用户看不到进度、也不知道它停了没」的问题。

## 组成

| 部件 | 位置 | 作用 |
|---|---|---|
| 状态服务 | `server/status-server.js` | 沙盒内 Node HTTP 服务（默认 `127.0.0.1:8790`），保存/推送当前状态，附带桌面/手机浏览器可看的状态页 |
| Android App | `android/` | `com.dshstatus.hud`：配置页 + 前台服务，轮询状态服务，用 `TYPE_APPLICATION_OVERLAY` 画顶部滚动条；完成变绿 5 秒后收起，点击可跳回 DSHBox |
| 构建脚本 | `android/build.sh` | 在 Debian arm64 沙盒里直接打 APK（javac + d8 + aapt + zipalign + apksigner） |

## 用法

### 1. 启动状态服务（沙盒里）

```bash
node server/status-server.js          # 前台运行
# 或后台： nohup node server/status-server.js >/tmp/hud.log 2>&1 &

# 推送状态（agent 每步调用）
node server/status-server.js push "正在滚动 B 站推荐 · 第 7 屏 · 41 条"
node server/status-server.js done "完成 · 7 屏 / 18 次滑动"
node server/status-server.js idle
```

浏览器打开 `http://127.0.0.1:8790/` 可直接看状态页（手机/电脑都行；如果要局域网其他设备看，
用 `HUD_HOST=0.0.0.0 node server/status-server.js`，注意局域网暴露风险）。

### 2. 安装 APK

构建产物 `android/dsh-status-hud.apk`，用 `app.install` 能力装（或系统文件管理器）。

### 3. App 内三步

1. 点「① 授权显示在其他应用上层」→ 系统设置里允许；
2. 点「② 启动状态条」；
3. 回到任意 App，顶部就会出现滚动条。

## 状态协议

`POST /status`（JSON）：

```json
{"state":"running|done|blocked|idle","text":"正在滚动 B 站推荐 · 第 7 屏","step":7,"total":10}
```

服务自动补 `elapsedSec` / `startedAt` / `updatedAt`；`GET /status` 返回当前快照。

## 已知限制

- 只能显示状态，不能点按钮控制 agent（下一步可加 `POST /cancel` + DSH 插件侧中断）；
- 状态由 agent 主动推送（当前实现），要做到「自动跟踪每次工具调用」，需要在 DSH 插件层 hook 工具生命周期；
- 悬浮窗被 MIUI 回收后需要重新打开 App 点启动（可加入电池白名单缓解）。

## v1.2 行为变化

- **环形连续滚动**：文本复制多份铺满屏宽，做 0 → -单份宽度 的无限线性动画，接缝处内容相同，视觉上是首尾相接的环，不会跳变；
- **空闲显示**：没有任务时不再隐藏，固定显示「暂时没有新的任务」；
- **放在状态栏那一行**：悬浮条高度 = 系统状态栏高度、y=0。应用窗口内容本来就画在状态栏下方，
  所以条与应用内容上下并列、互不遮挡（系统状态栏的时钟/电池会被条盖住）。

## 关于「让应用窗口真的变矮」

普通悬浮窗（SYSTEM_ALERT_WINDOW）只能覆盖显示，**无法改变其它应用的窗口尺寸**。
要让前台应用窗口真的缩短、把顶部让出来，需要 shell 级窗口管理：

```bash
# 顶部让出 120px（需要 shell 权限，即 Shizuku 或 root）
wm overscan 0,120,0,0
# 恢复
wm overscan 0,0,0,0
```

可选实现：配套 App 集成 Shizuku API 直接执行 `wm overscan`，任务开始让位、结束恢复。
风险：`wm overscan` 是全局显示级改动，旋转/重启后可能失效，部分 MIUI 版本会忽略；
做得不好会出现键盘错位或截图异常。所以默认方案仍是「占用状态栏那一行」。

## 自启动（DSH 插件）

- Android App：打开即自动启动前台服务（悬浮窗已授权时），不用再点②；
- DSH 插件 `@local/dsh-status-hud`：已装配进 web profile，**DSH 启动时自动拉起状态服务**，
  DSHBox 重启后条不会再显示「未连接」。
- 生效条件：需要重启一次 DSH（profile 变化在启动时加载）。注意 MIUI 重启 DSHBox 会掉无障碍，
  重启后要重新开一次；下次做 Shizuku 集成可自动恢复。

### 位置

条默认放在状态栏那一行：`y = -状态栏高度 + 用户偏移`，并带 `FLAG_LAYOUT_NO_LIMITS`。
App 内「⬆ 上移 / ⬇ 下移 / ↺ 重置」可实时微调（存 SharedPreferences `yOffsetExtra`）。

## Shizuku 增强（进行中，遇到真机启动崩溃）

目标：App 通过 Shizuku UserService 以 shell 身份执行命令，实现
① DSHBox 重启后自动恢复无障碍；② 可选 `wm overscan` 让全屏应用为状态条让位。

已完成：
- 依赖 `dev.rikka.shizuku:api/provider:13.1.5`，`IUserService.aidl` + UserService（以 shell 执行命令）编译通过；
- 手动打包时需合并 `provider.aar` 的清单声明：
  `uses-permission moe.shizuku.manager.permission.API_V23` 和
  `meta-data moe.shizuku.client.V3_SUPPORT=true`（否则管理器走旧协议拿不到 binder）。

阻塞：
- 只要清单里声明 `rikka.shizuku.ShizukuProvider`，App 进程会在 `Application.onCreate`
  之前被系统杀掉（无窗口、无崩溃日志）。用探针 Provider 反射加载/实例化/attachInfo/onCreate
  该 Provider 均正常，说明不是类本身的问题；需要真机 `logcat` 才能看到系统安装 Provider 时的异常。
- 曾尝试：关闭 Sui 自动初始化（static 块）、去掉 provider 的 permission 属性、限制 provider 权限，
  均无效；不声明 Provider 的版本一切正常。
- 结论：需要 `sys.shell`（宿主危险能力）读 logcat 继续定位，或改用宿主 shell 直接实现
  「自动恢复无障碍 + overscan」而不在 App 里集成 Shizuku。

## ✅ Shizuku 增强已修复（v4.3）

根因：**手动打包漏了 Shizuku API 的传递依赖 AAR**。
`api.aar` 的 POM 依赖 `dev.rikka.shizuku:aidl` 和 `dev.rikka.shizuku:shared`，
里面是 `moe.shizuku.server.*` 这些运行时必需的类。缺了它们时，Shizuku 服务端
在 App 启动瞬间回调 Provider 送 binder，`Shizuku.pingBinder()` 触发
`NoClassDefFoundError: moe.shizuku.server.IShizukuApplication$Stub`，
在 binder 线程变成未捕获异常 → 进程被杀（死在 Application.onCreate 之前，所以没有任何日志）。

修复：build.sh 里把 `shizuku-api.jar / shizuku-provider.jar / shizuku-aidl.jar / shizuku-shared.jar`
一起作为 d8 的 program input。验证结果：Provider 的 `sendBinder` 调用成功，
Shizuku 状态「运行中 / 已授权 / UserService 就绪（shell 可用）」。

已验证功能：
- 捕获 DSHBox 无障碍组件：`com.dshbox.app/interlock.relay.core.exec.a11y.RelayAccessibilityService`
- 手动恢复无障碍：✅ 成功（`settings put secure enabled_accessibility_services …`）
- 自动恢复看门狗：HudService 每 ~30 秒检查一次，掉线自动写回（可开关）
- `wm overscan 0,120,0,0`：**实测有效**（顶部让出黑带、应用窗口下移），`0,0,0,0` 可恢复
- 注意：overscan 全局生效，旋转/重启可能失效，建议默认关、需要时手动开

## v4.5 让位模式（解决“状态栏被挡/黑边/下移太多”）

设计：**滚动条高度 = 让位高度（overscan 值）**，位置顶到屏幕 y=0。
效果三行并列：

```
[ 滚动条 0..H ]          ← 我们画的，H = 让位高度
[ 系统状态栏 H..H+S ]     ← 系统画的，时钟/电池恢复可见
[ 应用内容 H+S.. ]        ← 窗口真的变矮
```

- 让位值可调：关闭 / 60 / 75 / 100 / 120 / 160 px，选择会持久化，服务启动自动应用；
- 点「关闭让位」用 `wm overscan reset`（比 `0,0,0,0` 可靠），恢复全屏；
- 「读取当前显示状态」按钮直接 `dumpsys display | grep -i overscan`，用于确认是否真的生效
  （之前 `wm overscan 0,0,0,0` 实际没清掉，残留 120px 导致黑边）；
- 停止状态条（③）时自动 `wm overscan reset`，避免留下全局显示副作用。

## 实测结论（MIUI 12 · Android 10）：overscan 会让系统状态栏消失

- 无 overscan：系统状态栏正常（时钟/电池/信号都在）；
- 一旦 `wm overscan` 生效：**系统状态栏被 MIUI 整个隐藏**（顶部 0..75 纯黑、无任何图标），
  无论把滚动条放在状态栏行还是让位带里，都看不到系统状态栏。
- 因此「系统状态栏可见 + 应用窗口变矮 + 滚动条不遮挡」三者在本机不可兼得。

最终方案（v4.7/4.8）：
- 默认**不用 overscan**；滚动条占据状态栏那一行，**内容里自带时间 + 电量**，作为状态栏的替代；
- 应用正文从状态栏下方开始，不被遮挡；
- 让位模式保留为高级选项（会隐藏系统状态栏，慎用），选择会持久化；
- 服务启动时若未选择让位，会主动 `wm overscan reset` 清理残留；
- 监听系统配置变化（旋转/显示尺寸变化）自动重建悬浮窗，避免窗口被系统移除后不恢复。

## v4.9 / v5.0：两行条覆盖整个顶部带（用户方案）

思路（用户提出）：保留 overscan（应用窗口真的下移），让滚动条**盖住整个顶部区域**
（让位带 + 被 MIUI 隐藏的状态栏带），条里自带状态栏信息，这样既看不到黑带，也不遮挡正文。

实现：
- 条改为两行：第一行 `HH:mm  🔋xx%`（静态居中），第二行操作文本（环形滚动）；
- 高度 = `overscanPx + statusBarHeight + 8px`，位置 `y = -overscanPx`（相对状态栏下方原点），
  即从屏幕最顶部开始，覆盖 0 .. ov+S 整块；
- `+8px` 重叠：框架资源里的状态栏高度（70px）与 MIUI 实际渲染（75px）差几像素，
  不加这 8px 会在底部留一条 5px 黑缝（实测截图确认）；
- 悬浮窗用 `TYPE_APPLICATION_OVERLAY` + `FLAG_LAYOUT_NO_LIMITS`，在 MIUI 上实测位于系统状态栏之上，
  所以即使系统状态栏偶尔恢复显示，也会被条盖住，不会出现双层重叠；
- 配置变化自动重建（v4.8 加入），避免旋转/overscan 切换后窗口丢失。

## v5.3 最终需求（用户定稿）：强制隐藏系统状态栏 + 不 overscan + 左侧信息

- **模式A（默认，v5.3 起迁移强制）**：
  - 不做 overscan（每 ~15s 校验并 `wm overscan reset`，并 `settings delete global policy_control`），
    应用布局完全不动；
  - 单行条：**左侧固定 `HH:mm 🔋xx%`**，**右侧环形滚动操作文本**；
  - 条高 = 状态栏高 + 6px，位置 `y = -状态栏高`，永远盖住系统状态栏那一行 →
    状态栏在任何 App（含 B 站）里都不会露出来，也不会因为 overscan 被推移/隐藏而忽隐忽现；
- 模式B（实验性，保留）：`policy_control immersive.status=*` + overscan 让位 + 两行条。
- 升级迁移：`layoutVersion=3` 时自动把 `statusMode=0 / overscanPx=0 / yOffsetExtra=0` 写回。

## 为什么 Shizuku 写 policy_control“一直不成功”（v5.6 诊断结论）

- 写入本身是**成功**的：App 内诊断按钮运行
  `settings put global policy_control immersive.status=*` 后回读 = `immersive.status=*`（有上报日志为证）；
- 但 **MIUI 12 并不会稳定按 policy_control 隐藏状态栏**：部分 App / 系统界面仍显示状态栏，
  且会被系统或 App 自身覆盖 → 表现为“一直不成功”。
- 可靠做法：**用悬浮层直接盖住状态栏那一行**（实测 MIUI 上 TYPE_APPLICATION_OVERLAY 位于系统状态栏之上）。
  条高/位置在运行时通过 `dumpsys window` 读取状态栏窗口真实 frame 自动对齐（v5.6）。
- 悬浮窗坐标是**屏幕绝对坐标**（原点 0）。此前按“状态栏下方原点”算 `y=-状态栏高`
  导致条被顶出屏幕（只剩几像素）——这是“初始偏移太上方、一开始一直不显示”的真正原因，v5.4 起修为 `y=0`。

## v6.1 定稿（按用户确认的方案）

**唯一模式**：强制隐藏系统状态栏 + 单行滚动条，无 overscan。

1. **强制隐藏状态栏**：Shizuku 执行 `settings put global policy_control immersive.status=*`
   （用户实测有效；App 内诊断按钮已确认写入并回读成功），每 ~14 秒自校验，掉线自动重写；
2. **单行滚动条**：左侧固定 `HH:mm 🔋xx%`，右侧环形滚动显示 agent 当前操作；
3. **自动对齐状态栏**：运行时用 `dumpsys window` 读系统状态栏窗口真实 frame 高度（76px），
   条高 = 真实高度 + 2，顶部 `y=0`（悬浮窗坐标是屏幕绝对坐标）；
4. **保留上下微调**：App 内「状态条上移/下移 10px」「重置偏移」；
5. **停止即恢复**：点「③ 停止状态条」会删除 policy_control 并 `wm overscan reset`，系统状态栏回归；
6. 已删除：模式A/B、overscan 预设、条高预设、policy 诊断按钮、读取显示状态等实验功能；
7. 迁移：`layoutVersion=6` 时自动清零历史偏移/overscan。

验证（v6.1 实测截图，B 站）：0..76px 为滚动条（左时间电量 + 右滚动文本），77px 起直接是 B 站内容；
系统状态栏完全不可见；`settings get global policy_control` = `immersive.status=*`。

## v6.2：为什么不能直接改系统状态栏 + 最终“状态栏替代”方案

### 为什么普通 App 无法把文字画进系统状态栏
- 状态栏是 SystemUI（`com.android.systemui`）的窗口，平台签名保护，无公开注入 API；
- 通知在状态栏只能显示**小图标**；Android 5 起已移除状态栏滚动 ticker 文字；
- 要真正改写状态栏只能 root 改 SystemUI / 自定义 ROM / Xposed-Magisk 模块（本机无 root，Shizuku 仅 shell）。
- 等效方案：`policy_control immersive.status=*` **隐藏**状态栏，再用悬浮条占据那块区域。

### 关键补充：配合 overscan 让位，解决“有的 App 被遮挡”
隐藏状态栏后 App 窗口变大，响应沉浸式的 App 会把内容顶到 0 区，导致条压内容。v6.2 的做法：
- 隐藏状态栏（policy_control）；
- `wm overscan 0,<真实状态栏高+2>,0,0` 把这块空间从 **所有** App 窗口里让出来；
- 条从 y=0 开始，高度 = 让位高度，正好盖住该区域；
- 结果：所有 App 布局一致（内容统一从 77px 开始），条不遮挡任何正文，也没有黑带（黑带区域被条盖住）。
- 真实状态栏高度通过 `dumpsys window` 读取并持久化（`realSbH`），资源值不准时也能对齐。

## v6.3：透明背景（用户要求）

- 背景默认透明，文字加阴影保证可读；
- 取消 overscan 让位（透明背景需要 App 内容透出来），只保留 policy_control 隐藏状态栏；
- App 内可切换：**透明 / 半透明 / 深色**（prefs `bgAlpha`：0 / 0x66 / 0xFF）；
- 状态颜色保留：运行=蓝、完成=绿、等待=琥珀、空闲=灰。

## v6.4：水平起始位置微调（避开刘海/挖孔）

- 新增 prefs `xOffsetExtra`（px），作为条的左内边距，整条内容（时间电量 + 滚动文字）一起右移；
- App 内按钮：「⬅ 文字左移 10px」「➡ 文字右移 10px」「↺ 重置水平偏移」，实时生效并持久化；
- 用途：避开 MI 9 的水滴刘海/MIUI 挖孔区域，让文字从安全区开始。

## v6.5：水平偏移只作用于滚动文字（修正）

- 时间/电量固定在左侧不动；`xOffsetExtra` 改为作用在滚动文字视图的左内边距上，
  只调整**滚动文字起始位置**，用于避开刘海/挖孔区域；
- App 内左右微调按钮不变。

## v6.6：修复“水平偏移没效果”

根因：`RingTextView` 之前用的是**写死的 14dp 左边距**（`padLeftPx` 在构造时固定），
完全没有读取 `View.setPadding()`，所以给滚动文字设水平偏移不生效。
修复：`onDraw` 里改为 `baseX = padLeftPx + getPaddingLeft()`，动态读取。
验证：xOffset=220 时，左侧时间/电量固定在 x≈29，滚动文字起点在 x≈670；偏移只影响滚动文字。

## 插件 v2.0：自动推送任务信息（2026-10-22）

- 早期插件只负责拉起状态服务；v2.0 起监听 DSH `session/event`：
  - `turn/start` → running「🤖 处理中…」
  - `tool/call`（event.data.name）→ running「🔧 <中文动作>（工具名）」，内置常见工具映射
  - `approval/asked` → blocked「⏸ 等待你的确认」
  - `turn/end`（活跃回合计数归零）→ idle
- 500ms 合并 + 去重，非阻塞；`ctx.on('dispose')` 清理监听与子进程；加载失败只 warn。
- 事件名与载荷来源：`dsh-agent-loop` 的 `session.append(...)`（turn/start、tool/call …），
  监听方式参照 `dsh-agent-instructions` 的 `ctx.on("session/event", (session, event) => …)`。
- 用假 ctx + 真实状态服务做了事件序列测试：running → 🔧 执行命令（bash）→ 🔧 点击手机控件（phone_click）
  → ⏸ 等待确认 → idle，全部符合预期。
