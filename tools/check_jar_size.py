#!/usr/bin/env python3
"""检查交付 jar 的体积没有超出预算（见 docs/rps-pack-maxperf-plan.md 第 18 节）。"""

from __future__ import annotations

import sys
from pathlib import Path

LIMITS = [
    ("Paper", "paper/build/libs", "resource-pack-service-", 2_750_000),
    ("Fabric", "fabric/build/libs", "resource-pack-service-fabric-", 2_900_000),
]


def main() -> int:
    bad: list[str] = []
    for label, folder, prefix, limit in LIMITS:
        jars = sorted(
            path
            for path in Path(folder).glob(prefix + "*.jar")
            if not path.name.endswith("-plain.jar")
        )
        if len(jars) != 1:
            bad.append(f"{label}: 期望 {folder} 下恰好一个交付 jar，实际 {[p.name for p in jars]}")
            continue
        size = jars[0].stat().st_size
        print(f"{label}: {jars[0].name} {size} 字节（上限 {limit}）")
        if size > limit:
            bad.append(f"{label}: {size} 字节超过上限 {limit}")
    if bad:
        print("\n".join(bad), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
