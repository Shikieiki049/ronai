<#  Win-rate table of the expert (team 0) per match spec.
    Wins = every enemy eliminated (or the decisive-collapse rule). A timeout never counts as a win;
    "AheadAtTimeout" is shown separately for information.
    .\report.ps1 results\batch2.jsonl [more files] #>
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Files)
$rows = foreach ($f in $Files) { Get-Content $f | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json } }
$rows | Group-Object spec | ForEach-Object {
    $n = $_.Count
    $w = @($_.Group | Where-Object { $_.winnerTeam -eq 0 -and -not $_.timeout }).Count
    $ahead = @($_.Group | Where-Object { $_.winnerTeam -eq 0 -and $_.timeout }).Count
    $to = @($_.Group | Where-Object { $_.timeout }).Count
    $len = [int](($_.Group | Measure-Object gameSeconds -Average).Average)
    [pscustomobject]@{ Spec = $_.Name; Games = $n; Wins = $w; WinRate = ('{0:P0}' -f ($w / $n)); Timeouts = $to; AheadAtTimeout = $ahead; AvgGameSec = $len }
} | Sort-Object Spec | Format-Table -AutoSize
