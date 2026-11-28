#!/usr/bin/env python3
"""生成高重叠档夹具（s7）：40 个来源，用于观察合并去重对客户端字节的影响。

每个来源 300 条 JSON（180 条落 shared 命名空间）+ 4 个 512 KB 纹理（2 个共享），
合并前共 12,200 条、去重后 5,063 条（重复率 58.5%）、182 条冲突。
固定随机种子与 zip 时间戳，同一脚本重复跑产物逐字节一致；纹理随机源与 docs/perf-benchmark.md
第 9 节实测那次的旧夹具不同，那份的 SHA-1 与这里生成的不同，只有规模与条目数一致。

用法：
    python tools/bench/gen_overlap.py .tmp/bench-s7
"""

import json
import os
import random
import sys
import zipfile
from zipfile import ZipInfo


def main():
    if len(sys.argv) != 2:
        raise SystemExit("用法：python tools/bench/gen_overlap.py <输出目录>")
    out = os.path.abspath(sys.argv[1])
    packs = os.path.join(out, "packs")
    os.makedirs(packs, exist_ok=True)
    rng = random.Random(11)
    stamp = (2024, 1, 1, 0, 0, 0)

    for index in range(40):
        path = os.path.join(packs, "many-%02d.zip" % index)
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            archive.writestr(
                ZipInfo("pack.mcmeta", date_time=stamp),
                json.dumps({
                    "pack": {"pack_format": 97, "min_format": 97, "max_format": 97,
                             "description": "many-%d" % index}}),
                compresslevel=6)
            for i in range(300):
                namespace = "shared" if i < 180 else "m%d" % index
                archive.writestr(
                    ZipInfo("assets/%s/models/item/i%04d.json" % (namespace, i), date_time=stamp),
                    json.dumps({"parent": "item/generated",
                                "textures": {"layer0": "x/%d" % (i % 50)},
                                "idx": index}),
                    compresslevel=6)
            for i in range(4):
                namespace = "shared" if i < 2 else "m%d" % index
                archive.writestr(
                    ZipInfo("assets/%s/textures/item/t%d.png" % (namespace, i), date_time=stamp),
                    rng.randbytes(512 * 1024), compresslevel=6)

    body = {
        "format": 0, "description": "bench", "policy": "override", "json_merge": True,
        "sources": [
            {"id": "many-%02d" % index,
             "path": os.path.join(packs, "many-%02d.zip" % index),
             "priority": 0}
            for index in range(40)
        ],
    }
    with open(os.path.join(out, "request.json"), "w", encoding="utf-8") as handle:
        json.dump(body, handle, ensure_ascii=False)
    print("ok")


if __name__ == "__main__":
    main()
