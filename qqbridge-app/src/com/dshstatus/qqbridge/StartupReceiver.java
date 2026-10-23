package com.dshstatus.qqbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机/更新后自动拉起常驻服务。 */
public class StartupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            context.startForegroundService(new Intent(context, KeepAliveService.class));
        } catch (Throwable ignored) {
        }
    }
}
