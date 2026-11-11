# Air Home для Android TV

Лаунчер для Android TV / Google TV 9+: приложения, HDMI, лента просмотра, обои с цветовой темой и настраиваемые цветные кнопки пульта. Air Remote работает независимо от него.

## Установка на телевизор

Скачайте [Air-Home.apk](https://github.com/belowevolve/a-ir-remote/releases/latest/download/Air-Home.apk). Нужны компьютер с [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) и доступ к ADB на своём ТВ. В релизах файл для телевизора называется `Air-Home.apk`; [сборка из исходников](#сборка) необязательна.

### 1. Подключить ADB

Включите режим разработчика и отладку в настройках ТВ. Телефон, компьютер и ТВ должны быть доступны в одной локальной сети.

Если прошивка предлагает **Беспроводную отладку**, используйте показанные телевизором адреса и порты:

```sh
adb pair TV_IP:PAIRING_PORT
adb connect TV_IP:DEBUG_PORT
adb devices
```

На моделях с обычной сетевой отладкой, включая проверенный Haier, подключение обычно выглядит как `adb connect TV_IP:5555`. Подтвердите доступ на экране ТВ. Один только переключатель USB-отладки не на всех моделях открывает порт 5555.

После перезагрузки повторно проверьте беспроводную отладку и её адрес: на проверенном Haier сетевой ADB сам не восстановился. Это не влияет на обычное управление через Air Remote.

Во всех командах ниже замените `TV_IP:PORT` реальным адресом из `adb devices`.

### 2. Установить APK и назначить домашний экран

Сначала сохраните имя нынешнего домашнего экрана — оно понадобится для возврата:

```sh
adb -s TV_IP:PORT shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s TV_IP:PORT install -r Air-Home.apk
adb -s TV_IP:PORT shell am start -n belowevolve.tvhome/.HomeActivity
```

Проверьте запуск Air Home, приложений и HDMI. Нажмите «Домой» и выберите Air Home как постоянный домашний экран. Если прошивка не показывает выбор:

```sh
adb -s TV_IP:PORT shell cmd package set-home-activity belowevolve.tvhome/.HomeActivity
```

Для возврата выполните ту же команду с сохранённым компонентом прежнего лаунчера. Удалять штатный лаунчер для обычной установки не требуется. Обновляйте Air Home через `install -r` APK с прежней подписью.

### 3. Включить нужные службы

| Возможность | Что включить |
| --- | --- |
| Цветные кнопки поверх приложений, закрытие заставки и сообщения текущей темы | **Настройки Android TV → Специальные возможности → Air Home · цветные кнопки** |
| Точная перемотка совместимых плееров | **Настройки Air Home → Управление медиа**, системное разрешение доступа к уведомлениям |
| Голосовые дополнения с телефона | Voice-сборка Air Remote → **Настройки ТВ → Голосовое управление**, переключатель в заголовке **Air Home** |

Служба управления медиа используется для доступа к системным MediaSession; содержимое уведомлений не читается и не сохраняется. Если прошивка не предоставляет экран разрешения:

```sh
adb -s TV_IP:PORT shell cmd notification allow_listener belowevolve.tvhome/.MediaAccessService
```

Отозвать разрешение: `adb -s TV_IP:PORT shell cmd notification disallow_listener belowevolve.tvhome/.MediaAccessService`.

После замены APK прошивка иногда оставляет службу цветных кнопок включённой, но не подключённой. Выключите и включите её в специальных возможностях. Другие службы отключать не нужно.

### 4. Установить приложения

**TizenTube Cobalt** — отдельное приложение [reisxd и участников](https://github.com/reisxd/TizenTubeCobalt) с блокировкой рекламы и SponsorBlock. Скачайте подходящий APK из [официальных релизов](https://github.com/reisxd/TizenTubeCobalt/releases/latest), установите через `adb -s TV_IP:PORT install -r /path/to/tizentube.apk` и откройте один раз. Архитектуру Android на ТВ можно узнать командой `adb -s TV_IP:PORT shell getprop ro.product.cpu.abilist`; она может быть 32-битной даже на 64-битном процессоре. Команда «Эйр, Ютуб» с дополнениями Air Home открывает TizenTube Cobalt. [Канал проекта](https://t.me/tizentubeofficial).

**film.mov** — проект [gamahacka](https://github.com/gamahacka/filmmov). Рекомендуется film.mov с дополнительными модификациями по [нашему гайду](integrations/film/README.md). [Официальный канал и установка](https://t.me/filmmov_app) · [Поддержать автора film.mov](https://t.me/tribute/app?startapp=dSVL).

Приложения устанавливаются отдельно; Air Home не скачивает и не заменяет их автоматически. Для повседневной работы ADB не нужен, после настройки отладку можно выключить.

## Необязательная очистка через CleanYourTV

[CleanYourTV](https://github.com/Gathin23/cleanyourtv) — отдельный проект Gathin23 и участников, с инструментами отключения ненужных пакетов и восстановления состояния. Для установки Air Home очистка не обязательна.

Если хотите её выполнить, используйте список именно для своей модели и прошивки и штатные `tools/disable.sh` / `tools/restore.sh` из документации проекта. Сохраните созданную запись восстановления. Установщик лаунчера CleanYourTV назначает его собственный домашний экран; для Air Home используйте шаги выше.

Сохраните Android TV Remote Service (`com.google.android.tv.remote.service`), системные настройки, TV Input / HDMI, специальные возможности и используемую заставку. На проверенном Haier удаление или отключение служб ради очистки не входит в установку Air Home. Универсальный список отключений для всех ТВ здесь не предлагается.

## Поведение и проверка

- Цветные кнопки настраиваются в **Настройки → Кнопки пульта**. Запуск приложений закрывает заставку и восстанавливает выбранный экран после системного перехода через HOME.
- Если HDMI не даёт изображение, окно «Нет изображения с HDMI» остаётся на десять секунд, затем открывается главная. Сообщение TV Input Service об отсутствии сигнала показывает окно сразу; неизвестное состояние сначала ждёт до пяти секунд. Изображение или источник со звуком отменяют возврат.
- Сообщения действий используют значок пульта и палитру текущих обоев, не забирают фокус у плеера.

### Системная перемотка

Перемотка отправляет одну стандартную команду `MediaController.TransportControls.seekTo`. Плеер должен предоставлять MediaSession, позицию и `ACTION_SEEK_TO`; HDMI и неперематываемый эфир этим требованиям не соответствуют. Успех подтверждается новой позицией от плеера; без ответа сообщение говорит только об отправке. Film.mov с дополнительными модификациями и TizenTube проверены на Haier. Другие плееры зависят от своей реализации MediaSession.

## Сборка

Из корня проекта, JDK 25 и Android SDK 37:

```sh
./gradlew :tv:testDebugUnitTest :tv:assembleRelease :tv:lintRelease
```

APK: `tv/build/outputs/apk/release/tv-release.apk`. Release использует отдельный приватный ключ `.signing/tv-release.p12`, alias `air-home`; пароль находится в `.signing/tv-password`. Сохраните эти файлы вне Git. Для своей сборки создайте собственный PKCS12-ключ с этим alias. Обновления требуют прежней подписи; переход с локальной debug-сборки на публичный APK с другим ключом потребует переустановки.

Workflow `Release` собирает Air Home вместе с двумя вариантами пульта и публикует подписанный `Air-Home.apk` с SHA-256. CI использует `TV_SIGNING_KEY_BASE64` и `TV_SIGNING_PASSWORD`; подпись выполняется отдельно от сборки, ключ не входит в APK и артефакты. С `-PunsignedRelease=true` Gradle создаёт `tv-release-unsigned.apk` для отдельной подписи.

[Ключи, безопасность CI и перенос данных при смене подписи](../docs/development/signing.md).

`ANDROID_SERIAL=TV_IP:PORT ./gradlew :tv:installRelease` устанавливает APK вместе с профилем запуска и прокрутки. Проверки находятся в `src/androidTest/`; `LauncherRegressionRunner` использует debug-сборку лаунчера и тестовый APK той же подписи. После instrumentation повторно включите службу цветных кнопок, если она осталась без подключения.

Замер вывода кадров: `python3 scripts/check_tv_frame_pacing.py --tv TV_IP:PORT --screen home`. Для настроек используйте `--screen settings`. Во время замера не управляйте ТВ вручную; гистограмма интервалов SurfaceFlinger сама по себе не измеряет запас CPU/GPU.

## Авторство

Air Home — [belowevolve](https://github.com/belowevolve). Авторство и лицензии встроенных и загружаемых обоев указаны в [CREDITS](src/main/assets/CREDITS.md). [Material Icons](third-party/material-icons/README.md) и [Material Color Utilities](third-party/material-color-utilities/README.md) — Google, Apache-2.0. TizenTube Cobalt, film.mov и CleanYourTV принадлежат их авторам; ссылки приведены выше.

[Основной README](../README.md) · [Голосовые команды](../docs/voice.md)
