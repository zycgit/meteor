#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

usage() {
    cat <<'EOF'
Usage:
  ./build.sh web [gradle options...]

Commands:
  web    Build console frontend and copy it into backend resources.
  help   Show this help message.

Examples:
  ./build.sh web
  ./build.sh web --rerun-tasks
EOF
}

target="${1:-help}"
if [[ "$#" -gt 0 ]]; then
    shift
fi

case "$target" in
    web)
        ./gradlew :meteor-console:webBuild :meteor-console:processResources "$@"
        ;;
    help|-h|--help)
        usage
        ;;
    *)
        echo "Unknown console build target: $target" >&2
        usage >&2
        exit 1
        ;;
esac
