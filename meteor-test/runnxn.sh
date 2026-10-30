#!/usr/bin/env bash
set -euo pipefail

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
    cat <<'EOF'
用法：./meteor-test/runnxn.sh [参数]

不传参数运行全部测试，默认 16 组并行。宿主需要 JDK 17 和 Docker（含 Compose）。
使用 sudo 启动时暂停 Linux timesyncd 和 WSL chronyd，测试及清理结束后恢复原状态。

  --groups N       并发工作组数，1 到 16
  --tests PATTERN  只运行匹配的 JUnit 类或方法，可重复
  --skipError BOOL 遇错继续执行，默认 true；false 表示遇错停止，失败仍记入报告
  --rebuild        重建工具链镜像
  --no-build       禁止构建和拉取镜像，仍构建当前源码
  -h, --help       查看帮助

例子：
  ./meteor-test/runnxn.sh
  ./meteor-test/runnxn.sh --groups 4
  ./meteor-test/runnxn.sh --tests 'net.hasor.realmeteor.protocol.grpc.*'

运行说明：meteor-test/README.md
EOF
    exit 0
fi

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# 控制目录始终归真实调用者所有，sudo 入口不以 root 身份写入调用者提供的路径。
entry_home=$HOME
entry_uid=
entry_gid=
entry_sudo=false
if [[ "$EUID" == 0 && -n ${SUDO_USER:-} && "$SUDO_USER" != root ]]; then
    entry_account=$(getent passwd "$SUDO_USER")
    IFS=: read -r entry_user _ entry_uid entry_gid _ entry_home entry_shell <<< "$entry_account"
    if [[ ! ${SUDO_UID:-} =~ ^[0-9]+$ || ! ${SUDO_GID:-} =~ ^[0-9]+$ ||
          "$entry_uid" != "$SUDO_UID" || "$entry_gid" != "$SUDO_GID" ]]; then
        printf '[entry] Cannot verify the original sudo caller.\n' >&2
        exit 1
    fi
    entry_sudo=true
fi
run_as_entry_caller() {
    if [[ "$entry_sudo" == true ]]; then
        env HOME="$entry_home" USER="$entry_user" LOGNAME="$entry_user" SHELL="$entry_shell" \
            setpriv --reuid="$entry_uid" --regid="$entry_gid" --init-groups -- "$@"
    else
        "$@"
    fi
}
# 锁、取消指针和本轮控制文件属于当前仓库，避免在用户目录留下测试状态。
entry_cache=$PWD/meteor-test/build/meteor-test-control
run_as_entry_caller mkdir -p -- "$entry_cache"
entry_control=$(run_as_entry_caller mktemp -d "$entry_cache/control.XXXXXXXX")
export METEOR_TEST_CONTROL_DIRECTORY=$entry_control
export METEOR_TEST_ENTRY_PID=$$
entry_interrupted=0
entry_child=

# 第一次中断只请求取消，Gradle 客户端继续接收归档、资源清理和校时恢复日志。
request_entry_cancel() {
    if [[ "$entry_interrupted" == 0 ]]; then
        entry_interrupted=$2
        run_as_entry_caller bash -c 'printf "%s\n" "$1" > "$2"' \
            entry-cancel "$1" "$entry_control/cancel.request"
        printf '[entry] %s requested; waiting for test archive and cleanup.\n' "$1" >&2
    else
        printf '[entry] Cancellation is already requested; waiting for cleanup.\n' >&2
    fi
}
finish_entry() {
    local result=$?
    trap - EXIT
    trap '' INT TERM
    if ! run_as_entry_caller bash -c '
        set -euo pipefail
        pointer=$1/docker.control
        if [[ -f "$pointer" ]]; then
            IFS= read -r request < "$pointer" || true
            [[ "$request" != "$2/cancel.request" ]] || rm -f -- "$pointer"
        fi
        rm -rf -- "$2"
    ' entry-cleanup "$entry_cache" "$entry_control"; then
        printf '[entry] Failed to remove this entry control state.\n' >&2
        [[ "$result" != 0 ]] || result=1
    fi
    exit "$result"
}
trap finish_entry EXIT
trap 'request_entry_cancel INT 130' INT
trap 'request_entry_cancel TERM 143' TERM

# 固定环境有旧所有者时先请求它退出，避免新一轮在归档过程中拆除旧容器。
if [[ -f "$entry_cache/docker.control" ]]; then
    previous_control=$(run_as_entry_caller cat -- "$entry_cache/docker.control")
    {
        IFS= read -r previous_request || true
        IFS= read -r previous_pid || true
    } <<< "$previous_control"
    previous_directory=${previous_request%/cancel.request}
    if [[ "$previous_request" == "$entry_cache"/control.*/cancel.request &&
          "${previous_directory%/*}" == "$entry_cache" && -d "$previous_directory" ]]; then
        run_as_entry_caller bash -c 'printf "RESTART\n" > "$1"' entry-restart "$previous_request"
        printf '[entry] Previous test cancellation requested.\n' >&2
        if [[ "$previous_pid" =~ ^[1-9][0-9]*$ && "$previous_pid" != "$$" ]]; then
            previous_deadline=$((SECONDS + 600))
            previous_notice=$((SECONDS + 15))
            while ps -p "$previous_pid" > /dev/null 2>&1; do
                if (( SECONDS >= previous_deadline )); then
                    printf '[entry] Previous test did not finish cleanup within 600 seconds; startup refused.\n' >&2
                    exit 1
                fi
                if (( SECONDS >= previous_notice )); then
                    printf '[entry] Waiting for previous test %s to finish archive and cleanup.\n' "$previous_pid" >&2
                    previous_notice=$((SECONDS + 15))
                fi
                [[ "$entry_interrupted" == 0 ]] || exit "$entry_interrupted"
                sleep 1
            done
        fi
    else
        printf '[entry] Ignoring an invalid previous control path; Gradle will verify environment ownership.\n' >&2
    fi
fi
[[ "$entry_interrupted" == 0 ]] || exit "$entry_interrupted"

# 校时暂停和 Gradle 配置之前即登记入口，重启时也能找到尚未进入任务的旧轮。
run_as_entry_caller bash -c '
    set -euo pipefail
    pointer=$(mktemp "$1/docker.control.XXXXXXXX")
    trap '\''rm -f -- "$pointer"'\'' EXIT
    printf "%s/cancel.request\n%s\n" "$2" "$3" > "$pointer"
    mv -f -- "$pointer" "$1/docker.control"
' entry-publish "$entry_cache" "$entry_control" "$$"
[[ "$entry_interrupted" == 0 ]] || exit "$entry_interrupted"

# 独立进程组隔离终端广播信号，所有中断统一经控制文件交给测试任务。
set -m
if [[ "$EUID" == 0 && -n ${SUDO_USER:-} ]]; then
    bash ./meteor-test/tools/clock/with-clock-paused.sh bash ./gradlew :meteor-test:dockerTest "$@" < /dev/null &
else
    bash ./gradlew :meteor-test:dockerTest "$@" < /dev/null &
fi
entry_child=$!
set +m
while true; do
    if wait "$entry_child"; then entry_result=0; else entry_result=$?; fi
    kill -0 "$entry_child" 2>/dev/null || break
done
if [[ "$entry_interrupted" == 0 && -f "$entry_control/cancel.request" ]]; then
    entry_request=$(run_as_entry_caller cat -- "$entry_control/cancel.request") || entry_request=
    case "$entry_request" in
        INT|RESTART) entry_interrupted=130 ;;
        TERM) entry_interrupted=143 ;;
    esac
fi
[[ "$entry_interrupted" == 0 ]] || entry_result=$entry_interrupted
exit "$entry_result"
