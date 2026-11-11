#!/usr/bin/env python3
"""Measure real SurfaceFlinger presents during repeatable DPAD navigation.

Opt-in: changes the TV's foreground screen and focus. Does not change graphics,
power or accessibility settings. Requires Android 14+, adb, JDK and Android SDK.
Idle gaps between animations are NOT classified as missed frames.
"""

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time


def parse_layers(raw, package):
    layers = []
    for block in raw.split("layerName = ")[1:]:
        name = block.splitlines()[0]
        if not name.startswith(package + "/"):
            continue
        def number(key):
            match = re.search(r"^" + key + r" = (\d+)$", block, re.M)
            return int(match[1]) if match else None
        match = re.search(r"present2present histogram is as below:\n([^\n]+)", block)
        histogram = {int(ms): int(count) for ms, count in re.findall(r"(\d+)ms=(\d+)", match[1])
                     if int(count)} if match else {}
        timeline, janky = number("totalTimelineFrames"), number("jankyFrames")
        layers.append({"layer": name, "presents": number("totalFrames"),
                       "timeline_frames": timeline, "janky_frames": janky if timeline else None,
                       "jank_percent": round(janky * 100 / timeline, 3) if timeline and janky is not None else None,
                       "present_interval_histogram_ms": histogram})
    return layers


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tv", required=True)
    parser.add_argument("--screen", choices=["home", "settings", "app"], default="home")
    parser.add_argument("--activity", help="Required for app: package/activity")
    parser.add_argument("--pattern", help="DPAD sequence containing only R, L, U, D")
    parser.add_argument("--interval-ms", type=int, default=80)
    parser.add_argument("--rounds", type=int, default=8)
    parser.add_argument("--output", type=Path, default=Path("artifacts/frame-pacing"))
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))))
    args = parser.parse_args()
    if args.screen == "app" and not args.activity:
        parser.error("--screen app requires --activity")
    pattern = args.pattern or ("DDDDUUUUDDDDUUUU" if args.screen == "settings" else "RRRRRRRRLLLLLLLL")
    if not re.fullmatch("[RLUD]+", pattern) or not 40 <= args.interval_ms <= 1000 or args.rounds < 1:
        parser.error("Use R/L/U/D, interval 40..1000 ms, positive rounds")
    if len(pattern) * args.interval_ms * args.rounds > 45_000:
        parser.error("Limit the navigation run to 45 seconds")
    args.output.mkdir(parents=True, exist_ok=True)

    def run(*command, **kwargs):
        return subprocess.run([str(c) for c in command], check=True, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60, **kwargs).stdout
    def adb(*command):
        return run("adb", "-s", args.tv, *command)
    def shell(*command):
        return adb("shell", shlex.join(command))
    def focus():
        # The full dump also includes the focus saved with the last ANR.
        match = re.search(r"mCurrentFocus=(.*)", shell("dumpsys", "window", "displays"))
        return match[1] if match else ""

    if int(shell("getprop", "ro.build.version.sdk").strip()) < 34:
        raise RuntimeError("The shell input helper requires Android 14 or newer")
    if args.screen == "app":
        activity = args.activity
    else:
        intent = ["-a", "android.settings.SETTINGS"] if args.screen == "settings" else [
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"]
        activity = shell("cmd", "package", "resolve-activity", "--brief", *intent).strip().splitlines()[-1]
    if not re.fullmatch(r"[\w.]+/[\w.$]+", activity):
        raise RuntimeError("Could not resolve activity: " + activity)
    package = activity.split("/")[0]
    tools = sorted((args.sdk / "build-tools").glob("*/d8"), key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.parent.name)))
    platforms = sorted((args.sdk / "platforms").glob("android-*/android.jar"),
                       key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.parent.name)))
    if not tools or not platforms:
        raise RuntimeError("Android SDK build-tools and platform required")
    remote = "/data/local/tmp/air-frame-check.dex"
    stats_enabled = False
    try:
        with tempfile.TemporaryDirectory(prefix="air-frame-check-") as tmp:
            work = Path(tmp)
            run("javac", "--release", "8", "-cp", platforms[-1], "-d", work,
                Path(__file__).parent / "tv/TvKeySweep.java")
            run(tools[-1], "--min-api", "34", "--output", work, work / "TvKeySweep.class")
            adb("push", work / "classes.dex", remote)
        shell("am", "start", "-n", activity)
        time.sleep(2)
        before = focus()
        if package + "/" not in before:
            raise RuntimeError("Unexpected foreground: " + before)
        shell("dumpsys", "SurfaceFlinger", "--timestats", "-enable", "-clear")
        stats_enabled = True
        adb("shell", "CLASSPATH=" + shlex.quote(remote) + " " + shlex.join([
            "app_process", "/system/bin", "--nice-name=air.frame.check", "TvKeySweep",
            pattern, str(args.interval_ms), str(args.rounds)]))
        raw = shell("dumpsys", "SurfaceFlinger", "--timestats", "-dump")
        (args.output / "surfaceflinger.txt").write_text(raw)
        after = focus()
        if package + "/" not in after:
            raise RuntimeError("Foreground changed during measurement: " + after)
        result = {"activity": activity, "pattern": pattern, "interval_ms": args.interval_ms,
                  "rounds": args.rounds, "focus_before": before, "focus_after": after,
                  "layers": parse_layers(raw, package),
                  "note": "Histogram includes idle gaps. Missing FrameTimeline coverage means unknown jank, not zero."}
        if not result["layers"]:
            raise RuntimeError("No presentation data for " + package)
        (args.output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
        print(json.dumps(result, ensure_ascii=False, indent=2))
    finally:
        if stats_enabled:
            shell("dumpsys", "SurfaceFlinger", "--timestats", "-disable", "-clear")
        shell("rm", "-f", remote)


if __name__ == "__main__":
    main()
