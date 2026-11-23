#!/usr/bin/env python3
"""Remove <uses-permission android:name="..."/> entries from a binary manifest.

Non-rooted Quest installs fail or misbehave when an APK asks for privileged
permissions it can never be granted, so the "免 root 补丁" drops the ones that only
ever worked because the app ran as a system uid.

Usage:
  python tools/remove_permission.py --apk in.apk --out out.apk \
      --permission android.permission.WRITE_SECURE_SETTINGS \
      [--permission android.permission.MANAGE_ACTIVITY_STACKS ...]
"""
from __future__ import annotations

import argparse
import struct
import zipfile

RES_STRING_POOL = 0x0001
RES_XML = 0x0003
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103
UTF8_FLAG = 0x00000100


def u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


class Pool:
    def __init__(self, data, off):
        self.off = off
        self.size = u32(data, off + 4)
        self.count = u32(data, off + 8)
        self.flags = u32(data, off + 16)
        self.strings_start = u32(data, off + 20)
        self.utf8 = bool(self.flags & UTF8_FLAG)
        self.strings = []
        base = off + self.strings_start
        for i in range(self.count):
            pos = base + u32(data, off + 28 + 4 * i)
            self.strings.append(self._decode(data, pos))

    def _decode(self, data, pos):
        if self.utf8:
            n = data[pos]
            pos += 1
            if n & 0x80:
                pos += 1
            m = data[pos]
            pos += 1
            if m & 0x80:
                pos += 1
            return data[pos:pos + m].decode("utf-8", "replace")
        n = u16(data, pos)
        pos += 2
        if n & 0x8000:
            pos += 2
        return data[pos:pos + 2 * n].decode("utf-16-le", "replace")


def element_name(data, pool, off):
    name_idx = u32(data, off + 20)
    return pool.strings[name_idx] if name_idx < pool.count else ""


def attr_value(data, pool, off):
    astart = u16(data, off + 24)
    acount = u16(data, off + 28)
    base = off + 16 + astart
    for i in range(acount):
        a = base + i * 20
        nidx = u32(data, a + 4)
        if nidx < pool.count and pool.strings[nidx] == "name":
            vidx = u32(data, a + 8)
            if vidx < pool.count:
                return pool.strings[vidx]
    return None


def patch(data: bytes, wanted: set[str]) -> tuple[bytes, list[str]]:
    if u16(data, 0) != RES_XML:
        raise SystemExit("not a binary XML manifest")
    off = u16(data, 2)
    pool = None
    cuts = []
    removed = []
    pending = None
    depth = 0

    while off + 8 <= len(data):
        ctype = u16(data, off)
        csize = u32(data, off + 4)
        if csize < 8 or off + csize > len(data):
            break
        if ctype == RES_STRING_POOL:
            pool = Pool(data, off)
        elif ctype == RES_XML_START_ELEMENT:
            depth += 1
            if element_name(data, pool, off) in ("uses-permission", "uses-permission-sdk-23"):
                value = attr_value(data, pool, off)
                if value in wanted:
                    pending = (off, value)
        elif ctype == RES_XML_END_ELEMENT:
            if pending is not None and element_name(data, pool, off) in (
                    "uses-permission", "uses-permission-sdk-23"):
                cuts.append((pending[0], off + csize))
                removed.append(pending[1])
                pending = None
            depth -= 1
        off += csize

    out = bytearray()
    pos = 0
    for start, end in cuts:
        out += data[pos:start]
        pos = end
    out += data[pos:]
    struct.pack_into("<I", out, 4, len(out))
    return bytes(out), removed


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--permission", action="append", default=[])
    args = ap.parse_args(argv)
    wanted = set(args.permission)
    if not wanted:
        raise SystemExit("no --permission given")

    with zipfile.ZipFile(args.apk) as src, zipfile.ZipFile(
            args.out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        for info in src.infolist():
            if info.is_dir():
                continue
            data = src.read(info)
            if info.filename == "AndroidManifest.xml":
                data, removed = patch(data, wanted)
                print("removed:", removed)
            dst.writestr(info, data)
    print("wrote", args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
