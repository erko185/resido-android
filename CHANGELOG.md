# Changelog — Resido Android client

All notable changes to the Android client (native Kotlin WebView shell for the
Resido web app). Versions match `RESIDO_ANDROID_CLIENT_VERSION` in
`script/.env`.

## [Unreleased]

### Fixed

- **Bon tails were cut off on printers with a long head-to-cutter distance (CK710)**: the feed before the cut used `ESC d 6` (six text lines), whose real length depends on the firmware's line spacing and fell short of the CK710's head-to-cutter distance — the last line(s) stayed behind the blade and came out on top of the next bon. The feed is now 36 mm of blank raster rows, which every printer advances dot-exactly (`ESC J`, the dot-based feed command, turned out to be ignored outright by XP-80 clones, so no pure feed command is trusted anymore). Matches Windows client 3.7.6.

## [1.0.15] - 2026-07-31

### Fixed

- **Receipts printed with the wrong size / partly blank**: several device-specific rendering problems, found on a Xiaomi (MIUI) phone and fixed one by one —
  - the print WebView is attached to the window (1×1 px, isolated from the UI): Chromium does not rasterize window-detached WebViews on some devices, which produced blank or partial receipts;
  - drawing retries until pixels appear (Chromium produces the first rasterizable frame asynchronously);
  - the page scale is calibrated per print and the bitmap is scaled on the Canvas instead of relying on `setInitialScale` / CSS zoom, which behave differently across WebView versions;
  - the system font-size setting no longer affects print output (`textZoom = 100`);
  - scrollbars are no longer rasterized into the receipt.
- **Printing froze the app**: the render WebView's layout requests were leaking into the activity (UI flicker and multi-second freezes) — it now lives in an isolating container, and ESC/POS encoding runs off the main thread.
- **Bluetooth printing failures**: `SecurityException` on Android 12 (MIUI checks the legacy `BLUETOOTH` permission on socket connect), broken pipes when two prints raced for one RFCOMM link (printing is now serialized), and rejected first connections (retry with an insecure socket).
- **Bon printouts came out as garbage characters**: raster chunks are capped at 255 rows (cheap firmwares only read the low byte of the row count) and Bluetooth writes are throttled to 512-byte chunks so the printer's buffer cannot overflow.
- **Paper was not cut**: switched to the widely implemented `GS V 0` full cut with a longer feed (Xprinter V330N ignores `GS V B n` over Bluetooth).
- **Blank space at the top of receipts** — leading blank raster rows are trimmed.
- **Settings screen was not usable in portrait** — the card is no longer a fixed width and the keyboard no longer covers the fields.
- Bluetooth permission is requested as soon as a slot is switched to Bluetooth, and a permanently denied permission opens the system settings page.
- Manual update check offers the download immediately instead of only reporting a new version.

## [1.0.0] - 2026-07-30

### Added

- Initial Android client: native Kotlin WebView shell loading `<server>/resido/`, with a drop-in compatible `window.reservationClient` bridge (Promise API over a synchronous JavascriptInterface), so the server needs no changes.
- Silent ESC/POS printing to thermal printers over network (TCP 9100) and Bluetooth SPP — a receipt printer plus four bon printers, honouring the `paperWidth` parameter, `--receipt-width`/`--bon-width` CSS variables and the `print-copies` meta tag.
- Native settings screen: server URL, five printer slots with test print, update check.
- Self-update from `residoandroid.vorntech.sk` (`latest.json` + APK, sideload distribution) with a `play` build flavor for Google Play (no self-updater, per store policy).
- Injected "Nastavenia" and back buttons matching the desktop clients.
