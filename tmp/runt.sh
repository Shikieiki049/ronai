cd /c/fz/claudemenu/RonStrategist
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard*4" -Seeds 3134,3135,3136,3137,3138,3139,3140,3141,3142,3143 -Parallel 6 -Out results/t10_hard4.jsonl -Label tH -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/tH.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs normal*5" -Seeds 2137,2138,2139,2140,2141,2142,2143,2144,2145,2146 -Parallel 6 -Out results/t10_normal5.jsonl -Label tN -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/tN.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs easy*6" -Seeds 1141,1142,1143,1144,1145,1146,1147,1148,1149,1150 -Parallel 6 -Out results/t10_easy6.jsonl -Label tE -Verbose2 -TimeoutMinutes 75 > tmp/tE.log 2>&1 &
wait
for f in results/t10_*.jsonl; do echo "$f $(grep -c '"winnerTeam":0,"timeout":false' $f)/$(wc -l <$f)"; grep -v '"winnerTeam":0,"timeout":false' $f | grep -o '"seed":[0-9]*'; done
grep -ho '"gameSeconds":[0-9]*' results/t10_*.jsonl | cut -d: -f2 | awk '{s+=$1;n++}END{print s/n/60" min"}'
