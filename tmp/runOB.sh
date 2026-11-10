cd /c/fz/claudemenu/RonStrategist
X=$1; CLSARG=""; [ -n "$CLS" ] && CLSARG="-ClassesDir $CLS"
powershell -ExecutionPolicy Bypass -File selfplay.ps1 -Spec "expert vs hard readied" -Seeds 9201,9202,9203,9204,9205,9206,9207,9208,9209,9210,9211,9212 -Parallel 12 -Out results/$X.jsonl -Label $X -Verbose2 -TimeoutMinutes 45 -Map 'C:\fz\claudemenu\RonStrategist\tmp\maps\Oceanborn' $CLSARG > tmp/$X.log 2>&1
