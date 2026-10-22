package com.dshstatus.hud;

interface IUserService {
    void destroy() = 16777114; // Shizuku 服务端保留的销毁事务码
    void exit() = 1;
    String runCommand(String cmd) = 2;
}
