package com.dshstatus.qqbridge;

import android.app.Notification;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * QQ 通知监听 + 快捷回复（RemoteInput）服务。
 *
 * 工作流：
 *   1. 监听 QQ/TIM 的新通知，提取 发送者/内容/会话名，并把通知对象（含回复 Action）缓存起来；
 *   2. 把消息 POST 到沙盒状态服务 /qq/event；
 *   3. 沙盒 qq-bridge 生成回复后 POST /qq/reply；
 *   4. 本服务轮询 /qq/replies，取到回复后通过通知自身的 RemoteInput 直接回给 QQ
 *      —— 不打开 QQ、不占屏，锁屏也能回。
 *
 * 需要用户在系统里开启本 App 的「通知使用权」，或由 Shizuku 写
 * settings put secure enabled_notification_listeners。
 */
public class QqNotificationService extends NotificationListenerService {
    private static final String TAG = "QqNotify";
    private static final String SERVER = "http://127.0.0.1:8790";
    private static final String[] QQ_PKGS = {"com.tencent.mobileqq", "com.tencent.tim"};
    private static final long EXPIRE_MS = 10 * 60 * 1000L;

    /** key -> 通知（用于之后从缓存里取出 RemoteInput 动作） */
    private final ConcurrentHashMap<String, StatusBarNotification> active = new ConcurrentHashMap<>();
    private HandlerThread thread;
    private Handler worker;
    private volatile boolean running = false;
    private long cursor = 0;
    private long uiCursor = 0;

    @Override
    public void onListenerConnected() {
        Log.i(TAG, "listener connected");
        report("qq-listener-connected");
        startPolling();
    }

    private void report(String msg) {
        final String m = msg;
        new Thread(new Runnable() {
            public void run() {
                reportBlocking(m);
            }
        }).start();
    }

    private void reportBlocking(String msg) {
        try {
            URL u = new URL(SERVER + "/report");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            byte[] body = ("{\"msg\":\"" + msg.replace("\"", "'") + "\"}").getBytes("utf-8");
            c.setFixedLengthStreamingMode(body.length);
            c.getOutputStream().write(body);
            c.getResponseCode();
            c.disconnect();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onListenerDisconnected() {
        running = false;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            report("qq-notif-posted:" + pkg);
            boolean qq = false;
            for (String p : QQ_PKGS) {
                if (p.equals(pkg)) {
                    qq = true;
                    break;
                }
            }
            Notification n = sbn.getNotification();
            String title = text(n.extras.getCharSequence(Notification.EXTRA_TITLE));
            // 自测：允许本 App 发出的测试通知进入链路（模拟"别人发消息"）
            if (!qq) {
                if (!getPackageName().equals(pkg) || !"测试发送者".equals(title)) return;
            }
            String body = text(n.extras.getCharSequence(Notification.EXTRA_TEXT));
            if (body == null || body.isEmpty()) {
                body = lastMessagingText(n.extras);
            }
            String conv = text(n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
            int replyIdx = findReplyAction(n);
            String key = sbn.getKey();
            if (replyIdx >= 0) {
                active.put(key, sbn);
                prune();
            }
            JSONObject o = new JSONObject();
            o.put("key", key);
            o.put("pkg", pkg);
            o.put("title", title == null ? "" : title);
            o.put("text", body == null ? "" : body);
            o.put("convTitle", conv == null ? "" : conv);
            o.put("canReply", replyIdx >= 0);
            o.put("time", sbn.getPostTime());
            final JSONObject payload = o;
            new Thread(new Runnable() {
                public void run() {
                    postJson("/qq/event", payload);
                }
            }).start();
            Log.i(TAG, "event: " + title + " / " + body + " canReply=" + (replyIdx >= 0));
        } catch (Throwable t) {
            Log.w(TAG, "onNotificationPosted error", t);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            active.remove(sbn.getKey());
        } catch (Throwable ignored) {
        }
    }

    // ---- 回复轮询 ----
    private void startPolling() {
        if (running) return;
        running = true;
        thread = new HandlerThread("qq-notify-poll");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(new Runnable() {
            public void run() {
                if (!running) return;
                pollReplies();
                pollUiCmds();
                worker.postDelayed(this, 1200);
            }
        });
    }

    private void pollReplies() {
        try {
            JSONObject res = getJson("/qq/replies?since=" + cursor);
            JSONArray arr = res.optJSONArray("replies");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.optJSONObject(i);
                    if (r == null) continue;
                    cursor = Math.max(cursor, r.optLong("id", 0));
                    String key = r.optString("key", "");
                    String txt = r.optString("text", "");
                    boolean ok = false;
                    String err = "";
                    try {
                        ok = sendReply(key, txt);
                        if (!ok) err = "notification-not-found-or-no-remote-input";
                    } catch (Throwable t) {
                        err = String.valueOf(t.getMessage());
                    }
                    JSONObject result = new JSONObject();
                    result.put("id", r.optLong("id", 0));
                    result.put("key", key);
                    result.put("ok", ok);
                    result.put("error", err);
                    postJson("/qq/reply-result", result);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 处理沙盒发来的 UI 指令：用通知自带的 contentIntent 打开对应聊天（绕过 MIUI 后台启动限制）。 */
    private void pollUiCmds() {
        try {
            JSONObject res = getJson("/qq/ui-cmds?since=" + uiCursor);
            JSONArray arr = res.optJSONArray("cmds");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) continue;
                uiCursor = Math.max(uiCursor, c.optLong("id", 0));
                String action = c.optString("action", "");
                String key = c.optString("key", "");
                String err = "";
                boolean ok = false;
                if ("open".equals(action)) {
                    try {
                        ok = openByKeyOrTitle(key, c.optString("title", ""));
                        if (!ok) err = "notification-not-found";
                    } catch (Throwable t) {
                        err = String.valueOf(t.getMessage());
                    }
                } else {
                    err = "unknown-action";
                }
                JSONObject result = new JSONObject();
                result.put("id", c.optLong("id", 0));
                result.put("ok", ok);
                result.put("error", err);
                result.put("action", action);
                postJson("/qq/ui-result", result);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 用通知的 contentIntent 打开 QQ 对应聊天页（缓存优先，其次从当前通知列表里按标题找）。 */
    private boolean openByKeyOrTitle(String key, String title) throws Exception {
        StatusBarNotification sbn = active.get(key);
        if (sbn == null && title != null && !title.isEmpty()) {
            try {
                StatusBarNotification[] all = getActiveNotifications();
                if (all != null) {
                    for (StatusBarNotification x : all) {
                        if (!"com.tencent.mobileqq".equals(x.getPackageName())) continue;
                        String t = String.valueOf(x.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE));
                        if (t != null && t.contains(title)) {
                            sbn = x;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (sbn == null) return false;
        android.app.PendingIntent pi = sbn.getNotification().contentIntent;
        if (pi == null) return false;
        pi.send(this, 0, new Intent());
        return true;
    }

    /** 用通知自带的 RemoteInput 直接回复。 */
    private boolean sendReply(String key, String text) throws Exception {
        StatusBarNotification sbn = active.get(key);
        if (sbn == null) return false;
        Notification n = sbn.getNotification();
        int idx = findReplyAction(n);
        if (idx < 0) return false;
        Notification.Action action = n.actions[idx];
        RemoteInput[] inputs = action.getRemoteInputs();
        if (inputs == null || inputs.length == 0) return false;
        Bundle results = new Bundle();
        for (RemoteInput ri : inputs) {
            results.putCharSequence(ri.getResultKey(), text);
        }
        Intent intent = new Intent();
        RemoteInput.addResultsToIntent(inputs, intent, results);
        action.actionIntent.send(this, 0, intent);
        active.remove(key);
        return true;
    }

    private static int findReplyAction(Notification n) {
        if (n == null || n.actions == null) return -1;
        for (int i = 0; i < n.actions.length; i++) {
            Notification.Action a = n.actions[i];
            if (a == null) continue;
            RemoteInput[] inputs = a.getRemoteInputs();
            if (inputs != null && inputs.length > 0) return i;
        }
        return -1;
    }

    private static String lastMessagingText(Bundle extras) {
        try {
            Object o = extras.get(Notification.EXTRA_MESSAGES);
            if (o instanceof ArrayList) {
                ArrayList list = (ArrayList) o;
                if (!list.isEmpty()) {
                    Object last = list.get(list.size() - 1);
                    if (last instanceof Bundle) {
                        CharSequence cs = ((Bundle) last).getCharSequence("text");
                        return text(cs);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String text(CharSequence cs) {
        return cs == null ? null : cs.toString().trim();
    }

    private void prune() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, StatusBarNotification>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, StatusBarNotification> e = it.next();
            if (now - e.getValue().getPostTime() > EXPIRE_MS) it.remove();
        }
    }

    // ---- 与沙盒状态服务的 HTTP ----
    private void postJson(String path, JSONObject obj) {
        try {
            URL u = new URL(SERVER + path);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(3000);
            c.setRequestMethod("POST");
            c.setRequestProperty("content-type", "application/json");
            c.setDoOutput(true);
            byte[] body = obj.toString().getBytes("utf-8");
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            c.getResponseCode();
            c.disconnect();
        } catch (Throwable ignored) {
        }
    }

    private JSONObject getJson(String path) throws Exception {
        URL u = new URL(SERVER + path);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(3000);
        c.setRequestMethod("GET");
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "utf-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();
        return new JSONObject(sb.toString());
    }
}
