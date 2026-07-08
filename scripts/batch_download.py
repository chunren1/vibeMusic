"""
批量下载热门歌曲到 MinIO 缓存
用法: python scripts/batch_download.py

策略：
1. 没有账号时自动注册 → 登录拿到 JWT
2. 搜索热门歌手/关键词 → 收集歌曲列表（去重）
3. 5 线程并发下载 → POST /api/download/{sourceId}

中途中断后可重新运行，已缓存的文件会被跳过（DownloadService 做了双重检查）。
"""
import requests
import json
import time
import concurrent.futures
import threading
import sys
import os
import uuid

BASE_URL = "http://localhost:8080"
MAX_WORKERS = 5          # 并发下载数（太多会触发限流）
TARGET_COUNT = 100       # 目标下载数
REQUEST_TIMEOUT = 90     # 单首下载超时（秒）

# ========== 热门搜索关键词 ==========
# 覆盖不同风格和年代，确保种子多样性
HOT_KEYWORDS = [
    # 顶流歌手
    "周杰伦", "林俊杰", "陈奕迅", "邓紫棋", "薛之谦",
    "李荣浩", "五月天", "王菲", "张学友", "刘德华",
    "蔡徐坤", "张杰", "毛不易", "周深", "华晨宇",
    # 经典老歌方向
    "十年", "好久不见", "海阔天空", "光辉岁月",
    # 流行方向
    "晴天", "告白气球", "演员", "泡沫", "光年之外",
    # 独立 / 民谣
    "南山南", "成都", "平凡之路", "起风了",
    # 英文 / K-pop 试试能不能搜到
    "Taylor Swift", "BTS",
]

# ========== 进度统计 ==========
stats_lock = threading.Lock()
stats = {"total": 0, "success": 0, "cached": 0, "failed": 0, "skipped": 0}


def log(msg: str):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}")


# ========== 认证 ==========

def register_and_login() -> str | None:
    """自动注册并登录，返回 JWT token"""
    username = f"batch_{uuid.uuid4().hex[:8]}"
    password = "batch_12345678"

    # 1. 尝试注册
    try:
        r = requests.post(
            f"{BASE_URL}/api/auth/register",
            json={"username": username, "password": password, "nickname": "批量下载"},
            timeout=10,
        )
        if r.status_code == 200:
            log(f"注册成功: {username}")
        else:
            # 可能已存在，继续尝试登录
            ...
    except Exception:
        pass

    # 2. 登录
    try:
        r = requests.post(
            f"{BASE_URL}/api/auth/login",
            json={"username": username, "password": password},
            timeout=10,
        )
        if r.status_code == 200:
            data = r.json()
            resp_data = data.get("data") or {}
            token = resp_data.get("token") or resp_data.get("accessToken")
            if token:
                log(f"登录成功，token: {token[:20]}...")
                return token
    except Exception as e:
        log(f"登录失败: {e}")

    log("认证失败，请确认后端正在运行")
    return None


# ========== 搜索 ==========

def search_songs(keyword: str, token: str) -> list[dict]:
    """搜索歌曲，返回 song 列表"""
    headers = {"Authorization": f"Bearer {token}"}
    try:
        r = requests.get(
            f"{BASE_URL}/api/songs/search",
            params={"keyword": keyword, "page": 1, "size": 20},
            headers=headers,
            timeout=15,
        )
        if r.status_code == 200:
            data = r.json()
            songs = data.get("data", {}).get("list", [])
            log(f"搜索 '{keyword}' → {len(songs)} 首")
            return songs
    except Exception as e:
        log(f"搜索 '{keyword}' 失败: {e}")
    return []


def collect_songs(token: str) -> list[dict]:
    """搜索所有关键词，收集歌曲列表（按 sourceId 去重）"""
    seen = set()
    all_songs = []

    for kw in HOT_KEYWORDS:
        songs = search_songs(kw, token)
        for s in songs:
            sid = s.get("sourceId")
            if sid and sid not in seen:
                seen.add(sid)
                all_songs.append(s)
        log(f"当前累计: {len(all_songs)} 首去重")

    log(f"共收集 {len(all_songs)} 首不重复歌曲")
    return all_songs


# ========== 下载 ==========

def download_one(song: dict, token: str) -> str:
    """
    下载单首歌曲到 RustFS
    返回: "success" / "cached" / "failed" / "skipped"
    """
    sid = song.get("sourceId", "")
    if not sid:
        return "skipped"

    name = song.get("name", sid)
    artist = song.get("artist", "未知歌手")
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }
    body = {
        "name": name,
        "artist": artist,
        "album": song.get("album", ""),
        "coverUrl": song.get("coverUrl", ""),
        "duration": song.get("duration", 0),
        "level": "exhigh",
    }

    try:
        r = requests.post(
            f"{BASE_URL}/api/download/{sid}",
            json=body,
            headers=headers,
            timeout=REQUEST_TIMEOUT,
        )
        data = r.json()
        if r.status_code == 200:
            resp_data = data.get("data") or {}
            if resp_data.get("cached"):
                return "cached"
            return "success"
        else:
            err_msg = data.get("message", r.text[:100])
            log(f"  下载 {name} 失败: HTTP {r.status_code} - {err_msg}")
            return "failed"
    except requests.Timeout:
        log(f"  下载 {name} 超时 ({REQUEST_TIMEOUT}s)")
        return "failed"
    except Exception as e:
        log(f"  下载 {name} 异常: {e}")
        return "failed"


def batch_download(songs: list[dict], token: str, target: int):
    """并发批量下载"""
    download_list = songs[:target]  # 只下载前 target 首

    log(f"\n{'='*50}")
    log(f"开始批量下载: {len(download_list)} 首（并发数={MAX_WORKERS}）")
    log(f"{'='*50}\n")

    completed = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {
            executor.submit(download_one, song, token): song
            for song in download_list
        }

        for future in concurrent.futures.as_completed(futures):
            song = futures[future]
            name = song.get("name", "?")
            try:
                result = future.result(timeout=REQUEST_TIMEOUT)
            except Exception:
                result = "failed"

            with stats_lock:
                stats["total"] += 1
                stats[result] = stats.get(result, 0) + 1
                completed += 1
                s = stats
                log(
                    f"[{completed}/{len(download_list)}] {name[:20]:<20} {result}\t"
                    f"成功={s.get('success',0)} 已缓存={s.get('cached',0)} "
                    f"失败={s.get('failed',0)}"
                )

    log(f"\n{'='*50}")
    log(f"批量下载完成!")
    log(f"  总计:   {stats['total']}")
    log(f"  新缓存: {stats.get('success', 0)}")
    log(f"  已缓存: {stats.get('cached', 0)}")
    log(f"  失败:   {stats.get('failed', 0)}")
    log(f"{'='*50}")


# ========== 主流程 ==========

def main():
    log("=== vibeMusic 批量下载脚本 ===\n")

    # 1. 认证
    token = register_and_login()
    if not token:
        sys.exit(1)

    # 2. 收集歌曲
    log("\n--- 阶段 1/2: 搜索歌曲 ---")
    songs = collect_songs(token)
    if not songs:
        log("未搜到任何歌曲，退出")
        sys.exit(1)

    # 3. 批量下载
    log(f"\n--- 阶段 2/2: 下载前 {TARGET_COUNT} 首 ---")
    batch_download(songs, token, TARGET_COUNT)

    log("\n提示: 播放音乐时会优先使用 MinIO 缓存，不再调用外部 API。")


if __name__ == "__main__":
    main()
