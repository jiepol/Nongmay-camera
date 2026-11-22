#!/usr/bin/env python3
"""Generate a compact ICC v2.1 sRGB display profile (written from the IEC 61966-2-1
numbers, no third-party profile copied). Used by StoryMax to embed sRGB in exported JPEGs.

Usage: python3 tools/make_srgb_icc.py app/src/main/res/raw/srgb_v2.icc
"""
import struct
import sys


def s15f16(v):
    return struct.pack(">i", int(round(v * 65536.0)))


def xyz_tag(x, y, z):
    return b"XYZ " + b"\0\0\0\0" + s15f16(x) + s15f16(y) + s15f16(z)


def srgb_to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def curv_tag(n=1024):
    vals = [int(round(srgb_to_linear(i / (n - 1)) * 65535)) for i in range(n)]
    return b"curv" + b"\0\0\0\0" + struct.pack(">I", n) + struct.pack(">%dH" % n, *vals)


def desc_tag(text):
    a = text.encode("ascii") + b"\0"
    body = b"desc" + b"\0\0\0\0" + struct.pack(">I", len(a)) + a
    body += struct.pack(">II", 0, 0)          # unicode language code + count
    body += struct.pack(">HB", 0, 0) + b"\0" * 67  # scriptcode code, count, 67 bytes
    return body


def text_tag(text):
    return b"text" + b"\0\0\0\0" + text.encode("ascii") + b"\0"


def pad4(b):
    return b + b"\0" * ((4 - len(b) % 4) % 4)


def build():
    # sRGB primaries chromatically adapted to D50 (Bradford), as published for ICC sRGB.
    tags = [
        (b"desc", desc_tag("sRGB (NongMay compact v2)")),
        (b"cprt", text_tag("No copyright, use freely")),
        (b"wtpt", xyz_tag(0.9642, 1.0, 0.8249)),
        (b"rXYZ", xyz_tag(0.4360747, 0.2225045, 0.0139322)),
        (b"gXYZ", xyz_tag(0.3850649, 0.7168786, 0.0971045)),
        (b"bXYZ", xyz_tag(0.1430804, 0.0606169, 0.7141733)),
    ]
    trc = curv_tag()
    header_len = 128
    table_len = 4 + 12 * (len(tags) + 3)
    offset = header_len + table_len
    table = []
    data = b""
    for sig, body in tags:
        body = pad4(body)
        table.append((sig, offset + len(data), len(body)))
        data += body
    trc_off = offset + len(data)
    trc_p = pad4(trc)
    data += trc_p
    for sig in (b"rTRC", b"gTRC", b"bTRC"):   # all three share one curve, allowed by ICC
        table.append((sig, trc_off, len(trc)))
    total = offset + len(data)
    hdr = struct.pack(">I", total) + b"\0\0\0\0" + struct.pack(">I", 0x02100000)
    hdr += b"mntr" + b"RGB " + b"XYZ "
    hdr += struct.pack(">6H", 2026, 11, 22, 0, 0, 0)
    hdr += b"acsp" + b"\0\0\0\0" + struct.pack(">I", 0) + b"\0\0\0\0" + b"\0\0\0\0"
    hdr += struct.pack(">Q", 0) + struct.pack(">I", 0)
    hdr += s15f16(0.9642) + s15f16(1.0) + s15f16(0.8249)
    hdr += b"\0\0\0\0" + b"\0" * 16 + b"\0" * 28
    assert len(hdr) == 128, len(hdr)
    tbl = struct.pack(">I", len(table)) + b"".join(struct.pack(">4sII", s, o, l) for s, o, l in table)
    out = hdr + tbl + data
    assert len(out) == total
    return out


if __name__ == "__main__":
    prof = build()
    with open(sys.argv[1], "wb") as f:
        f.write(prof)
    # Self-check with LittleCMS through Pillow when available.
    try:
        import io
        from PIL import Image, ImageCms
        p = ImageCms.ImageCmsProfile(io.BytesIO(prof))
        ref = ImageCms.createProfile("sRGB")
        t = ImageCms.buildTransform(p, ref, "RGB", "RGB")
        img = Image.new("RGB", (4, 1))
        img.putdata([(255, 0, 0), (0, 255, 0), (0, 0, 255), (128, 128, 128)])
        out = ImageCms.applyTransform(img, t)
        err = max(abs(a - b) for pa, pb in zip(img.getdata(), out.getdata()) for a, b in zip(pa, pb))
        print("wrote %d bytes; LittleCMS round-trip vs built-in sRGB max error = %d/255" % (len(prof), err))
        if err > 2:
            sys.exit("profile mismatch")
    except ImportError:
        print("wrote %d bytes (Pillow not available for self-check)" % len(prof))
