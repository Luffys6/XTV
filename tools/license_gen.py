#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
学习小卫士 · 月卡激活码生成工具（离线，不联网）

用法：
    # 月卡：30 天
    python tools/license_gen.py TVABC234 --months 1

    # 自定义天数（最少 30 天）
    python tools/license_gen.py TVABC234 --days 90

    # 永久授权（自己家用）
    python tools/license_gen.py TVABC234 --permanent

    # 直接指定到期日
    python tools/license_gen.py TVABC234 --expire 2027-03-01

    # 批量：一次给多台设备发码
    python tools/license_gen.py TVABC234 TVDEF567 --months 1

    # 交互式（不带参数直接运行）
    python tools/license_gen.py

算法必须与 App 里的 License.java 完全一致：
    激活码 = 4 位到期日序号 + 8 位 HMAC 签名
    到期日序号 = （东八区日期 - 2020-01-01）的天数，9999 = 永久
    签名 = HMAC-SHA256(设备码|序号, 密钥) 前 8 字节取十进制后 8 位
密钥读取顺序：环境变量 LICENSE_SECRET → 项目根目录 secrets.properties → gradle.properties（兼容旧布局）。
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import hmac
import os
import sys

BASE_DAY = 18262          # 2020-01-01 的天序号（1970 起算）
PERMANENT = 9999
MIN_DAYS = 30             # 最少一个月
TZ = datetime.timezone(datetime.timedelta(hours=8))   # 固定东八区，与 App 一致


def load_secret() -> str:
    """密钥优先级：环境变量 LICENSE_SECRET > secrets.properties > gradle.properties（兼容旧布局）"""
    env = os.environ.get("LICENSE_SECRET", "").strip()
    if env:
        return env
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    for name, key in (("secrets.properties", "LICENSE_SECRET"),
                      ("gradle.properties", "LICENSE_SECRET")):
        path = os.path.join(root, name)
        if not os.path.exists(path):
            continue
        secret = ""
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith(key + "="):
                    secret = line.split("=", 1)[1].strip()
        if secret:
            return secret
    sys.exit(
        "找不到 LICENSE_SECRET。请在项目根目录新建 secrets.properties（不入库），写入：\n"
        "    LICENSE_SECRET=你的32位随机字符串\n"
        "或者设置环境变量 LICENSE_SECRET。")


def today_index() -> int:
    """当天序号：与 App 的 License.today() 一致（毫秒 + 8 小时）/ 一天"""
    now = datetime.datetime.now(TZ)
    return (int(now.timestamp()) + 8 * 3600) * 1000 // 86400000


def date_to_index(d: datetime.date) -> int:
    return (d - datetime.date(1970, 1, 1)).days


def index_to_date(i: int) -> datetime.date:
    return datetime.date(1970, 1, 1) + datetime.timedelta(days=i)


def sign(secret: str, device: str, expire: int) -> str:
    msg = ("%s|%d" % (device, expire)).encode("utf-8")
    digest = hmac.new(secret.encode("utf-8"), msg, hashlib.sha256).digest()
    v = int.from_bytes(digest[:8], "big") & 0x7FFFFFFFFFFFFFFF
    return "%08d" % (v % 100000000)


def make_code(secret: str, device: str, expire: int) -> str:
    return "%04d%s" % (expire, sign(secret, device, expire))


def normalize_device(raw: str) -> str:
    d = "".join(ch for ch in raw.upper() if ch.isalnum())
    if len(d) != 8:
        sys.exit("设备码应为 8 位字母数字，收到：%s" % raw)
    return d


def main() -> None:
    ap = argparse.ArgumentParser(description="学习小卫士月卡激活码生成器")
    ap.add_argument("devices", nargs="*", help="设备码，可多个（如 TVABC234）")
    ap.add_argument("--months", type=int, default=None, help="月数（1 个月 = 30 天）")
    ap.add_argument("--days", type=int, default=None, help="天数（最少 30）")
    ap.add_argument("--expire", type=str, default=None, help="指定到期日，如 2027-03-01")
    ap.add_argument("--permanent", action="store_true", help="永久授权（自己家用）")
    args = ap.parse_args()

    secret = load_secret()

    devices = args.devices
    if not devices:
        raw = input("请输入设备码（电视上显示 8 位，可多个用空格隔开）：").strip()
        devices = raw.split()
    if not devices:
        sys.exit("没有设备码")

    # 计算到期日序号
    if args.permanent:
        expire = PERMANENT
    elif args.expire:
        try:
            d = datetime.datetime.strptime(args.expire, "%Y-%m-%d").date()
        except ValueError:
            sys.exit("日期格式应为 2027-03-01")
        expire = date_to_index(d) - BASE_DAY
        if expire < today_index() - BASE_DAY:
            sys.exit("到期日已过：%s" % args.expire)
    else:
        days = None
        if args.months is not None:
            days = args.months * 30
        elif args.days is not None:
            days = args.days
        else:
            raw = input("时长：输入月数（默认 1，即 30 天）：").strip() or "1"
            try:
                days = int(float(raw) * 30)
            except ValueError:
                sys.exit("请输入数字")
        if days < MIN_DAYS:
            sys.exit("最少 %d 天（一个月），当前 %d 天" % (MIN_DAYS, days))
        expire = today_index() - BASE_DAY + days

    if expire > 9998 and not args.permanent:
        sys.exit("到期日超出可编码范围（约 27 年）")

    # 输出
    print("=" * 46)
    if expire == PERMANENT:
        print("期限：永久授权")
    else:
        print("期限：%s 到期（%d 天）" % (index_to_date(expire + BASE_DAY), expire - (today_index() - BASE_DAY)))
    print("-" * 46)
    for dev in devices:
        dev = normalize_device(dev)
        code = make_code(secret, dev, expire)
        print("设备码：%s" % dev)
        print("激活码：%s %s %s" % (code[:4], code[4:8], code[8:12]))
        print("-" * 46)
    print("把激活码发给用户，在电视「🔑 使用期限 / 续期」里输入即可。")


if __name__ == "__main__":
    main()
