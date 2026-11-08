# Air Companion

Компаньон Air Remote для Windows 10/11 x64. Добавляет передачу микрофона, синхронизацию раскладки, ввод с клавиатуры Android и вставку текста с телефона. Обычные Bluetooth-клавиатура и трекпад работают без него.

Установите приложение, откройте его окно и отсканируйте QR-код пультом. Оба устройства должны быть в одной локальной сети. Соединение защищено TLS.

Для микрофона выберите **CABLE Output** в Discord, OBS или другом приложении. Проверка звука и запись трёх секунд запускаются кнопками в окне компаньона. [Подключение микрофона](../docs/microphone.md).

## Сборка

Из корня репозитория:

```sh
cargo test --locked --manifest-path companion/Cargo.toml
cargo fmt --check --manifest-path companion/Cargo.toml
cargo clippy --locked --manifest-path companion/Cargo.toml --all-targets -- -D clippy::all
bash companion/scripts/build-windows.sh
```

Для установщика нужны Rust target `x86_64-pc-windows-gnu`, MinGW и NSIS. EXE появляется в `companion/target/x86_64-pc-windows-gnu/release/`, установщик — в `artifacts/`.

Звуковые диагностические программы находятся в `examples/`.
[Основной README](../README.md).
