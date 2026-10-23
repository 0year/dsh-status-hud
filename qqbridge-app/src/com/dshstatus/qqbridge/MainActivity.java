package com.dshstatus.qqbridge;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
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

/** QQ 桥：通知监听 + 快捷回复的配置/自测页。 */
public class MainActivity extends Activity {
    private static final String SERVER = "http://127.0.0.1:8790";
    private TextView accessView;
    private TextView statsView;
    private TextView logView;
    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { startForegroundService(new Intent(this, KeepAliveService.class)); } catch (Throwable ignored) {}
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (18 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(22, 22, 28));

        TextView title = new TextView(this);
        title.setText("QQ 桥 v1.0");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        root.addView(title);

        TextView desc = text("把 QQ 新消息通知中转到 DSH（沙盒 bridge），生成回复后用通知快捷回复直接发回 QQ。", 13, Color.rgb(150, 150, 165));
        root.addView(desc);

        accessView = text("", 14, Color.rgb(140, 200, 255));
        root.addView(accessView);

        Button openSettings = button("打开「通知使用权」设置");
        openSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                } catch (Exception ignored) {
                }
            }
        });
        root.addView(openSettings);

        Button test = button("发送测试通知（带快捷回复）");
        test.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                postTestNotification();
                log("已发测试通知「测试发送者」，等待桥回复…");
            }
        });
        root.addView(test);

        Button refresh = button("刷新桥状态");
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                loadStats();
            }
        });
        root.addView(refresh);

        statsView = text("", 12, Color.rgb(200, 220, 200));
        statsView.setTextIsSelectable(true);
        root.addView(statsView);

        logView = text("", 12, Color.rgb(150, 150, 165));
        root.addView(logView);

        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        setContentView(sc);

        handler.post(new Runnable() {
            public void run() {
                refreshAccess();
                loadStats();
                handler.postDelayed(this, 3000);
            }
        });
    }

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    private void log(String s) {
        logView.setText(s);
    }

    private void refreshAccess() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            boolean ok = enabled != null && enabled.contains(QqNotificationService.class.getName());
            accessView.setText("通知使用权：" + (ok ? "✅ 已开启" : "❌ 未开启（需要开启才能监听 QQ 通知）"));
        } catch (Throwable t) {
            accessView.setText("通知使用权：未知 " + t);
        }
    }

    private void loadStats() {
        new Thread(new Runnable() {
            public void run() {
                String out;
                try {
                    URL u = new URL(SERVER + "/qq/stats");
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(2000);
                    c.setReadTimeout(3000);
                    BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "utf-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    c.disconnect();
                    out = sb.toString();
                } catch (Exception e) {
                    out = "连不上状态服务（沙盒 bridge 没跑？）: " + e.getMessage();
                }
                final String text = out;
                handler.post(new Runnable() {
                    public void run() {
                        statsView.setText(text);
                    }
                });
            }
        }).start();
    }

    private void postTestNotification() {
        try {
            String ch = "qq-bridge-test";
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel c = new NotificationChannel(ch, "QQ 桥测试", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(c);
            }
            Intent reply = new Intent(this, QqReplyReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(this, 1, reply, PendingIntent.FLAG_UPDATE_CURRENT);
            RemoteInput ri = new RemoteInput.Builder("qq_reply_text").setLabel("回复").build();
            Notification.Action act = new Notification.Action.Builder(null, "回复", pi).addRemoteInput(ri).build();
            Notification.Builder b = new Notification.Builder(this, ch)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("测试发送者")
                    .setContentText("这是一条测试消息")
                    .setAutoCancel(true)
                    .addAction(act);
            nm.notify(9001, b.build());
        } catch (Throwable t) {
            log("发通知失败: " + t);
        }
    }
}
