# Windows PowerShell 5.1 and PowerShell 7; all language toolchains run in Linux containers.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$launcherArguments = @($args)
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$noBuild = $false
$keepWorkspace = $false
$prepareOnly = $false
$platform = [Environment]::GetEnvironmentVariable('METEOR_TEST_PLATFORM')
$mavenSeed = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository/net/hasor'
$seedExplicit = $false
$gradleArguments = @()

# Native stderr is diagnostic output, including Docker build progress; exit status decides failure.
function Invoke-Docker {
    param([string[]]$DockerArguments, [string]$LogPath, [switch]$AllowFailure)
    $previousPreference = $ErrorActionPreference
    $previousEncoding = [Console]::OutputEncoding
    $encoding = [Text.UTF8Encoding]::new($false)
    $recentOutput = [Collections.Generic.Queue[string]]::new()
    $writer = $null
    $ErrorActionPreference = 'Continue'
    try {
        [Console]::OutputEncoding = $encoding
        if ($LogPath) {
            $writer = [IO.StreamWriter]::new($LogPath, $false, $encoding)
            $writer.NewLine = "`n"
            $writer.AutoFlush = $true
        }
        & docker @DockerArguments 2>&1 | ForEach-Object {
            $line = "$_"
            if ($writer) { $writer.WriteLine($line) }
            $recentOutput.Enqueue($line)
            if ($recentOutput.Count -gt 20) { $null = $recentOutput.Dequeue() }
            Write-Output $line
        }
        $script:DockerExitCode = $LASTEXITCODE
    } finally {
        if ($writer) { $writer.Dispose() }
        [Console]::OutputEncoding = $previousEncoding
        $ErrorActionPreference = $previousPreference
    }
    if ($script:DockerExitCode -ne 0 -and -not $AllowFailure) {
        $message = "Docker failed with exit code $script:DockerExitCode ($($DockerArguments[0]))."
        if ($LogPath) { $message += " Log: $LogPath" }
        if ($recentOutput.Count -gt 0) { $message += "`n" + ($recentOutput.ToArray() -join "`n") }
        throw $message
    }
}

function Write-Utf8 {
    param([string]$Path, [string[]]$Lines)
    [IO.File]::WriteAllText($Path, (($Lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding($false)))
}

for ($index = 0; $index -lt $launcherArguments.Count; $index++) {
    $argument = $launcherArguments[$index]
    switch ($argument) {
        '--no-build' { $noBuild = $true; continue }
        '--keep-workspace' { $keepWorkspace = $true; continue }
        '--prepare-only' { $prepareOnly = $true; continue }
        { $_ -eq '--platform' -or $_ -eq '--maven-seed' } {
            if ($index + 1 -ge $launcherArguments.Count) { throw "Missing value for $argument." }
            $index++
            if ($argument -eq '--platform') { $platform = $launcherArguments[$index] }
            else { $mavenSeed = $launcherArguments[$index]; $seedExplicit = $true }
            continue
        }
        { $_ -eq '--help' -or $_ -eq '-h' } {
            Write-Host 'Usage: run.ps1 [--no-build] [--keep-workspace] [--prepare-only]'
            Write-Host '               [--platform linux/amd64|linux/arm64] [--maven-seed PATH] [-- GRADLE_ARGS...]'
            Write-Host 'Default: :meteor-test:test --no-daemon --console=plain --rerun-tasks'
            exit 0
        }
        '--' {
            if ($index + 1 -lt $launcherArguments.Count) {
                $gradleArguments = @($launcherArguments[($index + 1)..($launcherArguments.Count - 1)])
            }
            $index = $launcherArguments.Count
            continue
        }
        default {
            $gradleArguments = @($launcherArguments[$index..($launcherArguments.Count - 1)])
            $index = $launcherArguments.Count
        }
    }
}
if ($gradleArguments.Count -eq 0) {
    $gradleArguments = @(':meteor-test:test', '--no-daemon', '--console=plain', '--rerun-tasks')
}

# Windows and macOS use Docker Desktop's Linux VM; Linux uses the selected Docker context.
$null = Get-Command docker -ErrorAction Stop
$null = Invoke-Docker -DockerArguments @('compose', 'version')
$daemonOs = (Invoke-Docker -DockerArguments @('info', '--format', '{{.OSType}}')) -join ''
if ($daemonOs -ne 'linux') { throw 'Switch Docker to Linux containers.' }
if (-not $platform) {
    $architecture = (Invoke-Docker -DockerArguments @('info', '--format', '{{.Architecture}}')) -join ''
    switch ($architecture) {
        { $_ -eq 'amd64' -or $_ -eq 'x86_64' } { $platform = 'linux/amd64' }
        { $_ -eq 'arm64' -or $_ -eq 'aarch64' } { $platform = 'linux/arm64' }
        default { throw "Unsupported Docker architecture: $architecture" }
    }
}
if ($platform -notin @('linux/amd64', 'linux/arm64')) { throw "Unsupported platform: $platform" }
$architecture = $platform.Substring(6)
$cacheKey = [Environment]::GetEnvironmentVariable('METEOR_TEST_CACHE_KEY')
if (-not $cacheKey) { $cacheKey = $architecture }
if ($cacheKey -cnotmatch '\A[a-zA-Z0-9][a-zA-Z0-9_.-]*\z') {
    throw 'Invalid METEOR_TEST_CACHE_KEY: use letters, digits, dots, underscores or hyphens, starting with a letter or digit.'
}
$runId = (Get-Date).ToUniversalTime().ToString("yyyyMMdd't'HHmmss'z'") + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$project = "meteor-test-$runId"
$environment = [ordered]@{
    METEOR_TEST_PROJECT = $project
    METEOR_TEST_RUN_ID = $runId
    METEOR_TEST_PLATFORM = $platform
    METEOR_TEST_CACHE_KEY = $cacheKey
    METEOR_TEST_NETWORK = "$project-network"
    METEOR_TEST_WORKSPACE_VOLUME = "$project-workspace"
    METEOR_TEST_GRADLE_VOLUME = "meteor-test-gradle-$cacheKey"
    METEOR_TEST_MAVEN_VOLUME = "meteor-test-maven-$cacheKey"
}
foreach ($runtime in @('runner', 'java', 'python', 'node', 'native', 'go', 'browser', 'envoy', 'h2')) {
    $key = 'METEOR_TEST_IMAGE_' + $runtime.ToUpperInvariant()
    $image = [Environment]::GetEnvironmentVariable($key)
    if (-not $image) { $image = "meteor-test/${runtime}:local-$architecture" }
    $environment[$key] = $image
}
foreach ($registry in @(@('NACOS', 'nacos/nacos-server:v3.1.2'), @('ZOOKEEPER', 'zookeeper:3.9.6'))) {
    $key = 'METEOR_TEST_IMAGE_' + $registry[0]
    $image = [Environment]::GetEnvironmentVariable($key)
    if (-not $image) { $image = $registry[1] }
    $environment[$key] = $image
}
foreach ($entry in $environment.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value) }
$output = Join-Path $repository "build/docker-runs/$runId"
$null = New-Item -ItemType Directory -Force -Path $output
$compose = @('compose', '--project-directory', $repository, '-f', (Join-Path $PSScriptRoot 'compose.yaml'))
$runnerName = "$project-runner"
$archiveName = "$project-archive"
$seedName = "$project-seed"
$retainResources = $false
$exitCode = 0

try {
    # Configuration records contain only test-owned values, never inherited credentials.
    $configuration = @($environment.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value })
    Write-Utf8 -Path (Join-Path $output 'compose.env') -Lines $configuration
    Write-Utf8 -Path (Join-Path $output 'gradle-arguments.txt') -Lines $gradleArguments
    $null = Invoke-Docker -DockerArguments @('version') -LogPath (Join-Path $output 'docker-version.txt')
    $null = Invoke-Docker -DockerArguments @('compose', 'version') -LogPath (Join-Path $output 'compose-version.txt')
    if (-not $noBuild) {
        foreach ($runtime in @('java', 'python', 'node', 'native', 'go', 'browser', 'h2', 'envoy', 'runner')) {
            $image = $environment['METEOR_TEST_IMAGE_' + $runtime.ToUpperInvariant()]
            $build = @('build', '--platform', $platform, '--target', $runtime, '--tag', $image,
                '--file', (Join-Path $PSScriptRoot 'images/Dockerfile'), $repository)
            Invoke-Docker -DockerArguments $build -LogPath (Join-Path $output "build-$runtime.log")
        }
        foreach ($runtime in @('NACOS', 'ZOOKEEPER')) {
            $image = $environment['METEOR_TEST_IMAGE_' + $runtime]
            Invoke-Docker -DockerArguments @('pull', '--platform', $platform, $image) `
                -LogPath (Join-Path $output ('pull-' + $runtime.ToLowerInvariant() + '.log'))
        }
    }
    $imageRecords = @()
    foreach ($entry in $environment.GetEnumerator()) {
        if ($entry.Key.StartsWith('METEOR_TEST_IMAGE_')) {
            $imageRecords += Invoke-Docker -DockerArguments @('image', 'inspect', '--format', '{{json .}}', $entry.Value)
            $imagePlatform = (Invoke-Docker -DockerArguments @('image', 'inspect', '--format', '{{.Os}}/{{.Architecture}}', $entry.Value)) -join ''
            if ($imagePlatform -ne $platform) {
                throw "Image $($entry.Value) uses $imagePlatform; this run requires $platform."
            }
        }
    }
    Write-Utf8 -Path (Join-Path $output 'images.jsonl') -Lines $imageRecords
    $null = Invoke-Docker -DockerArguments @('volume', 'create', $environment.METEOR_TEST_GRADLE_VOLUME)
    $null = Invoke-Docker -DockerArguments @('volume', 'create', $environment.METEOR_TEST_MAVEN_VOLUME)
    if ($seedExplicit -and -not (Test-Path -LiteralPath $mavenSeed -PathType Container)) {
        throw "Maven seed directory does not exist: $mavenSeed"
    }
    $seedHasData = $false
    if (Test-Path -LiteralPath $mavenSeed -PathType Container) {
        $seedHasData = $null -ne (Get-ChildItem -LiteralPath $mavenSeed -Force | Select-Object -First 1)
    }
    if ($seedHasData) {
        $seed = @('create', '--name', $seedName, '--label', "net.hasor.meteor.test.run=$runId",
            '--volume', ($environment.METEOR_TEST_MAVEN_VOLUME + ':/root/.m2'), $environment.METEOR_TEST_IMAGE_RUNNER,
            '/bin/sh', '-c', 'mkdir -p /root/.m2/repository/net/hasor')
        $null = Invoke-Docker -DockerArguments $seed -LogPath (Join-Path $output 'seed-create.log')
        $null = Invoke-Docker -DockerArguments @('start', '-a', $seedName) -LogPath (Join-Path $output 'seed-start.log')
        $seedSource = (Resolve-Path -LiteralPath $mavenSeed).ProviderPath + [IO.Path]::DirectorySeparatorChar + '.'
        $null = Invoke-Docker -DockerArguments @('cp', $seedSource, "${seedName}:/root/.m2/repository/net/hasor") `
            -LogPath (Join-Path $output 'seed-copy.log')
        $null = Invoke-Docker -DockerArguments @('rm', $seedName) -LogPath (Join-Path $output 'seed-cleanup.log')
    }

    # Compose initializes the fresh workspace from the runner image, with no host bind mounts.
    if ($prepareOnly) {
        Invoke-Docker -DockerArguments ($compose + @('create', '--no-build', 'runner'))
        $retainResources = $true
        Write-Host 'Prepared only; no Gradle task was executed.'
        Write-Host 'Prepared container uses the default Compose command; pass Gradle arguments to compose run when executing.'
    } else {
        Invoke-Docker -DockerArguments ($compose + @('run', '--no-deps', '--use-aliases', '--name', $runnerName, 'runner') + $gradleArguments) `
            -AllowFailure
        $exitCode = $script:DockerExitCode
    }
} catch {
    $exitCode = 1
    Write-Host $_ -ForegroundColor Red
    Write-Utf8 -Path (Join-Path $output 'launcher-error.txt') -Lines @("$_")
} finally {
    # Archive both success and failure before deleting this run's containers and network.
    Write-Utf8 -Path (Join-Path $output 'exit-code.txt') -Lines @("$exitCode")
    $null = Invoke-Docker -DockerArguments @('logs', $runnerName) -AllowFailure -LogPath (Join-Path $output 'runner.log')
    $null = Invoke-Docker -DockerArguments ($compose + @('config')) -AllowFailure -LogPath (Join-Path $output 'compose.yaml')
    $null = Invoke-Docker -DockerArguments @('ps', '-a', '--filter', "label=net.hasor.meteor.test.run=$runId", '--format', '{{json .}}') `
        -AllowFailure -LogPath (Join-Path $output 'containers.jsonl')
    $null = Invoke-Docker -DockerArguments @('volume', 'inspect', $environment.METEOR_TEST_WORKSPACE_VOLUME) -AllowFailure
    if ($script:DockerExitCode -eq 0) {
        $archived = $false
        $archive = @('create', '--name', $archiveName, '--label', "net.hasor.meteor.test.run=$runId",
            '--volume', ($environment.METEOR_TEST_WORKSPACE_VOLUME + ':/workspace'), '--env', "METEOR_TEST_RUN_ID=$runId",
            $environment.METEOR_TEST_IMAGE_RUNNER, '/bin/bash', '/workspace/meteor/meteor-test/docker/archive.sh')
        $null = Invoke-Docker -DockerArguments $archive -AllowFailure -LogPath (Join-Path $output 'archive-create.log')
        if ($script:DockerExitCode -eq 0) {
            $null = Invoke-Docker -DockerArguments @('start', '-a', $archiveName) -AllowFailure `
                -LogPath (Join-Path $output 'archive.log')
            if ($script:DockerExitCode -eq 0) {
                $null = Invoke-Docker -DockerArguments @('cp', "${archiveName}:/workspace/meteor-export/$runId/.", $output) `
                    -AllowFailure -LogPath (Join-Path $output 'archive-copy.log')
                $archived = $script:DockerExitCode -eq 0
            }
        }
        $null = Invoke-Docker -DockerArguments @('rm', '-f', $archiveName) -AllowFailure
        if (-not $archived) {
            Write-Host 'Report archival failed; workspace retained for inspection.' -ForegroundColor Red
            $keepWorkspace = $true
            if ($exitCode -eq 0) { $exitCode = 1 }
        }
    }
    if (-not $retainResources) {
        $cleanupFailed = $false
        $containers = @(Invoke-Docker -DockerArguments @('ps', '-aq', '--filter', "label=net.hasor.meteor.test.run=$runId") -AllowFailure)
        foreach ($container in $containers) {
            if ($container) {
                $null = Invoke-Docker -DockerArguments @('rm', '-f', $container) -AllowFailure `
                    -LogPath (Join-Path $output "cleanup-container-$container.log")
                if ($script:DockerExitCode -ne 0) { $cleanupFailed = $true }
            }
        }
        $null = Invoke-Docker -DockerArguments ($compose + @('down', '--remove-orphans')) -AllowFailure `
            -LogPath (Join-Path $output 'cleanup.log')
        if ($script:DockerExitCode -ne 0) { $cleanupFailed = $true }
        if (-not $keepWorkspace) {
            $null = Invoke-Docker -DockerArguments @('volume', 'rm', $environment.METEOR_TEST_WORKSPACE_VOLUME) -AllowFailure `
                -LogPath (Join-Path $output 'cleanup-workspace.log')
            if ($script:DockerExitCode -ne 0) { $cleanupFailed = $true }
        }
        if ($cleanupFailed -and $exitCode -eq 0) { $exitCode = 1 }
    }
    Write-Host "Docker test artifacts: $output"
    Write-Utf8 -Path (Join-Path $output 'exit-code.txt') -Lines @("$exitCode")
    if ($retainResources) { Write-Host "Prepared resources retained. Configuration: $output/compose.env" }
    elseif ($keepWorkspace) { Write-Host ('Workspace retained: ' + $environment.METEOR_TEST_WORKSPACE_VOLUME) }
}
exit $exitCode
