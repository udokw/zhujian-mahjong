#!/system/bin/sh
# PC -> 手机方向：接收两连发小帧，逐行打印到达时间戳(ns)与帧长
( echo GO; sleep 25 ) | nc 192.168.5.19 9301 | while IFS= read -r l; do
  echo "$(date +%s%N) ${#l}"
done
