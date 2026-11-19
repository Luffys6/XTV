#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模拟器专用：把电脑的局域网地址转发到模拟器里的手机管理面板。

背景：模拟器里的 App 自报的 IP 是它内部虚拟网络地址（如 10.0.2.15），
手机连同一个 WiFi 也访问不到；而 adb forward 只把端口开在电脑 127.0.0.1 上。
本脚本在电脑 0.0.0.0:8100 上开一个 TCP 转发，指向 127.0.0.1:8099（adb forward 的端口），
手机浏览器访问 http://<电脑局域网IP>:8100 即可打开面板。

用法（需先执行 adb forward tcp:8099 tcp:8099）：
    python tools/panel_proxy.py            # 监听 8100，转发到 8099
    python tools/panel_proxy.py 8100 8099  # 自定义端口

启动后它会自动列出电脑**所有**局域网 IP，并实时打印每条连接来自哪个 IP——
手机打不开时看日志：
    · 一条连接都没有 → 手机和电脑不在同一网络 / 防火墙拦截 / 双网卡选路问题
    · 有连接但报 502  → 电脑这侧的 adb forward 或电视端服务没起来

⚠ 双网卡同网段（电脑既有网线又有 WiFi，且都是 192.168.1.x）时会“请求进得来、回复出不去”：
   Windows 按最长前缀选路，回给手机的包会从网线发出，手机收不到 → 一直转圈。
   解决办法（任选）：① 手机改用“以太网那个 IP”访问；② 临时禁用/拔掉网线；③ 手机连到另一个网段。

真电视不需要这个脚本：电视的 IP 就是真实局域网地址，扫码直连即可。
"""
import socket
import sys
import threading
import time

LISTEN_PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8100
TARGET_PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8099
TARGET_HOST = "127.0.0.1"


def local_ips():
    """尽量列出本机所有 IPv4 局域网地址（含 WiFi 与有线）"""
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    try:  # 默认出口地址
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127.") and not ip.startswith("169.254."))


def pump(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                s.close()
            except OSError:
                pass


def fail_page(client, msg):
    """上游不通时给浏览器一个能看懂的页面，而不是空白/连接被重置"""
    body = ("<!doctype html><meta charset='utf-8'>"
            "<body style='font:16px/1.7 -apple-system,sans-serif;background:#0f1520;color:#e8eef7;padding:40px'>"
            "<h2 style='color:#ef5350'>面板暂时连不上电视</h2>"
            "<p>%s</p><p style='color:#9aa6b8'>请确认：<br>"
            "1) 电脑上执行过 <code>adb forward tcp:%d tcp:%d</code><br>"
            "2) 电视/模拟器里的 App 正在运行<br>"
            "3) 在电脑上访问 <code>http://127.0.0.1:%d</code> 能看到面板</p></body>"
            % (msg, TARGET_PORT, TARGET_PORT, TARGET_PORT))
    data = body.encode("utf-8")
    head = ("HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/html; charset=utf-8\r\n"
            "Content-Length: %d\r\nConnection: close\r\n\r\n" % len(data))
    try:
        client.sendall(head.encode("ascii") + data)
    except OSError:
        pass
    finally:
        try:
            client.close()
        except OSError:
            pass


def handle(client, addr):
    try:
        upstream = socket.create_connection((TARGET_HOST, TARGET_PORT), timeout=10)
        upstream.settimeout(None)
    except OSError as e:
        print("[%s] %s -> 502（连不上上游 %s:%d：%s）"
              % (time.strftime("%H:%M:%S"), addr[0], TARGET_HOST, TARGET_PORT, e))
        fail_page(client, "上游 127.0.0.1:%d 不可用（%s）——多半是 adb forward 掉了，或电视端服务没在运行。" % (TARGET_PORT, e))
        return
    client.settimeout(None)
    threading.Thread(target=pump, args=(client, upstream), daemon=True).start()
    threading.Thread(target=pump, args=(upstream, client), daemon=True).start()


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", LISTEN_PORT))
    srv.listen(64)

    print("=" * 58)
    print("转发已启动：0.0.0.0:%d -> %s:%d" % (LISTEN_PORT, TARGET_HOST, TARGET_PORT))
    ips = local_ips()
    if ips:
        print("手机可以试这些地址（哪个能开用哪个）：")
        for ip in ips:
            print("    http://%s:%d" % (ip, LISTEN_PORT))
    else:
        print("没拿到本机 IP，请手动用 ipconfig / ifconfig 查看")
    print("-" * 58)
    print("双网卡（网线+WiFi 同网段）时优先试“有线网卡”的 IP，或临时拔掉网线")
    print("按 Ctrl+C 结束")
    print("=" * 58)

    while True:
        client, addr = srv.accept()
        print("[%s] 连接来自 %s:%d" % (time.strftime("%H:%M:%S"), addr[0], addr[1]))
        threading.Thread(target=handle, args=(client, addr), daemon=True).start()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n已停止")
