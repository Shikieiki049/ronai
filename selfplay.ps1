<#
  Runs bot-vs-bot matches on headless dev servers, several in parallel, and appends one JSON line
  per match to the results file.

  .\selfplay.ps1 -Spec "expert vs normal*5" -Seeds 1,2,3,4 -Parallel 4 -Out results\x_vs_5n.jsonl
  Optional: -ExpertParams path\to\params.json (overrides the expert's strategic numbers)
#>
param(
    [string]$Spec = "",
    [string]$Plan = "",
    [string]$Seeds = "1",
    [int]$Parallel = 4,
    [string]$Out = "results\results.jsonl",
    # -AltRon: run the server on the alternate RON build (build.gradle ronAlt, e.g. 1.5.0b-2 with RON's own bots)
    [switch]$AltRon,
    [int]$TimeoutMinutes = 45,
    [string]$ExpertParams = "",
    [string]$HardParams = "",
    [string]$Map = "",
    [string]$Extra = "",
    [string]$Label = "",
    [string]$ClassesDir = "",
    # run this many servers instead of -Parallel while nobody is at the machine (no keyboard or mouse
    # input for -IdleMinutes and no RON client running); 0 = always -Parallel
    [int]$ParallelIdle = 0,
    [int]$IdleMinutes = 10,
    # a command run after every finished game; exit code 10 = the result is decided, start no more games
    [string]$StopCheck = "",
    [switch]$Verbose2
)
Add-Type -Namespace RonStrat -Name Idle -MemberDefinition @'
[StructLayout(LayoutKind.Sequential)] public struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }
[DllImport("user32.dll")] public static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);
public static double Seconds() {
    LASTINPUTINFO l = new LASTINPUTINFO(); l.cbSize = (uint)System.Runtime.InteropServices.Marshal.SizeOf(l);
    if (!GetLastInputInfo(ref l)) return 0;
    return unchecked((uint)Environment.TickCount - l.dwTime) / 1000.0;
}
'@
$script:awayChecked = [datetime]::MinValue
$script:away = $false
function Test-Away {
    if ($ParallelIdle -le $Parallel) { return $false }
    if (((Get-Date) - $script:awayChecked).TotalSeconds -lt 30) { return $script:away }
    $script:awayChecked = Get-Date
    $idle = [RonStrat.Idle]::Seconds() -ge 60 * $IdleMinutes
    $playing = $false
    if ($idle) { $playing = [bool](Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -match 'RON1\.20\.1' }) }
    $now = $idle -and -not $playing
    if ($now -ne $script:away) { Write-Host "[$(Get-Date -Format HH:mm:ss)] $(if ($now) { "nobody at the machine: up to $ParallelIdle servers" } else { "user is back: down to $Parallel servers" })" }
    $script:away = $now
    return $now
}
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$cmd = Get-Content (Join-Path $root 'build\servercmd.json') -Raw | ConvertFrom-Json
if ($AltRon) {
    $alt = (Get-Content (Join-Path $root 'build\ronalt.txt') -Raw).Trim()
    if (-not (Test-Path $alt)) { throw "alternate RON jar missing: run build.ps1 altRonPath" }
    $cmd.classpath = @($cmd.classpath | ForEach-Object { if ($_ -like '*reignofnether-1.5.1c*') { $alt } else { $_ } })
}
$outPath = if ([IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $root $Out }
New-Item -ItemType Directory -Force (Split-Path $outPath) | Out-Null

# freeze the mod's compiled classes for this batch, so recompiling during a long batch is safe
$snap = Join-Path $root ("work\_snap-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
# -ClassesDir: run an older build (a previous snapshot's classes) for A/B comparisons
$srcClasses = if ($ClassesDir) { $ClassesDir } else { Join-Path $root 'build\classes\java\main' }
Copy-Item -Recurse $srcClasses (Join-Path $snap 'classes')
# frozen older brains (tools/freeze.py, spec 'expert@base') live in the same classes folder
$baseClasses = Join-Path $root 'build\classes\java\baseline'
if (-not $ClassesDir -and (Test-Path $baseClasses)) { Copy-Item -Recurse -Force (Join-Path $baseClasses '*') (Join-Path $snap 'classes') }
Copy-Item -Recurse (Join-Path $root 'build\resources\main') (Join-Path $snap 'resources')
$modClasses = "ronstrat%%$snap\resources;ronstrat%%$snap\classes"
$cmd.env.MOD_CLASSES = $modClasses
$cmd.classpath = @($cmd.classpath | ForEach-Object {
    if ($_ -eq (Join-Path $root 'build\classes\java\main')) { "$snap\classes" }
    elseif ($_ -eq (Join-Path $root 'build\resources\main')) { "$snap\resources" }
    else { $_ } })

function Start-Match([string]$spec, [int]$seed, [string]$Map, [string]$GameExtra) {
    $short = $spec -replace '[^a-zA-Z0-9]+', ''
    $lab = if ($Label) { "$Label-$short" } else { $short }
    $dir = Join-Path $root "work\$lab-s$seed-$([guid]::NewGuid().ToString('N').Substring(0,6))"
    New-Item -ItemType Directory -Force $dir | Out-Null
    Copy-Item (Join-Path $root 'run-server\eula.txt') $dir
    Copy-Item (Join-Path $root 'run-server\server.properties') $dir
    $port = 30000 + (Get-Random -Maximum 20000)
    Add-Content (Join-Path $dir 'server.properties') "server-port=$port"
    if ($Map) {
        # play on a copy of a real RON map; the original save is never touched
        & robocopy $Map (Join-Path $dir 'world') /E /NFL /NDL /NJH /NJS /NP | Out-Null
        Remove-Item -Force (Join-Path $dir 'world\session.lock') -ErrorAction SilentlyContinue
    }
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($a in $cmd.jvmArgs) {
        if ($a -like '-Dfile.encoding*') { continue }
        $lines.Add('"' + ($a -replace '\\', '\\') + '"')
    }
    $lines.Add('"-Dfile.encoding=UTF-8"')
    $lines.Add('-Xmx1800m')
    $lines.Add('-Xms256m')
    # many servers share the machine: keep each one to a few cores and a small GC
    $lines.Add('-XX:ActiveProcessorCount=6')
    $lines.Add('-XX:+UseParallelGC')
    $lines.Add('-XX:ParallelGCThreads=3')
    $tmp = Join-Path $root 'tmp'
    $lines.Add('"-Djdk.net.unixdomain.tmpdir=' + ($tmp -replace '\\', '\\') + '"')
    $lines.Add('-Djava.net.preferIPv4Stack=true')
    $lines.Add('"-Dronstrat.autotest=' + $spec + '"')
    $lines.Add("-Dronstrat.seed=$seed")
    $lines.Add('"-Dronstrat.out=' + ($outPath -replace '\\', '\\') + '"')
    $lines.Add("-Dronstrat.timeoutMinutes=$TimeoutMinutes")
    if ($ExpertParams) { $lines.Add('"-Dronstrat.params.expert=' + ((Resolve-Path $ExpertParams).Path -replace '\\', '\\') + '"') }
    if ($HardParams) { $lines.Add('"-Dronstrat.params.hard=' + ((Resolve-Path $HardParams).Path -replace '\\', '\\') + '"') }
    $lines.Add('"-Dronstrat.profiles=' + ((Join-Path $root 'tuning\profiles') -replace '\\', '\\') + '"')
    if ($Verbose2) { $lines.Add('-Dronstrat.verbose=true') }
    if ($Extra) { foreach ($x in ($Extra -split " ")) { $lines.Add($x) } }
    if ($GameExtra) { foreach ($x in ($GameExtra -split " ")) { $lines.Add($x) } }
    if ($Map) { $lines.Add('"-Dronstrat.map=' + ((Split-Path $Map -Leaf) -replace '"', '') + '"') }
    $lines.Add('-cp')
    $lines.Add('"' + (($cmd.classpath -join ';') -replace '\\', '\\') + '"')
    $lines.Add($cmd.main)
    foreach ($a in $cmd.args) { $lines.Add('"' + $a + '"') }
    $argFile = Join-Path $dir 'java.args'
    [IO.File]::WriteAllLines($argFile, $lines)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "cmd.exe"
    # a renamed copy of java.exe, so other tools that kill "java" processes leave these alone
    $javaExe = $cmd.java -replace 'java\.exe$', 'ronstrat-java.exe'
    if (-not (Test-Path $javaExe)) { Copy-Item $cmd.java $javaExe }
    $psi.Arguments = "/c `"`"$javaExe`" @`"$argFile`" > console.log 2>&1 < nul`""
    $psi.WorkingDirectory = $dir
    $psi.UseShellExecute = $false
    foreach ($p in $cmd.env.PSObject.Properties) { $psi.EnvironmentVariables[$p.Name] = [string]$p.Value }
    $proc = [System.Diagnostics.Process]::Start($psi)
    return [pscustomobject]@{ Proc = $proc; Dir = $dir; Seed = $seed; Spec = $spec; Map = $Map; Extra = $GameExtra; Started = Get-Date }
}

$queue = New-Object System.Collections.Queue
if ($Plan) {
    # -Plan file.json: a list of { spec, seed, map } (suite.ps1 writes it); games on different maps share one pool
    foreach ($g in (Get-Content $Plan -Raw | ConvertFrom-Json)) { $queue.Enqueue([pscustomobject]@{ Spec = [string]$g.spec; Seed = [int]$g.seed; Map = [string]$g.map; Extra = [string]$g.extra }) }
} elseif ($Spec) {
    foreach ($sp in ($Spec -split '\|' |ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
        foreach ($s in ($Seeds -split "[,; ]+" | Where-Object { $_ })) { $queue.Enqueue([pscustomobject]@{ Spec = $sp; Seed = [int]$s; Map = $Map; Extra = "" }) }
    }
} else { throw "give -Spec or -Plan" }
$running = @()
$retries = @{}
$hardLimit = [TimeSpan]::FromMinutes([Math]::Max(20, $TimeoutMinutes * 1.5))
while ($queue.Count -gt 0 -or $running.Count -gt 0) {
    $limit = if (Test-Away) { $ParallelIdle } else { $Parallel }
    while ($running.Count -lt $limit -and $queue.Count -gt 0) {
        # other programs share this machine: only start a server when there is memory for it
        $freeCommitGB = (Get-CimInstance Win32_OperatingSystem).FreeVirtualMemory / 1MB
        if ($freeCommitGB -lt 3.0 -and $running.Count -gt 0) { break }
        $q = $queue.Dequeue()
        $running += Start-Match $q.Spec $q.Seed $q.Map $q.Extra
        Write-Host "[$(Get-Date -Format HH:mm:ss)] started $($q.Spec) seed $($q.Seed) $(if ($q.Map) { Split-Path $q.Map -Leaf })"
    }
    Start-Sleep -Seconds 5
    # servers run at normal priority (set "Idle" here while the user needs the machine)
    Get-Process ronstrat-java -ErrorAction SilentlyContinue | Where-Object { $_.PriorityClass -ne "Normal" } | ForEach-Object { try { $_.PriorityClass = "Normal" } catch {} }
    # leave about a quarter of the machine to the user: servers only run on the first ~69% of the logical CPUs (total load ~75-80% with everything else)
    $cpuMask = [IntPtr]([long][Math]::Pow(2, [Math]::Max(1, [int]([Environment]::ProcessorCount * 1.0))) - 1)
    Get-Process ronstrat-java -ErrorAction SilentlyContinue | Where-Object { $_.ProcessorAffinity -ne $cpuMask } | ForEach-Object { try { $_.ProcessorAffinity = $cpuMask } catch {} }
    $still = @()
    foreach ($r in $running) {
        if ($r.Proc.HasExited) {
            Write-Host "[$(Get-Date -Format HH:mm:ss)] $($r.Spec) seed $($r.Seed) finished (exit $($r.Proc.ExitCode)) after $([int]((Get-Date) - $r.Started).TotalSeconds)s"
            $wrote = Select-String -Path (Join-Path $r.Dir 'logs\latest.log') -Pattern 'autotest result' -Quiet -ErrorAction SilentlyContinue
            if (-not $wrote) {
                $key = "$($r.Spec)#$($r.Seed)#$($r.Map)"
                $retries[$key] = 1 + [int]$retries[$key]
                if ($retries[$key] -le 2) { Write-Host "  no result written, retrying $key"; $queue.Enqueue([pscustomobject]@{ Spec = $r.Spec; Seed = $r.Seed; Map = $r.Map; Extra = $r.Extra }) }
            }
            if ($wrote -and $StopCheck -and $queue.Count -gt 0) {
                Invoke-Expression $StopCheck | Out-Null
                if ($LASTEXITCODE -eq 10) { Write-Host "[$(Get-Date -Format HH:mm:ss)] result decided: $($queue.Count) queued games dropped"; $queue.Clear() }
            }
            # keep the log of the newest run only; worlds are big
            Remove-Item -Recurse -Force (Join-Path $r.Dir 'world') -ErrorAction SilentlyContinue
        } elseif (((Get-Date) - $r.Started) -gt $hardLimit) {
            Write-Host "seed $($r.Seed) hung, killing"
            & taskkill /T /F /PID $r.Proc.Id | Out-Null
        } else { $still += $r }
    }
    $running = $still
}
