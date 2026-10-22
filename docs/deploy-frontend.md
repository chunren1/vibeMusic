# 前端 dist 发版（scripts/deploy-frontend.sh）

一条命令把刚构建的 `vibemusic-web/dist` 原子发布到云主机 `/var/www/vibemusic`，带 md5 对包与公网校验。
```bash
./scripts/deploy-frontend.sh                       # 默认 dry-run：只打印命令，不碰服务器
./scripts/deploy-frontend.sh --apply               # 真发：构建→传包→对 md5→解包→nginx -t→原子切换→reload→公网验证
./scripts/deploy-frontend.sh --apply --skip-build  # 复用现有 dist（哈希仍现场重算）
./scripts/deploy-frontend.sh --apply --keep 5      # 远端保留 5 个旧备份（默认 3）
HOST=root@1.2.3.4 REMOTE_DIR=/var/www/vibemusic ./scripts/deploy-frontend.sh --apply
```

- 切换是**两个 rename**：旧目录→`vibemusic.bak-<时间戳>`，`vibemusic.new`→正式目录，无"半个站点"中间态。
- **公网验证失败自动回滚**：新目录挪到 `vibemusic.bad-<ts>` 取证，备份搬回原位并 `nginx -t && reload`，脚本 `exit 1`。
- 手工回滚：`ssh $HOST "mv /var/www/vibemusic /var/www/vibemusic.bad-manual && mv /var/www/vibemusic.bak-<ts> /var/www/vibemusic && nginx -t && systemctl reload nginx"`。
- 只走 SSH key（`BatchMode=yes`，不等你输密码）；脚本无任何口令。
