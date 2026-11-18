# LCE Savegame Extractor for Android

An Android UI for extracting indexed files from Minecraft Legacy Console Edition save files, based on the supplied Old-LCE-SaveGame-Extractor project.

## Use
1. Select a save file with Android's system file picker.
2. Leave endianness on Auto-detect, or choose big-endian / little-endian manually.
3. Tap **Extract save**.
4. Export the extracted entries as a ZIP.

The app does not request broad storage access. It uses Android's document picker for input and output.

## Compatibility notes
The original project's README says compatibility may vary and lists these as tested: Xbox 360 TU75, PlayStation 3 1.0.0, Wii U latest, PlayStation 4 latest, and Nintendo Switch Edition. The original docs specify little-endian for Switch and big-endian for PS3, Wii U, and Xbox 360. PS Vita is not listed as tested, so Vita compatibility is unconfirmed. Xbox 360 CON/PIRS containers require separate decryption/decompression first.

## Build
The GitHub Actions workflow builds a debug APK on pushes to main/master. Download the artifact named `LCE-Save-Extractor-debug-apk` from the completed Actions run.
