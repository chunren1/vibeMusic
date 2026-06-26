#!/usr/bin/env python3
"""vibeMusic 健康检查脚本 — 纯标准库，零依赖。

用法:
    python scripts/health_check.py                    # 默认 localhost
    python scripts/health_check.py --host 192.168.1.1  # 指定主机
"""

import json
import sys
import argparse
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

# Windows GBK 编码兼容：强制 stdout 使用 UTF-8
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# ── 颜色（Windows 10+ 原生支持 ANSI） ──
GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
CYAN = "\033[96m"
BOLD = "\033[1m"
RESET = "\033[0m"


def check_endpoint(name: str, url: str, timeout: int = 5) -> dict:
    """检查单个 HTTP GET 端点，返回状态字典。"""
    try:
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            status = resp.status
            try:
                data = json.loads(body)
            except json.JSONDecodeError:
                data = body[:200]
            return {
                "name": name,
                "url": url,
                "ok": 200 <= status < 300,
                "status": status,
                "data": data,
                "error": None,
            }
    except urllib.error.HTTPError as e:
        return {"name": name, "url": url, "ok": False, "status": e.code, "data": None, "error": str(e)}
    except Exception as e:
        return {"name": name, "url": url, "ok": False, "status": None, "data": None, "error": str(e)}


def main():
    parser = argparse.ArgumentParser(description="vibeMusic 健康检查")
    parser.add_argument("--host", default="localhost", help="目标主机 (默认: localhost)")
    args = parser.parse_args()
    host = args.host

    # ── 端点定义 ──
    endpoints = [
        ("Spring Boot 健康检查", f"http://{host}:8080/actuator/health"),
        ("ES 搜索健康检查", f"http://{host}:8080/api/songs/es-health"),
        ("MusicAPI 网关健康", f"http://{host}:3000/health"),
        ("MusicAPI Cookie 状态", f"http://{host}:3000/cookie-status"),
    ]

    print(f"\n{CYAN}{BOLD}🎵 vibeMusic 健康检查{BOLD}  →  {host}{RESET}\n")
    print(f"{'端点':<24} {'状态':<8} {'详情'}")
    print("-" * 60)

    # 并发检查
    all_ok = True
    with ThreadPoolExecutor(max_workers=4) as executor:
        futures = {executor.submit(check_endpoint, name, url): name for name, url in endpoints}
        for future in as_completed(futures):
            result = future.result()
            icon = f"{GREEN}✅{RESET}" if result["ok"] else f"{RED}❌{RESET}"
            status_str = f"{GREEN}OK({result['status']}){RESET}" if result["ok"] else f"{RED}FAIL{RESET}"
            detail = ""

            if result["ok"] and isinstance(result["data"], dict):
                # 提取关键信息
                if "status" in result["data"]:
                    detail = json.dumps(result["data"], ensure_ascii=False)[:80]
                elif "cookie" in result["url"]:
                    cookie_status = result["data"]
                    detail = str(cookie_status)[:80]
            elif result["error"]:
                detail = f"{RED}{result['error'][:60]}{RESET}"

            print(f"  {icon} {result['name']:<20} {status_str:<6} {detail}")
            if not result["ok"]:
                all_ok = False

    print("-" * 60)
    if all_ok:
        print(f"\n  {GREEN}✅ 全部 {len(endpoints)} 项检查通过{RESET}\n")
    else:
        failed = sum(1 for f in futures if not f.result().get("ok", True))
        print(f"\n  {RED}❌ {failed}/{len(endpoints)} 项检查失败{RESET}\n")
        sys.exit(1)


if __name__ == "__main__":
    main()
