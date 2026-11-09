#!/usr/bin/env bash
# 单文件体量守卫（跨语言版，口径对齐 App 仓 scripts/check-file-size.sh）
#
# 为什么需要：AGENTS.md 写了「新增文件硬上限 400 / 存量每季度治理」，
# 但主仓此前没有执行器——靠人记住的约定必须变成机器断言。
#
# 口径：
#   * 纯 LOC 是**烟感器**，不是设计原则。它只用来发现「一个文件有多个变更理由」。
#   * 硬上限 400：新增文件超过即失败。
#   * 警告线 250：250–400 只打印提醒，不失败。
#   * 存量白名单（scripts/file-size-baseline.txt）不得增长；
#     加功能导致超基线时，允许 --update 一次性上调 ≤5%，提交信息写明理由。
#
# 扫描范围（显式白名单，避免把 node_modules / target / docker-data 扫进来）：
#   vibeMusic-backend/src/main/java   *.java
#   musicapi/src                      *.js
#   vibemusic-web/src                 *.js *.vue
#
# 纯 LOC 口径：排除空行、// 行注释、/* */ 块注释、Vue 的 <!-- --> 块。
#
# 用法：
#   scripts/check-file-size.sh            # 校验（CI 跑这个）
#   scripts/check-file-size.sh --update   # 重写白名单记录值（拆分后同步）
set -euo pipefail

MAX_PURE_LOC=400
WARN_PURE_LOC=250
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE="$ROOT/scripts/file-size-baseline.txt"

python3 - "$ROOT" "$BASELINE" "$MAX_PURE_LOC" "$WARN_PURE_LOC" "${1:-}" <<'PY'
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
baseline_path = pathlib.Path(sys.argv[2])
max_loc = int(sys.argv[3])
warn_loc = int(sys.argv[4])
update = len(sys.argv) > 5 and sys.argv[5] == "--update"

ROOTS = [
    ("vibeMusic-backend/src/main/java", (".java",)),
    ("musicapi/src", (".js",)),
    ("vibemusic-web/src", (".js", ".vue")),
]


def pure_loc(path):
    """纯 LOC：排除空行、行注释、块注释。按扩展名选注释语法。"""
    ext = path.suffix
    n = 0
    in_c_block = False
    in_html_block = False
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        s = raw.strip()
        if ext == ".vue":
            if in_html_block:
                if "-->" in s:
                    in_html_block = False
                continue
            start = s.find("<!--")
            if start >= 0:
                if "-->" not in s[start:]:
                    in_html_block = True
                s = s[:start].strip()
                if not s:
                    continue
        if in_c_block:
            if "*/" in s:
                in_c_block = False
            continue
        if s.startswith("/*"):
            if "*/" not in s:
                in_c_block = True
            continue
        if not s or s.startswith("//"):
            continue
        n += 1
    return n


measured = {}
for rel_root, exts in ROOTS:
    base = root / rel_root
    if not base.exists():
        continue
    for p in base.rglob("*"):
        if not p.is_file() or p.suffix not in exts:
            continue
        if p.name.endswith(".min.js"):
            continue
        try:
            measured[str(p.relative_to(root))] = pure_loc(p)
        except OSError:
            continue

baseline = {}
if baseline_path.exists():
    for line in baseline_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        n, rel = line.split(None, 1)
        baseline[rel.strip()] = int(n)

if update:
    tracked = {rel: n for rel, n in measured.items() if n > warn_loc}
    lines = [
        "# 纯 LOC 白名单（scripts/check-file-size.sh 维护，勿手改）。",
        "# 格式：<纯 LOC> <相对仓库根的路径>",
        "# 规则：不得超过记录值；硬上限见脚本 MAX_PURE_LOC。",
    ]
    lines += ["{} {}".format(n, rel) for rel, n in sorted(tracked.items(), key=lambda kv: -kv[1])]
    baseline_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("已重写 {}（{} 个 >{} 纯 LOC 的文件，硬上限 {}）".format(
        baseline_path.name, len(tracked), warn_loc, max_loc))
    sys.exit(0)

failures = []
warnings = []
for rel, n in measured.items():
    recorded = baseline.get(rel)
    if recorded is None:
        if n > max_loc:
            failures.append("新增超标文件：{} = {} 纯 LOC（硬上限 {}），请按职责拆分".format(rel, n, max_loc))
        elif n > warn_loc:
            warnings.append("{} = {} 纯 LOC（>{}）：确认是一个概念；再长就拆".format(rel, n, warn_loc))
    elif n > recorded:
        failures.append("债务增长：{} = {} 纯 LOC（记录值 {}）；拆分，或按 AGENTS.md 允许的一次性调整跑 --update".format(
            rel, n, recorded))
    elif n < recorded:
        warnings.append("{} 已缩小到 {}（记录值 {}），可跑 --update 同步记录值".format(rel, n, recorded))

for rel in baseline:
    if rel not in measured:
        warnings.append("白名单条目已不存在：{}，可跑 --update 清理".format(rel))

over = sum(1 for n in measured.values() if n > max_loc)
legacy = sum(1 for rel, n in measured.items() if n > max_loc and rel in baseline)
tracked_count = sum(1 for n in measured.values() if n > warn_loc)
print("检查 {} 个文件：{} 个 >{} 纯 LOC，{} 个 >{}（其中 {} 个是白名单存量，豁免）".format(
    len(measured), tracked_count, warn_loc, over, max_loc, legacy))

if warnings:
    print("\n".join("  · " + w for w in warnings))

if failures:
    print("\n".join("  ✗ " + f for f in failures))
    sys.exit(1)
print("✓ 单文件体量守卫通过")
PY
