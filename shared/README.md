# Shared

Общая Android-библиотека интерфейса для Air Remote и Air Home.

- `AirButton`, `AirToggleRow` — кнопки и переключатели с касанием и D-pad-фокусом.
- `RemotePalette`, `RemoteShapes`, `RemoteSpacing` — цвета, скругления и отступы.
- `RemoteIcons` — общие векторные иконки.

Библиотека не содержит сервисов и разрешений. Навигация и соединения остаются в приложениях.

```sh
./gradlew :shared:assembleDebug
```

Material Icons распространяются по Apache-2.0: [LICENSE](src/main/assets/third-party/material-icons/LICENSE), [NOTICE](src/main/assets/third-party/material-icons/NOTICE).
