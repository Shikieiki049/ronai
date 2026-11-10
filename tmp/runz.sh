cd /c/fz/claudemenu/RonStrategist
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard*4" -Seeds 3169,3173,3174,3175,3176,3177,3178,3179,3180,3181,3182,3183,3184,3185,3186 -Parallel 4 -Out results/z15_hard4.jsonl -Label zH -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/zH.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs normal*5" -Seeds 2154,2178,2179,2180,2181,2182,2183,2184,2185,2186,2187,2188,2189,2190,2191 -Parallel 4 -Out results/z15_normal5.jsonl -Label zN -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/zN.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs easy*6" -Seeds 1186,1187,1188,1189,1190,1191,1192,1193,1194,1195,1196,1197,1198,1199,1200 -Parallel 4 -Out results/z15_easy6.jsonl -Label zE -Verbose2 -TimeoutMinutes 75 > tmp/zE.log 2>&1 &
wait
for f in results/z15_*.jsonl; do echo "$f $(grep -c '"winnerTeam":0,"timeout":false' $f)/$(wc -l <$f)"; grep -o '"seed":[0-9]*,"winnerTeam":[-0-9]*,"timeout":[a-z]*' $f | grep -v 'Team":0,"timeout":false'; done
grep -ho '"gameSeconds":[0-9]*' results/z15_*.jsonl | cut -d: -f2 | awk '{s+=$1;n++}END{print s/n/60" min"}'
exit 0
