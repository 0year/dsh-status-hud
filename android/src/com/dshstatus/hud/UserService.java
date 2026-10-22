package com.dshstatus.hud;

import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Shizuku UserService：以 shell（uid 2000）身份运行在我们的独立进程里，
 * 提供执行 shell 命令的能力。类名/包名与 Manifest 无关，由 Shizuku 反射创建。
 */
public class UserService extends IUserService.Stub {

    public UserService() {
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public void exit() {
        destroy();
    }

    @Override
    public String runCommand(String cmd) throws RemoteException {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            int code = p.waitFor();
            sb.append("[exit ").append(code).append(']');
        } catch (Exception e) {
            sb.append("ERROR: ").append(e.getMessage());
        } finally {
            if (p != null) try {
                p.destroy();
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }
}
