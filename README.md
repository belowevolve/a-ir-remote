# Air Remote

Пульт для Android TV, Bluetooth-клавиатура и трекпад для ПК, Wi-Fi-микрофон.

- **ТВ:** одна Wi-Fi-сеть → выберите телевизор → введите код с его экрана.
- **ПК:** включите видимость во вкладке ПК и выполните Bluetooth-сопряжение. Один палец двигает курсор, два прокручивают; удержание включает перетаскивание.
- **Компаньон Windows:** установите Air Remote Setup, затем в приложении откройте ПК → Устройства → Компаньон и отсканируйте QR. Раскладка RU / EN синхронизируется с активным окном Windows. Закрытие окна компаньона оставляет его в трее.
- **Микрофон:** компонент VB-CABLE включён в установщик. [Подключение](docs/microphone.md).

## Сборка

Android: SDK 37 и JDK 17+. Компаньон: Rust, NSIS и MinGW; Windows 10/11 x64.

```sh
bash scripts/build-release.sh
companion/scripts/build-windows.sh
```

Протокол ТВ основан на [androidtvremote2](https://github.com/tronikos/androidtvremote2) и [Google TV pairing protocol](https://android.googlesource.com/platform/external/google-tv-pairing-protocol/), [Apache-2.0](THIRD_PARTY_LICENSE_androidtvremote2).
