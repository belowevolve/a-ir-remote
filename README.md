# Air Remote

Пульт для Android TV, Bluetooth-клавиатура и трекпад для ПК, Wi-Fi-микрофон.

## Установка и обновления

Android 9 и новее: скачайте APK из [последнего релиза](https://github.com/belowevolve/a-ir-remote/releases/latest).

[Добавить в Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22belowevolve.airremote%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fbelowevolve%2Fa-ir-remote%22%2C%22author%22%3A%22belowevolve%22%2C%22name%22%3A%22Air%20Remote%22%7D) для последующих обновлений. Можно также вручную добавить в Obtainium адрес `https://github.com/belowevolve/a-ir-remote`. В каждом релизе один универсальный APK, фильтры не нужны. [Документация Obtainium](https://wiki.obtainium.imranr.dev/sources/).

Обычный пакет — `belowevolve.airremote`; Dev — `belowevolve.airremote.dev`. Релизный APK обновляет обычную версию, подписанную тем же ключом. Dev устанавливается отдельно.

## Подключение

- **ТВ:** одна Wi-Fi-сеть → выберите телевизор → введите код с его экрана.
- **ПК:** включите видимость во вкладке ПК и выполните Bluetooth-сопряжение. Один палец двигает курсор, два прокручивают; удержание включает перетаскивание.
- **Компаньон Windows:** установите Air Remote Setup, затем в приложении откройте ПК → Устройства → Компаньон и отсканируйте QR. Раскладка RU / EN синхронизируется с активным окном Windows. Закрытие окна компаньона оставляет его в трее.
- **Микрофон:** компонент VB-CABLE включён в установщик. [Подключение](docs/microphone.md).

Обычная команда при подключённом ТВ сначала пробуждает подтверждённо выключенный экран, затем выполняется. Открытие пульта без нажатий экран не включает. Питание, ИК и отпускание удерживаемых кнопок обрабатываются отдельно; повторные нажатия не добавляют лишний POWER. Сразу после выключения даётся завершиться переходу в ожидание; для уже спящего ТВ этой паузы нет.

## Сборка

Android: SDK 37, build-tools 36.0.0, JDK 25 и Python 3. Gradle использует JetBrains JDK 25 согласно `gradle/gradle-daemon-jvm.properties`. Задайте `ANDROID_HOME` и `JAVA_HOME`; на macOS скрипт также находит JDK из стандартной установки Android Studio. Компаньон: Rust, NSIS и MinGW; Windows 10/11 x64.

```sh
bash scripts/build-release.sh
companion/scripts/build-windows.sh
```

Android-скрипт запускает unit-тесты и lint, собирает release APK с R8, проверяет подпись и пакет, затем сохраняет `Air-Remote-<version>.apk` и `SHA256SUMS.txt` в `artifacts/release/<version>/`. Для release-сборки нужен исходный ключ `.signing/release.p12` и пароль в `.signing/password`; храните резервную копию этого каталога. Отпечаток публичного сертификата закреплён в `gradle/release-certificate.sha256`: APK с другим ключом не публикуется. Ключ и пароль остаются локально и не попадают в Git или GitHub Actions.

Без ключа можно собрать отдельную Dev-версию: `./gradlew assembleDebug`.

## Выпуск Android-релиза

1. Увеличьте `versionCode` и `versionName` в `app/build.gradle.kts`. `versionCode` должен быть больше, чем у всех предыдущих сборок, `versionName` — в формате `X.Y.Z`.
2. Добавьте описание в `docs/releases/<versionName>.md`, закоммитьте изменения и отправьте их в `master` на GitHub.
3. Выполните `bash scripts/publish-release.sh --dry-run` для проверки без публикации или `bash scripts/publish-release.sh` для выпуска. Нужны GitHub CLI и авторизация `gh auth login`.

Команда повторно проверяет сборку, создаёт тег `v<versionName>` и черновик GitHub Release с APK и SHA-256. После успешной загрузки публикует релиз как Latest, доступный Obtainium. Существующие релизы не перезаписываются. Если загрузка прервётся, черновик останется для проверки и ручного завершения.

GitHub Actions проверяет Android и компаньон при push и pull request. Подпись и публикация выполняются локальной командой выше.

Протокол ТВ основан на [androidtvremote2](https://github.com/tronikos/androidtvremote2) и [Google TV pairing protocol](https://android.googlesource.com/platform/external/google-tv-pairing-protocol/), [Apache-2.0](THIRD_PARTY_LICENSE_androidtvremote2).
