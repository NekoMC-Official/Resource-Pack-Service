#!/usr/bin/env python3
"""生成 many20k 档夹具：4 个来源、每个 4,900 条小 JSON，共 19,601 条目。

与 gen_tiers.py 的 many（400k）档不同规模，用来在旧版条目上限（20,000）下也能跑，
也是 docs/perf-benchmark.md §9 的一档。

用法：
    python tools/bench/gen_many20k.py .tmp/bench-s13
"""
import json
import os
import sys
import zipfile


def main():
    if len(sys.argv) != 2:
        raise SystemExit("用法：python tools/bench/gen_many20k.py <输出目录>")
    out = os.path.abspath(sys.argv[1])
    packs = os.path.join(out, "packs")
    os.makedirs(packs, exist_ok=True)

    def put(archive, name, data):
        info = zipfile.ZipInfo(name, date_time=(2024, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, data, compresslevel=6)

    paths = []
    for index in range(4):
        path = os.path.join(packs, "many20k-%02d.zip" % index)
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            put(archive, "pack.mcmeta", json.dumps({
                "pack": {"pack_format": 97, "min_format": 97, "max_format": 97,
                         "description": "many20k-%02d" % index}}))
            for i in range(4900):
                put(archive, "assets/m20k%02d/models/item/i%05d.json" % (index, i),
                    '{"parent":"item/generated","n":%d}' % (i % 50))
        paths.append(path)

    body = {
        "format": 0, "description": "bench", "policy": "override", "json_merge": True,
        "sources": [
            {"id": os.path.basename(path)[:-4], "path": path, "priority": index}
            for index, path in enumerate(paths)
        ],
    }
    target = os.path.join(out, "request-many20k.json")
    with open(target, "w", encoding="utf-8") as handle:
        json.dump(body, handle, ensure_ascii=False)
    print("done", os.path.getsize(target))


if __name__ == "__main__":
    main()
