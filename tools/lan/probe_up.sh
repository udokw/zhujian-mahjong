#!/system/bin/sh
# 手机 -> PC 方向：连续两行小包，各 20 组
i=0
while [ $i -lt 40 ]; do
  printf 'A%02d\n' $i
  printf 'B%02d\n' $i
  sleep 0.3
  i=$((i+1))
done | nc 192.168.5.19 9301
