cd /c/fz/claudemenu/RonStrategist
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard*4" -Seeds 3012,3011,3015,3009,3010,3013 -Parallel 6 -Out results/i_hard4.jsonl -Label iH -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/cH.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs normal*5" -Seeds 2002,2016,2011,2012,2013,2014 -Parallel 6 -Out results/i_normal5.jsonl -Label iN -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/cN.log 2>&1 &
wait
for f in results/i_*.jsonl; do echo $f; grep -o '"seed":[0-9]*,"winnerTeam":[-0-9]*,"timeout":[a-z]*' $f; grep -o '"gameSeconds":[0-9]*' $f | paste -sd' '; done
