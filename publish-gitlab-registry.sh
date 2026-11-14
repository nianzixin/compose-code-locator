#!/usr/bin/env bash
# 将 Maven 产物发布到 GitLab Package Registry（项目需 Public，团队成员匿名即可下载）
# 用法：先 ./gradlew 发布全部产物到 build/composeLocator/release/maven，再执行本脚本
# 认证：仓库根目录的 .deployToken 文件（已被 .gitignore 排除），格式为 KEY=VALUE 两行：
#         DEPLOY_USER=<GitLab Deploy Token 用户名>
#         DEPLOY_TOKEN=<GitLab Deploy Token，scope: write_package_registry>
set -euo pipefail

MAVEN_DIR="build/composeLocator/release/maven"
REGISTRY_URL="${GITLAB_REGISTRY_URL:-http://192.168.5.254/api/v4/projects/513/packages/maven}"
TOKEN_FILE="$(dirname "$0")/.deployToken"

if [ ! -d "$MAVEN_DIR" ]; then
    echo "未找到产物目录 $MAVEN_DIR，请先运行 gradle 发布任务" >&2
    exit 1
fi
if [ ! -f "$TOKEN_FILE" ]; then
    echo "缺少 $TOKEN_FILE（格式见脚本头注释），已加入 .gitignore，请本地创建" >&2
    exit 1
fi
# shellcheck disable=SC1090
source "$TOKEN_FILE"
if [ -z "${DEPLOY_USER:-}" ] || [ -z "${DEPLOY_TOKEN:-}" ]; then
    echo "$TOKEN_FILE 中 DEPLOY_USER / DEPLOY_TOKEN 不能为空" >&2
    exit 1
fi

count=0
fail=0
while IFS= read -r file; do
    rel="${file#"$MAVEN_DIR"/}"
    url="$REGISTRY_URL/$rel"
    code=$(curl -s -o /dev/null -w "%{http_code}" --user "$DEPLOY_USER:$DEPLOY_TOKEN" --upload-file "$file" "$url")
    # GitLab 对已存在的相同文件返回 204，视为成功
    if [ "$code" = "200" ] || [ "$code" = "201" ] || [ "$code" = "204" ]; then
        count=$((count + 1))
    else
        echo "上传失败 ($code): $rel" >&2
        fail=$((fail + 1))
    fi
done < <(find "$MAVEN_DIR" -type f | sort)

echo "已上传 $count 个文件到 Package Registry，失败 $fail 个"

# Studio 插件 zip 走 Generic 包仓库：项目 Public，团队成员可匿名下载安装
GENERIC_BASE="${GITLAB_GENERIC_URL:-http://192.168.5.254/api/v4/projects/513/packages/generic}"
zip_file=$(find studio-plugin/build/distributions -maxdepth 1 -name "compose-code-locator-*.zip" 2>/dev/null | head -1 || true)
if [ -n "$zip_file" ]; then
    zip_name="$(basename "$zip_file")"
    zip_version="${zip_name#compose-code-locator-}"
    zip_version="${zip_version%.zip}"
    url="$GENERIC_BASE/compose-code-locator-studio-plugin/$zip_version/$zip_name"
    code=$(curl -s -o /dev/null -w "%{http_code}" --user "$DEPLOY_USER:$DEPLOY_TOKEN" --upload-file "$zip_file" "$url")
    if [ "$code" = "200" ] || [ "$code" = "201" ]; then
        echo "Studio 插件已上传: $url"
    else
        echo "Studio 插件上传失败 ($code): $url" >&2
        exit 1
    fi
else
    echo "未找到 studio-plugin zip，跳过（先执行 ./gradlew :studio-plugin:buildPlugin）"
fi

[ "$fail" -eq 0 ]
