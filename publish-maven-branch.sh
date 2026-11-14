#!/usr/bin/env bash
# 将 Maven 产物发布到 maven 分支（仓库根目录即 Maven 仓库，团队克隆该分支引用）
# 用法：先 ./gradlew 发布全部产物到 build/composeLocator/release/maven，再执行本脚本
# 用 git worktree 完成全部操作，发布期间不影响 main 工作区，结束后自动删除 worktree
# 注意：脚本内有未提交改动时也可安全运行
set -euo pipefail

REMOTE="${1:-internal}"
MAVEN_DIR="build/composeLocator/release/maven"
BRANCH="maven"

if [ ! -d "$MAVEN_DIR" ]; then
    echo "未找到产物目录 $MAVEN_DIR，请先运行 gradle 发布任务" >&2
    exit 1
fi

WT="$(mktemp -d)/maven-wt"
# maven 分支已存在则在其上续提（先清空旧产物），否则建孤儿 worktree
if git rev-parse --verify --quiet "$BRANCH" >/dev/null; then
    git worktree add --force "$WT" "$BRANCH"
    git -C "$WT" rm -rf . >/dev/null
else
    git worktree add --orphan "$WT"
fi
trap 'git worktree remove --force "$WT" >/dev/null 2>&1 || true' EXIT

cp -r "$MAVEN_DIR"/. "$WT"/
git -C "$WT" add -A
if git -C "$WT" diff --cached --quiet; then
    echo "产物无变化，跳过提交"
else
    git -C "$WT" commit -m "chore(maven): 发布 Maven 产物"
fi
git push -f "$REMOTE" "$BRANCH"

echo "已推送 $BRANCH 分支到 $REMOTE"
