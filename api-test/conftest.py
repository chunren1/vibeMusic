"""共享 fixtures 与断言：BASE_URL / guest / auth_client / 信封校验。

后端不可达时 live 用例整批 skip（不整批失败）；
纯本地单测（test_envelope_unit.py）不依赖 live，离线也能跑。
"""
import os
import uuid

import pytest
import requests

BASE_URL = os.environ.get("API_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
TIMEOUT = 10


def backend_alive() -> bool:
    """健康探针：GET /api/auth/health，任意 <500 即视为后端存活。"""
    try:
        r = requests.get(BASE_URL + "/api/auth/health", timeout=3)
        return r.status_code < 500
    except requests.RequestException:
        return False


def assert_envelope(body, *, expect_code=200):
    """断言统一信封 {code,message,data}，返回 data。"""
    assert isinstance(body, dict), f"响应不是 JSON 对象: {body!r:.200}"
    assert {"code", "message", "data"} <= set(body), f"信封缺字段: {body!r:.200}"
    assert body["code"] == expect_code, f"code={body['code']} msg={body.get('message')}"
    return body["data"]


def assert_unauthorized(resp):
    """未登录写操作：HTTP 401 或 HTTP 200 + 信封 code 401 都算通过。"""
    if resp.status_code == 401:
        return
    assert resp.status_code == 200, f"意外状态码: {resp.status_code}"
    assert resp.json().get("code") == 401, f"期望 401 信封: {resp.text[:200]}"


def assert_rejected(resp):
    """非法输入被拒绝：HTTP 4xx 或信封 code != 200 都算通过。"""
    if 400 <= resp.status_code < 500:
        return
    assert resp.status_code == 200, f"意外状态码: {resp.status_code}"
    assert resp.json().get("code") != 200, f"非法输入竟通过了: {resp.text[:200]}"


def overlap_rate(first, second) -> float:
    """两批推荐 id 的重叠率（Jaccard 变体：交集 / max 长度）。"""
    if not first or not second:
        return 0.0
    return len(set(first) & set(second)) / max(len(first), len(second))


def random_user():
    """随机测试用户（无硬编码口令，uuid 后缀防并发冲突）。"""
    suffix = uuid.uuid4().hex[:8]
    return {
        "username": f"apitest_{suffix}",
        "password": f"Pw{suffix}__123",
        "nickname": f"接口测试_{suffix}",
    }


@pytest.fixture(scope="session")
def live():
    """活后端门控：连不上就 skip，整批用例变黄不爆红。"""
    if not backend_alive():
        pytest.skip(f"后端不可达({BASE_URL})，live 用例全部跳过")
    return True


@pytest.fixture
def guest(live):
    """游客 client：无 token 的干净 session。

    **必须是 function scope（每个用例一份）**：本套件有用例借用 guest 去 register/login，
    而真后端会在这些响应上下发 httpOnly Cookie（VIBE_TOKEN / VIBE_REFRESH，见
    AuthController.setTokenCookies）。若按 session 复用同一个 Session，
    其后所有"游客应 401"的断言都会带着登录 Cookie 发出、被后端正常放行——
    CI 实测：游客建出歌单 id=25、搜索历史返回 {"code":200,...,"data":[]}。
    """
    _ = live
    with requests.Session() as s:
        yield s
        s.cookies.clear()


@pytest.fixture(scope="function")
def auth_client(live):
    """登录态 client：随机注册 -> 再登录 -> 产出带 cookie + Bearer 的 session。

    yields: {"session", "headers", "token", "username", "password"}
    退出登录 best-effort 清理，不阻塞用例结果。
    """
    _ = live
    creds = random_user()
    with requests.Session() as s:
        r = s.post(f"{BASE_URL}/api/auth/register", json=creds, timeout=TIMEOUT)
        assert_envelope(r.json())
        r = s.post(f"{BASE_URL}/api/auth/login", json={
            "username": creds["username"], "password": creds["password"],
        }, timeout=TIMEOUT)
        data = assert_envelope(r.json())
        token = data.get("token", "")
        assert token, "登录响应缺 token"
        s.headers.update({"Authorization": f"Bearer {token}"})
        yield {"session": s, "token": token, **creds}
        try:
            s.post(f"{BASE_URL}/api/auth/logout", timeout=3)
        except requests.RequestException:
            pass
