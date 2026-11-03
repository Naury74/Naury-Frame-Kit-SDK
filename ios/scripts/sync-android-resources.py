#!/usr/bin/env python3
"""Android 문자열·아이콘을 iOS 리소스로 옮긴다.

- framekit-ui, framekit-ui-image, framekit-ui-video의 strings.xml(13개 언어) → ios/FrameKit/Resources/Localizable.xcstrings
- framekit-ui의 벡터 아이콘(채움 경로만) → ios/FrameKit/Resources/Icons.xcassets (템플릿 렌더링)

Android 쪽 문자열이나 아이콘을 바꾼 뒤 다시 실행한다.
"""
import json
import os
import re
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MODULES = ["framekit-ui", "framekit-ui-image", "framekit-ui-video"]
OUT = os.path.join(ROOT, "ios", "FrameKit", "Resources")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# Android values 폴더 접미사 → iOS 언어 코드. 접미사 없는 values는 영어(원문).
LOCALES = {"": "en", "ko": "ko", "ja": "ja", "zh-rCN": "zh-Hans", "zh-rTW": "zh-Hant", "vi": "vi", "th": "th",
           "in": "id", "ru": "ru", "es": "es", "pt-rBR": "pt-BR", "fr": "fr", "de": "de"}


def android_text(element):
    text = "".join(element.itertext()).strip()
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        text = text[1:-1]
    text = text.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\@", "@")
    # Android 서식 → Foundation 서식
    text = re.sub(r"%(\d+\$)?s", lambda m: "%" + (m.group(1) or "") + "@", text)
    text = re.sub(r"%(\d+\$)?d", lambda m: "%" + (m.group(1) or "") + "lld", text)
    return text


# iOS에서 뜻이 달라지는 문구. iOS는 WebP로 저장할 수 없고 HEIC를 쓴다.
IOS_REPLACEMENTS = {"cutout_description": [("WEBP", "HEIC"), ("WebP", "HEIC")]}


def read_strings():
    table = {}
    for module in MODULES:
        res = os.path.join(ROOT, module, "src", "main", "res")
        for suffix, lang in LOCALES.items():
            path = os.path.join(res, "values" + ("-" + suffix if suffix else ""), "strings.xml")
            if not os.path.exists(path):
                continue
            for element in ET.parse(path).getroot():
                name = element.get("name")
                if not name or element.get("translatable") == "false" and lang != "en":
                    continue
                key = name.removeprefix("framekit_")
                if element.tag == "string":
                    value = android_text(element)
                    for old, new in IOS_REPLACEMENTS.get(key, []):
                        value = value.replace(old, new)
                    table.setdefault(key, {})[lang] = {"stringUnit": {"state": "translated", "value": value}}
                elif element.tag == "plurals":
                    forms = {}
                    for item in element:
                        forms[item.get("quantity")] = {"stringUnit": {"state": "translated", "value": android_text(item)}}
                    if "other" in forms:
                        table.setdefault(key, {})[lang] = {"variations": {"plural": forms}}
    return table


def write_strings():
    table = read_strings()
    catalog = {"sourceLanguage": "en", "version": "1.0", "strings": {
        key: {"extractionState": "manual", "localizations": dict(sorted(locs.items()))}
        for key, locs in sorted(table.items())
    }}
    with open(os.path.join(OUT, "Localizable.xcstrings"), "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, indent=2, sort_keys=False)
        f.write("\n")
    return len(table)


def write_icons():
    drawables = os.path.join(ROOT, "framekit-ui", "src", "main", "res", "drawable")
    assets = os.path.join(OUT, "Icons.xcassets")
    os.makedirs(assets, exist_ok=True)
    with open(os.path.join(assets, "Contents.json"), "w") as f:
        json.dump({"info": {"author": "xcode", "version": 1}}, f, indent=2)
    count = 0
    for name in sorted(os.listdir(drawables)):
        if not (name.startswith("framekit_ic_") and name.endswith(".xml")):
            continue
        root = ET.parse(os.path.join(drawables, name)).getroot()
        if root.tag != "vector":
            continue
        vw = root.get(ANDROID_NS + "viewportWidth")
        vh = root.get(ANDROID_NS + "viewportHeight")
        paths = []
        for path in root.iter("path"):
            data = path.get(ANDROID_NS + "pathData")
            rule = "evenodd" if path.get(ANDROID_NS + "fillType") == "evenOdd" else "nonzero"
            fill_alpha = path.get(ANDROID_NS + "fillAlpha", "1")
            stroke_alpha = path.get(ANDROID_NS + "strokeAlpha", "1")
            if path.get(ANDROID_NS + "strokeColor") is not None:
                width = path.get(ANDROID_NS + "strokeWidth", "1")
                cap = path.get(ANDROID_NS + "strokeLineCap", "butt")
                join = path.get(ANDROID_NS + "strokeLineJoin", "miter")
                paths.append(f'<path d="{data}" fill="none" stroke="#000" stroke-width="{width}" '
                             f'stroke-linecap="{cap}" stroke-linejoin="{join}" stroke-opacity="{stroke_alpha}"/>')
            else:
                paths.append(f'<path d="{data}" fill="#000" fill-rule="{rule}" fill-opacity="{fill_alpha}"/>')
        icon = name.removeprefix("framekit_ic_").removesuffix(".xml")
        folder = os.path.join(assets, f"fk_{icon}.imageset")
        os.makedirs(folder, exist_ok=True)
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{vw}" height="{vh}" viewBox="0 0 {vw} {vh}">'
               + "".join(paths) + "</svg>\n")
        with open(os.path.join(folder, f"fk_{icon}.svg"), "w") as f:
            f.write(svg)
        with open(os.path.join(folder, "Contents.json"), "w") as f:
            json.dump({"images": [{"filename": f"fk_{icon}.svg", "idiom": "universal"}],
                       "info": {"author": "xcode", "version": 1},
                       "properties": {"preserves-vector-representation": True, "template-rendering-intent": "template"}},
                      f, indent=2)
        count += 1
    return count


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    print(f"strings: {write_strings()}, icons: {write_icons()}")
