"""Phone-only resource aliases and an adaptive/monochrome version of the original film logo."""

import xml.etree.ElementTree as ET

NS = "{http://schemas.android.com/apk/res/android}"


def resources(tree):
    res = tree / "res"
    foreground = "@mipmap/ic_launcher_fore"
    if not list(res.glob("mipmap-*/ic_launcher_fore.png")):
        raise RuntimeError("Original film icon foreground missing")
    drawable = res / "drawable"
    (drawable / "film_icon_foreground.xml").write_text(
        """<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@mipmap/ic_launcher_fore" android:tint="@color/film_icon_foreground" android:gravity="fill"/>"""
    )
    (drawable / "film_icon_monochrome.xml").write_text(
        """<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@mipmap/ic_launcher_fore" android:tint="@android:color/white" android:gravity="fill"/>"""
    )
    for qualifier, roles in {
        "values": {
            "film_icon_background": "#ff315318",
            "film_icon_foreground": "#ffb8f397",
        },
        "values-v31": {
            "film_icon_background": "@android:color/system_accent3_600",
            "film_icon_foreground": "@android:color/system_accent1_100",
        },
        "values-night-v31": {
            "film_icon_background": "@android:color/system_accent3_700",
            "film_icon_foreground": "@android:color/system_accent1_200",
        },
        "values-v34": {
            "accent": "@android:color/system_primary_light",
            "accent_hot": "@android:color/system_primary_dark",
            "back": "@android:color/system_background_dark",
            "icon_back": "@android:color/system_surface_container_dark",
            "text_dim": "@android:color/system_on_surface_variant_dark",
        },
    }.items():
        folder = res / qualifier
        folder.mkdir(exist_ok=True)
        root = ET.Element("resources")
        for name, value in roles.items():
            ET.SubElement(root, "color", {"name": name}).text = value
        ET.ElementTree(root).write(
            folder / "film_material_colors.xml", encoding="utf-8", xml_declaration=True
        )
    for qualifier in ["mipmap-anydpi-v26", "mipmap-anydpi-v33"]:
        folder = res / qualifier
        folder.mkdir(exist_ok=True)
        icon = ET.Element("adaptive-icon")
        ET.SubElement(
            icon, "background", {NS + "drawable": "@color/film_icon_background"}
        )
        ET.SubElement(
            icon, "foreground", {NS + "drawable": "@drawable/film_icon_foreground"}
        )
        if qualifier.endswith("v33"):
            ET.SubElement(
                icon, "monochrome", {NS + "drawable": "@drawable/film_icon_monochrome"}
            )
        ET.register_namespace("android", NS[1:-1])
        ET.ElementTree(icon).write(
            folder / "ic_launcher.xml", encoding="utf-8", xml_declaration=True
        )
    return dict(
        phone_material_you=True, phone_adaptive_icon=True, phone_monochrome_icon=True
    )
