package com.dshstatus.qqbridge;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** 自测用：接住测试通知的快捷回复结果，写日志给沙盒抓取。 */
public class QqReplyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String text = "";
        try {
            Bundle r = RemoteInput.getResultsFromIntent(intent);
            if (r != null) {
                CharSequence cs = r.getCharSequence("qq_reply_text");
                if (cs != null) text = cs.toString();
            }
        } catch (Throwable ignored) {
        }
        Log.i("QqBridge", "self-test reply received: " + text);
        // 回传沙盒（状态服务的 /report），方便远端验证
        try {
            java.net.URL u = new java.net.URL("http://127.0.0.1:8790/report");
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            byte[] body = ("{\"msg\":\"qq-self-test-reply: " + text.replace("\"", "'") + "\"}").getBytes("utf-8");
            c.setFixedLengthStreamingMode(body.length);
            c.getOutputStream().write(body);
            c.getResponseCode();
            c.disconnect();
        } catch (Throwable ignored) {
        }
    }
}
