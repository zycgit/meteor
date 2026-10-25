#!/usr/bin/env bash
# root 负责恢复校时状态，测试命令使用原调用者身份运行。
set -euo pipefail

start_wsl_clock_owner() {
    local kernel windows_state helper script
    kernel=$(uname -r) || return 1
    if [[ ! ${kernel,,} =~ microsoft|wsl ]]; then
        printf '[clock] WSL chronyd: not applicable on this kernel.\n' >&2
        return
    fi

    if command -v powershell.exe >/dev/null 2>&1; then
        clock_powershell=$(command -v powershell.exe)
    else
        clock_powershell=/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe
    fi
    if [[ ! -x "$clock_powershell" ]]; then
        clock_pause_error='Windows PowerShell is unavailable; WSL chronyd was not paused.'
        printf '[clock] Windows PowerShell is unavailable; WSL chronyd was not paused.\n' >&2
        return 1
    fi

    # 所有控制状态留在 meteor-test/build，通过 WSL UNC 路径交给 Windows 所有者。
    clock_state=$clock_report/clock-state
    mkdir -p -- "$clock_state" || return 1
    script=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/wsl-clock-owner.ps1 || return 1
    cp -- "$script" "$clock_state/owner.ps1" || return 1
    touch "$clock_state/chrony.log" "$clock_state/owner.stdout.log" "$clock_state/owner.stderr.log" \
        "$clock_state/owner-exit-code.txt" "$clock_state/restore-check.txt" || return 1
    printf '%s\n' "$clock_state" > "$clock_state/state-path.txt"
    if [[ "$EUID" == 0 && -n ${SUDO_USER:-} && "$SUDO_USER" != root ]]; then
        chown -R -- "$SUDO_UID:$SUDO_GID" "$clock_state" || return 1
    fi
    windows_state=$(wslpath -w "$clock_state") || return 1
    helper=$(wslpath -w "$clock_state/owner.ps1") || return 1
    printf '[clock] WSL diagnostics: %s\n' "$clock_state" >&2
    printf '[clock] Requesting WSL chronyd pause; Windows administrator approval may be required.\n' >&2
    (
        trap '' INT TERM
        # 单独保存实际进程退出码，不依赖已被 wait -n 消费的作业状态。
        owner_result=0
        "$clock_powershell" -NoProfile -ExecutionPolicy Bypass -File "$helper" \
            -StateDirectory "$windows_state" > "$clock_state/owner.stdout.log" \
            2> "$clock_state/owner.stderr.log" || owner_result=$?
        printf '%s\n' "$owner_result" > "$clock_state/owner-exit-code.txt"
        exit "$owner_result"
    ) &
    clock_owner=$!
    (
        trap '' INT TERM
        exec tail --pid="$clock_owner" --sleep-interval=0.2 -q -n +1 -f \
            "$clock_state/chrony.log" "$clock_state/owner.stdout.log" "$clock_state/owner.stderr.log"
    ) >&2 &
    clock_log=$!

    # 先排除失败和退出，避免旧的 ready 标记让测试误启动。
    local attempt
    for ((attempt = 0; attempt < 600; attempt++)); do
        if [[ -f "$clock_state/failed" || -f "$clock_state/finished" ||
                -f "$clock_state/bootstrap-finished.json" ]] || ! kill -0 "$clock_owner" 2>/dev/null; then
            clock_pause_error='WSL chronyd pause failed before the owner was ready.'
            local pause_detail=
            if [[ -f "$clock_state/failure-message.txt" ]]; then
                IFS= read -r pause_detail < "$clock_state/failure-message.txt" || true
            elif [[ -s "$clock_state/owner.stderr.log" ]]; then
                IFS= read -r pause_detail < "$clock_state/owner.stderr.log" || true
            fi
            [[ -z "$pause_detail" ]] || clock_pause_error+=" $pause_detail"
            printf '[clock] WSL chronyd pause failed.\n' >&2
            return 1
        fi
        if [[ -f "$clock_state/ready" ]]; then
            printf '[clock] WSL chronyd state confirmed; clock guard is ready.\n' >&2
            return
        fi
        [[ "$clock_interrupted" == 0 ]] || return "$clock_interrupted"
        sleep 0.2
    done
    clock_pause_error='WSL chronyd pause was not confirmed within 120 seconds.'
    printf '[clock] WSL chronyd pause was not confirmed within 120 seconds.\n' >&2
    return 1
}

restore_wsl_clock_owner() {
    if [[ -z "$clock_owner" ]]; then
        return 0
    fi

    printf '[clock] Requesting WSL chronyd restoration.\n' >&2
    if ! touch "$clock_state/release"; then
        clock_restore_error="Cannot write the WSL chronyd release request: $clock_state/release."
        printf '[clock] Cannot request WSL chronyd restoration; inspect %s.\n' "$clock_state" >&2
        return 1
    fi
    if [[ "$EUID" == 0 && -n ${SUDO_USER:-} && "$SUDO_USER" != root ]] &&
            ! chown -- "$SUDO_UID:$SUDO_GID" "$clock_state/release"; then
        clock_restore_error="Cannot preserve caller ownership of the WSL release request: $clock_state/release."
        printf '[clock] %s\n' "$clock_restore_error" >&2
        return 1
    fi
    # 等待整个 Windows 所有者退出，不能在 worker 写 finished 后只给 bootstrap 五秒。
    # 恢复和身份复核各自可能打开调试壳，预算与 Windows 端的两段控制相匹配。
    local attempt owner_status=unknown wait_status=0 finished_status=missing restored_status=missing
    for ((attempt = 0; attempt < 900; attempt++)); do
        if ! kill -0 "$clock_owner" 2>/dev/null; then
            break
        fi
        if ((attempt > 0 && attempt % 75 == 0)); then
            printf '[clock] Waiting for WSL chronyd restoration and owner exit (%s seconds).\n' \
                "$((attempt / 5))" >&2
        fi
        sleep 0.2
    done
    if ! kill -0 "$clock_owner" 2>/dev/null; then
        wait "$clock_owner" || wait_status=$?
        if [[ -f "$clock_state/owner-exit-code.txt" ]]; then
            IFS= read -r owner_status < "$clock_state/owner-exit-code.txt" || true
        fi
        wait "$clock_log" || true
    else
        owner_status=running
        # 只结束日志转发；仍持有恢复职责的 Windows 进程不能被强杀。
        kill -KILL "$clock_log" 2>/dev/null || true
        wait "$clock_log" 2>/dev/null || true
    fi

    if [[ -f "$clock_state/finished" ]]; then
        finished_status=$(sed -nE 's/^.*"exitCode"[[:space:]]*:[[:space:]]*(-?[0-9]+).*$/\1/p' \
            "$clock_state/finished")
        [[ "$finished_status" =~ ^-?[0-9]+$ ]] || finished_status=invalid
    fi
    [[ ! -f "$clock_state/restored" ]] || restored_status=present
    {
        printf 'owner exit=%s\nwait exit=%s\nfinished exit=%s\nrestored=%s\n' \
            "$owner_status" "$wait_status" "$finished_status" "$restored_status"
    } > "$clock_state/restore-check.txt"
    if [[ -f "$clock_state/unchanged" && ! -f "$clock_state/changed" &&
            ( -f "$clock_state/bootstrap-finished.json" || -f "$clock_state/finished" ) ]] &&
        ! kill -0 "$clock_owner" 2>/dev/null; then
        printf '[clock] WSL chronyd was not modified; no restoration was needed.\n' >&2
        return 0
    fi
    if [[ "$owner_status" == 0 && "$finished_status" == 0 && "$restored_status" == present &&
            ! -f "$clock_state/failed" ]]; then
        printf '[clock] WSL chronyd original state restored and verified.\n' >&2
        return 0
    fi

    clock_restore_error="WSL chronyd restoration was not confirmed: owner exit=$owner_status; finished exit=$finished_status."
    if [[ "$restored_status" == missing ]]; then
        clock_restore_error+=' missing restored marker.'
    fi
    if [[ "$finished_status" == missing ]]; then
        clock_restore_error+=' missing finished marker.'
    fi
    if [[ "$owner_status" == running ]]; then
        clock_restore_error+=' Windows owner did not exit within 180 seconds.'
    fi
    if [[ -f "$clock_state/failure-message.txt" ]]; then
        local failure_message
        IFS= read -r failure_message < "$clock_state/failure-message.txt" || true
        clock_restore_error+=" $failure_message"
    elif [[ -s "$clock_state/owner.stderr.log" ]]; then
        local owner_error
        IFS= read -r owner_error < "$clock_state/owner.stderr.log" || true
        clock_restore_error+=" $owner_error"
    fi
    printf '[clock] %s\n' "$clock_restore_error" >&2
    printf '[clock] Failed to restore WSL chronyd; inspect %s.\n' "$clock_state" >&2
    return 1
}

with_clock_paused() {
    local unit=systemd-timesyncd.service initial= child= pending= status=0 child_status=not-started
    local clock_interrupted=0 timesync_modified=0 clock_powershell= clock_state= clock_owner= clock_log=
    local skip_error=${METEOR_TEST_SKIP_ERROR:-true} clock_errors=0 clock_report argument previous=
    local clock_control_error=${clock_control_error:-} clock_monitor=0 clock_repository
    local clock_pause_error='WSL chronyd pause was not confirmed.' clock_restore_error=
    for argument in "$@"; do
        if [[ "$previous" == --skipError ]]; then skip_error=$argument; fi
        if [[ "$argument" == --skipError=* ]]; then skip_error=${argument#--skipError=}; fi
        previous=$argument
    done
    if [[ "$previous" == --skipError || ( "$skip_error" != true && "$skip_error" != false ) ]]; then
        printf '[script] --skipError must be true or false.\n' >&2
        return 2
    fi
    clock_repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
    mkdir -p -- "$clock_repository/meteor-test/build/meteor-test-clock" || return 1
    clock_report=$(mktemp -d "$clock_repository/meteor-test/build/meteor-test-clock/guard.XXXXXXXX") || return 1
    : > "$clock_report/errors.tsv"
    : > "$clock_report/script-summary.md"
    : > "$clock_report/script-exit-code.txt"
    if [[ "$EUID" == 0 && -n ${SUDO_USER:-} && "$SUDO_USER" != root ]]; then
        if [[ ! ${SUDO_UID:-} =~ ^[0-9]+$ || ! ${SUDO_GID:-} =~ ^[0-9]+$ ]] ||
            ! chown -- "$SUDO_UID:$SUDO_GID" "$clock_repository/meteor-test/build/meteor-test-clock" \
                "$clock_report" "$clock_report/errors.tsv" \
                "$clock_report/script-summary.md" "$clock_report/script-exit-code.txt"; then
            printf '[script] Cannot grant the original caller access to %s.\n' "$clock_report" >&2
            return 1
        fi
    fi
    export METEOR_TEST_SCRIPT_STATE_DIR=$clock_report

    record_script_error() {
        printf '%s\t%s\n' "$1" "$2" >> "$clock_report/errors.tsv"
        clock_errors=$((clock_errors + 1))
        printf '[script][ERROR] %s: %s\n' "$1" "$2" >&2
    }
    write_script_summary() {
        local final_status=$1 stage message diagnostics=$clock_state artifacts=
        if [[ -n "$clock_state" && -f "$clock_report/artifacts" ]]; then
            IFS= read -r artifacts < "$clock_report/artifacts" || true
            [[ -z "$artifacts" ]] || diagnostics=$artifacts/clock-state
        fi
        # 报告以 meteor-test 目录为基准，控制文件仍保留完整路径。
        diagnostics=${diagnostics#"$clock_repository/meteor-test/"}
        printf '%s\n' "$final_status" > "$clock_report/script-exit-code.txt"
        {
            printf '\n## 脚本阶段\n\n'
            if [[ "$final_status" == 0 ]]; then
                printf '整轮结果：通过。\n\n'
            else
                printf '整轮结果：失败。\n\n'
            fi
            printf '脚本错误：%s；测试命令退出码：%s；最终退出码：%s。\n' \
                "$clock_errors" "$child_status" "$final_status"
            if [[ "$clock_errors" != 0 ]]; then
                printf '\n| 阶段 | 错误 |\n| --- | --- |\n'
                while IFS=$'\t' read -r stage message; do
                    printf '| %s | %s |\n' "$stage" "$message"
                done < "$clock_report/errors.tsv"
            fi
            if [[ -n "$diagnostics" ]]; then
                printf '\n校时诊断：[clock-state](<%s>)。\n' "$diagnostics"
            fi
        } > "$clock_report/script-summary.md"
    }
    publish_script_summary() {
        local final_status=$1 artifacts
        if [[ ! -f "$clock_report/artifacts" ]]; then
            [[ "$final_status" != 0 ]] || return
            # Gradle 未生成归档时也覆盖旧的通过报告，明确本轮没有 RPC 用例结果。
            (
                run_as_caller bash -c '
                    set -euo pipefail
                    printf "结果：失败\n\n本轮未生成 RPC 用例报告，仅记录脚本阶段。\n" > "$1/meteor-test/report.md"
                    cat -- "$2/script-summary.md" >> "$1/meteor-test/report.md"
                ' script-report "$clock_repository" "$clock_report"
            )
            return
        fi
        IFS= read -r artifacts < "$clock_report/artifacts" || [[ -n "$artifacts" ]] || return 1
        # 归档路径由测试命令给出，所有目标写入都在原调用者身份的子进程中完成。
        (
            run_as_caller bash -c '
                set -euo pipefail
                cp -- "$2/errors.tsv" "$1/script-errors.tsv"
                cp -- "$2/script-summary.md" "$1/script-summary.md"
                cp -- "$2/script-exit-code.txt" "$1/script-exit-code.txt"
                if [[ -d "$2/clock-state" ]]; then
                    mkdir -p -- "$1/clock-state"
                    cp -R -- "$2/clock-state/." "$1/clock-state"
                fi
                if [[ ! -f "$1/report.md" ]]; then
                    printf "# 运行脚本报告\n\n本轮未生成 RPC 用例报告，以下仅列出脚本阶段结果。\n" > "$1/report.md"
                fi
                if [[ "$4" != 0 && -f "$1/report.md" ]]; then
                    sed -i "s/^结果：通过/结果：失败（含脚本异常）/" "$1/report.md"
                fi
                cat -- "$2/script-summary.md" >> "$1/report.md"
                cp -- "$1/report.md" "$3/meteor-test/report.md"
            ' script-report "$artifacts" "$clock_report" "$clock_repository" "$clock_errors"
        )
    }

    finish_clock_guard() {
        local result=$?
        trap - EXIT
        trap '' INT TERM
        # 等待测试进程组自行清理完毕，再按暂停的相反顺序恢复校时。
        if [[ "$timesync_modified" == 1 ]]; then
            if systemctl start "$unit" && [[ $(systemctl is-active "$unit") == active ]]; then
                printf '[clock] %s: restored active.\n' "$unit" >&2
            else
                printf '[clock] Failed to restore %s to active.\n' "$unit" >&2
                record_script_error timesyncd.restore 'timesyncd did not return to the original active state.'
            fi
        elif [[ -n "$initial" && $(systemctl is-active "$unit") == "$initial" ]]; then
            printf '[clock] %s: remains %s.\n' "$unit" "$initial" >&2
        elif [[ -n "$initial" ]]; then
            printf '[clock] %s: original %s state was not preserved.\n' "$unit" "$initial" >&2
            record_script_error timesyncd.restore 'Original service state was not preserved.'
        fi
        if ! restore_wsl_clock_owner; then
            record_script_error wsl.restore "$clock_restore_error"
        fi
        [[ "$result" != 0 || "$clock_errors" == 0 ]] || result=1
        write_script_summary "$result"
        if ! publish_script_summary "$result"; then
            record_script_error report.archive 'Could not publish the script-stage report as the original caller.'
            [[ "$result" != 0 ]] || result=1
            write_script_summary "$result"
        fi
        cat -- "$clock_report/script-summary.md" >&2
        printf '[script] Script diagnostics: %s\n' "$clock_report" >&2
        exit "$result"
    }
    interrupt_clock_guard() {
        clock_interrupted=$2
        if [[ -n "$child" ]]; then
            kill -s "$1" -- "-$child" 2>/dev/null || true
        else
            pending=$1
        fi
    }
    trap finish_clock_guard EXIT
    trap 'interrupt_clock_guard INT 130' INT
    trap 'interrupt_clock_guard TERM 143' TERM

    if [[ -n "$clock_control_error" ]]; then
        record_script_error clock.lock "$clock_control_error"
        [[ "$skip_error" == true ]] || exit 1
    else
        initial=$(systemctl is-active "$unit") || true
        case "$initial" in
            active|inactive) printf '[clock] %s: original state %s.\n' "$unit" "$initial" >&2 ;;
            *)
                printf '[clock] Cannot manage %s in state %s.\n' "$unit" "$initial" >&2
                initial=
                record_script_error timesyncd.inspect 'The original timesyncd state could not be determined.'
                [[ "$skip_error" == true ]] || exit 1
                ;;
        esac
        if start_wsl_clock_owner; then
            [[ -z "$clock_owner" ]] || clock_monitor=1
        else
            record_script_error wsl.pause "$clock_pause_error"
            [[ "$skip_error" == true ]] || exit 1
        fi
    fi
    [[ "$clock_interrupted" == 0 ]] || exit "$clock_interrupted"
    if [[ "$initial" == active ]]; then
        timesync_modified=1
        if ! systemctl stop "$unit" || [[ $(systemctl is-active "$unit") != inactive ]]; then
            printf '[clock] Failed to stop %s.\n' "$unit" >&2
            record_script_error timesyncd.pause 'timesyncd did not reach the inactive state.'
            [[ "$skip_error" == true ]] || exit 1
        else
            printf '[clock] %s: stopped and verified.\n' "$unit" >&2
        fi
    fi
    [[ "$clock_interrupted" == 0 ]] || exit "$clock_interrupted"

    # 独立进程组接收中断，Gradle 仍有机会完成容器和归档清理。
    set -m
    "$@" &
    child=$!
    set +m
    [[ -z "$pending" ]] || kill -s "$pending" -- "-$child" 2>/dev/null || true
    if [[ "$clock_monitor" == 1 ]]; then
        while kill -0 "$child" 2>/dev/null; do
            wait -n "$child" "$clock_owner" || true
            kill -0 "$child" 2>/dev/null || break
            if ! kill -0 "$clock_owner" 2>/dev/null; then
                printf '[clock] WSL clock owner exited during testing.\n' >&2
                record_script_error wsl.runtime 'The WSL clock owner exited before testing finished.'
                if [[ "$skip_error" == false ]]; then
                    kill -TERM -- "-$child" 2>/dev/null || true
                fi
                break
            fi
        done
    fi
    while true; do
        if wait "$child"; then child_status=0; else child_status=$?; fi
        kill -0 "$child" 2>/dev/null || break
    done
    status=$child_status
    [[ "$clock_interrupted" == 0 ]] || status=$clock_interrupted
    exit "$status"
}

run_as_caller() {
    if [[ -n ${SUDO_USER:-} && "$SUDO_USER" != root ]]; then
        local account username uid gid user_home user_shell
        account=$(getent passwd "$SUDO_USER") || return 1
        IFS=: read -r username _ uid gid _ user_home user_shell <<< "$account"
        exec env HOME="$user_home" USER="$username" LOGNAME="$username" SHELL="$user_shell" \
            setpriv --reuid="$uid" --regid="$gid" --init-groups -- "$@"
    fi
    exec "$@"
}

main() {
    if [[ $# == 0 ]]; then
        printf 'Usage: with-clock-paused.sh COMMAND [ARGUMENTS...]\n' >&2
        return 2
    fi
    if [[ "$EUID" != 0 ]]; then
        printf '[clock] Clock control requires root; use sudo ./meteor-test/runnxn.sh.\n' >&2
        return 1
    fi
    # 从暂停到中断清理结束，当前发行版只允许一轮 sudo 测试持有控制权。
    local clock_lock clock_control_error= repository
    repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
    if ! (run_as_caller mkdir -p -- "$repository/meteor-test/build/meteor-test-clock") ||
            ! exec {clock_lock}>"$repository/meteor-test/build/meteor-test-clock/control.lock"; then
        clock_control_error='Clock-control lock could not be opened; clocks were not modified.'
    elif ! flock -n "$clock_lock"; then
        printf '[clock] Another sudo test run already owns clock control.\n' >&2
        clock_control_error='Another sudo run owns clock control; clocks were not modified.'
    fi
    with_clock_paused run_as_caller "$@"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
