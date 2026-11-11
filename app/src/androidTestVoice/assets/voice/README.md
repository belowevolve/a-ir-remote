# Synthetic Russian voice fixtures

Generated locally with macOS `say`, voice Milena, 170 words/minute.
Each file is signed 16-bit little-endian mono PCM at 16 kHz, with one second
of trailing silence for the streaming recognizer's endpointer.

| File | Text | Expected action |
| --- | --- | --- |
| air-tv.pcm | Эйр, тэ вэ. | TV_TOGGLE |
| air-film.pcm | Эйр, фильм. | FILM |
| air-youtube.pcm | Эйр, ютуб. | YOUTUBE |
| air-pc.pcm | Эйр, пэ ка. | none (removed command) |
| air-on.pcm | Эйр, включи телевизор. | TV_ON |
| air-off.pcm | Эйр, выключи телевизор. | TV_OFF |
| air-stop.pcm | Эйр, стоп. | PAUSE |
| air-ok.pcm | Эйр, ок. | OK |
| air-okay.pcm | Эйр, окей. | OK |
| air-back.pcm | Эйр, назад. | BACK |
| air-no-back.pcm | Эйр, не назад. | none |
| air-back-cancel.pcm | Эйр, назад не надо. | none |
| air-seek-15.pcm | Эйр, перемотай пятнадцать минут. | SEEK +900 s |
| air-seek-back-5.pcm | Эйр, перемотай пять минут назад. | SEEK -300 s |
| air-seek-seconds.pcm | Эйр, перемотай на двадцать одну секунду вперёд. | SEEK +21 s |
| air-seek-default.pcm | Эйр, перемотай. | SEEK +10 s |
| air-seek-default-back.pcm | Эйр, перемотай назад. | SEEK -10 s |
| air-rewind-default.pcm | Эйр, отмотай. | SEEK -10 s |
| air-rewind-5.pcm | Эйр, отмотай пять минут. | SEEK -300 s |
| air-rewind-seconds.pcm | Эйр, отмотай на двадцать одну секунду назад. | SEEK -21 s |
| air-no-rewind.pcm | Эйр, не отмотай. | none |
| air-rewind-incomplete.pcm | Эйр, отмотай пять. | none |
| air-rewind-cancel.pcm | Эйр, отмотай пять минут не надо. | none |
| air-no-seek.pcm | Эйр, не перемотай пять минут. | none |
| air-seek-incomplete.pcm | Эйр, перемотай пять. | none |
| air-seek-cancel.pcm | Эйр, перемотай пять минут не надо. | none |
| air-comp.pcm | Эйр, комп. | PC |
| air-pause.pcm | Эйр, пауза. | PAUSE |
| air-play.pcm | Эйр, плей. | PLAY |
| air-continue.pcm | Эйр, продолжай. | PLAY |
| air-next.pcm | Эйр, дальше. | PLAY |
| air-next-item.pcm | Эйр, следующее. | NEXT |
| air-next-male.pcm | Эйр, следующий. | NEXT |
| air-previous.pcm | Эйр, прошлое. | PREVIOUS |
| air-louder.pcm | Эйр, громче. | VOLUME_UP |
| air-quieter.pcm | Эйр, тише. | VOLUME_DOWN |
| air-louder-two.pcm | Эйр, громче два. | VOLUME_UP × 2 |
| air-quieter-three.pcm | Эйр, тише три. | VOLUME_DOWN × 3 |
| air-louder-cancel.pcm | Эйр, громче два не надо. | none |
| air-louder-invalid.pcm | Эйр, громче двадцать один. | none |
| air-volume-zero.pcm | Эйр, громкость ноль. | VOLUME_SET 0 |
| air-volume-ten.pcm | Эйр, громкость десять. | VOLUME_SET 10 |
| air-volume-45.pcm | Эйр, громкость сорок пять. | VOLUME_SET 45 |
| air-volume-45-pause.pcm | Эйр, громкость сорок … пять. | VOLUME_SET 45 |
| air-volume-100.pcm | Эйр, громкость сто. | VOLUME_SET 100 |
| air-volume-invalid.pcm | Эйр, громкость сто один. | none |
| air-volume-no.pcm | Эйр, громкость сорок пять не надо. | none |
| air-stop-listening.pcm | Эйр, хватит слушать. | STOP_LISTENING |
| air-negation.pcm | Эйр, не включай телевизор. | none |
| air-no-film.pcm | Эйр, фильм не надо. | none |
| air-unaddressed.pcm | Посмотрим фильм. | none |
| air-only.pcm | Эйр. | none |
| air-goodbye.pcm | Эйр, пока. | none |
| turn-on.pcm | Пульт, включи телевизор. | none (old wake phrase) |
| unaddressed.pcm | Включи телевизор. | none |
| negation.pcm | Пульт, не включай телевизор. | none |
| stop.pcm | Пульт, хватит слушать. | none (old wake phrase) |

Example regeneration:

```sh
say -v Milena -r 170 -o /tmp/air-film.aiff 'Эйр, фильм.'
ffmpeg -v error -y -i /tmp/air-film.aiff -af apad=pad_dur=1 \
  -ar 16000 -ac 1 -f s16le air-film.pcm
```

The paused-number fixture joins separately synthesized words with 220 ms of silence.
Latency mode accepts `-e mode latency -e frameSamples 1600 -e trailingSilence 0.35`
and the optimized `-e frameSamples 640 -e trailingSilence 0.25`. It decodes files
silently and never sends a command to the TV.
