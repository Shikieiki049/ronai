cd /c/fz/claudemenu/RonStrategist
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard*4" -Seeds 3015,3001,3003,3004,3014,3008 -Parallel 6 -Out results/c_hard4.jsonl -Label cH -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/cH.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs normal*5" -Seeds 2016,2003,2004,2006,2008,2010 -Parallel 6 -Out results/c_normal5.jsonl -Label cN -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/cN.log 2>&1 &
wait
for f in results/c_*.jsonl; do echo $f; grep -o '"seed":[0-9]*,"winnerTeam":[-0-9]*,"timeout":[a-z]*' $f; grep -o '"gameSeconds":[0-9]*' $f | paste -sd' '; done
