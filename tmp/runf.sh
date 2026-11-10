cd /c/fz/claudemenu/RonStrategist
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard*4" -Seeds 3011,3015,3002,3005,3006,3007 -Parallel 6 -Out results/f_hard4.jsonl -Label fH -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/cH.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs normal*5" -Seeds 2016,2001,2002,2005,2007,2009 -Parallel 6 -Out results/f_normal5.jsonl -Label fN -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/cN.log 2>&1 &
wait
for f in results/f_*.jsonl; do echo $f; grep -o '"seed":[0-9]*,"winnerTeam":[-0-9]*,"timeout":[a-z]*' $f; grep -o '"gameSeconds":[0-9]*' $f | paste -sd' '; done
