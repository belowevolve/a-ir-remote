# Проверки на устройстве

SDK-only runners могут работать с минифицированным release APK. Тестовый APK должен быть подписан тем же ключом, что и приложение.

```sh
./gradlew :app:assembleDebugAndroidTest
adb -s PHONE shell am instrument -w -r -e modalFixture true \
  belowevolve.airremote.test/belowevolve.airremote.ModalRegressionRunner
```

`ModalRegressionRunner` проверяет окна и подключения. Режимы задаются аргументами:

- `microphoneWithoutCompanion=true` — общий экран ПК/микрофона и закрытие вложенных окон.
- `tvPairingFixture=true` — PIN-сопряжение с тестовым TLS-сервером из `fixtures/`; адрес и DNS-SD-имя задаются `tvFixtureHost` и `tvFixtureName`.
- `bluetoothProgress=true` — лоадер на существующей Bluetooth-паре, выбранной через `bluetoothName`.
- `microphoneSettings=true` — переключатели волны и анимации иконки.

Настройки сохраняются перед прогоном и восстанавливаются в конце. После прерванного запуска используйте `restoreOnly=true` с тем же runner.

`PcNativeImeRunner` и `InputRegressionRunner` проверяют настоящий ввод и могут отправлять команды устройствам: используйте подготовленные тестовые поля и явные параметры сценария в исходниках.

`ScreenPreviewRunner` снимает содержимое окна без системных панелей, шириной до 720 px. Аргумент `screenPreview`: `tv`, `pc`, `pc-collapsed`, `mic`. Снимки сохраняются в `files/screen-previews/` приложения.
