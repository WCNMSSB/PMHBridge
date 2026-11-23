#!/usr/bin/env python3
"""Delete one attribute from an element in a binary AndroidManifest.xml.

`adb install` refuses PICO packages because they declare
android:sharedUserId="android.uid.system". Replacing the value with a fake shared
id installs but makes the process uid stop matching the package name, and LSPosed
then cannot map the process back to its scope. Deleting the attribute entirely
keeps the app on its own uid.

Usage:
  python tools/remove_manifest_attr.py --apk in.apk --out out.apk \
      --tag manifest --attr sharedUserId
"""
from __future__ import annotations

import argparse
import struct
import zipfile

RES_STRING_POOL = 0x0001
RES_XML = 0x0003
RES_XML_START_ELEMENT = 0x0102
UTF8_FLAG = 0x00000100
ATTR_SIZE = 20


def u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


class Pool:
    def __init__(self, data, off):
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


def patch(data: bytes, tag: str, attr: str) -> tuple[bytes, bool]:
    if u16(data, 0) != RES_XML:
        raise SystemExit("not a binary XML manifest")
    off = u16(data, 2)
    pool = None

    while off + 8 <= len(data):
        ctype = u16(data, off)
        csize = u32(data, off + 4)
        if csize < 8 or off + csize > len(data):
            break
        if ctype == RES_STRING_POOL:
            pool = Pool(data, off)
        elif ctype == RES_XML_START_ELEMENT:
            name_idx = u32(data, off + 20)
            name = pool.strings[name_idx] if name_idx < pool.count else ""
            if name == tag:
                attr_start = u16(data, off + 24)
                attr_size = u16(data, off + 26)
                count = u16(data, off + 28)
                if attr_size != ATTR_SIZE:
                    raise SystemExit(f"unexpected attribute size {attr_size}")
                base = off + 16 + attr_start
                for i in range(count):
                    a = base + i * ATTR_SIZE
                    nidx = u32(data, a + 4)
                    if nidx < pool.count and pool.strings[nidx] == attr:
                        out = bytearray(data[:a] + data[a + ATTR_SIZE:])
                        struct.pack_into("<H", out, off + 28, count - 1)
                        struct.pack_into("<I", out, off + 4, csize - ATTR_SIZE)
                        struct.pack_into("<I", out, 4, len(out))
                        return bytes(out), True
        off += csize
    return data, False


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--tag", default="manifest")
    ap.add_argument("--attr", default="sharedUserId")
    args = ap.parse_args(argv)

    with zipfile.ZipFile(args.apk) as src, zipfile.ZipFile(
            args.out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
        for info in src.infolist():
            if info.is_dir():
                continue
            blob = src.read(info)
            if info.filename == "AndroidManifest.xml":
                blob, removed = patch(blob, args.tag, args.attr)
                print(f"  {args.tag}@{args.attr}: {'removed' if removed else 'not present'}")
            dst.writestr(info, blob)
    print("wrote", args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
