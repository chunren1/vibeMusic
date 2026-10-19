"""认证域冒烟：注册 / 登录 / me / 游客 401 / refresh。"""
import uuid

import pytest

from conftest import (
    BASE_URL, TIMEOUT, assert_envelope, assert_rejected,
    assert_unauthorized, random_user,
)

pytestmark = pytest.mark.live


def test_register_success(guest):
    """等价类：正常注册 -> code 200 且回 token + userId。"""
    creds = random_user()
    data = assert_envelope(
        guest.post(f"{BASE_URL}/api/auth/register", json=creds,
                   timeout=TIMEOUT).json())
    assert data.get("token"), "注册响应缺 token"
    assert data.get("userId") or data.get("username"), "注册响应缺用户信息"


def test_register_duplicate_rejected(guest):
    """等价类：重复用户名注册 -> 被拒绝（code 非 200 或 HTTP 4xx）。"""
    creds = random_user()
    assert_envelope(
        guest.post(f"{BASE_URL}/api/auth/register", json=creds,
                   timeout=TIMEOUT).json())
    assert_rejected(
        guest.post(f"{BASE_URL}/api/auth/register", json=creds, timeout=TIMEOUT))


def test_register_short_password_rejected(guest):
    """边界值：密码 <8 位 -> 被拒绝（AuthController 显式校验）。"""
    creds = random_user()
    creds["password"] = "short"
    assert_rejected(
        guest.post(f"{BASE_URL}/api/auth/register", json=creds, timeout=TIMEOUT))


def test_login_success(guest):
    """等价类：注册后登录 -> code 200 且回 token。"""
    creds = random_user()
    assert_envelope(
        guest.post(f"{BASE_URL}/api/auth/register", json=creds,
                   timeout=TIMEOUT).json())
    data = assert_envelope(guest.post(
        f"{BASE_URL}/api/auth/login",
        json={"username": creds["username"], "password": creds["password"]},
        timeout=TIMEOUT).json())
    assert data.get("token"), "登录响应缺 token"


def test_me_with_token(auth_client):
    """等价类：持 token 查 /me -> 返回同一用户名。"""
    s = auth_client["session"]
    data = assert_envelope(
        s.get(f"{BASE_URL}/api/auth/me", timeout=TIMEOUT).json())
    assert data.get("username") == auth_client["username"]


def test_guest_create_playlist_401(guest):
    """等价类：游客写操作 -> 401（POST /api/playlists/create 无 token）。"""
    name = f"游客歌单_{uuid.uuid4().hex[:6]}"
    assert_unauthorized(guest.post(
        f"{BASE_URL}/api/playlists/create", json={"name": name}, timeout=TIMEOUT))


def test_guest_session_not_polluted_by_earlier_auth(guest):
    """回归守护：本文件先前的 register/login 不得污染 guest 会话（CI 曾因此两红）。

    真后端会下发 httpOnly Cookie，若 guest fixture 被 session 级复用，
    同一 Session 里的后续「游客 401」断言实为登录态请求，会被正常放行。
    """
    names = {c.name for c in guest.cookies}
    assert not (names & {"VIBE_TOKEN", "VIBE_REFRESH"}), f"guest 会话被复用（应每个用例独立）: {names}"


def test_refresh_flow(auth_client):
    """等价类：refresh token 换新 access token -> code 200 且回 token。"""
    s = auth_client["session"]
    assert "VIBE_REFRESH" in {c.name for c in s.cookies}, "缺 refresh cookie"
    data = assert_envelope(
        s.post(f"{BASE_URL}/api/auth/refresh", timeout=TIMEOUT).json())
    assert data.get("token"), "refresh 响应缺新 token"
