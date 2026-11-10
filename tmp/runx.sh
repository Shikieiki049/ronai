cd /c/fz/claudemenu/RonStrategist
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard*4" -Seeds 3136,3137,3149,3161,3162,3163,3164,3165,3166,3167,3168,3169,3170,3171,3172 -Parallel 8 -Out results/x15_hard4.jsonl -Label xH -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/xH.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs normal*5" -Seeds 2154,2159,2165,2166,2167,2168,2169,2170,2171,2172,2173,2174,2175,2176,2177 -Parallel 8 -Out results/x15_normal5.jsonl -Label xN -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/xN.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs easy*6" -Seeds 1171,1172,1173,1174,1175,1176,1177,1178,1179,1180,1181,1182,1183,1184,1185 -Parallel 6 -Out results/x15_easy6.jsonl -Label xE -Verbose2 -TimeoutMinutes 75 > tmp/xE.log 2>&1 &
wait
for f in results/x15_*.jsonl; do echo "$f $(grep -c '"winnerTeam":0,"timeout":false' $f)/$(wc -l <$f)"; grep -o '"seed":[0-9]*,"winnerTeam":[-0-9]*,"timeout":[a-z]*' $f | grep -v 'Team":0,"timeout":false'; done
grep -ho '"gameSeconds":[0-9]*' results/x15_*.jsonl | cut -d: -f2 | awk '{s+=$1;n++}END{print s/n/60" min"}'
exit 0
