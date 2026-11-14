"""校验 APK 中实际打包的现代 Xposed 元数据和 DEX 类定义。"""

import struct
import sys
from pathlib import Path
from zipfile import ZipFile


def defined_classes(dex):
    string_count, string_offset = struct.unpack_from("<II", dex, 56)
    strings = []
    for index in range(string_count):
        offset = struct.unpack_from("<I", dex, string_offset + index * 4)[0]
        while dex[offset] & 0x80:
            offset += 1
        offset += 1
        strings.append(dex[offset:dex.index(b"\0", offset)])
    type_count, type_offset = struct.unpack_from("<II", dex, 64)
    types = struct.unpack_from(f"<{type_count}I", dex, type_offset)
    class_count, class_offset = struct.unpack_from("<II", dex, 96)
    return {
        strings[types[struct.unpack_from("<I", dex, class_offset + index * 32)[0]]]
        for index in range(class_count)
    }


apk = Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/debug/app-debug.apk")
with ZipFile(apk) as archive:
    prefix = "META-INF/xposed/"
    entries = archive.read(prefix + "java_init.list").decode().splitlines()
    assert entries == ["io.github.hunter.mihomepurifier.MiHomeModule"], entries
    assert archive.read(prefix + "scope.list").decode().splitlines() == [
        "com.xiaomi.smarthome", "com.MobileTicket", "com.taou.maimai"
    ]
    properties = dict(line.split("=", 1) for line in archive.read(prefix + "module.prop").decode().splitlines())
    assert properties["minApiVersion"] == properties["targetApiVersion"] == "102", properties
    assert properties["staticScope"] == "true", properties
    classes = set().union(*(defined_classes(archive.read(name))
                            for name in archive.namelist() if name.endswith(".dex")))
    assert ("L" + entries[0].replace(".", "/") + ";").encode() in classes, "模块入口未打包"
    assert b"Lio/github/hunter/mihomepurifier/UmetripHooks;" not in classes, "不兼容的航旅纵横实验规则不应打包"
    assert b"Lio/github/hunter/mihomepurifier/RailwayHooks;" in classes, "12306 规则未打包"
    assert b"Lio/github/hunter/mihomepurifier/MaimaiSplashRule;" in classes, "脉脉规则未打包"
    assert b"Lio/github/hunter/mihomepurifier/MaimaiDex;" in classes, "脉脉 DEX 定位器未打包"
    assert not any(name.startswith(b"Lio/github/libxposed/") for name in classes), "API 不应打入 APK"
    assert "assets/xposed_init" not in archive.namelist(), "不应混用旧版 Xposed 入口"
print(f"PASS: {apk} — API 102 元数据、作用域、入口类及 compileOnly 打包检查通过")
