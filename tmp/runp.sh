cd /c/fz/claudemenu/RonStrategist
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard*4" -Seeds 3010,3011,3012,3015,3121,3122,3123,3124,3125,3126 -Parallel 5 -Out results/p10_hard4.jsonl -Label pH -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/pH.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs normal*5" -Seeds 2002,2016,2121,2122,2123,2124,2125,2126,2127,2128 -Parallel 5 -Out results/p10_normal5.jsonl -Label pN -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/pN.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs easy*6" -Seeds 1121,1122,1123,1124,1125,1126,1127,1128,1129,1130 -Parallel 5 -Out results/p10_easy6.jsonl -Label pE -Verbose2 -TimeoutMinutes 75 > tmp/pE.log 2>&1 &
wait
for f in results/p10_*.jsonl; do echo "$f $(grep -c '"winnerTeam":0,"timeout":false' $f)/$(wc -l <$f)"; grep -v '"winnerTeam":0,"timeout":false' $f | grep -o '"seed":[0-9]*'; done
grep -ho '"gameSeconds":[0-9]*' results/p10_*.jsonl | cut -d: -f2 | awk '{s+=$1;n++}END{print s/n/60" min"}'
