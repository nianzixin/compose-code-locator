#!/usr/bin/env bash
# 将 Maven 产物发布到 maven 分支（仓库根目录即 Maven 仓库，团队用 git URL 引用）
# 用法：先 ./gradlew 发布全部产物到 build/composeLocator/release/maven，再执行本脚本
# 注意：脚本内的 git rm 会删除工作区未提交改动，修改本脚本后务必先提交再运行
set -euo pipefail

REMOTE="${1:-internal}"
MAVEN_DIR="build/composeLocator/release/maven"
BRANCH="maven"

if [ ! -d "$MAVEN_DIR" ]; then
    echo "未找到产物目录 $MAVEN_DIR，请先运行 gradle 发布任务" >&2
    exit 1
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp -r "$MAVEN_DIR"/. "$STAGE"/

TMP_BRANCH="maven-tmp-$$"
git checkout --orphan "$TMP_BRANCH"
# checkout --orphan 会保留索引，所有文件仍是跟踪状态；先清空索引和工作区再拷产物
git rm -rf . >/dev/null
git clean -fdx
cp -r "$STAGE"/. .
git add -A
git commit -m "chore(maven): 发布 Maven 产物"

# 用临时分支覆盖 maven 分支，保持历史线性
git branch -f "$BRANCH" "$TMP_BRANCH"
git checkout main
git branch -D "$TMP_BRANCH"
git push -f "$REMOTE" "$BRANCH"

echo "已推送 $BRANCH 分支到 $REMOTE"
