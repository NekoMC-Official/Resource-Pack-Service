"""生成 S13 五档基准的合成输入。

用法：python gen_tiers.py <输出目录>
产物：micro.zip / small.zip / large-a|b|c.zip + request-w1.json / w2-00..39.zip + request-w2.json /
      many-00..03.zip + request-many.json
"""
import json
import os
import random
import sys
import zipfile

rng = random.Random(7)
out = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
packs = os.path.join(out, "packs")
os.makedirs(packs, exist_ok=True)


def put(z, name, data):
    info = zipfile.ZipInfo(name, date_time=(2024, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    z.writestr(info, data, compresslevel=6)


def meta(z, name):
    put(z, 
        "pack.mcmeta",
        json.dumps(
            {
                "pack": {
                    "pack_format": 97,
                    "min_format": 97,
                    "max_format": 97,
                    "description": name,
                }
            }
        ),
    )


def source(index, path, priority=0):
    return {"id": os.path.basename(path)[:-4], "path": path, "priority": priority}


def write_request(name, paths):
    body = {
        "format": 0,
        "description": "bench",
        "policy": "override",
        "json_merge": True,
        "sources": [source(i, p, i) for i, p in enumerate(paths)],
    }
    target = os.path.join(out, name)
    with open(target, "w", encoding="utf-8") as fh:
        json.dump(body, fh, ensure_ascii=False)
    print("request", target)


# micro：1 条目 / 1 KB
micro = os.path.join(packs, "micro.zip")
with zipfile.ZipFile(micro, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    meta(z, "micro")
    put(z, "assets/micro/data.bin", rng.randbytes(1024))
write_request("request-micro.json", [micro])
print("micro", os.path.getsize(micro))

# small：201 条目 / 约 27 KB
small = os.path.join(packs, "small.zip")
with zipfile.ZipFile(small, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    meta(z, "small")
    for i in range(200):
        put(z, 
            "assets/small/models/item/m%03d.json" % i,
            json.dumps({"parent": "item/generated", "n": i}),
        )
write_request("request-small.json", [small])
print("small", os.path.getsize(small))

# W1：3 源 / 270 MB 载荷 / 469 条目
large = []
for idx, total in enumerate((120_000_000, 90_000_000, 60_000_000)):
    path = os.path.join(packs, "large-%s.zip" % "abc"[idx])
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        meta(z, "large-%s" % "abc"[idx])
        chunk = 15_000_000
        for i in range(total // chunk):
            put(z, "assets/big%d/textures/blob/b%03d.bin" % (idx, i), rng.randbytes(chunk))
        for i in range(150):
            put(z, 
                "assets/big%d/models/item/m%03d.json" % (idx, i),
                json.dumps({"parent": "item/generated", "n": i}),
            )
    large.append(path)
    print("large", path, os.path.getsize(path))
write_request("request-w1.json", large)

# many-400k：4 源 / 400,000 条目
many = []
for idx in range(4):
    path = os.path.join(packs, "many-%02d.zip" % idx)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        meta(z, "many-%02d" % idx)
        for i in range(100_000):
            put(z, 
                "assets/m%02d/models/item/i%06d.json" % (idx, i),
                '{"parent":"item/generated","n":%d}' % (i % 50),
            )
    many.append(path)
    print("many", path, os.path.getsize(path))
# W2：40 源 / 12,200 条目 / 约 86 MB（超多包档）
w2 = []
for idx in range(40):
    path = os.path.join(packs, "w2-%02d.zip" % idx)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        meta(z, "w2-%02d" % idx)
        for i in range(300):
            put(z, 
                "assets/w2-%02d/models/item/m%03d.json" % (idx, i),
                json.dumps({"parent": "item/generated", "n": idx * 300 + i}),
            )
        for i in range(4):
            put(z, 
                "assets/w2-%02d/textures/blob/b%d.bin" % (idx, i), rng.randbytes(512 * 1024)
            )
    w2.append(path)
    print("w2", path, os.path.getsize(path))
write_request("request-w2.json", w2)

write_request("request-many.json", many)
print("done", out)
