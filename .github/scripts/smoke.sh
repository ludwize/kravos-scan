#!/usr/bin/env bash
# Installs the APK, creates sample scans through the real pipeline (kravos_smoke extra),
# walks every screen, exports a PDF, and records screenshots + crashes.
set -u
OUT=smoke
mkdir -p "$OUT"
APK=$(ls out/*.apk | head -1)
PKG=net.kravos.scan

adb install -r "$APK" > "$OUT/install.txt" 2>&1
adb logcat -c
adb shell am start -W -n $PKG/.ui.MainActivity > "$OUT/start.txt" 2>&1
sleep 6

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" >> "$OUT/steps.txt"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1; }

# tap_text "<exact text or content-desc>" [index]
tap_text() {
  dump
  python3 - "$1" "${2:-0}" "$OUT/ui.xml" <<'PY' > "$OUT/tap.txt"
import re, sys, xml.etree.ElementTree as ET
want, idx, path = sys.argv[1], int(sys.argv[2]), sys.argv[3]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
hits = []
for n in root.iter('node'):
    if n.get('text') == want or n.get('content-desc') == want:
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
if len(hits) > idx:
    print(*hits[idx])
PY
  if [ -s "$OUT/tap.txt" ]; then adb shell input tap $(cat "$OUT/tap.txt"); echo "tapped $1" >> "$OUT/steps.txt"
  else echo "not found: $1" >> "$OUT/steps.txt"; fi
  sleep 3
}
back() { adb shell input keyevent KEYCODE_BACK; sleep 2; }

shot 01-empty
# sample scans: letter (doc + OCR), glare photo merge, ID card
adb shell am start -n $PKG/.ui.MainActivity --ez kravos_smoke true > /dev/null 2>&1
sleep 10; shot 02-processing
sleep 80; shot 03-library

dump
python3 - "$OUT/ui.xml" <<'PY' > "$OUT/tap.txt"
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for n in root.iter('node'):
    t = n.get('text') or n.get('content-desc') or ''
    if t.startswith('Dokument '):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds'))); print((x1 + x2) // 2, y1 - 200); break
PY
[ -s "$OUT/tap.txt" ] && adb shell input tap $(cat "$OUT/tap.txt"); sleep 4
shot 10-doc
tap_text "Original"; sleep 6; shot 11-doc-original
tap_text "Crno-belo"; sleep 6; shot 12-doc-bw
tap_text "Magija"; sleep 6; shot 13-doc-magic
tap_text "Tekst"; sleep 3; shot 14-doc-text
back
tap_text "Iseci"; sleep 3; shot 15-crop
back
tap_text "Potpis"; sleep 3; shot 16-signature
W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); WW=${W%x*}; HH=${W#*x}
adb shell input swipe $((WW/4)) $((HH/2)) $((WW/2)) $((HH/2-120)) 300
adb shell input swipe $((WW/2)) $((HH/2-120)) $((WW*3/4)) $((HH/2+40)) 300
sleep 1; shot 17-signature-drawn
tap_text "Sačuvaj"; sleep 4; shot 18-sign-place
tap_text "Postavi"; sleep 8; shot 19-signed
tap_text "Izvezi"; sleep 3; shot 20-export
tap_text "Preuzimanja"; sleep 8; shot 21-exported
back

# photo doc
dump
python3 - "$OUT/ui.xml" <<'PY' > "$OUT/tap.txt"
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for n in root.iter('node'):
    t = n.get('text') or ''
    if t.startswith('Fotografija '):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds'))); print((x1 + x2) // 2, y1 - 200); break
PY
[ -s "$OUT/tap.txt" ] && adb shell input tap $(cat "$OUT/tap.txt"); sleep 4
shot 30-photo
back
dump
python3 - "$OUT/ui.xml" <<'PY' > "$OUT/tap.txt"
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for n in root.iter('node'):
    t = n.get('text') or ''
    if t.startswith('Lična karta '):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds'))); print((x1 + x2) // 2, y1 - 200); break
PY
[ -s "$OUT/tap.txt" ] && adb shell input tap $(cat "$OUT/tap.txt"); sleep 4
shot 31-id
back

tap_text "Pretraga"; sleep 1
adb shell input text "Zakupac"; sleep 3; shot 40-search
tap_text "Pretraga"; sleep 1
tap_text "Podešavanja"; sleep 3; shot 50-settings
back
tap_text "Lična karta"; sleep 2; shot 60-id-dialog
tap_text "Otkaži"
tap_text "Dokument"; sleep 6; shot 61-scanner

# pull the exported PDF for inspection
adb shell ls "/sdcard/Download/Kravos Sken/" > "$OUT/downloads.txt" 2>&1
F=$(adb shell ls "/sdcard/Download/Kravos\ Sken/" 2>/dev/null | tr -d '\r' | grep pdf | head -1)
[ -n "$F" ] && adb pull "/sdcard/Download/Kravos Sken/$F" "$OUT/exported.pdf" > /dev/null 2>&1

PID=$(adb shell pidof $PKG | tr -d '\r')
echo "pid=$PID" > "$OUT/status.txt"
adb logcat -d > "$OUT/logcat.txt"
grep -n -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" > "$OUT/crash.txt" || true
grep -n -E "KravosScan|AndroidRuntime|net.kravos|tesseract|Tesseract" "$OUT/logcat.txt" | tail -500 > "$OUT/app-log.txt" || true
if [ -s "$OUT/crash.txt" ]; then echo "CRASH" >> "$OUT/status.txt"; fi
rm -f "$OUT/logcat.txt" "$OUT/ui.xml"
cat "$OUT/status.txt" "$OUT/steps.txt"
exit 0
