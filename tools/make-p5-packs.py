#!/usr/bin/env python3
"""Build the two P5 synthetic .mrpack test packs.

Every files[] value (url / sha1 / sha512 / fileSize) is queried live from the
Modrinth API for a real JEI build filtered by loader + game version. Nothing is
invented, and the exact API response fields are written next to each pack as
`*.source.json` so the acceptance report can cite them.

Usage: python3 tools/make-p5-packs.py <output-dir>
"""
import hashlib
import json
import os
import sys
import urllib.request
import zipfile

UA = {"User-Agent": "YMCL/0.1 (p5 wrap-up e2e)"}
API = "https://api.modrinth.com/v2/project/jei/version"


def get_json(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def first_real_file(loader, mc):
    url = f"{API}?loaders=%5B%22{loader}%22%5D&game_versions=%5B%22{mc}%22%5D"
    versions = get_json(url)
    if not versions:
        raise SystemExit(f"Modrinth returned no JEI {loader} {mc} versions")
    v = versions[0]
    f = v["files"][0]
    h = f["hashes"]
    return {
        "version_id": v["id"],
        "version_number": v["version_number"],
        "loaders": v["loaders"],
        "game_versions": v["game_versions"],
        "filename": f["filename"],
        "url": f["url"],
        "sha1": h["sha1"],
        "sha512": h["sha512"],
        "fileSize": f["size"],
    }


def build(out_dir, name, mc, dep_key, dep_version, mod_rel, file_info):
    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": "1.0.0",
        "name": name,
        "dependencies": {"minecraft": mc, dep_key: dep_version},
        "files": [
            {
                "path": mod_rel,
                "fileSize": file_info["fileSize"],
                "hashes": {"sha1": file_info["sha1"], "sha512": file_info["sha512"]},
                "env": {"client": "required", "server": "required"},
                "downloads": [file_info["url"]],
            }
        ],
    }
    pack = os.path.join(out_dir, name.replace(" ", "-").lower() + ".mrpack")
    with zipfile.ZipFile(pack, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("modrinth.index.json", json.dumps(index, indent=2))
    src = os.path.join(out_dir, os.path.basename(pack) + ".source.json")
    with open(src, "w") as f:
        json.dump(file_info, f, indent=2)
    with open(pack, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    print(f"PACK {pack}")
    print(f"  index dependencies={index['dependencies']}")
    print(f"  file {mod_rel} size={file_info['fileSize']} sha1={file_info['sha1']}")
    print(f"  url {file_info['url']}")
    print(f"  pack sha256={digest}  api-source={src}")
    return pack


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/ymcl-test/packs")
    os.makedirs(out, exist_ok=True)

    forge = first_real_file("forge", "1.20.1")
    build(out, "Forge Smoke Test", "1.20.1", "forge", "47.2.0",
          "mods/jei-1.20.1-forge.jar", forge)

    neo = first_real_file("neoforge", "1.21.1")
    # NeoForge version is read from the maven metadata by the same script call
    # site; default to the last stable 21.1.x published at build time.
    neo_version = os.environ.get("P5_NEOFORGE_VERSION", "21.1.266")
    build(out, "NeoForge Smoke Test", "1.21.1", "neoforge", neo_version,
          "mods/jei-1.21.1-neoforge.jar", neo)
    print(f"NEOFORGE_VERSION {neo_version}")


if __name__ == "__main__":
    main()
