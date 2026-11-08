# Air Remote

Android-пульт для телевизора и ПК. Kotlin, Jetpack Compose, Android 9+.

- Android TV: кнопки, голосовой поиск и ввод текста по Wi-Fi с PIN-сопряжением.
- ПК: Bluetooth-клавиатура и трекпад без дополнительных приложений.
- С Air Companion: клавиатура Android, вставка текста и передача микрофона.
- ИК-питание работает без сетевого соединения при наличии подходящего профиля. Встроенный профиль — Haier Android TV PRO.

Общие компоненты интерфейса находятся в [`:shared`](../shared/README.md).

## Сборка

Нужны JDK 25 и Android SDK 37. Команды из корня репозитория:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleRelease :app:lintRelease
```

Release APK: `app/build/outputs/apk/release/Air-Remote-<версия>.apk`.
Ключ подписи — `.signing/release.p12`, пароль — `.signing/password`; эти файлы не входят в репозиторий. Обновления требуют прежнего ключа.

## Публикация

Увеличьте `versionCode` и `versionName` и отправьте изменения в `master`. Workflow `Release` проверит приложения и опубликует APK вместе с установщиком Air Companion. Повторные push с уже опубликованной версией пропускаются.

Ключ и пароль хранятся в GitHub Actions secrets `ANDROID_SIGNING_KEY_BASE64` и `ANDROID_SIGNING_PASSWORD`. В релизах используются постоянные имена `Air-Remote.apk` и `Air-Companion-Setup.exe`.

[Проверки на устройстве](src/androidTest/README.md) · [Микрофон](../docs/microphone.md) · [Основной README](../README.md)
