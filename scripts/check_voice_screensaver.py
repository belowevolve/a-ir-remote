#!/usr/bin/env python3
"""Opt-in real-device check: launch voice targets through an active TV screensaver.

Requires matching debug/test phone APKs, paired to the TV. Changes the TV's
foreground app; never changes its screensaver or power settings.
"""

import argparse
import json
import pathlib
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone", required=True)
    parser.add_argument("--tv", required=True)
    parser.add_argument("--rounds", type=int, default=2)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("artifacts/dream-wake/regression"))
    parser.add_argument("--commands", nargs="+", choices=["FILM", "YOUTUBE", "PC", "TV_ON", "PLAY", "PAUSE",
                                                         "NEXT", "PREVIOUS", "VOLUME_UP", "VOLUME_DOWN"],
                        default=["FILM", "YOUTUBE", "PC", "TV_ON", "PLAY", "PAUSE"])
    args = parser.parse_args()
    if args.rounds < 1:
        parser.error("--rounds must be positive")
    args.output.mkdir(parents=True, exist_ok=True)
    results = []

    def adb(device, *command):
        return subprocess.run(["adb", "-s", device, *command], check=True, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=45).stdout

    def snapshot():
        dreams = adb(args.tv, "shell", "dumpsys", "dreams")
        window = adb(args.tv, "shell", "dumpsys", "window")
        focus = re.search(r"mCurrentFocus=(.*)", window)
        return {"dreaming": "mCurrentDream=DreamRecord" in dreams,
                "overlay": "mShowingDream=true" in window,
                "focus": focus.group(1) if focus else "", "dreams": dreams}

    def execute(command):
        output = adb(args.phone, "shell", "am", "instrument", "-w", "-r",
                     "-e", "mode", "action", "-e", "command", command,
                     "belowevolve.airremote.test/belowevolve.airremote.VoiceControlRegressionRunner")
        if "INSTRUMENTATION_CODE: -1" not in output:
            raise AssertionError(output)
        return output

    accessibility = adb(args.tv, "shell", "dumpsys", "accessibility")
    bound = next((line for line in accessibility.splitlines() if "Bound services:" in line), "")
    if "Air Home" not in bound:
        raise AssertionError("Air Home key service must be bound; re-enable it after installing/instrumenting the launcher")

    for round_number in range(1, args.rounds + 1):
        for command in args.commands:
            name = f"{round_number}-{command.lower()}"
            expected = {"FILM": "mov.film/", "YOUTUBE": "io.gh.reisxd.tizentube.cobalt/",
                        "PC": "belowevolve.tvhome/belowevolve.tvhome.HdmiActivity"}.get(command)
            if expected is None:
                # A known app must survive wake/media commands; merely reaching HOME is a failure.
                execute("FILM")
                deadline = time.monotonic() + 8
                while "mov.film/" not in snapshot()["focus"]:
                    if time.monotonic() >= deadline:
                        raise AssertionError("Could not prepare the underlying app")
                    time.sleep(0.1)
                expected = "mov.film/"
            # The Google TV settings action starts the configured system dream.
            # Isolate the settings trampoline so it cannot replace the underlying app's task.
            adb(args.tv, "shell", "am", "start", "-f", "0x18000000", "-a", "com.google.android.pano.action.SLEEP")
            deadline = time.monotonic() + 10
            while True:
                before = snapshot()
                if before["dreaming"] and before["overlay"]:
                    break
                if time.monotonic() >= deadline:
                    raise AssertionError("Could not start a visible system screensaver")
                time.sleep(0.1)
            (args.output / f"{name}-before.json").write_text(json.dumps(before, ensure_ascii=False, indent=2))
            command_started_ms = float(adb(args.tv, "shell", "cat", "/proc/uptime").split()[0]) * 1000
            output = execute(command)
            command_finished = time.monotonic()
            (args.output / f"{name}-command.txt").write_text(output)
            observations = []
            deadline = time.monotonic() + 8
            while True:
                after = snapshot()
                observations.append(after)
                visible = not after["dreaming"] and not after["overlay"]
                target = expected in after["focus"]
                if visible and target:
                    # Check again after the asynchronous dream/HOME transition.
                    time.sleep(1)
                    settled = snapshot()
                    observations.append(settled)
                    if (not settled["dreaming"] and not settled["overlay"]
                            and expected in settled["focus"] and time.monotonic() - command_finished >= 3):
                        break
                if time.monotonic() >= deadline:
                    (args.output / f"{name}-after.json").write_text(json.dumps(observations, ensure_ascii=False, indent=2))
                    raise AssertionError(f"{name}: screensaver remained or target was displaced: {after['focus']}")
                time.sleep(0.1)
            (args.output / f"{name}-after.json").write_text(json.dumps(observations, ensure_ascii=False, indent=2))
            trace = adb(args.tv, "shell", "dumpsys", "activity", "service", "belowevolve.tvhome/.ColorKeyService")
            (args.output / f"{name}-wake.txt").write_text(trace)
            match = re.search(r"AIRHOME_WAKE (\[.*\])", trace)
            if match and any(e["event"] == "dream_dismiss_timeout" and e["elapsed"] >= command_started_ms
                             for e in json.loads(match.group(1))):
                raise AssertionError(f"{name}: visible target received a false screensaver error")
            results.append({"case": name, "pass": True, "focus": settled["focus"]})
            (args.output / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
            print(f"{name}: PASS — dream stopped, target visible", flush=True)


if __name__ == "__main__":
    main()
