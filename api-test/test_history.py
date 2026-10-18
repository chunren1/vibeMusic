"""历史域冒烟：搜索历史云同步 push/pull/clear + 播放历史 list。"""
import uuid

import pytest

from conftest import BASE_URL, TIMEOUT, assert_envelope, assert_unauthorized

pytestmark = pytest.mark.live


def test_search_history_push_pull(auth_client):
    """等价类：push 关键词 -> pull 含该词（云同步 upsert）。"""
    s = auth_client["session"]
    word = f"同步词_{uuid.uuid4().hex[:6]}"
    pushed = assert_envelope(s.post(
        f"{BASE_URL}/api/search/history/sync", json={"keywords": [word]},
        timeout=TIMEOUT).json())
    assert word in pushed, f"push 回显缺关键词: {pushed!r:.200}"
    pulled = assert_envelope(s.get(
        f"{BASE_URL}/api/search/history", params={"count": 20},
        timeout=TIMEOUT).json())
    assert word in pulled, "pull 拉不到刚 push 的词"


def test_search_history_guest_401(guest):
    """等价类：游客拉搜索历史 -> 401（Controller 层二次校验兜底）。"""
    assert_unauthorized(guest.get(
        f"{BASE_URL}/api/search/history", timeout=TIMEOUT))


def test_search_history_clear(auth_client):
    """等价类：push 后 clear -> pull 不再含该词。"""
    s = auth_client["session"]
    word = f"待清_{uuid.uuid4().hex[:6]}"
    assert_envelope(s.post(
        f"{BASE_URL}/api/search/history/sync", json={"keywords": [word]},
        timeout=TIMEOUT).json())
    cleared = assert_envelope(s.delete(
        f"{BASE_URL}/api/search/history", timeout=TIMEOUT).json())
    assert isinstance(cleared, int) and cleared >= 1, "clear 应返回条数"
    pulled = assert_envelope(s.get(
        f"{BASE_URL}/api/search/history", params={"count": 20},
        timeout=TIMEOUT).json())
    assert word not in pulled, "clear 后仍能拉到该词"


def test_play_history_list(auth_client):
    """等价类：登录态查播放历史 -> 信封 200 且为列表（新号可为空）。"""
    s = auth_client["session"]
    items = assert_envelope(s.get(
        f"{BASE_URL}/api/songs/history", params={"count": 20},
        timeout=TIMEOUT).json())
    assert isinstance(items, list), f"播放历史应为列表: {items!r:.200}"


def test_play_history_guest_empty(guest):
    """等价类：游客查播放历史 -> 200 空列表（不 401，读侧宽容）。"""
    items = assert_envelope(guest.get(
        f"{BASE_URL}/api/songs/history", timeout=TIMEOUT).json())
    assert items == [], f"游客播放历史应为空列表: {items!r:.200}"
