
import json, os, zipfile, random
random.seed(7)
out = os.path.dirname(os.path.abspath(__file__))
def meta(z, name):
    z.writestr("pack.mcmeta", json.dumps({"pack": {"pack_format": 97, "min_format": 97, "max_format": 97, "description": name}}))
# W1: few packs, very large entries (270MB raw total, each entry <= 32MB to stay under the 64MB cap)
large = [("large-a", 120_000_000), ("large-b", 90_000_000), ("large-c", 60_000_000)]
for idx, (name, total) in enumerate(large):
    path = os.path.join(out, name + ".zip")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        meta(z, name)
        chunk = 15_000_000
        n = total // chunk
        for i in range(n):
            z.writestr(f"assets/big{idx}/textures/blob/b{i:03d}.bin", os.urandom(chunk))
        for i in range(150):
            z.writestr(f"assets/big{idx}/models/item/m{i:03d}.json", json.dumps({"parent": "item/generated", "n": i, "pack": name}))
    print(name, os.path.getsize(path))
# W2: many packs, many entries, heavy overlap
total_packs = 40
for idx in range(total_packs):
    path = os.path.join(out, f"many-{idx:02d}.zip")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        meta(z, f"many-{idx}")
        for i in range(300):
            shared = i < 180
            ns = "shared" if shared else f"m{idx}"
            z.writestr(f"assets/{ns}/models/item/i{i:04d}.json", json.dumps({"parent": "item/generated", "textures": {"layer0": f"x/{i%50}"}, "idx": idx}))
        for i in range(4):
            shared = i < 2
            ns = "shared" if shared else f"m{idx}"
            z.writestr(f"assets/{ns}/textures/item/t{i}.png", os.urandom(512 * 1024))
    if idx % 10 == 0: print("many", idx, os.path.getsize(path))
print("large_total", sum(os.path.getsize(os.path.join(out, n + ".zip")) for n, _ in large))
print("many_total", sum(os.path.getsize(os.path.join(out, f"many-{i:02d}.zip")) for i in range(total_packs)))
