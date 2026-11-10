cd /c/fz/claudemenu/RonStrategist
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard*4" -Seeds 3010,3011,3012,3127,3128,3129,3130,3131,3132,3133 -Parallel 5 -Out results/q10_hard4.jsonl -Label qH -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/qH.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs normal*5" -Seeds 2121,2125,2129,2130,2131,2132,2133,2134,2135,2136 -Parallel 5 -Out results/q10_normal5.jsonl -Label qN -Verbose2 -TimeoutMinutes 75 -Map 'C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\saves\[6p] Burnt-Out_Paradise' > tmp/qN.log 2>&1 &
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs easy*6" -Seeds 1131,1132,1133,1134,1135,1136,1137,1138,1139,1140 -Parallel 5 -Out results/q10_easy6.jsonl -Label qE -Verbose2 -TimeoutMinutes 75 > tmp/qE.log 2>&1 &
wait
for f in results/q10_*.jsonl; do echo "$f $(grep -c '"winnerTeam":0,"timeout":false' $f)/$(wc -l <$f)"; grep -v '"winnerTeam":0,"timeout":false' $f | grep -o '"seed":[0-9]*'; done
grep -ho '"gameSeconds":[0-9]*' results/q10_*.jsonl | cut -d: -f2 | awk '{s+=$1;n++}END{print s/n/60" min"}'
