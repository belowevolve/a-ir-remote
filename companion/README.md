# Air Companion

Компаньон обеих сборок Air Remote для Windows 10/11 x64. Добавляет передачу микрофона, синхронизацию раскладки, ввод с клавиатуры Android и вставку текста с телефона. Обычные Bluetooth-клавиатура и трекпад работают без него.

[Скачайте установщик](https://github.com/belowevolve/a-ir-remote/releases/latest/download/Air-Companion-Setup.exe), установите приложение, откройте его окно и отсканируйте QR-код пультом. Оба устройства должны быть в одной локальной сети. Соединение защищено TLS.

Для микрофона выберите **CABLE Output** в Discord, OBS или другом приложении. Проверка звука и запись трёх секунд запускаются кнопками в окне компаньона. [Подключение микрофона](../docs/microphone.md).

## Сборка

Из корня репозитория:

```sh
cargo test --locked --manifest-path companion/Cargo.toml
cargo fmt --check --manifest-path companion/Cargo.toml
cargo clippy --locked --manifest-path companion/Cargo.toml --all-targets -- -D clippy::all
python3 companion/scripts/build-installer.py
```

Упаковка локально и в GitHub Actions использует один скрипт. Нужны Python 3 и NSIS; на Windows — Rust MSVC, на macOS/Linux — target `x86_64-pc-windows-gnu` и MinGW. EXE появляется в `companion/target/<target>/release/`, установщик — в `artifacts/`.

`scripts/collect-notices.py` собирает лицензии зависимостей для установщика, `scripts/make-icon.py` пересоздаёт Windows-иконку.

Диагностика звука собирается отдельно из `examples/`.
[Основной README](../README.md).

Микрофон выбирается компонентом одного установщика; отдельные варианты EXE для него не публикуются. VB-CABLE — [VB-Audio](https://vb-audio.com/Cable/), donationware; название и авторство сохраняются в установщике.
