"""离线单测：不依赖后端，校验 conftest 断言 helpers 本身的行为。"""
import pytest
import requests

from conftest import assert_envelope, assert_rejected, assert_unauthorized, overlap_rate


def _resp(status, body=None):
    r = requests.Response()
    r.status_code = status
    r._content = b"{}" if body is None else __import__("json").dumps(body).encode()
    r.headers["Content-Type"] = "application/json"
    r.encoding = "utf-8"
    return r


def test_assert_envelope_ok_shape():
    """等价类：标准信封 -> 透出 data。"""
    assert assert_envelope({"code": 200, "message": "ok", "data": [1]}) == [1]


def test_assert_envelope_bad_shape():
    """边界值：缺字段 / code 非 200 -> 抛 AssertionError。"""
    with pytest.raises(AssertionError):
        assert_envelope({"code": 200, "data": None})
    with pytest.raises(AssertionError):
        assert_envelope({"code": 500, "message": "x", "data": None})


def test_assert_unauthorized_both_shapes():
    """等价类：HTTP 401 与 200+信封 401 都算未登录。"""
    assert_unauthorized(_resp(401))
    assert_unauthorized(_resp(200, {"code": 401, "message": "请先登录", "data": None}))
    with pytest.raises(AssertionError):
        assert_unauthorized(_resp(200, {"code": 200, "message": "ok", "data": 1}))


def test_assert_rejected_shapes():
    """等价类：HTTP 400 与 200+非 200 信封都算拒绝。"""
    assert_rejected(_resp(400))
    assert_rejected(_resp(200, {"code": 500, "message": "err", "data": None}))
    with pytest.raises(AssertionError):
        assert_rejected(_resp(200, {"code": 200, "message": "ok", "data": 1}))


def test_overlap_rate_bounds():
    """边界值：空集 -> 0.0；全同 -> 1.0；半交 -> 0.5。"""
    assert overlap_rate([], [1]) == 0.0
    assert overlap_rate([1, 2], [1, 2]) == 1.0
    assert overlap_rate([1, 2], [2, 3]) == 0.5
