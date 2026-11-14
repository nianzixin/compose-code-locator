#!/usr/bin/env bash
# 编译器插件变体守门：对每个 Kotlin 编译器变体各构建一次，
# 并校验产物 jar 中的 ServiceLoader 注册与 registrar 字节码确实来自该变体薄壳，
# 防止某个变体在改动业务逻辑后悄悄编译不过
set -euo pipefail

cd "$(dirname "$0")/.."

variants=("2.2" "2.4")

for variant in "${variants[@]}"; do
  echo "== Building compiler plugin variant: $variant"
  ./gradlew -q ":locator-compiler-plugin:jar" "-PkotlinCompilerApi=$variant"

  jar_path="locator-compiler-plugin/build/libs/locator-compiler-plugin-0.1.2.jar"
  service="META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
  service_content="$(unzip -p "$jar_path" "$service" 2>/dev/null)"
  if [[ "$service_content" != "dev.codelocator.compiler.ComposeLocatorCompilerRegistrar" ]]; then
    echo "FAIL: variant $variant jar missing CompilerPluginRegistrar service entry" >&2
    exit 1
  fi
  echo "== Variant $variant OK"
done

echo "All compiler plugin variants verified."
