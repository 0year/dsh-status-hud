package com.dshstatus.hud;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import rikka.shizuku.Shizuku;

/** Shizuku 授权 + UserService 绑定 + 命令执行的薄封装（静态单例）。 */
public final class ShizukuShell {
    public interface Callback {
        void onResult(String result);
    }

    private static IUserService userService;
    private static boolean binding;
    private static Context appCtx;

    private ShizukuShell() {
    }

    public static void init(Context ctx) {
        if (appCtx != null) {
            bind();
            return;
        }
        appCtx = ctx.getApplicationContext();
        try {
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                public void onBinderReceived() {
                    userService = null;
                    binding = false;
                    bind();
                }
            });
            Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
                public void onBinderDead() {
                    userService = null;
                    binding = false;
                }
            });
        } catch (Throwable ignored) {
        }
        bind();
    }

    public static boolean binderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission(int code) {
        try {
            Shizuku.requestPermission(code);
        } catch (Throwable ignored) {
        }
    }

    public static void bind() {
        if (appCtx == null) return;
        if (!binderAlive() || !hasPermission()) return;
        if (userService != null || binding) return;
        binding = true;
        try {
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(appCtx.getPackageName(), UserService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("dsh_hud")
                    .debuggable(false)
                    .version(1);
            Shizuku.bindUserService(args, new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    userService = IUserService.Stub.asInterface(binder);
                    binding = false;
                }

                public void onServiceDisconnected(ComponentName name) {
                    userService = null;
                    binding = false;
                }
            });
        } catch (Throwable t) {
            binding = false;
        }
    }

    public static boolean ready() {
        bind();
        return userService != null;
    }

    /** 同步执行；未就绪返回 null。 */
    public static String run(String cmd) {
        if (userService == null) bind();
        if (userService == null) return null;
        try {
            return userService.runCommand(cmd);
        } catch (Throwable t) {
            userService = null;
            return null;
        }
    }

    public static void runAsync(final String cmd, final Callback cb) {
        new Thread(new Runnable() {
            public void run() {
                String r = ShizukuShell.run(cmd);
                if (cb != null) cb.onResult(r);
            }
        }).start();
    }
}
