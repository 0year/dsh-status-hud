package com.dshstatus.hud;

import android.app.Application;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;

import rikka.shizuku.ShizukuProvider;

/**
 * 启动/崩溃上报：把 Application.onCreate、Activity/Service 启动和未捕获异常通过 HTTP
 * POST 到沙盒里的状态服务（127.0.0.1:8790/report），供开发期排查“打开即退”这类问题。
 */
public class HudApp extends Application {
    static {
        // 必须在这里关掉 Sui 自动初始化：Android 10 会先安装 ContentProvider 再调用
        // Application.onCreate，放在 onCreate 里已经太晚（Provider.onCreate 里 Sui.init 会崩）。
        try {
            ShizukuProvider.disableAutomaticSuiInitialization();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        reportSync("application-onCreate");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                report("CRASH-" + t.getName() + ": " + e.getClass().getName() + ": " + e.getMessage()
                        + " | " + truncate(sw.toString(), 1800));
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    /** 后台线程发送 + join 等待：既不违反 NetworkOnMainThread，又能保证进程被杀前发出去。 */
    static void reportSync(final String msg) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                reportBlocking(msg);
            }
        });
        t.start();
        try {
            t.join(800);
        } catch (InterruptedException ignored) {
        }
    }

    private static void reportBlocking(String msg) {
        try {
            byte[] body = ("{\"msg\":" + jsonEscape(msg) + "}").getBytes("UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:8790/report").openConnection();
            c.setConnectTimeout(500);
            c.setReadTimeout(500);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            c.getResponseCode();
            c.disconnect();
        } catch (Throwable ignored) {
        }
    }

    static void report(final String msg) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] body = ("{\"msg\":" + jsonEscape(msg) + "}").getBytes("UTF-8");
                    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:8790/report").openConnection();
                    c.setConnectTimeout(800);
                    c.setReadTimeout(800);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    OutputStream os = c.getOutputStream();
                    os.write(body);
                    os.close();
                    c.getResponseCode();
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return "\"" + sb + "\"";
    }
}
