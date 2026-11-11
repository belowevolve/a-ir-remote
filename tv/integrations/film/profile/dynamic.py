"""Generate exact native drawable geometry with runtime colors; compile helper into smali."""

from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

NS = "{http://schemas.android.com/apk/res/android}"


def inject(
    tree,
    palette,
    mapping,
    roles,
    build,
    java,
    env,
    command,
    run,
    incompatible,
    phone=False,
):
    root = Path(__file__).resolve().parent
    files = list(tree.glob("smali*/mov/film/MainActivity.smali"))
    if len(files) != 1:
        raise incompatible("Main activity moved")
    directory = files[0].parent
    resource_ids = {
        e.get("name"): int(e.get("id"), 16)
        for e in ET.parse(tree / "res/values/public.xml").getroot()
        if e.get("type") == "drawable"
    }
    generated = []
    methods = []
    serial = 0

    def integer(n):
        return "0x%08x" % (n & 0xFFFFFFFF)

    def dim(raw):
        match = re.fullmatch(r"([\d.]+)(?:dip|dp)", raw)
        if not match:
            raise incompatible("Unsupported drawable dimension: " + raw)
        return "(" + match[1] + "f * r.getDisplayMetrics().density)"

    def attrs(element, allowed):
        if set(element.attrib) - {NS + k for k in allowed}:
            raise incompatible("Drawable attributes changed: " + element.tag)
        return {k.removeprefix(NS): v for k, v in element.attrib.items()}

    def color(raw):
        if not re.fullmatch(r"#[\da-fA-F]{8}", raw):
            raise incompatible("Drawable color changed: " + raw)
        return "DynamicTheme.color(" + integer(int(raw[1:], 16)) + ")"

    def node(el):
        nonlocal serial
        serial += 1
        name = "d" + str(serial)
        lines = []
        if el.tag == "shape":
            a = attrs(el, ["shape"])
            shape = a.get("shape", "rectangle")
            if shape not in ["rectangle", "oval"]:
                raise incompatible("Unsupported shape")
            lines = [
                "GradientDrawable d = new GradientDrawable();",
                "d.setShape(GradientDrawable."
                + ("OVAL" if shape == "oval" else "RECTANGLE")
                + ");",
            ]
            for child in el:
                if child.tag == "solid":
                    a = attrs(child, ["color"])
                    lines.append("d.setColor(" + color(a["color"]) + ");")
                elif child.tag == "corners":
                    a = attrs(child, ["radius", "topLeftRadius", "topRightRadius", "bottomRightRadius", "bottomLeftRadius"])
                    corners = [dim(a.get(k, a.get("radius", "0.0dip"))) for k in ["topLeftRadius", "topRightRadius", "bottomRightRadius", "bottomLeftRadius"]]
                    lines.append("d.setCornerRadii(new float[]{" + ",".join(v for c in corners for v in [c,c]) + "});")
                elif child.tag == "stroke":
                    a = attrs(child, ["width", "color", "dashWidth", "dashGap"])
                    lines.append(
                        "d.setStroke(Math.round("
                        + dim(a["width"])
                        + "), "
                        + color(a["color"])
                        + ("," + dim(a.get("dashWidth", "0.0dip")) + "," + dim(a.get("dashGap", "0.0dip")) if "dashWidth" in a or "dashGap" in a else "")
                        + ");"
                    )
                elif child.tag == "size":
                    a = attrs(child, ["width", "height"])
                    lines.append(
                        "d.setSize(Math.round("
                        + dim(a["width"])
                        + "), Math.round("
                        + dim(a["height"])
                        + "));"
                    )
                elif child.tag == "gradient":
                    a = attrs(
                        child,
                        ["startColor", "endColor", "centerColor", "angle", "type"],
                    )
                    if a.get("type", "linear") != "linear":
                        raise incompatible("Unsupported gradient")
                    angle = int(float(a.get("angle", "0"))) % 360
                    orientation = {
                        0: "LEFT_RIGHT",
                        45: "BL_TR",
                        90: "BOTTOM_TOP",
                        135: "BR_TL",
                        180: "RIGHT_LEFT",
                        225: "TR_BL",
                        270: "TOP_BOTTOM",
                        315: "TL_BR",
                    }.get(angle)
                    if not orientation:
                        raise incompatible("Unsupported gradient angle")
                    colors = [
                        a[k]
                        for k in ["startColor", "centerColor", "endColor"]
                        if k in a
                    ]
                    # Keep the existing patch's dark, readable accent fills.
                    accent = any(
                        (int(c[1:], 16) & 0xFFFFFF)
                        in {0x7C3AED, 0xA855F7, 0x8B5CF6, 0x5B2BB0, 0x7E3FB8}
                        for c in colors
                    )
                    values = [
                        "DynamicTheme.color(0xff8b5cf6)" if accent else color(c)
                        for c in colors
                    ]
                    lines += [
                        "d.setOrientation(GradientDrawable.Orientation."
                        + orientation
                        + ");",
                        "d.setColors(new int[]{" + ",".join(values) + "});",
                    ]
                else:
                    raise incompatible("Drawable shape changed: " + child.tag)
        elif el.tag == "selector":
            attrs(el, [])
            lines = ["StateListDrawable d = new StateListDrawable();"]
            for item in el:
                if item.tag != "item" or len(item) != 1:
                    raise incompatible("Selector changed")
                a = attrs(item, ["state_focused", "state_pressed", "state_selected", "state_enabled", "state_checked", "state_checkable", "state_activated", "state_hovered", "state_window_focused"])
                states = [
                    ("" if v == "true" else "-") + "android.R.attr." + k
                    for k, v in a.items()
                ]
                lines.append(
                    "d.addState(new int[]{"
                    + ",".join(states)
                    + "}, "
                    + node(item[0])
                    + "(r));"
                )
        elif el.tag == "layer-list":
            attrs(el, [])
            children = []
            insets = []
            for item in el:
                a = attrs(item, ["left", "top", "right", "bottom", "gravity", "height", "width"])
                if item.tag != "item" or len(item) != 1:
                    raise incompatible("Layer changed")
                children.append(node(item[0]) + "(r)")
                insets.append(a)
            lines = [
                "LayerDrawable d = new LayerDrawable(new Drawable[]{"
                + ",".join(children)
                + "});"
            ]
            for i, a in enumerate(insets):
                if "gravity" in a:
                    tokens = a["gravity"].split("|")
                    if set(tokens) - {"top", "bottom", "left", "right", "start", "end", "center", "center_horizontal", "center_vertical", "fill", "fill_horizontal", "fill_vertical", "clip_horizontal", "clip_vertical"}:
                        raise incompatible("Unsupported layer gravity")
                    lines.append("d.setLayerGravity(" + str(i) + "," + "|".join("android.view.Gravity." + t.upper() for t in tokens) + ");")
                for key in ["height", "width"]:
                    if key in a: lines.append("d.setLayer" + key.title() + "(" + str(i) + ",Math.round(" + dim(a[key]) + "));")
                if a:
                    lines.append(
                        "d.setLayerInset("
                        + str(i)
                        + ","
                        + ",".join(
                            "Math.round(" + dim(a.get(k, "0.0dip")) + ")"
                            for k in ["left", "top", "right", "bottom"]
                        )
                        + ");"
                    )
        elif el.tag == "inset":
            a = attrs(el, ["insetLeft", "insetTop", "insetRight", "insetBottom"])
            if len(el) != 1:
                raise incompatible("Inset changed")
            lines = [
                "InsetDrawable d = new InsetDrawable("
                + node(el[0])
                + "(r),"
                + ",".join(
                    "Math.round(" + dim(a.get("inset" + k, "0.0dip")) + ")"
                    for k in ["Left", "Top", "Right", "Bottom"]
                )
                + ");"
            ]
        else:
            raise incompatible("Drawable type changed: " + el.tag)
        methods.append(
            "private static Drawable "
            + name
            + "(Resources r) {"
            + "".join(lines)
            + "return d;}"
        )
        return name

    for path in sorted((tree / "res/drawable").glob("*.xml")):
        text = path.read_text()
        if not any(
            (int(raw[1:], 16) | 0xFF000000) in mapping
            for raw in re.findall(r"#[\da-fA-F]{8}\b", text)
        ):
            continue
        drawable = ET.fromstring(text)
        if drawable.tag == "vector":
            colors = set(re.findall(r"#[\da-fA-F]{8}\b", text))
            if colors != {"#ffa855f7"}:
                raise incompatible("New multicolor themed vector requires review")
            serial += 1
            name = "d" + str(serial)
            methods.append(
                "private static Drawable "
                + name
                + "(Resources r) {Drawable d=r.getDrawable("
                + integer(resource_ids[path.stem])
                + ",null).mutate();d.setTint(DynamicTheme.color(0xffa855f7));return d;}"
            )
            generated.append(
                "case " + integer(resource_ids[path.stem]) + ": return " + name + "(r);"
            )
            continue
        generated.append(
            "case "
            + integer(resource_ids[path.stem])
            + ": return "
            + node(drawable)
            + "(r);"
        )
    # Map original colors to roles, never compare colors against a previous wallpaper's RGB.
    cases = []
    for original, target in mapping.items():
        if original >> 24 != 255:
            continue
        value = (
            "p[" + str(roles[original]) + "]"
            if roles[original] is not None
            else "blend(p[4], p[1])"
        )
        cases.append("case " + integer(original) + ": mapped=" + value + "; break;")
    template = (root / "DynamicTheme.java").read_text()
    template = template.replace(
        "FALLBACK",
        "new int[]{"
        + ",".join(
            integer(palette[k])
            for k in [
                "accent",
                "accent_hot",
                "accent_soft",
                "back",
                "surface",
                "text",
                "muted",
            ]
        )
        + "}",
    ).replace("COLOR_CASES", "\n".join(cases))
    source = (
        "return PhonePalette.read(context);"
        if phone
        else 'return context.getContentResolver().call(Uri.parse("content://belowevolve.tvhome.theme"), "palette", null, null);'
    )
    template = template.replace("PALETTE_SOURCE", source)
    # Add runtime hooks only to known, original palette constants.
    constants = 0
    backgrounds = 0
    activities = 0
    progress_widgets = 0
    for path in tree.glob("smali*/mov/film/**/*.smali"):
        text = path.read_text()

        def constant(m):
            nonlocal constants
            value = int(m[2], 16) & 0xFFFFFFFF
            if (value | 0xFF000000) not in mapping:
                return m[0]
            constants += 1
            reg = m[1]
            return (
                m[0]
                + "\n    invoke-static/range {"
                + reg
                + " .. "
                + reg
                + "}, Lmov/film/DynamicTheme;->color(I)I\n    move-result "
                + reg
            )

        text = re.sub(
            r"^    const(?:/high16)? (\w+), (-?0x[0-9a-f]+)[ \t]*$",
            constant,
            text,
            flags=re.M,
        )
        text = re.sub(
            r"^    sget-object (\w+), Lmov/film/PlayerActivity;->SHAPE_LIT:Landroid/content/res/ColorStateList;$",
            lambda m: (
                "    invoke-static {}, Lmov/film/DynamicTheme;->shapeColors()Landroid/content/res/ColorStateList;\n    move-result-object "
                + m[1]
            ),
            text,
            flags=re.M,
        )

        def progress(m):
            nonlocal progress_widgets
            progress_widgets += 1
            register = m[1]
            return (m[0] + "\n    invoke-static/range {" + register + " .. " + register
                    + "}, Lmov/film/DynamicTheme;->progress(Landroid/widget/ProgressBar;)V")

        # Framework widget defaults use the APK's static accent. Tint every creation
        # site, including dialogs and catalogue screens, from the cached live palette.
        text = re.sub(
            r"^[ \t]*invoke-direct(?:/range)? \{([vp]\d+)(?:,| \.\.)[^}]*\}, Landroid/widget/ProgressBar;-><init>\(Landroid/content/Context;[^)]*\)V$",
            progress,
            text,
            flags=re.M,
        )

        def background(m):
            nonlocal backgrounds
            backgrounds += 1
            return (
                "    invoke-static"
                + (m[1] or "")
                + " "
                + m[2]
                + ", Lmov/film/DynamicTheme;->background(Landroid/view/View;I)V"
            )

        text = re.sub(
            r"^    invoke-virtual(/range)? (\{[^}]+\}), Landroid/view/View;->setBackgroundResource\(I\)V$",
            background,
            text,
            flags=re.M,
        )
        text = re.sub(
            r"^    invoke-virtual(/range)? (\{[^}]+\}), Landroid/widget/ImageView;->setImageResource\(I\)V$",
            lambda m: (
                "    invoke-static"
                + (m[1] or "")
                + " "
                + m[2]
                + ", Lmov/film/DynamicTheme;->image(Landroid/widget/ImageView;I)V"
            ),
            text,
            flags=re.M,
        )
        for owner in ["android/content/Context", "android/content/res/Resources"]:
            for args in ["I", "ILandroid/content/res/Resources$Theme;"]:
                text = re.sub(
                    r"(invoke-)virtual(/range)? (\{[^}]+\}), L"
                    + re.escape(owner)
                    + r";->getDrawable\("
                    + re.escape(args)
                    + r"\)Landroid/graphics/drawable/Drawable;",
                    lambda m: (
                        m[1]
                        + "static"
                        + (m[2] or "")
                        + " "
                        + m[3]
                        + ", Lmov/film/DynamicTheme;->drawable(L"
                        + owner
                        + ";"
                        + args
                        + ")Landroid/graphics/drawable/Drawable;"
                    ),
                    text,
                )
        if phone:
            for owner, original, replacement, arguments in [
                ("android/widget/TextView", "setTextColor", "text", "I"),
                ("android/widget/ImageView", "setColorFilter", "tint", "I"),
                (
                    "android/widget/ImageView",
                    "setColorFilter",
                    "tint",
                    "ILandroid/graphics/PorterDuff$Mode;",
                ),
            ]:
                text = re.sub(
                    r"^    invoke-virtual(/range)? (\{[^}]+\}), L"
                    + re.escape(owner)
                    + ";->"
                    + original
                    + r"\("
                    + re.escape(arguments)
                    + r"\)V$",
                    lambda m: (
                        "    invoke-static"
                        + (m[1] or "")
                        + " "
                        + m[2]
                        + ", Lmov/film/PhonePalette;->"
                        + replacement
                        + "(L"
                        + owner
                        + ";"
                        + arguments
                        + ")V"
                    ),
                    text,
                    flags=re.M,
                )
        if (
            ".super Landroid/app/Activity;" in text
            and ".method protected onCreate(Landroid/os/Bundle;)V" in text
        ):
            marker = "    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V"
            if text.count(marker) != 1:
                raise incompatible("Activity lifecycle changed: " + path.name)
            text = text.replace(
                marker,
                "    invoke-static/range {p0 .. p0}, Lmov/film/DynamicTheme;->init(Landroid/app/Activity;)V\n\n"
                + marker,
            )
            resume = "    invoke-super {p0}, Landroid/app/Activity;->onResume()V"
            if resume in text:
                text = text.replace(
                    resume,
                    resume
                    + "\n\n    invoke-static/range {p0 .. p0}, Lmov/film/DynamicTheme;->resume(Landroid/app/Activity;)V",
                )
            else:
                text += "\n.method protected onResume()V\n    .locals 0\n    invoke-super {p0}, Landroid/app/Activity;->onResume()V\n    invoke-static/range {p0 .. p0}, Lmov/film/DynamicTheme;->resume(Landroid/app/Activity;)V\n    return-void\n.end method\n"
            activities += 1
        path.write_text(text)
    if constants < 20 or backgrounds < 50 or activities < 8 or progress_widgets < 20:
        raise incompatible("Dynamic theme coverage changed")
    print("Dynamic progress widgets:", progress_widgets, flush=True)
    work = tree.parent / "theme-helper"
    work.mkdir()
    (work / "DynamicTheme.java").write_text(template)
    (work / "DynamicDrawables.java").write_text(
        "package mov.film; import android.content.res.Resources; import android.graphics.drawable.*; final class DynamicDrawables { static Drawable create(Resources r,int id) {switch(id){"
        + "".join(generated)
        + "default:return null;}}"
        + "".join(methods)
        + "}"
    )
    sdk = build.parent.parent
    jdk = java.parent
    android = next(
        p
        for p in [
            sdk / "platforms/android-35/android.jar",
            sdk / "platforms/android-37.0/android.jar",
        ]
        if p.exists()
    )
    phone_source = []
    if phone:
        path = work / "PhonePalette.java"
        path.write_bytes((root / "PhonePalette.java").read_bytes())
        phone_source.append(path)
    classes = work / "classes"
    classes.mkdir()
    dex = work / "dex"
    dex.mkdir()
    run(
        [
            jdk / "javac",
            "-encoding",
            "UTF-8",
            "--release",
            "8",
            "-cp",
            android,
            "-d",
            classes,
            work / "DynamicTheme.java",
            work / "DynamicDrawables.java",
            *phone_source,
        ],
        env,
    )
    run(
        [
            build / "d8",
            "--min-api",
            "28",
            "--lib",
            android,
            "--output",
            dex,
            *classes.rglob("*.class"),
        ],
        env,
    )
    manifest = work / "AndroidManifest.xml"
    manifest.write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="mov.film.themehelper"><uses-sdk android:minSdkVersion="28"/><application/></manifest>'
    )
    apk = work / "helper.apk"
    run(
        [build / "aapt2", "link", "-I", android, "--manifest", manifest, "-o", apk], env
    )
    with zipfile.ZipFile(apk, "a") as z:
        z.write(dex / "classes.dex", "classes.dex")
    decoded = work / "decoded"
    run(command + ["d", "-f", "-o", decoded, apk], env)
    for path in (decoded / "smali/mov/film").glob("*.smali"):
        (directory / path.name).write_bytes(path.read_bytes())
    return dict(
        dynamic_constants=constants,
        dynamic_backgrounds=backgrounds,
        dynamic_activities=activities,
        dynamic_drawables=len(generated),
    )
