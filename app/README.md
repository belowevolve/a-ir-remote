# Air Remote для Android

Пульт для Android TV / Google TV и Windows, Android 9+. Интерфейс на Kotlin и Jetpack Compose, общие компоненты — в [`:shared`](../shared/README.md).

## Выбрать сборку

| Файл в релизе | Состав |
| --- | --- |
| `Air-Remote.apk` | ТВ-пульт, Bluetooth-клавиатура и трекпад ПК, Air Companion, микрофон, голосовой поиск ТВ |
| `Air-Remote-Voice.apk` | Всё из обычной сборки + фоновые команды «Эйр» и русская офлайн-модель |

Обе сборки поддерживают ARM32 / ARM64 и используют один ID приложения и одну подпись. Переключение между ними сохраняет настройки и сопряжения; одновременно ставить обе не требуется. Обычная сборка при первом открытии удаляет распакованную модель от ранее установленной Voice. Команды Эйра работают только в Voice.

Микрофон для ПК не требует модели распознавания, поэтому отдельной сборки «с микрофоном» нет. Debug-сборки также поддерживают процессоры эмуляторов; release содержит только ARM.

[Скачать](https://github.com/belowevolve/a-ir-remote/releases/latest). Для ручной настройки Obtainium выберите фильтр имени APK `^Air-Remote\.apk$` или `^Air-Remote-Voice\.apk$` — он закрепляет выбранную сборку.

## Телевизор

1. Подключите телефон и Android TV / Google TV к одной домашней сети. На ТВ нужна совместимая **Android TV Remote Service**.
2. В пульте откройте **ТВ → Подключить ТВ**, выберите телевизор и введите показанный им код.
3. Кнопки, текст и голосовой поиск ТВ работают через штатный сетевой протокол пульта. Быстрое включение зависит от сетевого режима ожидания прошивки ТВ.

[Air Home](../tv/README.md) необязателен. Его дополнения дают запуск фильма, последний HDMI, точную громкость, системную перемотку и выход из заставки. Для Эйра переключатель находится в **Настройки ТВ → Голосовое управление → Air Home**.

Ручное ИК-питание доступно на телефонах с ИК-излучателем и подходящим профилем; встроенный профиль — Haier Android TV PRO.

## ПК и микрофон

Обычные клавиатура и трекпад подключаются по Bluetooth. [Air Companion](../companion/README.md) добавляет вставку текста, клавиатуру Android, синхронизацию раскладки и [передачу микрофона](../docs/microphone.md) по локальной сети.

[Голосовые команды Эйра](../docs/voice.md) · [Проверки на устройстве](src/androidTest/README.md).

## Сборка

Нужны JDK 25 и Android SDK 37. Из корня репозитория:

```sh
./gradlew :app:assembleStandardDebug :app:assembleVoiceDebug
./gradlew :app:testStandardDebugUnitTest :app:testVoiceDebugUnitTest
./gradlew :app:assembleStandardRelease :app:assembleVoiceRelease
./gradlew :app:lintStandardRelease :app:lintVoiceRelease
```

| Вариант | Локальный APK |
| --- | --- |
| Обычный release | `app/build/outputs/apk/standard/release/Air-Remote-<версия>.apk` |
| Voice release | `app/build/outputs/apk/voice/release/Air-Remote-Voice-<версия>.apk` |
| Debug | `app/build/outputs/apk/<standard или voice>/debug/app-<вариант>-debug.apk` |

Стандартная сборка не зависит от Vosk и не запускает задачу загрузки модели. Для Voice Gradle один раз скачивает модель с сайта Alpha Cephei, сверяет закреплённый SHA-256 и добавляет её в APK.

Release подписывается `.signing/release.p12`, пароль читается из `.signing/password`; файлы не входят в Git. Обновление требует прежнего ключа. Конфигурация вариантов использует [product flavors](https://developer.android.com/build/build-variants); два ARM ABI включены в каждый release APK без дополнительных файлов выбора процессора.

## Публикация

Увеличьте `versionCode` и `versionName`. После push в `master` workflow `Release` проверяет обе сборки и публикует `Air-Remote.apk`, `Air-Remote-Voice.apk`, `Air-Home.apk`, `Air-Companion-Setup.exe` и SHA-256. Уже опубликованная версия повторно не создаётся.

Скрипт `scripts/release_metadata.py` требует Python 3.11+. GitHub Actions использует secrets `ANDROID_SIGNING_KEY_BASE64` и `ANDROID_SIGNING_PASSWORD`, а для Air Home — отдельные `TV_SIGNING_KEY_BASE64` и `TV_SIGNING_PASSWORD`. Gradle собирает без ключей (`-PunsignedRelease=true`); отдельный job без checkout и Gradle подписывает APK, сверяет отпечатки сертификатов и удаляет временные ключи. Сборки из pull request секретов подписи не получают. Варианты пульта имеют одинаковую версию; Obtainium должен фильтровать файл по имени.

[Основной README](../README.md)

[Ключи и безопасность релизной сборки](../docs/development/signing.md).
