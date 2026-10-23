# 契约层（contract/）

## 这是什么

`gateway.contract.json` 定义 **musicapi 网关 → Java 后端** 的响应形状。字段不是拍脑袋写的，而是按后端
`NeteaseApiService` / `SongPlayService` 实际读取的 key 反推：

- `/song/url/v1` 系：`code`、`data[].url`、`data[].freeTrialInfo`、`data[].time`
- 搜索系：`code`、`data[].id / name / artists / duration`
- 歌词系：`code`、`data.lyric`

`musicapi/test/contract.test.js` 用这份 JSON 校验真实路由的响应，字段名或类型漂移会先在 CI 变红。

## 规则

1. **先改契约，再改代码**：任何响应字段的增删改，先动 `gateway.contract.json`，再同步后端解析。
2. **只增不删**：新增可选字段不算破坏；重命名/改类型/删字段属 breaking，必须同时改后端。
3. **契约只覆盖消费方真正读的字段**，不做全量 schema，避免为了"完整"维护一堆没人用的定义。
4. 未声明的字段允许存在（例如网关内部诊断字段），契约不限制扩展。

## 运行

```bash
cd musicapi
node --test --test-force-exit test/contract.test.js
# 或随全量：npm test
```

CI 已把 `test/contract.test.js` 加入测试清单（`ci.yml` / `deploy.yml` 两处），无需额外步骤。

## 当前覆盖与下一步

已覆盖：`/song/url/qq`、`/qq/lyric`、`/qq/playlist`。

下一步（按后端消费量排序）：

1. `/song/url/v1`、`/netease/search`（网易透传体，需固定上游返回的关键字段）
2. `/search`（聚合搜索：`data.list[].id/name/artists/album/cover/duration/vip/platform`）
3. App ↔ Java 后端的公开 API（`/api/songs/search`、`/api/songs/lyric`、`/api/songs/{id}/stream`），
   建议用 Spring MockMvc + 同一份契约思路，放在 `vibeMusic-backend/src/test/java/.../contract/`。
