#!/usr/bin/env bash
set -euo pipefail

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
    cat <<'EOF'
用法：./meteor-test/build_report.sh

从 meteor-test/build/docker-runs/ 重新生成 meteor-test/report.md，不重新执行测试。
EOF
    exit 0
fi

if [[ $# != 0 ]]; then
    printf '不支持参数，请直接运行 ./meteor-test/build_report.sh\n' >&2
    exit 2
fi

cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec bash ./gradlew :meteor-test:nxnReport
