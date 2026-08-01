# Resido — Android klient

Natívny Kotlin WebView klient pre Resido: načíta `<server>/resido/`, tlačí
bločky a bony potichu cez ESC/POS (sieťové aj Bluetooth termotlačiarne) a sám
sa aktualizuje z `residoandroid.vorntech.sk`.

## Build a vydanie novej verzie

```
script/build.sh                 # verzia → podpísaný APK → SFTP upload
script/build.sh --play          # AAB pre Google Play (bez self-updateru)
```

Podrobnosti, inštalácia na tablet a nastavenie tlačiarní: `script/instal.txt`.

## Štruktúra

| Cesta | Obsah |
|---|---|
| `resido-client/` | Gradle projekt (Kotlin, minSdk 26, targetSdk 35) |
| `resido-client/app/src/main/java/sk/efabrica/resido/` | zdrojové kódy |
| `script/build.sh` | release skript |
| `script/.env` | `RESIDO_ANDROID_CLIENT_VERSION` |

**Pozor:** podpisový keystore (`resido-client/keystore/`) nie je v gite —
bez neho sa nedajú vydávať aktualizácie pre už nainštalované zariadenia.
