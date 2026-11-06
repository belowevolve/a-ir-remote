# Air Remote

Пульт Android TV, Bluetooth-клавиатура и трекпад для ПК, Wi-Fi-микрофон.

[Android APK](https://github.com/belowevolve/a-ir-remote/releases/download/v0.1.36/Air-Remote-0.1.36.apk) · [Windows Setup (.exe)](https://github.com/belowevolve/a-ir-remote/releases/download/v0.1.36/Air-Remote-Setup-0.5.6.exe) · [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22belowevolve.airremote%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fbelowevolve%2Fa-ir-remote%22%2C%22author%22%3A%22belowevolve%22%2C%22name%22%3A%22Air%20Remote%22%7D)

Android 9+. Компаньон: Windows 10/11 x64.

- **ТВ:** одна Wi-Fi-сеть, выбор телевизора, код с экрана.
- **ПК:** Bluetooth-сопряжение; Wi-Fi-компаньон подключается по QR.
- **Микрофон:** [настройка VB-CABLE](docs/microphone.md).

## Сборка

Android: SDK 37, build-tools 36.0.0, JDK 25. Компаньон: Rust, NSIS, MinGW.

```sh
./gradlew testDebugUnitTest lintDebug assembleRelease
companion/scripts/build-windows.sh
```

Gradle подписывает APK ключом `.signing/release.p12`, пароль — `.signing/password`. Эти файлы хранятся локально; для обновлений нужен тот же ключ.

APK: `app/build/outputs/apk/release/Air-Remote-<version>.apk`.

Релиз: увеличить `versionCode` и `versionName`, создать тег `v<version>` и приложить APK к GitHub Release.

Протокол ТВ: [androidtvremote2](https://github.com/tronikos/androidtvremote2), [Google TV pairing](https://android.googlesource.com/platform/external/google-tv-pairing-protocol/), [Apache-2.0](THIRD_PARTY_LICENSE_androidtvremote2).
