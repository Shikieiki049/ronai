cd /c/fz/claudemenu/RonStrategist
X=$1; CLSARG=""; [ -n "$CLS" ] && CLSARG="-ClassesDir $CLS"
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard readied" -Seeds 9101,9102,9103,9104,9105,9106,9107,9108,9109,9110,9111,9112 -Parallel 12 -Out results/$X.jsonl -Label $X -Verbose2 -TimeoutMinutes 45 -Map 'C:\fz\claudemenu\RonStrategist\tmp\maps\Post-Youth' $CLSARG > tmp/$X.log 2>&1
