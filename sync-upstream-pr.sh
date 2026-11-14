#!/usr/bin/env bash
# 同步 main 到 upstream-pr 对外 PR 分支：merge → 剔除内网文件 → 推 GitHub fork
# 本脚本提交在内网 main；同步到 upstream-pr 时只从索引剔除、保留本地文件（脚本运行中不能删自己）
set -euo pipefail
cd "$(dirname "$0")"

# 内网专属文件清单：merge 后从 upstream-pr 剔除，永远不进对外 PR
INTERNAL_FILES=(
    docs/internal-notes.md
    publish-gitlab-registry.sh
    publish-maven-branch.sh
    sync-upstream-pr.sh
    .gitlab-ci.yml
    .github/workflows/publish.yml
    .githooks
)

git checkout upstream-pr

if git merge main --no-edit 2>/dev/null; then
    echo "merge 完成"
else
    # 预期冲突：内网文件在 main 上被修改、upstream-pr 上已删除（DU = deleted by us）——保留删除
    du_resolved=0
    while IFS= read -r f; do
        [[ -z "$f" ]] && continue
        git rm -q -- "$f"
        du_resolved=1
    done < <(git status --porcelain | awk '$1=="DU"{print $2}')

    # 其余冲突（UU 等双边内容冲突）无法自动裁决，交还人工
    if git status --porcelain | awk '$1=="UU"||$1=="AA"||$1=="DD"||$1=="AU"||$1=="UA"{exit 1}'; then
        git add -A
        git commit -q --no-edit
        echo "merge 完成（自动解决 $du_resolved 处内网文件 modify/delete 冲突）"
    else
        echo "merge main 出现双边内容冲突，请手工解决后重新运行本脚本" >&2
        git merge --abort
        git checkout main
        exit 1
    fi
fi

# 剔除内网文件（-q：文件可能已不存在，忽略报错）。
# 本脚本自身用 --cached 只从索引剔除：文件还在磁盘上，脚本剩余部分才能继续执行
for f in "${INTERNAL_FILES[@]}"; do
    if [[ "$f" == "sync-upstream-pr.sh" ]]; then
        git rm -q --cached --ignore-unmatch -- "$f" 2>/dev/null || true
    else
        git rm -rq --ignore-unmatch -- "$f" 2>/dev/null || rm -rf -- "$f"
    fi
done

# 残留检查：只看新增行（+ 开头）；删除内网内容是本脚本的目的，不算残留
if git diff --cached -U0 | grep "^+" | grep -Ei "192\.168\.|zhangjunjie@|guidefuture|deployToken" >/dev/null; then
    echo "发现内网敏感信息残留，已中止推送，请检查上方 diff" >&2
    exit 1
fi

if git diff --cached --quiet; then
    echo "无待提交变更"
else
    git commit -q -m "chore(sync): 同步 main 并剔除内网文件"
fi

git push fork upstream-pr
git checkout main
echo "同步完成：fork upstream-pr 已更新，已切回 main"
