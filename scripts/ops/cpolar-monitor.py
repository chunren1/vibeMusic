#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cpolar 内网穿透存活监控脚本
每隔 3~5 分钟检测一次 HTTPS 地址是否可达，
不可达则自动重启 cpolar 并通过 Server酱 发送新地址。
"""

import os
import sys
import time
import random
import subprocess
import datetime
import requests
import logging

# ==================== 配置参数 ====================
CPOLAR_PORT = 5173                     # 映射的本地端口
CHECK_INTERVAL_MIN = 180               # 检测间隔：3 分钟
CHECK_INTERVAL_MAX = 300               # 检测间隔：5 分钟
MAX_FAILURES = 2                       # 连续失败即重启
HEALTH_PATH = "/"                      # 健康检查路径（访问首页即可）
SCKEY = os.environ.get("SCKEY", "")    # Server酱 SendKey

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.dirname(os.path.dirname(SCRIPT_DIR))  # ops/ → scripts/ → root
MUSICAPI_DIR = os.path.join(ROOT_DIR, "musicapi")
LOG_DIR = os.path.join(MUSICAPI_DIR, "logs")
LOG_FILE = os.path.join(LOG_DIR, "cpolar-monitor.log")

os.makedirs(LOG_DIR, exist_ok=True)

# ==================== 日志 ====================
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.FileHandler(LOG_FILE, encoding='utf-8'),
        logging.StreamHandler()
    ]
)

# ==================== Server酱 推送 ====================
SERVERCHAN_URL = f"https://sctapi.ftqq.com/{SCKEY}.send" if SCKEY else ""

def send_wechat(title, content):
    """通过 Server酱 发送微信消息"""
    if not SCKEY:
        logging.warning("SCKEY 未配置，跳过微信通知")
        return False
    try:
        resp = requests.post(SERVERCHAN_URL, data={
            "title": title,
            "desp": content,
        }, timeout=15)
        if resp.status_code == 200 and resp.json().get("code") == 0:
            logging.info(f"微信通知已发送: {title}")
            return True
        else:
            logging.warning(f"微信通知发送失败: {resp.text[:200]}")
            return False
    except Exception as e:
        logging.error(f"微信通知异常: {e}")
        return False


# ==================== 健康检查 ====================
def check_url(url, timeout=10):
    """检查 URL 是否可达"""
    if not url:
        return False
    try:
        resp = requests.get(url, timeout=timeout, allow_redirects=True,
                          headers={"User-Agent": "vibeMusic-CpolarMonitor/1.0"})
        # 200-499 都算可达（403/404 说明服务在跑只是路由不对）
        return resp.status_code < 500
    except Exception as e:
        logging.debug(f"健康检查失败: {url} — {e}")
        return False


# ==================== cpolar 进程管理 ====================
current_url = None

def get_cpolar_url():
    """通过 cpolar 本地 API (localhost:4040) 获取公网地址"""
    try:
        resp = requests.get("http://127.0.0.1:4040/api/tunnels", timeout=5)
        if resp.status_code == 200:
            data = resp.json()
            tunnels = data.get("tunnels", [])
            for t in tunnels:
                public_url = t.get("public_url", "")
                if public_url.startswith("https://"):
                    return public_url
                elif public_url.startswith("http://"):
                    return public_url
        else:
            logging.debug(f"cpolar API 返回 {resp.status_code}")
    except requests.ConnectionError:
        pass  # 还没启动完成
    except Exception as e:
        logging.debug(f"cpolar API 查询异常: {e}")
    return None

def start_cpolar():
    """启动 cpolar（Windows: 独立窗口；其他: 后台进程）"""
    try:
        logging.info(f"启动 cpolar http {CPOLAR_PORT} ...")
        if sys.platform == 'win32':
            # Windows: 用 start /min 启动独立窗口，不依赖 Popen 生命周期
            subprocess.Popen(
                ["cmd", "/c", "start", "/min", "cpolar", "http", str(CPOLAR_PORT)],
                shell=True
            )
        else:
            subprocess.Popen(
                ["cpolar", "http", str(CPOLAR_PORT)],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
        return True
    except FileNotFoundError:
        logging.error("cpolar 未安装，下载: https://www.cpolar.com/")
        return False
    except Exception as e:
        logging.error(f"启动 cpolar 失败: {e}")
        return False

def stop_cpolar():
    """终止所有 cpolar 进程"""
    logging.info("正在终止 cpolar ...")
    try:
        if sys.platform == 'win32':
            subprocess.run(["taskkill", "/F", "/IM", "cpolar.exe"], capture_output=True)
        else:
            subprocess.run(["pkill", "-f", "cpolar"], capture_output=True)
    except Exception as e:
        logging.warning(f"终止 cpolar 失败: {e}")

def poll_url():
    """轮询 API 获取 URL，同时更新全局变量"""
    global current_url
    url = get_cpolar_url()
    if url and url != current_url:
        old = current_url
        current_url = url
        if old:
            logging.info(f"cpolar URL 变更: {old} → {current_url}")
        else:
            logging.info(f"cpolar 隧道已建立: {current_url}")
        return True
    return False


# ==================== 主循环 ====================
def main():
    global current_url

    logging.info("=" * 50)
    logging.info("vibeMusic cpolar 内网穿透监控启动")
    logging.info(f"端口: {CPOLAR_PORT}  |  检测间隔: {CHECK_INTERVAL_MIN}~{CHECK_INTERVAL_MAX}s")
    logging.info(f"连续失败 {MAX_FAILURES} 次自动重启")
    logging.info("=" * 50)

    if not start_cpolar():
        logging.critical("无法启动 cpolar，退出")
        sys.exit(1)

    # 等待 cpolar API 就绪 + 获取首次 URL（轮询最多60秒）
    logging.info("等待 cpolar 隧道建立 (轮询 localhost:4040)...")
    waited = 0
    while not current_url and waited < 60:
        poll_url()
        if not current_url:
            time.sleep(2)
            waited += 2
            if waited % 10 == 0:
                logging.info(f"  等待中... ({waited}s)")

    if not current_url:
        logging.critical("60秒内未获取到 cpolar URL")
        logging.critical("请确认 cpolar 是否已在运行: 浏览器打开 http://127.0.0.1:4040")
        stop_cpolar()
        sys.exit(1)

    # 首次接通通知
    send_wechat(
        "vibeMusic 内网穿透已启动",
        f"时间: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
        f"地址: {current_url}\n"
        f"端口: {CPOLAR_PORT}\n"
        f"监控: 每 {CHECK_INTERVAL_MIN}~{CHECK_INTERVAL_MAX} 秒检测"
    )

    failure_count = 0
    url_sent = current_url

    while True:
        interval = random.randint(CHECK_INTERVAL_MIN, CHECK_INTERVAL_MAX)
        time.sleep(interval)

        # 轮询获取最新 URL
        url_changed = poll_url()

        # 健康检查
        url_to_check = current_url + HEALTH_PATH if current_url else None
        if check_url(url_to_check):
            if failure_count > 0:
                logging.info(f"健康检查恢复正常 (之前失败 {failure_count} 次)")
            failure_count = 0
            if url_changed and current_url != url_sent:
                send_wechat(
                    "vibeMusic 内网穿透地址已变更",
                    f"时间: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
                    f"新地址: {current_url}\n"
                    f"端口: {CPOLAR_PORT}"
                )
                url_sent = current_url
        else:
            failure_count += 1
            logging.warning(f"健康检查失败 ({failure_count}/{MAX_FAILURES}): {url_to_check}")

        # 连续失败 → 重启
        if failure_count >= MAX_FAILURES:
            logging.error(f"连续 {MAX_FAILURES} 次失败，重启 cpolar...")
            stop_cpolar()
            current_url = None
            time.sleep(2)

            if not start_cpolar():
                logging.critical("重启 cpolar 失败")
                sys.exit(1)

            # 等待新 URL
            waited = 0
            while not current_url and waited < 60:
                poll_url()
                if not current_url:
                    time.sleep(2)
                    waited += 2

            if current_url:
                logging.info(f"cpolar 重启成功，新地址: {current_url}")
                send_wechat(
                    "vibeMusic 内网穿透已重启",
                    f"时间: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
                    f"新地址: {current_url}\n"
                    f"原因: 连续 {MAX_FAILURES} 次健康检查失败"
                )
                url_sent = current_url
            else:
                logging.critical("重启后60秒仍未获取到 URL")
                send_wechat(
                    "vibeMusic 内网穿透异常",
                    f"时间: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
                    f"问题: 重启 cpolar 60 秒未获取隧道地址"
                )
            failure_count = 0


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        logging.info("用户中断，正在退出...")
        stop_cpolar()
        sys.exit(0)
