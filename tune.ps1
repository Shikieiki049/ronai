<#
  Self-play parameter search for a difficulty tier: a (1+lambda) evolution strategy over the bot's
  strategic numbers. Each generation mutates the current best parameter set, plays every candidate
  on the same seeds of a benchmark match and keeps the candidate with the best score.

  .\tune.ps1 -Spec "expert vs normal*5" -Generations 6 -Lambda 3 -Seeds 1,2 -Parallel 6
#>
param(
    [string]$Spec = "expert vs normal*5",
    [string]$Tier = "expert",
    [int]$Generations = 5,
    [int]$Lambda = 3,
    [int[]]$Seeds = @(1, 2),
    [int]$Parallel = 6,
    [int]$TimeoutMinutes = 40,
    [double]$Sigma = 0.25
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$dir = Join-Path $root "tuning\$Tier"
New-Item -ItemType Directory -Force $dir | Out-Null

# the knobs worth searching, with hard bounds
$space = [ordered]@{
    workerTarget        = @(30, 75)
    farmWorkersShare    = @(0.3, 0.55)
    oreShare            = @(0.1, 0.35)
    houseBuffer         = @(4, 14)
    barracksPerWorkers  = @(6, 18)
    maxBarracks         = @(2, 6)
    maxTechBuildings    = @(1, 5)
    attackMinArmyPop    = @(6, 30)
    attackAdvantage     = @(0.9, 2.0)
    retreatRatio        = @(0.4, 0.95)
    regroupDistance     = @(8, 20)
    golemShare          = @(0.0, 0.45)
    casterShare         = @(0.0, 0.3)
    attackWaveCooldown  = @(20, 200)
    defendRadius        = @(36, 70)
}
$intKeys = @('workerTarget', 'houseBuffer', 'barracksPerWorkers', 'maxBarracks', 'maxTechBuildings', 'attackMinArmyPop', 'regroupDistance', 'defendRadius', 'attackWaveCooldown')

$bestFile = Join-Path $dir 'best.json'
$defaults = Get-ChildItem (Join-Path $root 'work') -Recurse -Filter "params-$Tier.defaults.json" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (Test-Path $bestFile) { $best = Get-Content $bestFile -Raw | ConvertFrom-Json }
elseif ($defaults) { $best = Get-Content $defaults.FullName -Raw | ConvertFrom-Json }
else { throw "no starting params: run one self-play match first so the defaults file is written" }

function Score([string]$resultsFile) {
    if (-not (Test-Path $resultsFile)) { return -1 }
    $s = 0; $n = 0
    foreach ($line in Get-Content $resultsFile) {
        $r = $line | ConvertFrom-Json
        $n++
        $mine = $r.teams[0]
        $alive = @($mine | Where-Object { $_.alive }).Count
        if ($r.winnerTeam -eq 0 -and -not $r.timeout) { $s += 1.0 + 0.3 * (1 - $r.gameSeconds / ($TimeoutMinutes * 60)) }
        elseif ($r.winnerTeam -eq 0) { $s += 0.5 }
        else {
            # a loss still says something: how many enemies did it take down, how long did it last
            $dead = 0; for ($i = 1; $i -lt $r.teams.Count; $i++) { $dead += @($r.teams[$i] | Where-Object { -not $_.alive }).Count }
            $s += 0.1 * $dead + 0.2 * ($r.gameSeconds / ($TimeoutMinutes * 60))
        }
    }
    if ($n -eq 0) { return -1 }
    return $s / $n
}

function Mutate($p) {
    $q = [ordered]@{}
    foreach ($prop in $p.PSObject.Properties) { $q[$prop.Name] = [double]$prop.Value }
    foreach ($k in $space.Keys) {
        if ((Get-Random -Maximum 1.0) -gt 0.5) { continue }   # mutate about half of the knobs
        $lo = $space[$k][0]; $hi = $space[$k][1]
        $u1 = Get-Random -Minimum 0.0001 -Maximum 1.0; $u2 = Get-Random -Maximum 1.0
        $g = [Math]::Sqrt(-2 * [Math]::Log($u1)) * [Math]::Cos(2 * [Math]::PI * $u2)
        $v = $q[$k] + $g * $Sigma * ($hi - $lo)
        $v = [Math]::Max($lo, [Math]::Min($hi, $v))
        if ($intKeys -contains $k) { $v = [Math]::Round($v) }
        $q[$k] = $v
    }
    return [pscustomobject]$q
}

$bestScoreFile = Join-Path $dir 'best.score'
if (Test-Path $bestScoreFile) { $bestScore = [double](Get-Content $bestScoreFile) } else { $bestScore = $null }

for ($g = 1; $g -le $Generations; $g++) {
    $gen = Join-Path $dir ("gen-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Force $gen | Out-Null
    $cands = @()
    if ($null -eq $bestScore) { $cands += [pscustomobject]@{ Name = 'parent'; P = $best } }
    for ($i = 0; $i -lt $Lambda; $i++) { $cands += [pscustomobject]@{ Name = "c$i"; P = (Mutate $best) } }
    foreach ($c in $cands) {
        $c | Add-Member Pf (Join-Path $gen "$($c.Name).json")
        $c | Add-Member Rf (Join-Path $gen "$($c.Name).jsonl")
        $c.P | ConvertTo-Json | Set-Content -Encoding utf8 $c.Pf
    }
    # successive halving: everyone plays the first seed, only the better half plays the rest
    function Run-Stage($list, $seeds) {
        $jobs = @()
        $per = [Math]::Max(1, [Math]::Floor($Parallel / [Math]::Max(1, $list.Count)))
        foreach ($c in $list) {
            $argList = @('-NoProfile', '-File', (Join-Path $root 'selfplay.ps1'), '-Spec', "`"$Spec`"", '-Seeds', ($seeds -join ','),
                '-Parallel', $per, '-Out', $c.Rf, '-TimeoutMinutes', $TimeoutMinutes, '-Label', "tune-$Tier-$($c.Name)")
            if ($Tier -eq 'expert') { $argList += @('-ExpertParams', $c.Pf) } else { $argList += @('-HardParams', $c.Pf) }
            $jobs += Start-Process powershell -ArgumentList $argList -PassThru -WindowStyle Hidden
        }
        foreach ($p in $jobs) { $p.WaitForExit() }
    }
    Run-Stage $cands @($Seeds[0])
    if ($Seeds.Count -gt 1) {
        $keep = $cands | Sort-Object { -(Score $_.Rf) } | Select-Object -First ([Math]::Ceiling($cands.Count / 2))
        Run-Stage @($keep) @($Seeds | Select-Object -Skip 1)
    }
    $jobs = $cands | ForEach-Object { [pscustomobject]@{ C = $_; Rf = $_.Rf } }
    foreach ($j in $jobs) {
        $sc = Score $j.Rf
        Write-Host ("gen {0} {1}: score {2:N3}" -f $g, $j.C.Name, $sc)
        if ($j.C.Name -eq 'parent') { $bestScore = $sc; continue }
        if ($null -eq $bestScore -or $sc -gt $bestScore) {
            $bestScore = $sc; $best = $j.C.P
            $best | ConvertTo-Json | Set-Content -Encoding utf8 $bestFile
            Set-Content $bestScoreFile $bestScore
            Write-Host "  -> new best"
        }
    }
    Add-Content (Join-Path $dir 'history.txt') ("{0} gen {1} best={2:N3}" -f (Get-Date -Format s), $g, $bestScore)
}
Write-Host "best score $bestScore, params in $bestFile"
