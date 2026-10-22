#!/bin/bash
# 状态条推送助手：./hud.sh run "正在做什么" / ./hud.sh done "完成" / ./hud.sh idle
cd "$(dirname "$0")"
case "$1" in
  run|push) node server/status-server.js push "${2:-工作中}";;
  done)     node server/status-server.js done "${2:-完成}";;
  idle)     node server/status-server.js idle;;
  *) echo "用法: $0 run|done|idle [文本]"; exit 1;;
esac
