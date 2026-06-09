#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Cookie 存活监控脚本 —— 单次执行模式
由 Windows 任务计划程序每 2 小时调用一次。
"""

import os
import sys
import requests
import datetime
import logging
import json

# ==================== 配置参数 ====================
NETEASE_API_URL = "http://localhost:3000/cloudsearch"
QQ_MUSIC_API_URL = "http://localhost:3000/qq/search"
TEST_KEYWORD = "周杰伦"
FAILURE_THRESHOLD = 3  # 连续失败达到阈值才告警

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MUSICAPI_DIR = os.path.join(os.path.dirname(SCRIPT_DIR), "musicapi")
LOG_DIR = os.path.join(MUSICAPI_DIR, "logs")
LOG_FILE = os.path.join(LOG_DIR, "cookie-monitor.log")
STATE_FILE = os.path.join(SCRIPT_DIR, "monitor_state.json")

# 确保日志目录存在
os.makedirs(LOG_DIR, exist_ok=True)

SCKEY = os.environ.get("SCKEY", "")
SERVERCHAN_URL = f"https://sctapi.ftqq.com/{SCKEY}.send" if SCKEY else ""

# ==================== 全局变量 ====================
session = requests.Session()
session.headers.update({
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
})

# ==================== 日志 ====================
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(message)s",
    handlers=[
        logging.FileHandler(LOG_FILE, encoding='utf-8'),
        logging.StreamHandler()
    ]
)

# ==================== 状态持久化 ====================
def load_state():
    """加载失败计数状态"""
    if os.path.exists(STATE_FILE):
        try:
            with open(STATE_FILE, 'r', encoding='utf-8') as f:
                return json.load(f)
        except:
            pass
    return {"netease": 0, "qq": 0}

def save_state(state):
    """保存失败计数状态"""
    with open(STATE_FILE, 'w', encoding='utf-8') as f:
        json.dump(state, f)

# ==================== API 检查 ====================
def test_netease_api():
    try:
        params = {"keywords": TEST_KEYWORD, "limit": 10, "type": 1}
        response = session.get(NETEASE_API_URL, params=params, timeout=10)
        if response.status_code == 200:
            data = response.json()
            if data.get("code") == 200 and data.get("result"):
                count = data["result"].get("songCount", 0)
                logging.info(f"✅ [网易云] OK，搜索到 {count} 首")
                return True, None
            else:
                err = f"返回异常: {json.dumps(data, ensure_ascii=False)[:200]}"
                logging.error(f"❌ [网易云] {err}")
                return False, err
        else:
            err = f"HTTP {response.status_code}"
            logging.error(f"❌ [网易云] {err}")
            return False, err
    except requests.exceptions.RequestException as e:
        err = f"请求异常: {str(e)}"
        logging.error(f"❌ [网易云] {err}")
        return False, err

def test_qq_music_api():
    try:
        params = {"key": TEST_KEYWORD, "limit": 10}
        response = session.get(QQ_MUSIC_API_URL, params=params, timeout=10)
        if response.status_code == 200:
            data = response.json()
            if data.get("code") == 200 and data.get("data"):
                qq_data = data["data"]
                song_list = qq_data.get("list") or qq_data.get("song", {}).get("list", [])
                logging.info(f"✅ [QQ音乐] OK，搜索到 {len(song_list)} 首")
                return True, None
            else:
                err = f"返回异常: {json.dumps(data, ensure_ascii=False)[:200]}"
                logging.error(f"❌ [QQ音乐] {err}")
                return False, err
        else:
            err = f"HTTP {response.status_code}"
            logging.error(f"❌ [QQ音乐] {err}")
            return False, err
    except requests.exceptions.RequestException as e:
        err = f"请求异常: {str(e)}"
        logging.error(f"❌ [QQ音乐] {err}")
        return False, err

# ==================== 告警 ====================
def send_wechat_alert(platform, error_msg):
    if not SCKEY:
        logging.warning("⚠️ SCKEY 未配置，跳过微信通知")
        return
    try:
        data = {
            "title": f"🔴 {platform} API 监控告警",
            "desp": (
                f"**时间**: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
                f"**平台**: {platform}\n"
                f"**连续失败**: {FAILURE_THRESHOLD}次\n"
                f"**错误**: {error_msg}\n\n"
                f"⚠️ 请检查 Cookie 状态或 API 服务！"
            )
        }
        resp = requests.post(SERVERCHAN_URL, data=data, timeout=30)
        if resp.status_code == 200 and resp.json().get("code") == 0:
            logging.info(f"✅ [{platform}] 微信告警已发送")
        else:
            logging.error(f"❌ [{platform}] 告警发送失败")
    except Exception as e:
        logging.error(f"❌ [{platform}] 告警异常: {e}")

# ==================== 单次检查 ====================
def check_platform(name, test_func, state):
    key = name.lower()
    success, error = test_func()
    if success:
        if state[key] > 0:
            logging.info(f"✅ [{name}] 已恢复，重置计数")
            state[key] = 0
    else:
        state[key] += 1
        logging.warning(f"❌ [{name}] 第 {state[key]}/{FAILURE_THRESHOLD} 次失败")
        if state[key] >= FAILURE_THRESHOLD:
            logging.error(f"🚨 [{name}] 达到告警阈值！")
            send_wechat_alert(name, error)
            state[key] = 0

def main():
    state = load_state()
    logging.info("=" * 50)
    logging.info("🎯 Cookie 监控检查开始")
    logging.info(f"   上次状态: 网易云={state['netease']} QQ={state['qq']}")
    logging.info("=" * 50)

    try:
        check_platform("网易云音乐", test_netease_api, state)
    except Exception as e:
        logging.error(f"❌ 网易云检查异常: {e}")

    try:
        check_platform("QQ音乐", test_qq_music_api, state)
    except Exception as e:
        logging.error(f"❌ QQ音乐检查异常: {e}")

    save_state(state)
    logging.info("检查完成。")

if __name__ == "__main__":
    main()
