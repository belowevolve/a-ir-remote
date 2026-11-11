![Air Remote — ТВ, ПК и микрофон](assets/readme/cover.svg)

<p align="center">
  <a href="https://github.com/belowevolve/a-ir-remote/releases/latest/download/Air-Remote.apk"><img src="assets/readme/android.svg" alt="Скачать Air Remote" height="42"></a>
  <a href="https://github.com/belowevolve/a-ir-remote/releases/latest/download/Air-Companion-Setup.exe"><img src="assets/readme/windows.svg" alt="Скачать Air Companion" height="42"></a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%7B%22id%22%3A%22belowevolve.airremote%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fbelowevolve%2Fa-ir-remote%22%2C%22author%22%3A%22belowevolve%22%2C%22name%22%3A%22Air%20Remote%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22%5EAir-Remote%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D"><img src="assets/readme/obtainium.svg" alt="Добавить обычную сборку в Obtainium" height="42"></a>
</p>

<p align="center">Android 9+ · Android TV / Google TV · Windows 10/11 x64</p>

**Air Remote** — пульт для телевизора и ПК: кнопки, клавиатура, трекпад и передача микрофона с телефона. На Android 12+ цвета интерфейса подстраиваются под тему телефона. Аккаунт и облачный сервер не нужны.

![ТВ, ПК и микрофон — экраны Air Remote](assets/readme/preview.png)

## Скачать

| Приложение | Для чего | Скачать |
| --- | --- | --- |
| Air Remote | Пульт, ПК, микрофон; без фоновых команд «Эйр» | [APK](https://github.com/belowevolve/a-ir-remote/releases/latest/download/Air-Remote.apk) · [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%7B%22id%22%3A%22belowevolve.airremote%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fbelowevolve%2Fa-ir-remote%22%2C%22author%22%3A%22belowevolve%22%2C%22name%22%3A%22Air%20Remote%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22%5EAir-Remote%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D) |
| Air Remote Voice | Всё то же + локальные голосовые команды «Эйр» | [APK](https://github.com/belowevolve/a-ir-remote/releases/latest/download/Air-Remote-Voice.apk) · [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%7B%22id%22%3A%22belowevolve.airremote%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fbelowevolve%2Fa-ir-remote%22%2C%22author%22%3A%22belowevolve%22%2C%22name%22%3A%22Air%20Remote%20Voice%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22%5EAir-Remote-Voice%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D) |
| Air Home | Лаунчер для Android TV / Google TV | [APK](https://github.com/belowevolve/a-ir-remote/releases/latest/download/Air-Home.apk) · [Установка](tv/README.md#установка-на-телевизор) |
| Air Companion | Компаньон для Windows | [Установщик](https://github.com/belowevolve/a-ir-remote/releases/latest/download/Air-Companion-Setup.exe) |

Оба APK пульта поддерживают ARM32 и ARM64. Микрофон для ПК и голосовой поиск ТВ есть в обеих сборках; крупная русская модель включена только в Voice. Можно сменить сборку установкой поверх приложения: сопряжения сохранятся. При первом открытии обычной сборки прежняя модель Эйра удаляется из данных.

## Подключить устройства

- **Телевизор:** выберите Android TV / Google TV в пульте и введите код с экрана ТВ. Устройства должны находиться в одной домашней сети; нужна совместимая Android TV Remote Service. [Подробнее](app/README.md#телевизор).
- **ПК:** Bluetooth-клавиатура и трекпад работают без компаньона. Для клавиатуры Android, вставки текста и передачи микрофона установите [Air Companion](companion/README.md) и отсканируйте его QR-код.
- **Микрофон:** [настройка телефона и Windows](docs/microphone.md).
- **Эйр:** [включение прослушивания и список команд](docs/voice.md), только в сборке Voice.

[Air Home](tv/README.md) — отдельный необязательный лаунчер Android TV: приложения, HDMI, обои и лента просмотра. [Установка на телевизор и настройка служб](tv/README.md#установка-на-телевизор). Обычный пульт работает без него.

## Разработка и авторы

[Сборка Android](app/README.md#сборка) · [Air Home](tv/README.md#сборка) · [Windows](companion/README.md#сборка) · [Общие компоненты](shared/README.md).

Air Remote, Air Home и Air Companion — [belowevolve](https://github.com/belowevolve). Сторонние приложения устанавливаются отдельно:

- **TizenTube Cobalt** — [reisxd и участники проекта](https://github.com/reisxd/TizenTubeCobalt); [релизы](https://github.com/reisxd/TizenTubeCobalt/releases/latest).
- **film.mov** — [gamahacka](https://github.com/gamahacka/filmmov); [официальный канал](https://t.me/filmmov_app), [поддержать автора](https://t.me/tribute/app?startapp=dSVL).
- **CleanYourTV** — [Gathin23 и участники](https://github.com/Gathin23/cleanyourtv), необязательные инструменты очистки ТВ.
- **Vosk** — [Alpha Cephei](https://alphacephei.com/vosk/), локальное распознавание в Voice, Apache-2.0.
- **VB-CABLE** — [VB-Audio](https://vb-audio.com/Cable/), donationware-компонент микрофона Windows.

Протокол ТВ: [androidtvremote2](https://github.com/tronikos/androidtvremote2), [Google TV pairing](https://android.googlesource.com/platform/external/google-tv-pairing-protocol/), [Apache-2.0](THIRD_PARTY_LICENSE_androidtvremote2). Авторство обоев и компонентов Air Home — в [CREDITS](tv/src/main/assets/CREDITS.md).
