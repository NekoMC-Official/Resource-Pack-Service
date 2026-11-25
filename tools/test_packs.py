import argparse
import contextlib
import io
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path

import packs


def make_pack(
    pack_id,
    plugin="companion",
    relation="official",
    release="auto",
    priority=0,
    default=False,
    variant="",
    also=None,
):
    return {
        "id": pack_id,
        "plugin": plugin,
        "relation": relation,
        "release": release,
        "priority": priority,
        "default": default,
        "variant": variant,
        "also": also or [],
    }


def ids(selection):
    return [pack["id"] for pack in selection["selected"]]


def reasons(selection):
    return {item["id"]: item["reason"] for item in selection["excluded"]}


class SelectionTests(unittest.TestCase):
    def test_official_variant_picks_default(self):
        selection = packs.resolve_selection(
            [
                make_pack("base", default=True),
                make_pack("hd", variant="hd"),
            ]
        )
        self.assertEqual(ids(selection), ["base"])
        self.assertIn("hd", reasons(selection))

    def test_multiple_official_defaults_are_all_selected(self):
        selection = packs.resolve_selection(
            [make_pack("one", default=True), make_pack("two", default=True)]
        )
        self.assertEqual(ids(selection), ["one", "two"])

    def test_multiple_official_without_default_is_rejected(self):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as caught:
                packs.resolve_selection(
                    [make_pack("one"), make_pack("two")]
                )
        self.assertNotEqual(caught.exception.code, 0)

    def test_addon_requires_official(self):
        selection = packs.resolve_selection([make_pack("addon", relation="addon")])
        self.assertEqual(ids(selection), [])
        self.assertIn("official", reasons(selection)["addon"])

    def test_addon_with_official_is_selected(self):
        selection = packs.resolve_selection(
            [
                make_pack("addon", relation="addon", priority=10),
                make_pack("base", default=True),
            ]
        )
        self.assertEqual(set(ids(selection)), {"addon", "base"})

    def test_integration_requires_every_plugin(self):
        selection = packs.resolve_selection(
            [
                make_pack("companion", plugin="companion", default=True),
                make_pack("bettermodel", plugin="bettermodel", default=True),
                make_pack(
                    "bridge",
                    relation="integration",
                    also=["bettermodel"],
                    priority=5,
                ),
            ]
        )
        self.assertEqual(set(ids(selection)), {"companion", "bettermodel", "bridge"})

    def test_integration_missing_plugin_is_excluded(self):
        selection = packs.resolve_selection(
            [
                make_pack("companion", plugin="companion", default=True),
                make_pack("bridge", relation="integration", also=["bettermodel"]),
            ]
        )
        self.assertEqual(ids(selection), ["companion"])
        self.assertIn("bettermodel", reasons(selection)["bridge"])

    def test_release_none_is_excluded(self):
        selection = packs.resolve_selection(
            [make_pack("theme", plugin="universal", relation="standalone", release="none")]
        )
        self.assertEqual(ids(selection), [])
        self.assertIn("release=none", reasons(selection)["theme"])

    def test_non_official_variant_group_picks_default(self):
        selection = packs.resolve_selection(
            [
                make_pack("base", default=True),
                make_pack("addon-a", relation="addon", variant="style", default=True),
                make_pack("addon-b", relation="addon", variant="style"),
            ]
        )
        self.assertEqual(set(ids(selection)), {"base", "addon-a"})
        self.assertIn("addon-b", reasons(selection))

    def test_selection_order_is_priority_then_id(self):
        selection = packs.resolve_selection(
            [
                make_pack("base", default=True, priority=0),
                make_pack("low", relation="addon", priority=-5),
                make_pack("high", relation="addon", priority=10),
            ]
        )
        self.assertEqual(ids(selection), ["low", "base", "high"])


class PackMetaTests(unittest.TestCase):
    def make_pack(self, version="1.2.0", minecraft=">=1.21 <1.22", formats=None, pack_format=26):
        root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, root, True)
        pack = root / "demo"
        (pack / "assets" / "demo").mkdir(parents=True)
        (pack / "assets" / "demo" / "x.txt").write_text("x", encoding="utf-8")
        lines = [
            "id: demo",
            "name: Demo",
            "license: MIT",
            "plugin: companion",
            "relation: official",
            "release: auto",
        ]
        if version is not None:
            lines.append("version: " + version)
        if minecraft is not None:
            lines.append('minecraft: "' + minecraft + '"')
        (pack / "pack.yml").write_text("\n".join(lines) + "\n", encoding="utf-8")
        meta = {"pack_format": pack_format, "description": "x"}
        if formats is not None:
            meta["supported_formats"] = formats
        (pack / "pack.mcmeta").write_text(json.dumps({"pack": meta}), encoding="utf-8")
        return pack

    def check(self, **kwargs):
        return packs.check_pack(self.make_pack(**kwargs))

    def test_reads_version_minecraft_and_object_format_range(self):
        meta = self.check(formats={"min_inclusive": 18, "max_inclusive": 34})
        self.assertEqual(meta["version"], "1.2.0")
        self.assertEqual(meta["minecraft"], ">=1.21 <1.22")
        self.assertEqual(meta["format_min"], 18)
        self.assertEqual(meta["format_max"], 34)
        self.assertEqual(meta["namespaces"], ["demo"])

    def test_accepts_list_format_range_in_any_order(self):
        meta = self.check(formats=[34, 18])
        self.assertEqual((meta["format_min"], meta["format_max"]), (18, 34))

    def test_pack_format_is_used_when_supported_formats_missing(self):
        meta = self.check(pack_format=26)
        self.assertEqual((meta["format_min"], meta["format_max"]), (26, 26))

    def test_version_is_optional(self):
        meta = self.check(version=None, minecraft=None)
        self.assertEqual(meta["version"], "")
        self.assertEqual(meta["minecraft"], "")

    def test_invalid_version_is_rejected(self):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as caught:
                self.check(version="not-a-version")
        self.assertNotEqual(caught.exception.code, 0)

    def test_invalid_minecraft_range_is_rejected(self):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as caught:
                self.check(minecraft=">=")
        self.assertNotEqual(caught.exception.code, 0)


class IndexTests(unittest.TestCase):
    def test_index_includes_multiple_official_defaults(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            records = []
            for pack_id, sha1 in (("one", "a" * 40), ("two", "b" * 40)):
                (root / (pack_id + ".zip")).write_bytes(b"PK\x05\x06" + b"\0" * 18)
                record = make_pack(pack_id, default=True)
                record.update(
                    {
                        "name": pack_id,
                        "version": "1.0.0",
                        "minecraft": ">=26.3 <26.4",
                        "requires": "",
                        "format_min": 64,
                        "format_max": 64,
                        "sha1": sha1,
                        "bytes": 22,
                    }
                )
                records.append(record)
            (root / "manifest.json").write_text(
                json.dumps({"version": 1, "packs": records}), encoding="utf-8"
            )
            args = argparse.Namespace(
                manifest=str(root / "manifest.json"),
                out=str(root / "pages"),
                only=None,
            )
            self.assertEqual(packs.cmd_index(args), 0)
            index = json.loads((root / "pages" / "community-index.json").read_text(encoding="utf-8"))
            self.assertEqual([item["id"] for item in index["packs"]], ["one", "two"])
            self.assertEqual(index["packs"][0]["file"], "objects/" + "a" * 40 + ".zip")
            self.assertTrue((root / "pages" / "objects" / ("a" * 40 + ".zip")).is_file())
            self.assertTrue((root / "pages" / ".nojekyll").is_file())

    def test_explicit_empty_selection_writes_empty_index(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "one.zip").write_bytes(b"PK\x05\x06" + b"\0" * 18)
            record = make_pack("one", default=True)
            record.update({"sha1": "a" * 40, "bytes": 22})
            (root / "manifest.json").write_text(
                json.dumps({"version": 1, "packs": [record]}), encoding="utf-8"
            )
            args = argparse.Namespace(
                manifest=str(root / "manifest.json"),
                out=str(root / "pages"),
                only="",
            )
            self.assertEqual(packs.cmd_index(args), 0)
            index = json.loads((root / "pages" / "community-index.json").read_text(encoding="utf-8"))
            self.assertEqual(index["packs"], [])


class VersionGateTests(unittest.TestCase):
    def test_version_tuple_orders_numerically(self):
        self.assertGreater(packs.version_tuple("1.2.10"), packs.version_tuple("1.2.9"))
        self.assertEqual(packs.version_tuple("v1.2"), (1, 2, 0, 0))
        self.assertEqual(packs.version_tuple("2.0.0-beta"), (2, 0, 0, 0))

    def test_version_increased(self):
        self.assertTrue(packs.version_increased("", "0.0.1"))
        self.assertTrue(packs.version_increased("1.0.0", "1.0.1"))
        self.assertFalse(packs.version_increased("1.0.0", "1.0.0"))
        self.assertFalse(packs.version_increased("1.0.0", ""))
        self.assertFalse(packs.version_increased("1.0.0", "0.9.9"))

    def test_meta_version_reads_flat_field(self):
        self.assertEqual(packs.meta_version('version: "1.4.2"\nplugin: demo\n'), "1.4.2")
        self.assertEqual(packs.meta_version("plugin: demo\n"), "")


class PlanGateTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="pack-plan-"))
        self.addCleanup(shutil.rmtree, self.root, True)
        self.addCleanup(lambda: None)

    def git(self, *args):
        import subprocess

        result = subprocess.run(["git", *args], cwd=self.root, capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(result.returncode, 0, result.stderr)

    def write_pack(self, version):
        pack = self.root / "packs" / "demo"
        (pack / "assets" / "demo").mkdir(parents=True, exist_ok=True)
        (pack / "pack.yml").write_text(
            "id: demo\nname: Demo\nlicense: MIT\nplugin: demo\nrelation: official\n"
            "release: auto\nversion: " + version + "\ndefault: true\n",
            encoding="utf-8",
        )
        (pack / "pack.mcmeta").write_text('{"pack":{"pack_format":64,"description":"demo"}}', encoding="utf-8")
        (pack / "assets" / "demo" / "note.txt").write_text("v" + version, encoding="utf-8")

    def test_content_change_requires_version_bump(self):
        if shutil.which("git") is None:
            self.skipTest("需要 git")
        self.write_pack("1.0.0")
        self.git("init")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "user.name", "test")
        self.git("add", ".")
        self.git("commit", "-m", "v1")
        self.git("tag", "v0.1.0")
        self.write_pack("1.0.0")
        (self.root / "packs" / "demo" / "assets" / "demo" / "note.txt").write_text("changed", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-m", "change without bump")

        old = os.getcwd()
        os.chdir(self.root)
        try:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                packs.cmd_build(argparse.Namespace(root="packs", out="build/packs"))
                with self.assertRaises(SystemExit) as caught:
                    packs.cmd_plan(argparse.Namespace(
                        manifest="build/packs/manifest.json",
                        previous="v0.1.0",
                        force="",
                        out="build/release-plan.json",
                        ids_out="build/approved-packs.txt",
                        github_output="",
                        code_paths="src",
                    ))
            self.assertNotEqual(caught.exception.code, 0)

            self.write_pack("1.0.1")
            packs.cmd_build(argparse.Namespace(root="packs", out="build/packs"))
            packs.cmd_plan(argparse.Namespace(
                manifest="build/packs/manifest.json",
                previous="v0.1.0",
                force="",
                out="build/release-plan.json",
                ids_out="build/approved-packs.txt",
                github_output="",
                code_paths="src",
            ))
            plan = json.loads((self.root / "build" / "release-plan.json").read_text(encoding="utf-8"))
            self.assertEqual(plan["approved"], ["demo"])
            self.assertEqual(plan["changed_released"], ["demo"])
            self.assertTrue(plan["should_release"])
        finally:
            os.chdir(old)


    def init_repo(self, version="1.0.0", tag="v0.1.0"):
        self.write_pack(version)
        src = self.root / "src"
        src.mkdir(parents=True, exist_ok=True)
        (src / "Main.java").write_text("class Main {}\n", encoding="utf-8")
        self.git("init")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "user.name", "test")
        self.git("add", ".")
        self.git("commit", "-m", "v1")
        self.git("tag", tag)

    def build_and_plan(self, force="", code_paths="src"):
        old = os.getcwd()
        os.chdir(self.root)
        try:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                packs.cmd_build(argparse.Namespace(root="packs", out="build/packs"))
                packs.cmd_plan(
                    argparse.Namespace(
                        manifest="build/packs/manifest.json",
                        previous="v0.1.0",
                        force=force,
                        out="build/release-plan.json",
                        ids_out="build/approved-packs.txt",
                        github_output="",
                        code_paths=code_paths,
                    )
                )
        finally:
            os.chdir(old)
        report = self.root / "build" / "release-plan.json"
        return json.loads(report.read_text(encoding="utf-8"))

    def test_code_change_only_leaves_packs_changed_false(self):
        if shutil.which("git") is None:
            self.skipTest("需要 git")
        self.init_repo()
        (self.root / "src" / "Main.java").write_text("class Main { int answer = 42; }\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-m", "code only")
        plan = self.build_and_plan()
        self.assertTrue(plan["code_changed"])
        self.assertTrue(plan["should_release"])
        self.assertFalse(plan["packs_changed"])

    def test_force_sets_packs_changed(self):
        if shutil.which("git") is None:
            self.skipTest("需要 git")
        self.init_repo()
        plan = self.build_and_plan(force="demo")
        self.assertTrue(plan["packs_changed"])

    def test_changed_released_sets_packs_changed(self):
        if shutil.which("git") is None:
            self.skipTest("需要 git")
        self.init_repo()
        self.write_pack("1.0.1")
        note = self.root / "packs" / "demo" / "assets" / "demo" / "note.txt"
        note.write_text("v1.0.1 changed", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-m", "pack change")
        plan = self.build_and_plan()
        self.assertEqual(plan["changed_released"], ["demo"])
        self.assertTrue(plan["packs_changed"])

    def test_withdrawn_pack_triggers_index_refresh(self):
        if shutil.which("git") is None:
            self.skipTest("需要 git")
        self.init_repo()
        shutil.rmtree(self.root / "packs" / "demo")
        self.git("add", "-A")
        self.git("commit", "-m", "drop demo")
        plan = self.build_and_plan()
        self.assertEqual(plan["withdrawn"], ["demo"])
        self.assertTrue(plan["should_release"])
        self.assertTrue(plan["packs_changed"])

    def test_switching_release_to_manual_withdraws_from_index(self):
        if shutil.which("git") is None:
            self.skipTest("需要 git")
        self.init_repo()
        pack_yml = self.root / "packs" / "demo" / "pack.yml"
        pack_yml.write_text(
            pack_yml.read_text(encoding="utf-8").replace("release: auto", "release: manual"),
            encoding="utf-8",
        )
        self.git("add", ".")
        self.git("commit", "-m", "manual only")
        plan = self.build_and_plan()
        self.assertEqual(plan["withdrawn"], ["demo"])
        self.assertTrue(plan["should_release"])
        self.assertTrue(plan["packs_changed"])


if __name__ == "__main__":
    unittest.main()
