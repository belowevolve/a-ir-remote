# Air Remote

Пульт для Android TV, Bluetooth-клавиатура и трекпад для ПК, Wi-Fi-микрофон для Windows.

## Подключение

- **ТВ:** одна Wi-Fi-сеть → выберите телевизор → введите код с его экрана.
- **ПК:** включите видимость во вкладке ПК → выполните Bluetooth-сопряжение. Один палец двигает курсор, два прокручивают; удержание включает перетаскивание. Win + Space переключает язык Windows
- **Микрофон:** установите на Windows AndroidMic и VB-CABLE, выберите TCP и укажите адрес ПК в приложении. [Короткая инструкция](docs/microphone.md).
## Сборка

Нужны Android SDK 37 и JDK 17 или новее.

```sh
./gradlew assembleDebug
# Подписанный APK (ключ сохраняется в .signing/):
bash scripts/build-release.sh
```

Протокол ТВ основан на [androidtvremote2](https://github.com/tronikos/androidtvremote2) и [Google TV pairing protocol](https://android.googlesource.com/platform/external/google-tv-pairing-protocol/). Лицензия: [Apache-2.0](THIRD_PARTY_LICENSE_androidtvremote2).
