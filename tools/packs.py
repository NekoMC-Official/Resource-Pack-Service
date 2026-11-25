#!/usr/bin/env python3
"""校验、打包、规划并合并仓库内的社区资源包。

只使用 Python 标准库，不执行资源包里的任何内容。

子命令：
  build  校验 packs/，为每个包生成确定性 zip 与 manifest.json
  plan   根据 manifest 与 git 历史计算这次到底该不该发行、哪些包已批准
  bundle 把已批准/已选择的包打成服务端用的 community-packs.zip（每包独立 zip + manifest）
  index  生成 GitHub Pages 用的 community-index.json 与 objects/<sha1>.zip

绑定模型见 packs/README.md。简而言之：每个包必须声明 plugin、relation 与 release；
新增包默认不会自动发行，必须由维护者通过 workflow_dispatch 的 force_packs 明确批准一次。
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

FIXED_TIME = (1980, 1, 1, 0, 0, 0)
MAX_ENTRIES = 20000
MAX_BYTES = 64 * 1024 * 1024
MAX_PACKS = 50
MAX_TOTAL_BYTES = 128 * 1024 * 1024
ALLOWED_ID = set("abcdefghijklmnopqrstuvwxyz0123456789_-")
PLUGIN_RE = re.compile(r"^[a-z0-9][a-z0-9_-]*$")
PLAIN_VERSION_RE = re.compile(r"^v?\d+(?:\.\d+){0,3}(?:[-+][0-9A-Za-z.-]+)?$")
VERSION_RE = re.compile(r"^(?:>=|<=|>|<|==|=)?\d+(?:\.\d+){0,3}(?:[-+][0-9A-Za-z.-]+)?$")
RELATIONS = {"official", "addon", "patch", "integration", "standalone"}
RELEASES = {"auto", "manual", "none"}
TRUE_WORDS = {"true", "yes", "1", "on"}
FALSE_WORDS = {"false", "no", "0", "off"}


def die(message):
    print("错误：" + message, file=sys.stderr)
    raise SystemExit(1)


def warn(message):
    print("警告：" + message, file=sys.stderr)


def read_meta(path):
    """读取扁平的 key: value 文件。

    只支持本项目 pack.yml 用到的 YAML 子集；出现嵌套、重复键或非法行时直接失败，
    避免看起来像 YAML、实际被忽略的配置错误。
    """
    data = {}
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if raw.startswith((" ", "\t")) and raw.strip() and not raw.lstrip().startswith("#"):
            die(str(path) + ":" + str(lineno) + " 只支持扁平的 key: value，不支持嵌套 YAML")
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if ":" not in line:
            die(str(path) + ":" + str(lineno) + " 不是合法的 key: value 行：" + line)
        key, value = line.split(":", 1)
        key = key.strip()
        value = value.strip()
        if not key:
            die(str(path) + ":" + str(lineno) + " 的键为空")
        if any(ch.isspace() for ch in key):
            die(str(path) + ":" + str(lineno) + " 的键不能包含空白")
        if key in data:
            die(str(path) + ":" + str(lineno) + " 重复的键：" + key)
        if value[:1] in ("'", '"'):
            if len(value) < 2 or value[-1] != value[0]:
                die(str(path) + ":" + str(lineno) + " 的引号没有闭合")
            value = value[1:-1]
        elif " #" in value:
            value = value.split(" #", 1)[0].strip()
        data[key] = value
    return data


def parse_bool(value, path, key):
    word = str(value).strip().lower()
    if word in TRUE_WORDS:
        return True
    if word in FALSE_WORDS:
        return False
    die(str(path) + " 的 " + key + " 必须是 true/false")


def parse_plugin(value, path, key, allow_universal=False):
    value = str(value).strip()
    if allow_universal and value == "universal":
        return value
    if not PLUGIN_RE.match(value):
        die(str(path) + " 的 " + key + " 只能使用小写字母、数字、下划线和短横线")
    return value


def validate_range(value, path, key):
    value = str(value or "").strip()
    if not value or value == "*":
        return value
    for token in re.split(r"[,\s]+", value):
        if token and not VERSION_RE.match(token):
            die(str(path) + " 的 " + key + " 不是合法的版本范围：" + value)
    return value


def validate_requires(value, path):
    return validate_range(value, path, "requires")


def parse_csv(value):
    return [item.strip() for item in str(value or "").split(",") if item.strip()]


def version_tuple(value):
    """把 1.2.3 / v1.2.3-beta 归一成四段整数，用于严格递增比较。"""
    match = re.match(r"^v?(\d+(?:\.\d+){0,3})", str(value or "").strip())
    if not match:
        return None
    parts = [int(item) for item in match.group(1).split(".")]
    while len(parts) < 4:
        parts.append(0)
    return tuple(parts)


def version_increased(previous, current):
    """上一版没有 version 时不拦；有 version 时当前版本必须严格更大。"""
    before = version_tuple(previous)
    if before is None:
        return True
    after = version_tuple(current)
    return after is not None and after > before


def meta_version(text):
    """从 pack.yml 文本里取扁平 version 字段；文件已由 read_meta 校验过。"""
    for raw in str(text or "").splitlines():
        line = raw.strip()
        if line.startswith("version:"):
            value = line.split(":", 1)[1].strip()
            if value[:1] in ("'", '"') and value[-1:] == value[:1]:
                value = value[1:-1]
            return value.strip()
    return ""


def git_file(rev, path):
    result = subprocess.run(
        ["git", "show", rev + ":" + path],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return result.stdout if result.returncode == 0 else None


def parse_format_range(pack, path):
    supported = pack.get("supported_formats")
    if supported:
        if isinstance(supported, dict):
            low = supported.get("min_inclusive")
            high = supported.get("max_inclusive")
            if not isinstance(low, int) or not isinstance(high, int):
                die(str(path) + " 的 supported_formats 需要 min_inclusive/max_inclusive 整数")
        elif isinstance(supported, list) and supported and all(isinstance(item, int) for item in supported):
            low, high = min(supported), max(supported)
        elif isinstance(supported, int):
            low = high = supported
        else:
            die(str(path) + " 的 supported_formats 格式不受支持")
        if low > high:
            die(str(path) + " 的 supported_formats 下限大于上限")
        return low, high
    value = pack.get("pack_format")
    if isinstance(value, int):
        return value, value
    die(str(path) + " 无法确定 pack_format 范围")


def check_pack(root):
    meta_path = root / "pack.yml"
    mcmeta_path = root / "pack.mcmeta"
    assets = root / "assets"
    if not meta_path.is_file():
        die(str(root) + " 缺少 pack.yml")
    if not mcmeta_path.is_file():
        die(str(root) + " 缺少 pack.mcmeta")
    if not assets.is_dir():
        die(str(root) + " 缺少 assets/ 目录")

    meta = read_meta(meta_path)
    for key in ("id", "name", "license", "plugin", "relation", "release"):
        if not meta.get(key):
            die(str(meta_path) + " 缺少 " + key)
    if meta["id"] != root.name:
        die(str(meta_path) + " 的 id 与目录名 " + root.name + " 不一致")
    if any(ch not in ALLOWED_ID for ch in meta["id"]):
        die(str(meta_path) + " 的 id 只允许小写字母、数字、下划线和短横线")

    plugin = parse_plugin(meta["plugin"], meta_path, "plugin", allow_universal=True)
    relation = meta["relation"].strip()
    if relation not in RELATIONS:
        die(str(meta_path) + " 的 relation 必须是 " + "、".join(sorted(RELATIONS)))
    release = meta["release"].strip()
    if release not in RELEASES:
        die(str(meta_path) + " 的 release 必须是 " + "、".join(sorted(RELEASES)))

    if relation == "standalone":
        if plugin != "universal":
            die(str(meta_path) + " 的 standalone 包必须写 plugin: universal")
    elif plugin == "universal":
        die(str(meta_path) + " 只有 standalone 才能使用 plugin: universal")

    also = []
    if meta.get("also"):
        if relation != "integration":
            die(str(meta_path) + " 只有 integration 才能写 also")
        also = parse_csv(meta["also"])
        if not also:
            die(str(meta_path) + " 的 also 不能为空")
        if len(set(also)) != len(also):
            die(str(meta_path) + " 的 also 有重复插件")
        for item in also:
            parse_plugin(item, meta_path, "also")
            if item == "universal":
                die(str(meta_path) + " 的 also 不能是 universal")
            if item == plugin:
                die(str(meta_path) + " 的 also 不能包含主插件")
    elif relation == "integration":
        die(str(meta_path) + " 的 integration 必须写 also，列出同时服务的其它插件")

    variant = meta.get("variant", "").strip()
    if variant and not PLUGIN_RE.match(variant):
        die(str(meta_path) + " 的 variant 只能使用小写字母、数字、下划线和短横线")
    is_default = parse_bool(meta.get("default", "false"), meta_path, "default")
    requires = validate_requires(meta.get("requires", ""), meta_path)
    version = str(meta.get("version", "")).strip()
    if version and not PLAIN_VERSION_RE.match(version):
        die(str(meta_path) + " 的 version 不是合法的版本号：" + version)
    minecraft = validate_range(meta.get("minecraft", ""), meta_path, "minecraft")

    priority = 0
    if meta.get("priority"):
        if not re.fullmatch(r"-?\d+", meta["priority"].strip()):
            die(str(meta_path) + " 的 priority 必须是整数")
        priority = int(meta["priority"].strip())

    try:
        mcmeta = json.loads(mcmeta_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        die(str(mcmeta_path) + " 不是合法 JSON：" + str(exc))
    pack = mcmeta.get("pack") if isinstance(mcmeta, dict) else None
    if not isinstance(pack, dict):
        die(str(mcmeta_path) + " 缺少 pack 对象")
    if not isinstance(pack.get("pack_format"), int) and not pack.get("supported_formats"):
        die(str(mcmeta_path) + " 需要 pack.pack_format 或 pack.supported_formats")
    if "description" not in pack:
        die(str(mcmeta_path) + " 需要 pack.description")
    format_min, format_max = parse_format_range(pack, mcmeta_path)

    entries = []
    total = 0
    namespaces = set()
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            die(str(root) + " 不允许符号链接：" + path.relative_to(root).as_posix())
        if path.is_dir():
            continue
        rel = path.relative_to(root).as_posix()
        if rel == "pack.yml":
            continue
        if ".." in Path(rel).parts or rel.startswith("/") or "\\" in rel:
            die(str(root) + " 含非法路径：" + rel)
        data = path.read_bytes()
        total += len(data)
        entries.append((rel, data))
        parts = Path(rel).parts
        if len(parts) >= 3 and parts[0] == "assets":
            namespaces.add(parts[1])

    if not entries:
        die(str(root) + " 没有任何可下发的文件")
    if len(entries) > MAX_ENTRIES:
        die(str(root) + " 条目数超过上限 " + str(MAX_ENTRIES))
    if total > MAX_BYTES:
        die(str(root) + " 解压后大小超过上限 " + str(MAX_BYTES))
    if not namespaces:
        die(str(root) + "/assets 下没有命名空间目录")

    return {
        "id": meta["id"],
        "name": meta.get("name", meta["id"]),
        "priority": priority,
        "description": meta.get("description", ""),
        "license": meta.get("license", ""),
        "plugin": plugin,
        "relation": relation,
        "also": also,
        "requires": requires,
        "version": version,
        "minecraft": minecraft,
        "variant": variant,
        "default": is_default,
        "release": release,
        "format": pack.get("pack_format"),
        "format_min": format_min,
        "format_max": format_max,
        "namespaces": sorted(namespaces),
        "files": len(entries),
        "uncompressed_bytes": total,
        "entries": entries,
    }


def write_zip(target, entries):
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for rel, data in entries:
            info = zipfile.ZipInfo(rel, FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = (0o644 & 0xFFFF) << 16
            archive.writestr(info, data)


def sha1_file(path):
    digest = hashlib.sha1()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def pack_dirs(root):
    if not root.is_dir():
        die(str(root) + " 不是目录")
    result = []
    for child in sorted(root.iterdir()):
        if not child.is_dir() or child.name == "_template" or child.name.startswith("."):
            continue
        if (child / "pack.yml").is_file():
            result.append(child)
    return result


def resolve_selection(packs):
    """按绑定关系选出这次应该参与合并的包。

    - release=none：永远不进入社区包。
    - official：每个插件至少一个默认包；可按不同 minecraft 范围提供多个 default，全部进入候选，运行期再按优先级择一。
    - addon/patch：必须能找到同插件的 official 默认包。
    - integration：主插件与 also 里的每个插件都必须有 official 默认包。
    - standalone：不依赖插件。
    - 同一 variant 组（非 official）只能有一个默认包；没有 variant 时各自独立。
    """
    by_id = {}
    for pack in packs:
        if pack["id"] in by_id:
            die("资源包 id 重复：" + pack["id"])
        by_id[pack["id"]] = pack

    excluded = {}
    eligible = []
    for pack in sorted(packs, key=lambda item: item["id"]):
        if pack["release"] == "none":
            excluded[pack["id"]] = "release=none，不进入社区包"
        else:
            eligible.append(pack)

    selected = []
    official_by_plugin = {}
    for pack in eligible:
        if pack["relation"] == "official":
            official_by_plugin.setdefault(pack["plugin"], []).append(pack)

    for plugin, group in sorted(official_by_plugin.items()):
        group = sorted(group, key=lambda item: item["id"])
        defaults = [item for item in group if item["default"]]
        if len(group) > 1 and not defaults:
            die("插件 " + plugin + " 有 " + str(len(group)) + " 个 official 包，至少需要一个 default: true")
        # 同一插件可以按不同的 minecraft 范围提供多个 official 默认包；
        # 运行期由服务端先按 MC/format/requires 过滤，再决定是否唯一。
        chosen = defaults if defaults else [group[0]]
        selected.extend(chosen)
        for item in group:
            if item not in chosen:
                excluded[item["id"]] = "同插件 official 的非默认包"

    variant_groups = {}
    for pack in eligible:
        if pack["relation"] == "official":
            continue
        key = (pack["plugin"], pack["relation"], pack["variant"]) if pack["variant"] else ("id", pack["id"])
        variant_groups.setdefault(key, []).append(pack)

    for key, group in variant_groups.items():
        group = sorted(group, key=lambda item: item["id"])
        if len(group) > 1:
            defaults = [item for item in group if item["default"]]
            if len(defaults) != 1:
                die("变体组 " + str(key) + " 有多个包，必须且只能有一个 default: true")
            chosen = defaults[0]
        else:
            chosen = group[0]
        selected.append(chosen)
        for item in group:
            if item["id"] != chosen["id"]:
                excluded[item["id"]] = "同变体组的非默认包"

    official = {pack["plugin"]: pack for pack in selected if pack["relation"] == "official"}
    final = []
    for pack in selected:
        if pack["relation"] in ("addon", "patch"):
            if pack["plugin"] not in official:
                excluded[pack["id"]] = "缺少同插件的 official 包：" + pack["plugin"]
                continue
        if pack["relation"] == "integration":
            needed = [pack["plugin"]] + pack["also"]
            missing = [item for item in needed if item not in official]
            if missing:
                excluded[pack["id"]] = "缺少 official 包：" + "、".join(missing)
                continue
        final.append(pack)

    final.sort(key=lambda item: (item["priority"], item["id"]))
    return {
        "selected": final,
        "excluded": [{"id": key, "reason": value} for key, value in sorted(excluded.items())],
        "by_id": by_id,
    }


def cmd_build(args):
    root = Path(args.root)
    out = Path(args.out)
    dirs = pack_dirs(root)
    if len(dirs) > MAX_PACKS:
        die("资源包数量超过上限 " + str(MAX_PACKS))

    packs = []
    total_uncompressed = 0
    for directory in dirs:
        pack = check_pack(directory)
        entries = pack.pop("entries")
        total_uncompressed += pack["uncompressed_bytes"]
        target = out / (pack["id"] + ".zip")
        write_zip(target, entries)
        pack["sha1"] = sha1_file(target)
        pack["bytes"] = target.stat().st_size
        packs.append(pack)

    if total_uncompressed > MAX_TOTAL_BYTES:
        die("所有资源包解压后总大小超过上限 " + str(MAX_TOTAL_BYTES))

    selection = resolve_selection(packs)
    manifest = {
        "version": 1,
        "packs": sorted(packs, key=lambda item: item["id"]),
        "bundle": {
            "selected": [item["id"] for item in selection["selected"]],
            "excluded": selection["excluded"],
        },
    }
    out.mkdir(parents=True, exist_ok=True)
    (out / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print("已打包 " + str(len(packs)) + " 个资源包到 " + str(out))
    print("可进入社区包：" + (", ".join(manifest["bundle"]["selected"]) or "无"))
    return 0


def find_binary(value):
    path = Path(value).expanduser()
    candidates = [path]
    if path.suffix.lower() != ".exe":
        candidates.append(Path(str(path) + ".exe"))
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    die("找不到 rps-pack 合并器：" + str(path))


def human(size):
    value = float(size)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if value < 1024 or unit == "GiB":
            return ("%.0f %s" if unit == "B" else "%.1f %s") % (value, unit)
        value /= 1024
    return str(size) + " B"


def read_manifest(path):
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except FileNotFoundError:
        die("找不到 manifest：" + str(path))
    except json.JSONDecodeError as exc:
        die(str(path) + " 不是合法 JSON：" + str(exc))
    if not isinstance(data, dict) or not isinstance(data.get("packs"), list):
        die(str(path) + " 缺少 packs 数组")
    return data


def md_cell(value):
    text = "" if value is None else str(value)
    return text.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").replace("\r", " ")


def cmd_plan(args):
    manifest = read_manifest(args.manifest)
    packs = manifest["packs"]
    bundle = manifest.get("bundle") or {}
    selected = set(bundle.get("selected") or [])
    excluded = bundle.get("excluded") or []
    by_id = {pack["id"]: pack for pack in packs}
    force = parse_csv(args.force)

    for pack_id in force:
        if pack_id not in by_id:
            die("force_packs 里有未知资源包：" + pack_id)
        if by_id[pack_id]["release"] == "none":
            die("force_packs 不能包含 release=none 的资源包：" + pack_id)
        if pack_id not in selected:
            reason = next(
                (item.get("reason", "") for item in excluded if item.get("id") == pack_id),
                "未进入本次合并",
            )
            die("force_packs 里的资源包未进入本次合并：" + pack_id + "（" + reason + "）")

    previous = (args.previous or "").strip()
    existing = set()
    if previous:
        for pack in packs:
            if pack["id"] not in selected:
                continue
            probe = subprocess.run(
                ["git", "cat-file", "-e", previous + ":packs/" + pack["id"] + "/pack.yml"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            if probe.returncode == 0:
                existing.add(pack["id"])

    previous_ids = set()
    if previous:
        tree = subprocess.run(
            ["git", "ls-tree", "-r", "-d", "--name-only", previous, "packs"],
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        if tree.returncode != 0:
            die("git ls-tree 失败：" + tree.stderr.strip())
        for line in tree.stdout.splitlines():
            parts = Path(line).parts
            if len(parts) >= 2 and parts[0] == "packs":
                previous_ids.add(parts[1])
    code_changed = True
    changed_packs = set()
    content_changed = set()
    if previous:
        code_paths = parse_csv(args.code_paths)
        diff = subprocess.run(
            ["git", "diff", "--name-only", previous + "..HEAD", "--", *code_paths],
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        if diff.returncode != 0:
            die("git diff 失败：" + diff.stderr.strip())
        code_changed = bool(diff.stdout.strip())

        pack_diff = subprocess.run(
            ["git", "diff", "--name-only", previous + "..HEAD", "--", "packs"],
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        if pack_diff.returncode != 0:
            die("git diff packs 失败：" + pack_diff.stderr.strip())
        for line in pack_diff.stdout.splitlines():
            parts = Path(line).parts
            if len(parts) >= 2 and parts[0] == "packs":
                changed_packs.add(parts[1])
                if len(parts) >= 3 and parts[-1] not in ("pack.yml", "README.md"):
                    content_changed.add(parts[1])
    else:
        changed_packs = {pack["id"] for pack in packs}

    approved = []
    new_auto = []
    new_manual = []
    for pack in packs:
        pack_id = pack["id"]
        if pack_id not in selected:
            continue
        if pack_id in force or (pack_id in existing and pack["release"] == "auto"):
            approved.append(pack_id)
        elif pack["release"] == "auto":
            new_auto.append(pack_id)
        elif pack["release"] == "manual":
            new_manual.append(pack_id)
    approved = sorted(set(approved))
    new_auto = sorted(set(new_auto))
    new_manual = sorted(set(new_manual))
    newly_approved = sorted(set(pack_id for pack_id in force if pack_id not in existing))
    changed_released = sorted(pack_id for pack_id in approved if pack_id in changed_packs)
    withdrawn = sorted(
        (previous_ids - selected)
        | {pack_id for pack_id in changed_packs if pack_id in previous_ids and pack_id not in approved}
    )

    regressions = []
    if previous:
        for pack_id in approved:
            if pack_id not in existing or pack_id not in content_changed:
                continue
            previous_text = git_file(previous, "packs/" + pack_id + "/pack.yml")
            if previous_text is None:
                continue
            before = meta_version(previous_text)
            after = (by_id[pack_id].get("version") or "").strip()
            if not version_increased(before, after):
                regressions.append(pack_id + "（" + (before or "无") + " → " + (after or "无") + "）")
        if regressions:
            die("资源包内容有变更但 version 没有递增：" + "；".join(regressions) + "。请先提升 pack.yml 的 version 再发行。")

    not_approved = []
    for pack in packs:
        if pack["id"] not in selected:
            continue
        if pack["id"] in approved or pack["release"] == "none":
            continue
        if pack["release"] == "manual":
            reason = "release=manual，仅在 force_packs 点名时纳入"
        elif pack["id"] in new_auto:
            reason = "新增包，需维护者 force_packs 批准"
        else:
            reason = "未批准"
        not_approved.append({"id": pack["id"], "reason": reason})

    if approved:
        approved_set = set(approved)
        check = resolve_selection([pack for pack in packs if pack["id"] in approved_set])
        blocked = check.get("excluded") or []
        if blocked:
            details = "；".join(
                item["id"] + "：" + item.get("reason", "") for item in blocked
            )
            die("已批准资源包的绑定关系不完整：" + details)

    should_release = bool(code_changed or force or changed_released or withdrawn)
    packs_changed = bool(force) or bool(changed_released) or bool(newly_approved) or bool(withdrawn)
    plan = {
        "previous": previous,
        "code_changed": code_changed,
        "selected": sorted(selected),
        "excluded": excluded,
        "approved": approved,
        "new_auto": new_auto,
        "new_manual": new_manual,
        "newly_approved": newly_approved,
        "changed_packs": sorted(changed_packs),
        "content_changed": sorted(content_changed),
        "changed_released": changed_released,
        "withdrawn": withdrawn,
        "not_approved": not_approved,
        "should_release": should_release,
        "packs_changed": packs_changed,
    }
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(
        json.dumps(plan, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    if args.ids_out:
        Path(args.ids_out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.ids_out).write_text("\n".join(approved) + ("\n" if approved else ""), encoding="utf-8")
    if args.github_output:
        with Path(args.github_output).open("a", encoding="utf-8") as handle:
            handle.write("should_release=" + ("true" if should_release else "false") + "\n")
            handle.write("packs_changed=" + ("true" if packs_changed else "false") + "\n")
            handle.write("approved=" + ",".join(approved) + "\n")
            handle.write("new_auto=" + ",".join(new_auto) + "\n")
            handle.write("previous=" + previous + "\n")
            handle.write("code_changed=" + ("true" if code_changed else "false") + "\n")
            handle.write("withdrawn=" + ",".join(withdrawn) + "\n")

    print("上次发行：" + (previous or "无"))
    print("代码变更：" + ("是" if code_changed else "否"))
    print("资源包变更：" + ("是" if packs_changed else "否"))
    print("已批准资源包：" + (", ".join(approved) or "无"))
    print("新增但未批准：" + (", ".join(new_auto) or "无"))
    print("已撤下：" + (", ".join(withdrawn) or "无"))
    print("本次" + ("需要发行" if should_release else "不需要发行"))
    return 0


def cmd_bundle(args):
    packs_dir = Path(args.packs)
    manifest = read_manifest(packs_dir / "manifest.json")
    packs = manifest["packs"]
    by_id = {pack["id"]: pack for pack in packs}

    only = parse_csv(args.only)
    if only:
        unknown = [pack_id for pack_id in only if pack_id not in by_id]
        if unknown:
            die("--only 里有未知资源包：" + "、".join(unknown))
        blocked = [pack_id for pack_id in only if by_id[pack_id]["release"] == "none"]
        if blocked:
            die("--only 不能包含 release=none 的资源包：" + "、".join(blocked))
        subset = [pack for pack in packs if pack["id"] in set(only)]
    else:
        subset = packs

    selection = resolve_selection(subset)
    if only:
        excluded_ids = {
            item["id"]: item.get("reason", "")
            for item in selection.get("excluded") or []
        }
        blocked = [pack_id for pack_id in only if pack_id in excluded_ids]
        if blocked:
            details = "；".join(
                pack_id + "：" + excluded_ids[pack_id] for pack_id in blocked
            )
            die("--only 里的资源包未通过绑定选择：" + details)
    selected = selection["selected"]
    excluded = selection["excluded"]

    out = Path(args.out)
    summary_path = Path(args.summary)
    out.parent.mkdir(parents=True, exist_ok=True)
    summary_path.parent.mkdir(parents=True, exist_ok=True)

    bundle_packs = []
    entries = []
    for pack in selected:
        archive = packs_dir / (pack["id"] + ".zip")
        if not archive.is_file():
            die("缺少资源包 zip：" + str(archive))
        data = archive.read_bytes()
        entries.append((pack["id"] + ".zip", data))
        bundle_packs.append({
            key: pack.get(key)
            for key in (
                "id", "name", "plugin", "relation", "also", "priority",
                "variant", "default", "release", "requires", "version",
                "minecraft", "format", "format_min", "format_max",
                "namespaces", "sha1", "bytes", "files", "uncompressed_bytes",
            )
        })

    bundle_manifest = {
        "version": 1,
        "packs": sorted(bundle_packs, key=lambda item: item["id"]),
        "bundle": {
            "selected": [pack["id"] for pack in selected],
            "excluded": excluded,
        },
    }
    entries.append((
        "manifest.json",
        (json.dumps(bundle_manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8"),
    ))
    entries.sort(key=lambda item: item[0])
    write_zip(out, entries)

    lines = [
        "# 社区资源包目录",
        "",
        "服务端解压后由插件按已安装插件再次筛选；没有安装对应插件的包不会参与合成。",
        "",
        "## 已纳入",
        "",
        "| 资源包 | 插件 | 关系 | 优先级 | 发行 | 文件 |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for pack in selected:
        lines.append(
            "| " + md_cell(pack["id"])
            + " | " + md_cell(pack["plugin"])
            + " | " + md_cell(pack["relation"])
            + " | " + md_cell(pack["priority"])
            + " | " + md_cell(pack["release"])
            + " | " + md_cell(pack["id"] + ".zip")
            + " |"
        )
    lines += ["", "## 未纳入", ""]
    if excluded:
        lines += ["| 资源包 | 原因 |", "| --- | --- |"]
        for item in excluded[:50]:
            lines.append("| " + md_cell(item["id"]) + " | " + md_cell(item["reason"]) + " |")
        if len(excluded) > 50:
            lines.append("")
            lines.append("仅显示前 50 条，共 " + str(len(excluded)) + " 条。")
    else:
        lines.append("无。")
    lines += [
        "",
        "## 使用",
        "",
        "把 community-packs.zip 解压到服务端的 plugins/ResourcePackService/community/，",
        "然后执行 /rps reload。目录里必须有 manifest.json，每个包是独立的 <id>.zip。",
        "",
        "新增资源包不会因为这次构建自动发行；维护者确认后，用 Auto Release 工作流的",
        "force_packs 输入明确批准一次。已批准且 release: auto 的包在后续变更中才会自动发行。",
        "",
    ]
    summary_path.write_text("\n".join(lines), encoding="utf-8")

    print("已生成 " + str(out) + "，纳入 " + str(len(selected)) + " 个资源包")
    return 0


def cmd_index(args):
    manifest_path = Path(args.manifest)
    manifest = read_manifest(manifest_path)
    packs = manifest["packs"]
    by_id = {pack["id"]: pack for pack in packs}
    only_raw = args.only
    explicit = only_raw is not None
    only = parse_csv(only_raw)
    if explicit:
        unknown = [pack_id for pack_id in only if pack_id not in by_id]
        if unknown:
            die("--only 里有未知资源包：" + "、".join(unknown))
        blocked = [pack_id for pack_id in only if by_id[pack_id]["release"] == "none"]
        if blocked:
            die("--only 不能包含 release=none 的资源包：" + "、".join(blocked))
        subset = [pack for pack in packs if pack["id"] in set(only)]
    else:
        subset = [pack for pack in packs if pack["release"] != "none"]

    selection = resolve_selection(subset)
    if explicit:
        excluded_ids = {item["id"]: item.get("reason", "") for item in selection.get("excluded") or []}
        blocked = [pack_id for pack_id in only if pack_id in excluded_ids]
        if blocked:
            details = "；".join(pack_id + "：" + excluded_ids[pack_id] for pack_id in blocked)
            die("--only 里的资源包未通过绑定选择：" + details)

    out = Path(args.out)
    objects = out / "objects"
    objects.mkdir(parents=True, exist_ok=True)
    entries = []
    for pack in sorted(selection["selected"], key=lambda item: item["id"]):
        archive = manifest_path.parent / (pack["id"] + ".zip")
        if not archive.is_file():
            die("缺少资源包 zip：" + str(archive))
        sha1 = str(pack.get("sha1") or "")
        if not sha1:
            die("资源包缺少 sha1：" + pack["id"])
        target = objects / (sha1 + ".zip")
        if not target.is_file():
            target.write_bytes(archive.read_bytes())
        entries.append({
            "id": pack["id"],
            "name": pack.get("name") or pack["id"],
            "plugin": pack["plugin"],
            "relation": pack["relation"],
            "also": pack.get("also") or [],
            "version": pack.get("version") or "",
            "minecraft": pack.get("minecraft") or "",
            "requires": pack.get("requires") or "",
            "variant": pack.get("variant") or "",
            "default": bool(pack.get("default")),
            "priority": int(pack.get("priority") or 0),
            "release": pack.get("release") or "auto",
            "formatMin": int(pack.get("format_min") or 0),
            "formatMax": int(pack.get("format_max") or 0),
            "sha1": sha1,
            "bytes": int(pack.get("bytes") or 0),
            "file": "objects/" + sha1 + ".zip",
        })

    commit = (os.environ.get("GITHUB_SHA") or "").strip()
    if not commit:
        try:
            commit = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
        except (OSError, subprocess.CalledProcessError):
            commit = ""
    generated = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    index = {
        "schema": 1,
        "generatedAt": generated,
        "commit": commit,
        "packs": entries,
    }
    (out / "community-index.json").write_text(
        json.dumps(index, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (out / ".nojekyll").write_text("", encoding="utf-8")

    total = sum(item["bytes"] for item in entries)
    print("已生成 " + str(out / "community-index.json") + "，收录 " + str(len(entries))
          + " 个资源包，共 " + human(total) + "，commit=" + (commit or "unknown"))
    return 0

def main():
    parser = argparse.ArgumentParser(description="校验、打包、规划并生成社区资源包目录")
    sub = parser.add_subparsers(dest="command", required=True)

    build = sub.add_parser("build", help="校验 packs/ 并生成 zip")
    build.add_argument("--root", default="packs")
    build.add_argument("--out", default="build/packs")
    build.set_defaults(func=cmd_build)

    plan = sub.add_parser("plan", help="计算本次是否发行以及哪些包已批准")
    plan.add_argument("--manifest", default="build/packs/manifest.json")
    plan.add_argument("--previous", default="")
    plan.add_argument("--force", default="")
    plan.add_argument("--out", default="build/release-plan.json")
    plan.add_argument("--ids-out", default="build/approved-packs.txt")
    plan.add_argument("--github-output", default="")
    plan.add_argument(
        "--code-paths",
        default="src,rust,build.gradle.kts,settings.gradle.kts,tools/packs.py,.github/workflows/auto-release.yml",
    )
    plan.set_defaults(func=cmd_plan)

    bundle = sub.add_parser("bundle", help="生成服务端用的社区资源包目录")
    bundle.add_argument("--packs", default="build/packs")
    bundle.add_argument("--out", default="build/community-packs.zip")
    bundle.add_argument("--summary", default="build/pack-summary.md")
    bundle.add_argument("--only", default="", help="逗号分隔的资源包 id；只打包这些包")
    bundle.set_defaults(func=cmd_bundle)

    index = sub.add_parser("index", help="生成 GitHub Pages 用的社区资源包索引")
    index.add_argument("--manifest", default="build/packs/manifest.json")
    index.add_argument("--out", default="build/pages")
    index.add_argument("--only", default=None, help="逗号分隔的资源包 id；只收录这些包，传空串生成空索引")
    index.set_defaults(func=cmd_index)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    SystemExit(main())
