# Air Remote

Пульт для Android TV по локальной сети и ИК-передатчику телефона.

## Особенности протокола

- Схемы `polo.proto` и `remotemessage.proto` генерируются Gradle при сборке. Не редактируйте файлы в `app/build/generated`.
- Телефон и телевизор должны быть в одной сети. Сопряжение использует шестизначный код с экрана ТВ; сертификат телевизора закрепляется после проверки кода.
- Голосовой ввод передаёт моно PCM 8 кГц и требует разрешение на микрофон.

## ИК-питание

Питание по ИК работает только на телефонах с ИК-передатчиком. Профиль сохраняется отдельно от подключения к телевизору; сначала проверьте команду в настройках.

## Проверка

```sh
./gradlew lintDebug testDebugUnitTest assembleDebug
```

## Источники протокола

- [androidtvremote2](https://github.com/tronikos/androidtvremote2) — Apache-2.0; текст лицензии: `THIRD_PARTY_LICENSE_androidtvremote2`.
- [Google TV pairing protocol](https://android.googlesource.com/platform/external/google-tv-pairing-protocol/).
