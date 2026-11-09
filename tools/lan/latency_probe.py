"""局域网小包延迟探针：复现「事件帧 + 状态帧」背靠背两次小写入的 Nagle / 延迟 ACK 停顿。

用法：
  python latency_probe.py down nodelay    # PC 作为发送端，连上来的对端逐帧打时间戳
  python latency_probe.py down nagle      # 同上，但发送端不开 TCP_NODELAY（复现旧行为）
  python latency_probe.py up   nodelay    # 对端连续发两行小包，PC 记录到达间隔
  python latency_probe.py up   nagle

帧长故意贴近真实协议：event ≈ 90B，state ≈ 1.2KB。
"""

import socket
import sys
import time

MODE = sys.argv[1] if len(sys.argv) > 1 else "down"
NODELAY = (sys.argv[2] if len(sys.argv) > 2 else "nodelay") == "nodelay"
BURSTS = int(sys.argv[3]) if len(sys.argv) > 3 else 20
PORT = 9301

EVENT = b"EV" + b"e" * 88 + b"\n"
STATE = b"ST" + b"s" * 1200 + b"\n"


def main() -> None:
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(1)
    print(f"listening {PORT} mode={MODE} nodelay={NODELAY}", flush=True)
    conn, addr = srv.accept()
    print(f"peer {addr}", flush=True)
    if NODELAY:
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    conn.settimeout(15)

    if MODE == "down":
        f = conn.makefile("rb")
        f.readline()  # 等对端发 GO
        for i in range(BURSTS):
            conn.sendall(EVENT)
            conn.sendall(STATE)
            time.sleep(0.3)
        time.sleep(0.3)
    else:
        buf = b""
        stamps = []
        while True:
            try:
                data = conn.recv(4096)
            except socket.timeout:
                break
            if not data:
                break
            now = time.perf_counter()
            buf += data
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                stamps.append((now, line[:1].decode("latin1")))
        # 相邻两行（同一次突发）的到达间隔
        gaps = []
        for i in range(1, len(stamps)):
            if stamps[i][1] == "B" and stamps[i - 1][1] == "A":
                gaps.append((stamps[i][0] - stamps[i - 1][0]) * 1000)
        print(f"lines={len(stamps)} pairs={len(gaps)}", flush=True)
        if gaps:
            gaps.sort()
            print(
                "gap ms: "
                f"min={gaps[0]:.2f} p50={gaps[len(gaps) // 2]:.2f} "
                f"p95={gaps[int(len(gaps) * 0.95) - 1]:.2f} max={gaps[-1]:.2f}",
                flush=True,
            )
    conn.close()
    srv.close()


if __name__ == "__main__":
    main()
