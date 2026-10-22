package com.dshstatus.hud;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import rikka.shizuku.Shizuku;

/** 配置页：悬浮条 + 位置微调 + Shizuku 增强（自动恢复无障碍 / overscan）。 */
public class MainActivity extends Activity {
    private TextView statusView;
    private TextView permView;
    private TextView shzView;
    private TextView a11yView;
    private TextView cmdView;
    private SharedPreferences prefs;
    private final Handler handler = new Handler();
    private int shzReqCounter = 1000;

    private final Runnable refresher = new Runnable() {
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("hud", 0);
        HudApp.reportSync("activity-onCreate");
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (18 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(22, 22, 28));

        TextView title = new TextView(this);
        String ver = "2.0";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        title.setText("DSH 状态条 v" + ver);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("在其它 App 上方显示 DSH agent 当前操作。\n状态服务默认 http://127.0.0.1:8790（由 DSH 插件自启动）。");
        desc.setTextColor(Color.rgb(160, 160, 172));
        desc.setTextSize(13);
        desc.setPadding(0, (int) (6 * d), 0, (int) (14 * d));
        root.addView(desc);

        permView = smallText(Color.rgb(230, 190, 90));
        root.addView(permView);

        Button btnPerm = mkButton("① 授权「显示在其他应用上层」");
        btnPerm.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } else {
                    refreshPerm();
                }
            }
        });
        root.addView(btnPerm);

        Button btnStart = mkButton("② 启动状态条");
        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startForegroundService(new Intent(MainActivity.this, HudService.class));
                statusView.setText("已请求启动状态条…");
            }
        });
        root.addView(btnStart);

        Button btnStop = mkButton("③ 停止状态条");
        btnStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, HudService.class));
                statusView.setText("已停止。");
            }
        });
        root.addView(btnStop);

        // ---- 位置微调 ----
        final TextView offView = smallText(Color.rgb(160, 160, 172));
        offView.setText("状态条偏移：" + prefs.getInt("yOffsetExtra", 0) + "px");
        root.addView(offView);

        Button btnUp = mkButton("⬆ 状态条上移 10px");
        btnUp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int cur = prefs.getInt("yOffsetExtra", 0) - 10;
                prefs.edit().putInt("yOffsetExtra", cur).apply();
                offView.setText("状态条偏移：" + cur + "px");
            }
        });
        root.addView(btnUp);
        Button btnDown = mkButton("⬇ 状态条下移 10px");
        btnDown.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int cur = prefs.getInt("yOffsetExtra", 0) + 10;
                prefs.edit().putInt("yOffsetExtra", cur).apply();
                offView.setText("状态条偏移：" + cur + "px");
            }
        });
        root.addView(btnDown);
        Button btnResetOff = mkButton("↺ 重置偏移");
        btnResetOff.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().putInt("yOffsetExtra", 0).apply();
                offView.setText("状态条偏移：0px");
            }
        });
        root.addView(btnResetOff);

        // ---- Shizuku 增强 ----
        TextView shzTitle = new TextView(this);
        shzTitle.setText("\nShizuku 增强（可选）");
        shzTitle.setTextColor(Color.WHITE);
        shzTitle.setTextSize(16);
        root.addView(shzTitle);

        shzView = smallText(Color.rgb(120, 200, 255));
        shzView.setText("Shizuku：未初始化");
        root.addView(shzView);

        a11yView = smallText(Color.rgb(160, 160, 172));
        String savedA11y = prefs.getString("a11yServices", "");
        a11yView.setText("已保存的无障碍组件：" + (savedA11y == null || savedA11y.isEmpty() ? "（无）" : savedA11y));
        root.addView(a11yView);

        cmdView = smallText(Color.rgb(120, 220, 160));
        cmdView.setText("命令输出：（空）");
        root.addView(cmdView);

        Button btnShzAuth = mkButton("① 授权 Shizuku");
        btnShzAuth.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!ShizukuShell.binderAlive()) {
                    shzView.setText("Shizuku 未运行：请先按之前的步骤启动 Shizuku");
                    return;
                }
                if (ShizukuShell.hasPermission()) {
                    ShizukuShell.bind();
                    shzView.setText("已授权，正在绑定 UserService…");
                } else {
                    ShizukuShell.requestPermission(++shzReqCounter);
                }
            }
        });
        root.addView(btnShzAuth);

        Button btnShzTest = mkButton("🧪 测试 shell（id）");
        btnShzTest.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                runCmd("/system/bin/id", "id");
            }
        });
        root.addView(btnShzTest);

        Button btnCaptureA11y = mkButton("📥 保存当前无障碍组件");
        btnCaptureA11y.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                captureA11y();
            }
        });
        root.addView(btnCaptureA11y);

        Button btnRestoreA11y = mkButton("🩹 恢复无障碍");
        btnRestoreA11y.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                restoreA11y("手动");
            }
        });
        root.addView(btnRestoreA11y);

        final Button btnAuto = mkButton("");
        final Runnable[] renderAuto = new Runnable[1];
        renderAuto[0] = new Runnable() {
            public void run() {
                btnAuto.setText("自动恢复无障碍：" + (prefs.getBoolean("autoRestoreA11y", true) ? "开" : "关"));
            }
        };
        renderAuto[0].run();
        btnAuto.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().putBoolean("autoRestoreA11y", !prefs.getBoolean("autoRestoreA11y", true)).apply();
                renderAuto[0].run();
            }
        });
        root.addView(btnAuto);

        // 定稿：状态栏强制隐藏（policy_control immersive.status=*），无需其它开关
        TextView ovTitle = new TextView(this);
        ovTitle.setText("\n状态栏：已强制隐藏（Shizuku 写 policy_control immersive.status=*）");
        ovTitle.setTextColor(Color.rgb(140, 200, 255));
        ovTitle.setTextSize(14);
        root.addView(ovTitle);

        TextView hudTip = smallText(Color.rgb(150, 150, 165));
        hudTip.setText("滚动条：左侧时间/电量，右侧滚动操作文本；用上面的上移/下移微调位置。");
        root.addView(hudTip);

        final TextView bgTip = smallText(Color.rgb(150, 150, 165));
        final Runnable renderBg = new Runnable() {
            public void run() {
                int a = prefs.getInt("bgAlpha", 0);
                bgTip.setText("当前背景：" + (a == 0 ? "透明" : (a == 0xFF ? "深色" : "半透明")));
            }
        };
        renderBg.run();
        root.addView(bgTip);

        final TextView xTip = smallText(Color.rgb(150, 150, 165));
        final Runnable renderX = new Runnable() {
            public void run() {
                xTip.setText("水平偏移：" + prefs.getInt("xOffsetExtra", 0) + "px（避开刘海/挖孔）");
            }
        };
        renderX.run();
        root.addView(xTip);

        Button xLeft = mkButton("⬅ 文字左移 10px");
        xLeft.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int x = Math.max(0, prefs.getInt("xOffsetExtra", 0) - 10);
                prefs.edit().putInt("xOffsetExtra", x).apply();
                renderX.run();
            }
        });
        root.addView(xLeft);

        Button xRight = mkButton("➡ 文字右移 10px");
        xRight.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int x = prefs.getInt("xOffsetExtra", 0) + 10;
                prefs.edit().putInt("xOffsetExtra", x).apply();
                renderX.run();
            }
        });
        root.addView(xRight);

        Button xReset = mkButton("↺ 重置水平偏移");
        xReset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().putInt("xOffsetExtra", 0).apply();
                renderX.run();
            }
        });
        root.addView(xReset);

        Button bgTransparent = mkButton("背景：透明（内容透出来）");
        bgTransparent.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().putInt("bgAlpha", 0).apply();
                renderBg.run();
            }
        });
        root.addView(bgTransparent);

        Button bgHalf = mkButton("背景：半透明");
        bgHalf.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().putInt("bgAlpha", 0x66).apply();
                renderBg.run();
            }
        });
        root.addView(bgHalf);

        Button bgDark = mkButton("背景：深色（不透明）");
        bgDark.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().putInt("bgAlpha", 0xFF).apply();
                renderBg.run();
            }
        });
        root.addView(bgDark);

        Button btnDsh = mkButton("打开 DSHBox");
        btnDsh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = getPackageManager().getLaunchIntentForPackage("com.dshbox.app");
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } else {
                    statusView.setText("找不到 DSHBox（com.dshbox.app）");
                }
            }
        });
        root.addView(btnDsh);

        TextView label = new TextView(this);
        label.setText("\n状态服务当前内容：");
        label.setTextColor(Color.rgb(160, 160, 172));
        label.setTextSize(13);
        root.addView(label);

        statusView = new TextView(this);
        statusView.setText("读取中…");
        statusView.setTextColor(Color.rgb(120, 200, 255));
        statusView.setTextSize(13);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        // Shizuku 初始化 + 权限回调
        ShizukuShell.init(this);
        try {
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        ShizukuShell.bind();
                        shzView.setText("Shizuku：已授权，绑定中…");
                        maybeAutoCapture();
                    } else {
                        shzView.setText("Shizuku：授权被拒绝");
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        // 打开 App 即自动启动状态条
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(new Intent(this, HudService.class));
        }

        handler.postDelayed(new Runnable() {
            public void run() {
                if (ShizukuShell.hasPermission()) {
                    maybeAutoCapture();
                    if (prefs.getBoolean("autoRestoreA11y", true)) {
                        restoreA11y("启动时");
                    }
                }
            }
        }, 2500);
    }

    private void maybeAutoCapture() {
        if (prefs.getString("a11yServices", "").length() > 0) return;
        ShizukuShell.runAsync("/system/bin/settings get secure enabled_accessibility_services", new ShizukuShell.Callback() {
            public void onResult(final String result) {
                final String v = clean(result);
                if (v != null && !v.isEmpty() && !"null".equals(v)) {
                    prefs.edit().putString("a11yServices", v).apply();
                    handler.post(new Runnable() {
                        public void run() {
                            a11yView.setText("已保存的无障碍组件：" + v);
                        }
                    });
                }
            }
        });
    }

    private void captureA11y() {
        ShizukuShell.runAsync("/system/bin/settings get secure enabled_accessibility_services",
                new ShizukuShell.Callback() {
                    public void onResult(final String result) {
                        final String v = clean(result);
                        if (v == null || v.isEmpty() || "null".equals(v)) {
                            handler.post(new Runnable() {
                                public void run() {
                                    a11yView.setText("读取失败或无障碍为空：" + result);
                                }
                            });
                            return;
                        }
                        prefs.edit().putString("a11yServices", v).apply();
                        handler.post(new Runnable() {
                            public void run() {
                                a11yView.setText("已保存的无障碍组件：" + v);
                            }
                        });
                    }
                });
    }

    private void restoreA11y(String from) {
        final String saved = prefs.getString("a11yServices", "");
        if (saved == null || saved.isEmpty()) {
            a11yView.setText("没有保存过无障碍组件，无法恢复（先点「保存当前无障碍组件」）");
            return;
        }
        final String cmd = "/system/bin/settings put secure enabled_accessibility_services '" + saved + "' ; "
                + "/system/bin/settings put secure accessibility_enabled 1 ; "
                + "/system/bin/settings get secure enabled_accessibility_services";
        ShizukuShell.runAsync(cmd, new ShizukuShell.Callback() {
            public void onResult(final String result) {
                handler.post(new Runnable() {
                    public void run() {
                        String v = clean(result);
                        boolean ok = v != null && v.contains(saved);
                        a11yView.setText(("恢复无障碍(" + from + ")：" + (ok ? "✅ 成功" : "❌ 失败")) + "\n" + v);
                        cmdView.setText("[恢复无障碍] " + (result == null ? "Shizuku 未就绪" : result.trim()));
                    }
                });
            }
        });
    }

    private void runCmd(final String cmd, final String label) {
        ShizukuShell.runAsync(cmd, new ShizukuShell.Callback() {
            public void onResult(final String result) {
                handler.post(new Runnable() {
                    public void run() {
                        cmdView.setText("[" + label + "]\n" + (result == null ? "Shizuku 未就绪" : result.trim()));
                    }
                });
            }
        });
    }

    private static String clean(String raw) {
        if (raw == null) return null;
        return raw.replaceAll("(?m)^\\[exit -?\\d+\\]$", "").trim();
    }

    private Button mkButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private TextView smallText(int color) {
        TextView t = new TextView(this);
        t.setTextSize(13);
        t.setTextColor(color);
        t.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private void refreshPerm() {
        boolean ok = Settings.canDrawOverlays(this);
        permView.setText(ok ? "悬浮窗权限：✅ 已授权" : "悬浮窗权限：❌ 未授权（先点①）");
        if (shzView != null) {
            String binder = ShizukuShell.binderAlive() ? "运行中" : "未运行";
            String perm = ShizukuShell.hasPermission() ? "已授权" : "未授权";
            String svc = ShizukuShell.ready() ? "UserService 就绪（shell 可用）" : "UserService 未就绪";
            shzView.setText("Shizuku：" + binder + " / " + perm + " / " + svc);
        }
    }

    private void refreshStatus() {
        refreshPerm();
        new Thread(new Runnable() {
            public void run() {
                String out;
                try {
                    out = httpGet("http://127.0.0.1:8790/status");
                } catch (Exception e) {
                    out = null;
                }
                final String text = (out == null)
                        ? "❌ 连不上状态服务（DSH 插件未启动？）"
                        : out;
                handler.post(new Runnable() {
                    public void run() {
                        statusView.setText(text);
                    }
                });
            }
        }).start();
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(800);
        c.setReadTimeout(800);
        c.setRequestMethod("GET");
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        c.disconnect();
        return sb.toString();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPerm();
        handler.postDelayed(refresher, 300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }
}
