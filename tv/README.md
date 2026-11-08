# Air Home

Лаунчер Android TV: приложения, лента film.mov, обои, темы и настраиваемые цветные кнопки. При пропаже HDMI-сигнала возвращается на главную через шесть секунд.

Пульт Air Remote работает независимо от лаунчера. Обработка HDMI зависит от TV Input Service прошивки телевизора.

Интерфейс написан на Kotlin и Jetpack Compose; системный HDMI использует `TvView`. Общие компоненты находятся в [`:shared`](../shared/README.md), адаптеры film.mov — в [`integrations/film`](integrations/film/README.md).

Автор и лицензия встроенных обоев указаны в [CREDITS](src/main/assets/CREDITS.md).

## Сборка

Из корня репозитория, с JDK 25 и Android SDK 37:

```sh
./gradlew :tv:testDebugUnitTest :tv:assembleRelease :tv:lintRelease
```

APK: `tv/build/outputs/apk/release/tv-release.apk`.
Release использует стандартный Android debug-ключ. Для обновления установленного лаунчера и интеграции палитры film.mov нужна прежняя подпись.

Проверки на устройстве находятся в `src/androidTest/`; они запускаются отдельно через `LauncherRegressionRunner`.

[Основной README](../README.md).
