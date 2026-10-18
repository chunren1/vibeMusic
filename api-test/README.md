# api-test — vibeMusic 后端接口冒烟（pytest）

```
api-test/                  5 分钟可读：上 fixtures/断言，下按域分文件
├── conftest.py            BASE_URL / guest / auth_client / 信封断言 + 活后端门控
├── test_auth.py           注册/登录/me/游客 401/refresh（7）
├── test_search.py         搜索/热词/随机/个性化/换一批重叠率（7）
├── test_playlist_favorite.py  建单/加歌/收藏/toggle/删单（6）
├── test_history.py        搜索历史同步/播放历史（5）
└── test_envelope_unit.py  离线单测：断言 helpers 自检，无需后端（5）
```

## 本地跑

```bash
cd api-test && pip install -r requirements.txt
pytest -q                                  # 默认打 http://127.0.0.1:8080
API_BASE_URL=http://127.0.0.1:8080 pytest -q   # 指定后端地址
npm run test:api                           # 根目录快捷方式（同上）
```

后端不可达时 live 用例整批 skip（黄）不爆红；报告见 `report.html`。

## 约定

- 信封 `{code,message,data}`：code 200 成功 / 401 未登录（见后端 `Result.java`）。
- 登录态 = register 回的 httpOnly cookie（`VIBE_TOKEN`/`VIBE_REFRESH`）+ Bearer 双带。
- 游客可读（歌曲/推荐/热词），写操作（歌单/收藏/历史同步）游客 401。
- 测试用户随机后缀（`apitest_<hex8>`），无硬编码口令；删单/退登 best-effort 清理。
- CI：第 5 个 job `api-test`（仅 push 触发），services 起 mysql:8.0 + redis:7-alpine，
  musicapi(3000) 与后端实机启动、健康检查放行后跑 pytest，`report.html` 传 artifact。
  起 musicapi 是为了让搜索/推荐类用例真跑（不上游时五路 fanout 全空会大面积 skip）。
