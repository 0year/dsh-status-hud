#!/bin/bash
# QQ bridge + 状态服务保活：进程挂了自动拉起；每分钟记录一次状态，便于早上查看。
cd "$(dirname "$0")"
LOG=/tmp/qq-supervisor.log
BLOG=/tmp/qq-bridge.log
echo "$(date -Is) supervisor start" >> "$LOG"
while true; do
  # 状态服务（8790）
  if ! curl -s -m 2 http://127.0.0.1:8790/status >/dev/null 2>&1; then
    echo "$(date -Is) restart status-server" >> "$LOG"
    nohup node ../server/status-server.js >> /tmp/hud-server.log 2>&1 &
    sleep 1
  fi
  # QQ bridge
  if ! pgrep -f "bridge.js --daemon" >/dev/null 2>&1; then
    echo "$(date -Is) restart qq-bridge" >> "$LOG"
    QQ_BRIDGE_MODE=relay QQ_BRIDGE_ALLOW="测试发送者,文件传输助手,Myself" nohup node bridge.js --daemon >> /tmp/qq-bridge.out 2>&1 &
    sleep 1
  fi
  # qq-brain（双通道事件驱动大脑）：用 pid 文件判断，避免 pgrep 自匹配
  BRAIN_PID_FILE=/root/projects/dsh优化/qq-brain/data/brain.pid
  BRAIN_ALIVE=1
  if [ -f "$BRAIN_PID_FILE" ]; then
    kill -0 "$(cat "$BRAIN_PID_FILE" 2>/dev/null)" >/dev/null 2>&1 || BRAIN_ALIVE=0
  else
    BRAIN_ALIVE=0
  fi
  if [ "$BRAIN_ALIVE" = "0" ]; then
    echo "$(date -Is) restart qq-brain" >> "$LOG"
    ( cd /root/projects/dsh优化/qq-brain && nohup node src/index.js start >> /tmp/qq-brain.out 2>&1 & )
    sleep 1
  fi
  # 每分钟记录状态
  if [ "$(date +%M)" != "$(cat /tmp/qq-monitor.min 2>/dev/null)" ]; then
    date +%M > /tmp/qq-monitor.min
    S=$(curl -s -m 2 http://127.0.0.1:8790/qq/stats 2>/dev/null | head -c 300)
    echo "$(date -Is) $S" >> /tmp/qq-monitor.log
  fi
  sleep 20
done
