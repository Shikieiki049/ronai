<#
  Runs a test suite (tools/suites/<name>.json) through selfplay.ps1 and prints win rates with
  confidence intervals.

  .\suite.ps1 -Suite duel -Label D205            60 games, current code against the frozen baseline
  .\suite.ps1 -Suite duel -Label D205q -Games 20 a quick screen: the first 20 games, spread over the maps
  .\suite.ps1 -Suite duel -Label D205 -ReportOnly
  .\suite.ps1 -Suite econ -Label E205           24 games of 12 minutes: what each side gathered and built
  .\suite.ps1 -Suite duel -Label D205 -Full     play all games even when the result is already decided

  Time savers: games of the same map and seed with the seats swapped are judged as one pair; a suite
  stops when the paired result is decided (sequential test); equal games end once one side has twice
  the units and the bigger army for 90 s; up to -ParallelIdle servers run while nobody is at the machine.

  Suites: duel (main measure), spar (against playing styles), handicap (the user's lobby settings),
  team (2v2 against the baseline), regress (the old checks against the hard AI).
  Maps are copies of the official map pack in tmp\maps.
#>
param(
    [Parameter(Mandatory = $true)][string]$Suite,
    [Parameter(Mandatory = $true)][string]$Label,
    [int]$Parallel = 12,
    [int]$Games = 0,
    [int]$ParallelIdle = 16,
    [int]$TimeoutMinutes = 40,
    [string]$Maps = "",
    [string]$Extra = "",
    [switch]$Verbose2,
    [switch]$Full,
    [switch]$AltRon,
    [switch]$ReportOnly
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $Maps) { $Maps = Join-Path $root 'tmp\maps' }
$out = Join-Path $root "results\$Label.jsonl"
$tool = Join-Path $root 'tools\suite.py'
if (-not $ReportOnly) {
    $plan = Join-Path $root "tmp\$Label.plan.json"
    Remove-Item -Force $out -ErrorAction SilentlyContinue
    $planArgs = @($tool, 'plan', $Suite, $Maps, $plan)
    if ($Games -gt 0) { $planArgs += @('--games', $Games) }
    & py @planArgs
    if ($LASTEXITCODE -ne 0) { throw "suite plan failed" }
    $spArgs = @{ Plan = $plan; Parallel = $Parallel; Out = $out; Label = $Label; TimeoutMinutes = $TimeoutMinutes }
    $spArgs.ParallelIdle = $ParallelIdle
    # suites with swapped seats stop as soon as the paired result is decided (-Full plays every game)
    if (-not $Full -and $Games -eq 0) { $spArgs.StopCheck = "& py `"$tool`" sprt $Suite `"$out`"" }
    if ($Extra) { $spArgs.Extra = $Extra }
    if ($Verbose2) { $spArgs.Verbose2 = $true }
    if ($AltRon) { $spArgs.AltRon = $true }
    & (Join-Path $root 'selfplay.ps1') @spArgs *>&1 | Out-File -Encoding utf8 (Join-Path $root "tmp\$Label.log")
}
& py $tool report $Suite $out
