#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Cookie 快速检测脚本 — 启动时运行
检查网易云和QQ音乐 Cookie 是否有效，直接输出结果。
"""
import requests
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def ok(msg): print(f"[OK] {msg}")
def fail(msg): print(f"[FAIL] {msg}")
def warn(msg): print(f"[WARN] {msg}")

def test_netease():
    """测试网易云 Cookie"""
    try:
        r = requests.get(
            "http://localhost:3000/cloudsearch",
            params={"keywords": "周杰伦", "limit": 3, "type": 1},
            timeout=10
        )
        if r.status_code == 200 and r.json().get("code") == 200:
            count = r.json()["result"].get("songCount", 0)
            ok(f"Netease Cookie OK (found {count} songs)")
            return True
        fail(f"Netease Cookie FAIL: {r.text[:100]}")
        return False
    except requests.exceptions.ConnectionError:
        fail("musicapi not running (localhost:3000)")
        return False
    except Exception as e:
        fail(f"Netease error: {e}")
        return False

def test_qq():
    """Test QQ Music Cookie"""
    try:
        r = requests.get(
            "http://localhost:3000/qq/search",
            params={"keyword": "周杰伦", "limit": 5},
            timeout=10
        )
        if r.status_code != 200 or r.json().get("code") != 200:
            fail(f"QQ search failed: {r.text[:100]}")
            return False

        data = r.json().get("data", {})
        songs = data.get("list") if isinstance(data, dict) else (data if isinstance(data, list) else [])
        if not songs:
            warn("QQ search: no results")
            return False

        song_id = songs[0]["id"]
        ur = requests.get(
            "http://localhost:3000/song/url/qq",
            params={"id": song_id},
            timeout=10
        )
        if ur.status_code == 200:
            url = ur.json()["data"][0].get("url") if ur.json()["data"] else None
            if url:
                ok(f"QQ Cookie OK (play URL valid)")
                return True
            else:
                warn(f"QQ search OK but {song_id} has no play URL")
                return True
        fail(f"QQ Cookie FAIL: {ur.text[:100]}")
        return False
    except requests.exceptions.ConnectionError:
        fail("musicapi not running (localhost:3000)")
        return False
    except Exception as e:
        fail(f"QQ error: {e}")
        return False

if __name__ == "__main__":
    print("\n=== vibeMusic Cookie Check ===\n")

    n = test_netease()
    q = test_qq()

    print()
    if n and q:
        print(">>> All cookies OK!\n")
    elif not n and not q:
        print(">>> All cookies FAILED, please update!\n")
        sys.exit(1)
    else:
        print(">>> Some cookies failed, please check\n")
        sys.exit(1)
