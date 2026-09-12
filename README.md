# Air Remote

Personal Android TV remote for realme GT 7 + Haier S2 Pro. Kotlin + Jetpack Compose, no backend, ads or WebView.

## Development

JDK 17, Android SDK platform 35 / build tools 35.0.0. Put your SDK path in untracked `local.properties` (`sdk.dir=...`).

```sh
./gradlew assembleDebug testDebugUnitTest
./gradlew assembleLocal  # optimized, signed with this Mac's debug key for personal use
AIR_DEVICE=your-adb-device ./scripts/dev.sh
```

On this Mac the script finds Homebrew's JDK 17. `adb devices -l` lists device identifiers. Wireless ADB can show the same phone twice; select either active identifier explicitly.

The Android Studio project can be opened directly for Compose previews / Live Edit. Command-line development supports rebuild + reinstall without Android Studio.

## Device setup

Open settings, select the discovered TV, and enter its six-character pairing code. Phone and TV must be on the same LAN. The RSA client key stays in Android Keystore (TLS raw-RSA operations are authorized for Conscrypt); the TV certificate is pinned after verification of the pairing PIN. Backup is disabled. The foreground app reconnects to the saved address. If DHCP changes the address, select and pair again.

Power uses a separately saved IR profile and never depends on the TV network connection. Test the provided Haier profile, then save only if the TV responds. It is a NEC 0x04 / 0x08 profile documented for Haier L42C1180; power operation on this Haier S2 Pro was confirmed by the user on 2026-09-12. No automatic power command is sent on connection failure.

Text requires an active compatible input field on the TV. Voice requests Android microphone permission, negotiates a voice session with the TV, and streams mono 8 kHz PCM. Tap Stop to finish; recording also stops after 30 seconds or when the app leaves the foreground. These features require verification on the specific TV/apps.

## Sources

- Protocol schemas: https://github.com/tronikos/androidtvremote2 (Apache-2.0, see `THIRD_PARTY_LICENSE_androidtvremote2`). Original schema provenance is retained in the `.proto` files.
- Pairing protocol: https://android.googlesource.com/platform/external/google-tv-pairing-protocol/
- Haier power command: https://github.com/Lucaslhm/Flipper-IRDB/blob/main/TVs/Haier/Haier_L42C1180.ir

## Current limits

Verified on realme GT 7 (Android 16): installation, rendering, IR hardware availability, LAN discovery, PIN pairing, reconnect after optimized-build update, and Android TV control-session handshake with Haier Android TV PRO. Two IR encoding/validation unit tests pass; Android Lint has no errors. The user also confirmed the IR power profile works and saved it. Physical key response, text and voice are pending user verification. No touchpad, app shortcuts or long-press key repeat yet. The optimized local APK is approximately 2.3 MB. One ADB `am start -W` cold launch on the realme GT 7 reported 136 ms (first activity draw, not TV connection readiness); this is a single observation, not a benchmark. The debug APK is for iteration, not a size/startup benchmark. The `local` build enables shrinking and uses the local debug signing key for personal device installation. The `release` build remains unsigned for a future distribution signing setup.
