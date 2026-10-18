"""歌单/收藏域冒烟：建单 -> 加歌 -> 查歌 -> 收藏 -> 查 ids -> 删单。"""
import uuid

import pytest

from conftest import BASE_URL, TIMEOUT, assert_envelope, assert_rejected

pytestmark = pytest.mark.live


def _song_payload(tag=None):
    tag = tag or uuid.uuid4().hex[:6]
    return {
        "sourceId": f"apitest_{tag}",
        "songName": f"接口测试歌曲_{tag}",
        "artist": "接口测试歌手",
        "coverUrl": "",
        "duration": 180,
        "platform": "netease",
    }


@pytest.fixture()
def playlist(auth_client):
    """前置建单、后置删单的歌单 fixture（用例隔离，名带 uuid）。"""
    s = auth_client["session"]
    name = f"接口歌单_{uuid.uuid4().hex[:8]}"
    data = assert_envelope(s.post(
        f"{BASE_URL}/api/playlists/create",
        json={"name": name, "description": "pytest 冒烟"}, timeout=TIMEOUT).json())
    pid = data.get("id")
    assert pid, f"建单响应缺 id: {data!r:.200}"
    yield pid
    try:
        s.delete(f"{BASE_URL}/api/playlists/delete",
                 params={"playlistId": pid}, timeout=3)
    except Exception:
        pass


def test_create_playlist(auth_client):
    """等价类：正常建单 -> 回 id；删单清理。"""
    s = auth_client["session"]
    name = f"建单_{uuid.uuid4().hex[:8]}"
    data = assert_envelope(s.post(
        f"{BASE_URL}/api/playlists/create", json={"name": name},
        timeout=TIMEOUT).json())
    assert data.get("id"), f"建单响应缺 id: {data!r:.200}"
    s.delete(f"{BASE_URL}/api/playlists/delete",
             params={"playlistId": data["id"]}, timeout=TIMEOUT)


def test_create_playlist_blank_name_rejected(auth_client):
    """边界值：空歌单名 -> 被拒绝（Bug3 修复点：name 必填校验）。"""
    s = auth_client["session"]
    assert_rejected(s.post(
        f"{BASE_URL}/api/playlists/create", json={"name": "   "},
        timeout=TIMEOUT))


def test_add_and_list_songs(auth_client, playlist):
    """等价类：加歌 -> 查歌单歌曲含该 sourceId。"""
    s = auth_client["session"]
    song = _song_payload()
    assert_envelope(s.post(
        f"{BASE_URL}/api/playlists/add-song",
        json={"playlistId": playlist, **song}, timeout=TIMEOUT).json())
    songs = assert_envelope(s.get(
        f"{BASE_URL}/api/playlists/songs",
        params={"playlistId": playlist}, timeout=TIMEOUT).json())
    assert isinstance(songs, list), f"songs 应为列表: {songs!r:.200}"
    assert song["sourceId"] in {str(x.get("sourceId") or x.get("id"))
                                for x in songs if isinstance(x, dict)}, "加歌后查不到"


def test_toggle_and_list_favorite(auth_client):
    """等价类：toggle 收藏 -> ids 含该歌 -> 再 toggle 取消。"""
    s = auth_client["session"]
    song = _song_payload()
    faved = assert_envelope(s.post(
        f"{BASE_URL}/api/favorites/toggle", json=song, timeout=TIMEOUT).json())
    assert faved is True, "首次 toggle 应返回已收藏 True"
    try:
        ids = assert_envelope(s.get(
            f"{BASE_URL}/api/favorites/ids", timeout=TIMEOUT).json())
        assert song["sourceId"] in ids, "ids 集合缺刚收藏的歌"
        items = assert_envelope(s.get(
            f"{BASE_URL}/api/favorites/list", params={"count": 50},
            timeout=TIMEOUT).json())
        assert isinstance(items, list) and items, "收藏列表为空"
    finally:
        unfaved = assert_envelope(s.post(
            f"{BASE_URL}/api/favorites/toggle", json=song,
            timeout=TIMEOUT).json())
        assert unfaved is False, "二次 toggle 应返回已取消 False"


def test_toggle_missing_sourceid_rejected(auth_client):
    """边界值：toggle 缺 sourceId -> 被拒绝（Bug4 修复点：必填校验）。"""
    s = auth_client["session"]
    assert_rejected(s.post(
        f"{BASE_URL}/api/favorites/toggle",
        json={"songName": "缺 id 的歌"}, timeout=TIMEOUT))


def test_delete_playlist(auth_client):
    """等价类：删单 -> list 不再含该歌单。"""
    s = auth_client["session"]
    name = f"待删_{uuid.uuid4().hex[:8]}"
    data = assert_envelope(s.post(
        f"{BASE_URL}/api/playlists/create", json={"name": name},
        timeout=TIMEOUT).json())
    pid = data["id"]
    r = s.delete(f"{BASE_URL}/api/playlists/delete",
                 params={"playlistId": pid}, timeout=TIMEOUT)
    if r.text.strip():
        assert_envelope(r.json())  # Result.ok() data 为 null 也算 200
    else:
        assert r.status_code in (200, 204), f"删单状态码异常: {r.status_code}"
    items = assert_envelope(s.get(
        f"{BASE_URL}/api/playlists/list", timeout=TIMEOUT).json())
    assert pid not in {x.get("id") for x in items if isinstance(x, dict)}, "删单后仍在列表"
