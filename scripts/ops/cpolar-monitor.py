#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cpolar 内网穿透存活监控脚本
每隔 3~5 分钟检测一次 HTTPS 地址是否可达，
不可达则自动重启 cpolar 并通过 Server酱 发送新地址。
"""

import os
import sys
import re
import time
import random
import signal
import subprocess
import threading
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


# ==================== cpolar URL 提取 ====================
# cpolar 输出示例行:
# Forwarding  https://xxxx.cpolar.top -> http://localhost:5173
URL_PATTERN = re.compile(r'(https://[\w\-]+\.(cpolar\.(top|io|cn|xyz|fun|site|me)|trycloudflare\.com))')

def extract_url(line):
    """从 cpolar 输出行中提取 HTTPS 地址"""
    m = URL_PATTERN.search(line)
    return m.group(1) if m else None


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
cpolar_process = None
current_url = None

def start_cpolar():
    """启动 cpolar 进程并持续读取输出"""
    global cpolar_process
    try:
        logging.info(f"启动 cpolar http {CPOLAR_PORT} ...")
        cpolar_process = subprocess.Popen(
            ["cpolar", "http", str(CPOLAR_PORT)],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            universal_newlines=True,
            bufsize=1,
            creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == 'win32' else 0,
        )
        return True
    except FileNotFoundError:
        logging.error("cpolar 命令未找到，请确认已安装并加入 PATH")
        return False
    except Exception as e:
        logging.error(f"启动 cpolar 失败: {e}")
        return False

def stop_cpolar():
    """强制终止 cpolar 进程"""
    global cpolar_process
    if cpolar_process and cpolar_process.poll() is None:
        logging.info("正在终止 cpolar 进程...")
        try:
            if sys.platform == 'win32':
                subprocess.run(["taskkill", "/F", "/PID", str(cpolar_process.pid)],
                             capture_output=True)
            else:
                cpolar_process.terminate()
                try:
                    cpolar_process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    cpolar_process.kill()
        except Exception as e:
            logging.warning(f"终止 cpolar 失败: {e}")
    cpolar_process = None


def read_output():
    """非阻塞读取 cpolar 输出，发现新 URL 就更新"""
    global current_url, cpolar_process
    if not cpolar_process or cpolar_process.poll() is not None:
        return
    try:
        while True:
            line = cpolar_process.stdout.readline()
            if not line:
                break
            line = line.strip()
            if line:
                logging.debug(f"[cpolar] {line[:120]}")
                url = extract_url(line)
                if url and url != current_url:
                    old = current_url
                    current_url = url
                    if old:
                        logging.info(f"cpolar URL 变更: {old} → {current_url}")
                    else:
                        logging.info(f"cpolar 隧道已建立: {current_url}")
                    return True
    except Exception:
        pass
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

    # 等待首次 URL 出现（最多30秒）
    logging.info("等待 cpolar 建立隧道...")
    waited = 0
    while not current_url and waited < 30:
        read_output()
        if not current_url:
            time.sleep(1)
            waited += 1

    if not current_url:
        logging.critical("30秒内未检测到 cpolar URL，请检查 cpolar 是否正常运行")
        stop_cpolar()
        sys.exit(1)

    # 首次接通通知
    send_wechat(
        "✅ vibeMusic 内网穿透已启动",
        f"**时间**: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
        f"**地址**: {current_url}\n"
        f"**端口**: {CPOLAR_PORT}\n"
        f"**监控**: 每 {CHECK_INTERVAL_MIN}~{CHECK_INTERVAL_MAX} 秒检测一次"
    )

    failure_count = 0
    url_sent = current_url  # 记录上次发送的 URL，避免重复

    while True:
        # 随机间隔
        interval = random.randint(CHECK_INTERVAL_MIN, CHECK_INTERVAL_MAX)
        time.sleep(interval)

        # 检查 cpolar 是否还活着
        if cpolar_process and cpolar_process.poll() is not None:
            logging.warning("cpolar 进程已退出，重启中...")
            failure_count = MAX_FAILURES  # 直接触发重启

        # 读取最新输出
        url_changed = read_output()

        # 健康检查
        url_to_check = current_url + HEALTH_PATH if current_url else None
        if check_url(url_to_check):
            if failure_count > 0:
                logging.info(f"健康检查恢复正常 (连续失败 {failure_count} 次)")
            failure_count = 0

            # URL 变更通知
            if url_changed and current_url != url_sent:
                send_wechat(
                    "🔄 vibeMusic 内网穿透地址已变更",
                    f"**时间**: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
                    f"**新地址**: {current_url}\n"
                    f"**端口**: {CPOLAR_PORT}"
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
                read_output()
                if not current_url:
                    time.sleep(1)
                    waited += 1

            if current_url:
                logging.info(f"cpolar 重启成功，新地址: {current_url}")
                send_wechat(
                    "🔴 vibeMusic 内网穿透已重启",
                    f"**时间**: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
                    f"**新地址**: {current_url}\n"
                    f"**原因**: 连续 {MAX_FAILURES} 次健康检查失败"
                )
                url_sent = current_url
            else:
                logging.critical("重启后60秒仍未获取到 URL")
                send_wechat(
                    "🚨 vibeMusic 内网穿透异常",
                    f"**时间**: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
                    f"**问题**: 重启 cpolar 后 60 秒未获取到隧道地址\n"
                    f"**请检查**: cpolar 账号是否有效、网络是否正常"
                )
            failure_count = 0


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        logging.info("用户中断，正在退出...")
        stop_cpolar()
        sys.exit(0)
