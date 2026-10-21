# Kravos Sken

Lični Android skener (sideload) u stilu Microsoft Lens / Adobe Scan / Google PhotoScan, sa „premium" funkcijama, bez reklama.

- Dokumenti: ML Kit skener (ivice, perspektiva), filteri Auto / Magija / Sivo / Crno-belo / Foto, ručno sečenje, rotacija, redosled strana
- Foto mod: više snimaka iste fotografije → uklanjanje odsjaja + vraćanje boja
- Lična karta / vozačka / pasoš: obe strane u stvarnoj veličini na A4
- OCR na telefonu (Tesseract: srpska ćirilica, latinica, engleski), pretraživ PDF, pretraga po tekstu
- Potpis, izvoz PDF/JPG/TXT, Podeli / Preuzimanja / Google Drive
- Samoažuriranje iz GitHub Releases

Build: GitHub Actions (`.github/workflows/build.yml`), potpis iz secreta `KP_SIGNING_PASS`.
