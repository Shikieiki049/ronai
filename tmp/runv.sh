cd /c/fz/claudemenu/RonStrategist
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard*4" -Seeds 3136,3137,3143,3149,3150,3151,3152,3153,3154,3155,3156,3157,3158,3159,3160 -Parallel 8 -Out results/v15_hard4.jsonl -Label vH -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/vH.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs normal*5" -Seeds 2138,2139,2152,2153,2154,2155,2156,2157,2158,2159,2160,2161,2162,2163,2164 -Parallel 8 -Out results/v15_normal5.jsonl -Label vN -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/vN.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs easy*6" -Seeds 1156,1157,1158,1159,1160,1161,1162,1163,1164,1165,1166,1167,1168,1169,1170 -Parallel 6 -Out results/v15_easy6.jsonl -Label vE -Verbose2 -TimeoutMinutes 75 > tmp/vE.log 2>&1 &
wait
for f in results/v15_*.jsonl; do echo "$f $(grep -c '"winnerTeam":0,"timeout":false' $f)/$(wc -l <$f)"; grep -o '"seed":[0-9]*,"winnerTeam":[-0-9]*,"timeout":[a-z]*' $f | grep -v 'Team":0,"timeout":false'; done
grep -ho '"gameSeconds":[0-9]*' results/v15_*.jsonl | cut -d: -f2 | awk '{s+=$1;n++}END{print s/n/60" min"}'
exit 0
