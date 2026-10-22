package com.dshstatus.hud;

import android.app.Notification;
import android.os.BatteryManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 前台服务：轮询状态服务，在状态栏那一行画单向环形滚动条。
 * 空闲时固定显示「暂时没有新的任务」。
 */
public class HudService extends Service {
    private static final String CHANNEL_ID = "dsh_status_hud";
    private static final int NOTIF_ID = 42;
    private static final String ENDPOINT = "http://127.0.0.1:8790/status";
    private static final String IDLE_TEXT = "暂时没有新的任务";

    private WindowManager wm;
    private LinearLayout bar;
    private TextView statusTv;
    private RingTextView scrollTv;
    private WindowManager.LayoutParams barLp;
    private int extraOffset = 0;
    private int xOffset = 0;
    private int realStatusBarH = 0;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = true;

    private String curState = "";
    private String curLine = "";

    @Override
    public void onCreate() {
        super.onCreate();
        HudApp.reportSync("service-onCreate");
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        createChannel();
        startForeground(NOTIF_ID, buildNotification("DSH 状态条运行中", "正在监听 DSH 状态服务"));
        // 定稿：强制隐藏状态栏 + 单行条（左时间电量 / 右滚动）；迁移时清掉历史偏移与 overscan
        if (getSharedPreferences("hud", 0).getInt("layoutVersion", 0) < 7) {
            getSharedPreferences("hud", 0).edit()
                    .putInt("layoutVersion", 7)
                    .putInt("yOffsetExtra", 0)
                    .putInt("overscanPx", 0)
                    .putInt("bgAlpha", 0)
                    .apply();
        }
        realStatusBarH = getSharedPreferences("hud", 0).getInt("realSbH", 0);
        addBar();
        render("idle", "");
        try {
            ShizukuShell.init(this);
        } catch (Throwable ignored) {
        }
        // 等 Shizuku UserService 就绪后强制隐藏系统状态栏（重试若干次）
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 10; i++) {
                    enforceState();
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
        }).start();
        new Thread(new Runnable() {
            public void run() {
                pollLoop();
            }
        }).start();
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return (int) (26 * getResources().getDisplayMetrics().density);
    }

    private void addBar() {
        if (bar != null) return;
        int sPx = statusBarHeight();

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF17171D);

        statusTv = new TextView(this);
        statusTv.setTextSize(13);
        statusTv.setIncludeFontPadding(false);
        statusTv.setGravity(Gravity.CENTER_VERTICAL);
        statusTv.setSingleLine(true);
        statusTv.setPadding((int) (10 * getResources().getDisplayMetrics().density), 0,
                (int) (8 * getResources().getDisplayMetrics().density), 0);
        bar.addView(statusTv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        scrollTv = new RingTextView(this);
        bar.addView(scrollTv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        extraOffset = getSharedPreferences("hud", 0).getInt("yOffsetExtra", 0);
        int realH = realStatusBarH > 0 ? realStatusBarH : sPx;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                barStripHeight(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.y = extraOffset;
        this.barLp = lp;

        bar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = getPackageManager().getLaunchIntentForPackage("com.dshbox.app");
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
            }
        });

        try {
            wm.addView(bar, lp);
        } catch (Exception e) {
            bar = null;
        }
    }

    /** 条位置/高度：高 = 状态栏高 + 2，顶部 y = 0 + 用户上下微调。 */
    private void applyLayout() {
        extraOffset = getSharedPreferences("hud", 0).getInt("yOffsetExtra", 0);
        xOffset = getSharedPreferences("hud", 0).getInt("xOffsetExtra", 0);
        if (bar == null || barLp == null) {
            addBar();
            return;
        }
        if (scrollTv != null) scrollTv.setPadding(xOffset, 0, 0, 0);
        int real = realStatusBarH > 0 ? realStatusBarH : statusBarHeight();
        barLp.height = barStripHeight();
        barLp.y = extraOffset;
        try {
            wm.updateViewLayout(bar, barLp);
        } catch (Exception e) {
            try {
                wm.removeView(bar);
            } catch (Exception ignored) {
            }
            bar = null;
            addBar();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        main.post(new Runnable() {
            public void run() {
                applyLayout();
            }
        });
    }

    /** 条（顶部让位带）总高度：真实状态栏高 + 2。 */
    private int barStripHeight() {
        int s = realStatusBarH > 0 ? realStatusBarH : statusBarHeight();
        return s + 2;
    }

    /** 读系统状态栏窗口真实高度（dumpsys window），用于精确对齐；读不到返回 -1。 */
    private int measureStatusBarHeight() {
        try {
            if (!ShizukuShell.binderAlive() || !ShizukuShell.hasPermission()) return -1;
            String out = ShizukuShell.run("/system/bin/dumpsys window windows | /system/bin/grep -A8 -i 'StatusBar'");
            if (out == null) return -1;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("mFrame=Rect\\((-?\\d+), *(-?\\d+) - (-?\\d+), *(-?\\d+)\\)")
                    .matcher(out);
            while (m.find()) {
                int top = Integer.parseInt(m.group(2));
                int bottom = Integer.parseInt(m.group(4));
                int h = bottom - top;
                if (top == 0 && h > 40 && h < 400) return h;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String clean(String raw) {
        if (raw == null) return null;
        return raw.replaceAll("(?m)^\\[exit -?\\d+\\]$", "").trim();
    }

    private static int parseOverscanTop(String out) {
        if (out == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("overscan \\((-?\\d+), *(-?\\d+), *(-?\\d+), *(-?\\d+)\\)")
                .matcher(out);
        if (m.find()) return Integer.parseInt(m.group(2));
        return 0; // 没有 overscan 行 = 未开启
    }

    /**
     * 状态栏模式：
     *  0 = 覆盖模式：不 overscan，条盖住系统状态栏那一行（条内自带时间/电量）
     *  1 = 强制隐藏模式：policy_control immersive.status=* 让所有 App 隐藏状态栏，
     *      overscan 顶部让出条高，条填满这条带 —— 任何 App（含 B 站）表现一致
     */
    /** 唯一状态处理：强制隐藏系统状态栏（policy_control），并清理历史残留 overscan。 */
    private void enforceState() {
        try {
            if (!ShizukuShell.binderAlive() || !ShizukuShell.hasPermission()) return;
            int measured = measureStatusBarHeight();
            if (measured > 0 && measured != realStatusBarH) {
                realStatusBarH = measured;
                getSharedPreferences("hud", 0).edit().putInt("realSbH", measured).apply();
                main.post(new Runnable() {
                    public void run() {
                        applyLayout();
                    }
                });
            }
            String cur = ShizukuShell.run("/system/bin/settings get global policy_control");
            if (cur == null || !cur.contains("immersive.status=*")) {
                ShizukuShell.run("/system/bin/settings put global policy_control immersive.status=*");
            }
            // 透明背景模式：不做 overscan 让位，App 内容透过条可见；只清理残留
            int realTop = parseOverscanTop(ShizukuShell.run(
                    "/system/bin/dumpsys display | /system/bin/grep -i 'overscan ('"));
            if (realTop > 0) {
                ShizukuShell.run("/system/bin/wm overscan reset");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 每 ~30 秒检查一次：DSHBox 的无障碍是否掉线，掉了就用 Shizuku 写回去。 */
    private void watchdog() {
        try {
            if (!getSharedPreferences("hud", 0).getBoolean("autoRestoreA11y", true)) return;
            if (!ShizukuShell.binderAlive() || !ShizukuShell.hasPermission()) return;
            String saved = getSharedPreferences("hud", 0).getString("a11yServices", "");
            if (saved == null || saved.isEmpty() || "null".equals(saved)) {
                String cur = clean(ShizukuShell.run("/system/bin/settings get secure enabled_accessibility_services"));
                if (cur != null && !cur.isEmpty() && !"null".equals(cur)) {
                    getSharedPreferences("hud", 0).edit().putString("a11yServices", cur).apply();
                }
                return;
            }
            String cur = clean(ShizukuShell.run("/system/bin/settings get secure enabled_accessibility_services"));
            if (cur == null || !cur.contains(saved.trim())) {
                ShizukuShell.run("/system/bin/settings put secure enabled_accessibility_services '" + saved.trim() + "'");
                ShizukuShell.run("/system/bin/settings put secure accessibility_enabled 1");
            }
        } catch (Throwable ignored) {
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "DSH 状态条",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title, String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private String statusPrefix() {
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        int cap = -1;
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Throwable ignored) {
        }
        return time + (cap >= 0 ? ("  🔋" + cap + "%") : "") + "  ·  ";
    }

    private void render(String st, String text) {
        if (bar == null) return;
        String prefix = statusPrefix();
        String content;
        int bg;
        int fg;
        boolean scroll;
        if ("running".equals(st)) {
            content = "🤖  " + text + "      · v" + versionName() + "      ";
            bg = 0xFF17171D;
            fg = 0xFF9AD0FF;
            scroll = true;
        } else if ("done".equals(st)) {
            content = "✅  " + (text.isEmpty() ? "完成" : text) + "      · v" + versionName() + "      ";
            bg = 0xFF12351F;
            fg = 0xFFA8F0C0;
            scroll = true;
        } else if ("blocked".equals(st)) {
            content = "⏸  " + (text.isEmpty() ? "等待你的确认" : text) + "      · v" + versionName() + "      ";
            bg = 0xFF3A2C12;
            fg = 0xFFF0D08A;
            scroll = true;
        } else {
            content = IDLE_TEXT;
            bg = 0xFF1D1D24;
            fg = 0xFF8A8A96;
            scroll = false;
        }
        String key = st + "|" + content + "|" + prefix;
        if (key.equals(curLine)) return;
        curLine = key;
        curState = st;
        int alpha = getSharedPreferences("hud", 0).getInt("bgAlpha", 0);
        bar.setBackgroundColor((alpha << 24) | (bg & 0x00FFFFFF));
        statusTv.setTextColor(fg);
        statusTv.setText(prefix);
        if (scroll) {
            scrollTv.setContent(content, fg, true, 55f);
        } else {
            scrollTv.setContent(content, fg, false, 0f);
        }
    }

    private void pollLoop() {
        int tick = 0;
        while (running) {
            if (tick % 40 == 0) watchdog();
            if (tick % 20 == 0) enforceState();
            tick++;
            String body = null;
            try {
                body = httpGet(ENDPOINT);
            } catch (Exception ignored) {
            }
            if (body != null) {
                try {
                    final JSONObject o = new JSONObject(body);
                    main.post(new Runnable() {
                        public void run() {
                            String st = o.optString("state", "idle");
                            String text = o.optString("text", "");
                            // 注意：不要把 elapsedSec 拼进滚动文本——内容每变一次，环形滚动就会重置。
                            render(st, text);
                        }
                    });
                } catch (Exception ignored) {
                }
            } else {
                main.post(new Runnable() {
                    public void run() {
                        render("blocked", "状态服务未连接（先在沙盒启动 status-server.js）");
                    }
                });
            }
            final int prefOffset = getSharedPreferences("hud", 0).getInt("yOffsetExtra", 0);
            final int prefX = getSharedPreferences("hud", 0).getInt("xOffsetExtra", 0);
            if (prefOffset != extraOffset || prefX != xOffset) {
                main.post(new Runnable() {
                    public void run() {
                        applyLayout();
                    }
                });
            }
            if (bar == null) {
                main.post(new Runnable() {
                    public void run() {
                        applyLayout();
                    }
                });
            }
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(700);
        c.setReadTimeout(700);
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
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            // 停止状态条 = 恢复系统状态栏
            ShizukuShell.runAsync("/system/bin/settings delete global policy_control", null);
            ShizukuShell.runAsync("/system/bin/wm overscan reset", null);
        } catch (Throwable ignored) {
        }
        if (bar != null) {
            try {
                wm.removeView(bar);
            } catch (Exception ignored) {
            }
            bar = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
