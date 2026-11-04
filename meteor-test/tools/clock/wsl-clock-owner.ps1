param(
    [Parameter(Mandatory = $true)]
    [string] $StateDirectory,
    [switch] $Elevated
)

$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$logPath = Join-Path $StateDirectory 'chrony.log'
$releasePath = Join-Path $StateDirectory 'release'
$exitCode = 0
$modified = $false
$original = $null
$mutex = $null
$ownsMutex = $false
$bootstrap = $false
$elevationRequested = $false
$launchRejected = $false
$workerLaunched = $false
$workerExited = $false
$workerPid = $null
$stateAccessible = $false
$script:failureStage = 'startup'
$script:uncertainControl = $false
$script:debugShell = $null

function Write-ClockLog([string] $Message) {
    $line = '[WSL chronyd] ' + $Message + [Environment]::NewLine
    [System.IO.File]::AppendAllText($logPath, $line, $utf8)
    # 外层已转发日志；管理员控制台处于选择模式时，Write-Host 会阻塞恢复。
}

function Write-Marker([string] $Name, [object] $Value) {
    $target = Join-Path $StateDirectory $Name
    $temporary = $target + '.writing-' + $PID
    $content = ConvertTo-Json -InputObject $Value -Compress
    [System.IO.File]::WriteAllText($temporary, $content + [Environment]::NewLine, $utf8)
    if ([System.IO.File]::Exists($target)) {
        [System.IO.File]::Delete($target)
    }
    [System.IO.File]::Move($temporary, $target)
}

function Write-Failure([object] $Failure) {
    $details = ($Failure | Format-List * -Force | Out-String) + $Failure.Exception.ToString()
    $message = $script:failureStage + ': ' + $Failure.Exception.Message
    $message = $message.Replace("`r", ' ').Replace("`n", ' ')
    try {
        [System.IO.File]::AppendAllText((Join-Path $StateDirectory 'failed'), $details, $utf8)
        $summary = Join-Path $StateDirectory 'failure-message.txt'
        if (-not $bootstrap -or -not [System.IO.File]::Exists($summary)) {
            [System.IO.File]::WriteAllText($summary, $message + "`n", $utf8)
        }
        Write-ClockLog $details
    } catch {
        # 已开始控制 chronyd 后不写管理员控制台，避免其选择模式阻塞恢复。
        if (-not $stateAccessible) {
            [Console]::Error.WriteLine($message + [Environment]::NewLine + $details +
                [Environment]::NewLine + 'Cannot retain clock failure in the state directory: ' + $_.Exception.ToString())
        }
    }
}

function Quote-WindowsArgument([string] $Value) {
    $result = New-Object System.Text.StringBuilder
    [void] $result.Append('"')
    $slashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') {
            $slashes++
            continue
        }
        if ($character -eq '"') {
            [void] $result.Append(('\' * (2 * $slashes + 1)))
        } else {
            [void] $result.Append(('\' * $slashes))
        }
        [void] $result.Append($character)
        $slashes = 0
    }
    [void] $result.Append(('\' * (2 * $slashes)))
    [void] $result.Append('"')
    return $result.ToString()
}

function Open-DebugShell([int] $TimeoutMilliseconds = 30000) {
    # 整轮只持有一个调试会话，检查、暂停和恢复均通过该连接串行执行。
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = Join-Path $env:WINDIR 'System32\wsl.exe'
    $info.Arguments = '--debug-shell'
    $info.UseShellExecute = $false
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    [void] $process.Start()
    $process.StandardInput.AutoFlush = $true
    $script:debugShell = @{
        Process = $process
        Errors = $process.StandardError.ReadToEndAsync()
        Buffer = New-Object char[] 2048
        PendingRead = $null
        PendingWrite = $null
        LastConfirmed = $false
    }
    $script:failureStage = 'debug:login'
    Write-ClockLog ('Opened the owned WSL debug client: PID=' + $process.Id + '.')
    $transcript = New-Object System.Text.StringBuilder
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.ElapsedMilliseconds -lt $TimeoutMilliseconds) {
        $script:debugShell.PendingRead = $process.StandardOutput.ReadAsync($script:debugShell.Buffer, 0, 2048)
        $remaining = [Math]::Max(1, $TimeoutMilliseconds - [int] $watch.ElapsedMilliseconds)
        if (-not $script:debugShell.PendingRead.Wait($remaining)) { break }
        $count = $script:debugShell.PendingRead.Result
        $script:debugShell.PendingRead = $null
        if ($count -eq 0) {
            throw 'WSL debug-shell login ended with EOF; see chrony.log and the client exit diagnostics.'
        }
        $chunk = New-Object string($script:debugShell.Buffer, 0, $count)
        [void] $transcript.Append($chunk)
        [System.IO.File]::AppendAllText($logPath, $chunk, $utf8)
        $normalized = [regex]::Replace($transcript.ToString(), '\x1b\[[0-9;?]*[ -/]*[@-~]', '')
        if ($normalized -match '(?m)[^\r\n]*#\s*$') { return }
    }
    throw 'WSL debug-shell login timed out before the root prompt; see chrony.log.'
}

function Invoke-DebugShell([string] $Commands, [string] $Completion, [switch] $Control,
        [int] $TimeoutMilliseconds = 30000) {
    # 每轮命令独立收集确认，避免复用会话中的同名阶段互相匹配。
    if ($null -eq $script:debugShell) { throw 'The owned WSL debug session is not open.' }
    $process = $script:debugShell.Process
    if ($process.HasExited) {
        throw ('The owned WSL debug client exited before ' + $Completion + ': exit=' + $process.ExitCode + '.')
    }
    $resultMarker = 'METEOR_COMMAND_END_' + [Guid]::NewGuid().ToString('N')
    $transcript = New-Object System.Text.StringBuilder
    $normalized = ''
    $remoteExitCode = $null
    $commandsSent = $false
    $endReason = 'EOF'
    $script:failureStage = 'debug:' + $Completion + ':command'
    $script:debugShell.LastConfirmed = $false
    try {
        # 保持外层 root shell 和 stdin 打开，只结束当前受控子 shell。
        $delimiter = 'METEOR_SCRIPT_' + [Guid]::NewGuid().ToString('N')
        $payload = "sh <<'" + $delimiter + "'`n" + $Commands.Replace("`r`n", "`n") + "`n" +
            $delimiter + "`nmeteor_status=`$?`nprintf '\n" + $resultMarker + ":%s\n' `"`$meteor_status`"`n"
        # 发送与读取共用预算，并持续读取输出，避免两端管道相互等待。
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        Write-ClockLog ('Sending debug command ' + $Completion + ': client PID=' + $process.Id + '.')
        $script:failureStage = 'debug:' + $Completion + ':send'
        $commandsSent = $true
        $script:debugShell.PendingWrite = $process.StandardInput.WriteAsync($payload)
        while ($watch.ElapsedMilliseconds -lt $TimeoutMilliseconds) {
            if ($null -ne $script:debugShell.PendingWrite -and $script:debugShell.PendingWrite.IsCompleted) {
                [void] $script:debugShell.PendingWrite.GetAwaiter().GetResult()
                $script:debugShell.PendingWrite = $null
                $script:failureStage = 'debug:' + $Completion + ':response'
                Write-ClockLog ('Sent debug command ' + $Completion + ': client PID=' + $process.Id + '.')
            }
            if ($null -eq $script:debugShell.PendingRead) {
                $script:debugShell.PendingRead = $process.StandardOutput.ReadAsync($script:debugShell.Buffer, 0, 2048)
            }
            $remaining = [Math]::Max(1, $TimeoutMilliseconds - [int] $watch.ElapsedMilliseconds)
            if ($null -ne $script:debugShell.PendingWrite) {
                $pending = [System.Threading.Tasks.Task[]] @($script:debugShell.PendingRead, $script:debugShell.PendingWrite)
                if ([System.Threading.Tasks.Task]::WaitAny($pending, $remaining) -eq -1) {
                    $endReason = 'timeout'
                    break
                }
                if (-not $script:debugShell.PendingRead.IsCompleted) { continue }
            } elseif (-not $script:debugShell.PendingRead.Wait($remaining)) {
                $endReason = 'timeout'
                break
            }
            $count = $script:debugShell.PendingRead.Result
            $script:debugShell.PendingRead = $null
            if ($count -eq 0) { break }
            $chunk = New-Object string($script:debugShell.Buffer, 0, $count)
            [void] $transcript.Append($chunk)
            [System.IO.File]::AppendAllText($logPath, $chunk, $utf8)
            $normalized = [regex]::Replace($transcript.ToString(), '\x1b\[[0-9;?]*[ -/]*[@-~]', '')
            $result = [regex]::Match($normalized,
                '(?m)^' + [regex]::Escape($resultMarker) + ':(\d+)\r?$')
            if ($result.Success) {
                $remoteExitCode = [int] $result.Groups[1].Value
                break
            }
        }
        if ($null -eq $remoteExitCode) {
            if ($watch.ElapsedMilliseconds -ge $TimeoutMilliseconds) { $endReason = 'timeout' }
            $clientExitCode = if ($process.HasExited) { $process.ExitCode } else { 'running' }
            throw ('WSL command ' + $Completion + ' ended with ' + $endReason +
                ' before confirmation; client exit=' + $clientExitCode + '; see chrony.log.')
        }
        if ($null -ne $script:debugShell.PendingWrite) {
            $remaining = [Math]::Max(1, $TimeoutMilliseconds - [int] $watch.ElapsedMilliseconds)
            if (-not $script:debugShell.PendingWrite.Wait($remaining)) {
                throw ('WSL command ' + $Completion + ' send timed out before completion; see chrony.log.')
            }
            [void] $script:debugShell.PendingWrite.GetAwaiter().GetResult()
            $script:debugShell.PendingWrite = $null
            $script:failureStage = 'debug:' + $Completion + ':response'
            Write-ClockLog ('Sent debug command ' + $Completion + ': client PID=' + $process.Id + '.')
        }
        Write-ClockLog ('Debug command ' + $Completion + ': client PID=' + $process.Id + ', remote exit=' + $remoteExitCode + '.')
        if ($remoteExitCode -ne 0) { throw ('WSL command ' + $Completion + ' failed with exit code ' + $remoteExitCode + '.') }
        $markerPattern = '(?m)^' + [regex]::Escape($Completion) + '\r?$'
        if ($normalized -notmatch $markerPattern) { throw ('WSL command omitted its ' + $Completion + ' confirmation.') }
        $script:debugShell.LastConfirmed = $true
        return $normalized
    } finally {
        if ($Control -and $commandsSent -and $null -eq $remoteExitCode) { $script:uncertainControl = $true }
    }
}

function Close-DebugShell {
    if ($null -eq $script:debugShell) { return }
    $script:failureStage = 'debug:disconnect'
    $owned = $script:debugShell
    $process = $owned.Process
    $expectedDisconnect = $false
    $remainingOutput = $null
    try {
        # 先排空输出再等待 exit 发送，避免对端先写尾部日志时两根管道互等。
        if ($null -eq $owned.PendingRead) { $remainingOutput = $process.StandardOutput.ReadToEndAsync() }
        # 所有远端业务阶段结束后，才退出 root shell 并释放本地传输客户端。
        if (-not $process.HasExited) {
            if ($null -eq $owned.PendingWrite) {
                $owned.PendingWrite = $process.StandardInput.WriteAsync("exit`n")
            }
            if (-not $owned.PendingWrite.Wait(1000)) {
                $expectedDisconnect = $owned.LastConfirmed
                $process.Kill()
            }
        }
        if (-not $process.WaitForExit(1000) -and -not $process.HasExited) {
            $expectedDisconnect = $owned.LastConfirmed
            $process.Kill()
        }
        if (-not $process.WaitForExit(5000)) { throw 'The owned WSL debug client did not disconnect.' }
        if ($null -ne $owned.PendingRead) {
            if (-not $owned.PendingRead.Wait(2000)) { throw 'The owned WSL stdout read did not finish.' }
            if ($owned.PendingRead.Result -gt 0) {
                $chunk = New-Object string($owned.Buffer, 0, $owned.PendingRead.Result)
                [System.IO.File]::AppendAllText($logPath, $chunk, $utf8)
            }
            $remainingOutput = $process.StandardOutput.ReadToEndAsync()
        }
        if (-not $remainingOutput.Wait(2000)) { throw 'The owned WSL stdout could not be drained.' }
        [System.IO.File]::AppendAllText($logPath, $remainingOutput.Result, $utf8)
        if (-not $owned.Errors.Wait(2000)) { throw 'The owned WSL stderr could not be drained.' }
        [System.IO.File]::AppendAllText($logPath, $owned.Errors.Result, $utf8)
        $clientExitCode = $process.ExitCode
        if ($clientExitCode -ne -1) { $expectedDisconnect = $false }
        Write-ClockLog ('Closed the owned WSL debug client: PID=' + $process.Id + ', client exit=' + $clientExitCode +
            ', expectedDisconnect=' + $expectedDisconnect.ToString().ToLowerInvariant() + '.')
        if ($clientExitCode -ne 0 -and -not $expectedDisconnect) {
            throw ('The owned WSL debug client exited unexpectedly with code ' + $clientExitCode + '.')
        }
    } finally {
        if (-not $process.HasExited) { $process.Kill(); [void] $process.WaitForExit(5000) }
        $process.Dispose()
        $script:debugShell = $null
    }
}

function Read-DaemonState {
    # 同时读取进程号、启动标识和状态，后续只允许控制同一进程。
    $output = Invoke-DebugShell -Completion 'METEOR_CHRONY_INSPECTED' -Commands @'
set -eu
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
daemon_pid=$(pidof chronyd || true)
if [ -z "$daemon_pid" ]; then
    printf '\nMETEOR_CHRONY_STATE:absent\n'
else
    case "$daemon_pid" in *[!0-9]*) exit 1;; esac
    [ "$(cat /proc/$daemon_pid/comm)" = chronyd ]
    daemon_start=$(awk '{print $22}' /proc/$daemon_pid/stat)
    daemon_state=$(awk '{print $3}' /proc/$daemon_pid/stat)
    printf '\nMETEOR_CHRONY_STATE:%s:%s:%s\n' "$daemon_pid" "$daemon_start" "$daemon_state"
fi
printf '\n%s%s\n' 'METEOR_CHRONY_' 'INSPECTED'
'@
    if ($output -match '(?m)^METEOR_CHRONY_STATE:absent\r?$') {
        return [PSCustomObject] @{ Present = $false; DaemonPid = ''; StartTicks = ''; State = 'absent' }
    }
    $identity = [regex]::Match($output, '(?m)^METEOR_CHRONY_STATE:(\d+):(\d+):([A-Za-z])\r?$')
    if (-not $identity.Success) { throw 'A single WSL chronyd process identity was not confirmed.' }
    if ($identity.Groups[3].Value -match '^[ZXx]$') { throw 'WSL chronyd is not a live daemon.' }
    return [PSCustomObject] @{
        Present = $true
        DaemonPid = $identity.Groups[1].Value
        StartTicks = $identity.Groups[2].Value
        State = $identity.Groups[3].Value
    }
}

function Assert-OriginalIdentity([object] $Current) {
    if ($original.Present -ne $Current.Present) {
        throw 'WSL chronyd presence changed; original state cannot be verified.'
    }
    if ($original.Present -and ($original.DaemonPid -ne $Current.DaemonPid -or
            $original.StartTicks -ne $Current.StartTicks)) {
        throw 'WSL chronyd identity changed; refusing to signal another process.'
    }
}

try {
    # 状态由外层在仓库 build 中创建；UNC 不可读写时直接输出原始错误。
    if (-not [System.IO.Directory]::Exists($StateDirectory)) { throw 'The existing clock state directory is not accessible.' }
    $probe = Join-Path $StateDirectory ('.access-check-' + $PID)
    try {
        [System.IO.File]::WriteAllText($probe, 'meteor-test-clock', $utf8)
        if ([System.IO.File]::ReadAllText($probe, $utf8) -ne 'meteor-test-clock') {
            throw 'The clock state directory failed its read/write check.'
        }
    } finally {
        if ([System.IO.File]::Exists($probe)) { [System.IO.File]::Delete($probe) }
    }
    $stateAccessible = $true
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object System.Security.Principal.WindowsPrincipal($identity)
    $administrator = $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)

    # Linux sudo 不包含 Windows 管理员权限，单独申请 Windows 提权。
    if (-not $administrator) {
        $bootstrap = $true
        Write-Marker 'bootstrap-started.json' @{ processPid = $PID }
        if ($Elevated) { throw 'Windows administrator privileges were not granted.' }
        Write-ClockLog 'Requesting Windows administrator approval to pause WSL chronyd.'
        $shell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $arguments = '-NoProfile -ExecutionPolicy Bypass -File ' + (Quote-WindowsArgument $PSCommandPath) +
            ' -StateDirectory ' + (Quote-WindowsArgument $StateDirectory) + ' -Elevated'
        $elevationRequested = $true
        try {
            $worker = Start-Process -FilePath $shell -ArgumentList $arguments -Verb RunAs -PassThru
            $workerLaunched = $true
            $workerPid = $worker.Id
            Write-Marker 'elevated-launched.json' @{ processPid = $workerPid; bootstrapPid = $PID }
            $worker.WaitForExit()
            $workerExited = $true
            $worker.Refresh()
        } catch {
            $cause = $_.Exception
            while ($null -ne $cause) {
                if (-not $workerLaunched -and $cause -is [System.ComponentModel.Win32Exception]) {
                    $launchRejected = $true
                }
                $cause = $cause.InnerException
            }
            throw
        }
        $exitCode = $worker.ExitCode
        Write-Marker 'elevated-exit-observed.json' @{ processPid = $workerPid; exitCode = $exitCode }
        if ($exitCode -ne 0) {
            throw ('The elevated WSL clock owner failed with exit code ' + $exitCode + '. State directory: ' +
                $StateDirectory + '. Verify that the elevated Windows user can access this UNC path; ' +
                'see failed and chrony.log, or the elevated window if that directory was inaccessible.')
        }
        if (-not (Test-Path -LiteralPath (Join-Path $StateDirectory 'finished'))) {
            throw 'The elevated owner exited without its finished marker; restoration is not confirmed.'
        }
    } else {
        Write-Marker 'elevated-started.json' @{ processPid = $PID }
        $mutex = New-Object System.Threading.Mutex($false, 'Global\MeteorTestWslClock')
        try {
            $ownsMutex = $mutex.WaitOne(0)
        } catch [System.Threading.AbandonedMutexException] {
            $ownsMutex = $true
            Write-ClockLog 'The previous Windows clock owner exited without releasing its mutex.'
        }
        if (-not $ownsMutex) { throw 'Another meteor-test run owns the WSL chronyd pause.' }
        Open-DebugShell

        # 记录原状态，仅暂停本轮确认仍在运行的原进程。
        $script:failureStage = 'inspect'
        $original = Read-DaemonState
        Write-ClockLog ('Original state: PID=' + $original.DaemonPid + ', start ticks=' +
            $original.StartTicks + ', state=' + $original.State + '.')
        if ($original.Present -and $original.State -notmatch '^[Tt]$' -and
                -not (Test-Path -LiteralPath $releasePath)) {
            $pause = @'
set -eu
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
[ "$(cat /proc/__PID__/comm)" = chronyd ]
[ "$(awk '{print $22}' /proc/__PID__/stat)" = __START__ ]
kill -STOP __PID__
attempt=0
while [ "$attempt" -lt 20 ]; do
    case "$(awk '{print $3}' /proc/__PID__/stat)" in T|t) break;; esac
    attempt=$((attempt + 1))
    sleep 0.05
done
case "$(awk '{print $3}' /proc/__PID__/stat)" in T|t) ;; *) exit 1;; esac
printf '\n%s%s\n' 'METEOR_CHRONY_' 'PAUSED'
'@
            $pause = $pause.Replace('__PID__', $original.DaemonPid).Replace('__START__', $original.StartTicks)
            $script:failureStage = 'pause'
            Write-Marker 'changed' @{ originalPid = $original.DaemonPid; originalStartTicks = $original.StartTicks }
            $modified = $true
            [void] (Invoke-DebugShell -Commands $pause -Completion 'METEOR_CHRONY_PAUSED' -Control)
            Write-ClockLog 'Paused WSL chronyd and verified its stopped state.'
        } elseif (-not $original.Present) {
            Write-ClockLog 'WSL chronyd is absent; no process was changed.'
        } elseif ($original.State -match '^[Tt]$') {
            Write-ClockLog 'WSL chronyd was already stopped; its original state is retained.'
        } else {
            Write-ClockLog 'Release was requested before pausing; no process was changed.'
        }

        # 准备完成后等待外层释放，使用单调时钟限制持有时间。
        Write-Marker 'ready' @{
            originalPid = $original.DaemonPid
            originalStartTicks = $original.StartTicks
            originalState = $original.State
            modified = $modified
        }
        $watchdog = [System.Diagnostics.Stopwatch]::StartNew()
        $script:failureStage = 'waiting-for-release'
        while (-not (Test-Path -LiteralPath $releasePath)) {
            if ($watchdog.Elapsed.TotalMinutes -ge 90) { throw 'The 90-minute WSL clock owner watchdog expired.' }
            Start-Sleep -Milliseconds 250
        }
        Write-ClockLog 'Release requested; restoring the original WSL chronyd state.'
    }
} catch {
    $exitCode = 1
    Write-Failure $_
} finally {
    # 无论测试结束还是控制失败，均核验并恢复本轮修改的原进程。
    if ($ownsMutex -and $null -ne $original) {
        try {
            $script:failureStage = 'restore'
            if ($modified) {
                $resume = @'
set -eu
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
[ "$(cat /proc/__PID__/comm)" = chronyd ]
[ "$(awk '{print $22}' /proc/__PID__/stat)" = __START__ ]
kill -CONT __PID__
attempt=0
while [ "$attempt" -lt 20 ]; do
    case "$(awk '{print $3}' /proc/__PID__/stat)" in T|t) ;; *) break;; esac
    attempt=$((attempt + 1))
    sleep 0.05
done
case "$(awk '{print $3}' /proc/__PID__/stat)" in T|t) exit 1;; esac
printf '\n%s%s\n' 'METEOR_CHRONY_' 'RESUMED'
'@
                $resume = $resume.Replace('__PID__', $original.DaemonPid).Replace('__START__', $original.StartTicks)
                [void] (Invoke-DebugShell -Commands $resume -Completion 'METEOR_CHRONY_RESUMED' -Control)
            }
            $current = Read-DaemonState
            Assert-OriginalIdentity $current
            if ($original.Present) {
                $wasStopped = $original.State -match '^[Tt]$'
                $isStopped = $current.State -match '^[Tt]$'
                if ($wasStopped -ne $isStopped) {
                    throw 'The original WSL chronyd stopped/running state was not restored.'
                }
            }
            if ($script:uncertainControl) {
                throw 'A control command was not confirmed; manually verify restoration in chrony.log.'
            }
            Write-Marker 'restored' @{ originalPid = $original.DaemonPid; originalState = $original.State }
            Write-ClockLog 'Verified that the original WSL chronyd state is restored.'
        } catch {
            $exitCode = 1
            Write-Failure $_
            if ($modified) {
                Write-Marker 'restoration-required.json' @{
                    originalPid = $original.DaemonPid
                    originalStartTicks = $original.StartTicks
                    reason = $_.Exception.Message
                }
                Write-ClockLog ('RESTORATION REQUIRED: chronyd PID=' + $original.DaemonPid + ', start ticks=' +
                    $original.StartTicks + ' was modified, but its original state was not verified. Inspect chrony.log.')
            }
        }
    }
    try {
        Close-DebugShell
    } catch {
        $exitCode = 1
        Write-Failure $_
    }
    try {
        if ($ownsMutex) { $mutex.ReleaseMutex() }
        if ($null -ne $mutex) { $mutex.Dispose() }
    } catch {
        $exitCode = 1
        Write-Failure $_
    } finally {
        if ($stateAccessible) {
            if ($bootstrap) {
                # 引导退出与实际控制进程退出分开记录，不能覆盖控制者的完成信号。
                if (-not $elevationRequested -or $launchRejected) {
                    Write-Marker 'unchanged' @{ modified = $false }
                    Write-ClockLog 'Windows elevation did not launch an owner; chronyd was not modified.'
                }
                Write-Marker 'bootstrap-finished.json' @{
                    processPid = $PID
                    exitCode = $exitCode
                    workerPid = $workerPid
                    workerLaunched = $workerLaunched
                    workerExited = $workerExited
                }
                if ($workerLaunched -and -not $workerExited) {
                    Write-ClockLog 'The elevated owner has not confirmed exit; waiting for its restoration marker is required.'
                }
            } else {
                # finished 只由实际控制者在恢复与资源释放结束后写入。
                Write-Marker 'finished' @{
                    processPid = $PID
                    exitCode = $exitCode
                    modified = $modified
                    restored = (Test-Path -LiteralPath (Join-Path $StateDirectory 'restored'))
                }
            }
        }
    }
}
exit $exitCode
