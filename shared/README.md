# Shared

Общая Android-библиотека интерфейса для Air Remote и Air Home.

- `AirButton`, `AirToggleRow` — кнопки и переключатели с касанием и D-pad-фокусом.
- `RemotePalette`, `RemoteShapes`, `RemoteSpacing` — цвета, скругления и отступы.
- `RemoteIcons` — общие векторные иконки.
- `SeekText` — формат длительности и сообщений перемотки.
- `PosterBadgeSpec` — размеры и формат бейджей сезона/серии для лаунчера и адаптера film.mov.

Библиотека не содержит сервисов и разрешений. Навигация и соединения остаются в приложениях.

Шкала отступов: 4 / 8 / 12 / 16 / 24 dp. Внутренние элементы используют меньший шаг, соседние группы — больший; размер зоны нажатия задаётся отдельно от визуального отступа.

```sh
./gradlew :shared:assembleDebug
```

Material Icons распространяются по Apache-2.0: [LICENSE](src/main/assets/third-party/material-icons/LICENSE), [NOTICE](src/main/assets/third-party/material-icons/NOTICE).

[Основной README](../README.md) · [Android-приложение](../app/README.md) · [Air Home](../tv/README.md)
