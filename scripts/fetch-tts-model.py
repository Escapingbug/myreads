"""Fetch the pinned model for host verification; never installs an Android app."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("destination", type=Path)
args = parser.parse_args()
catalog = json.loads((Path(__file__).resolve().parent.parent /
    "android/app/src/main/assets/tts/catalog.json").read_text())
root = args.destination.resolve()
root.mkdir(parents=True, exist_ok=True)


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while data := stream.read(1024 * 1024):
            digest.update(data)
    return digest.hexdigest()


def fetch(entry):
    target = (root / entry["path"]).resolve()
    if not target.is_relative_to(root):
        raise ValueError("Model path escapes destination")
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file() and target.stat().st_size == entry["size"]:
        if file_hash(target) == entry["sha256"]:
            return
    partial = target.with_suffix(target.suffix + ".part")
    digest = hashlib.sha256()
    try:
        source = ((Path(__file__).resolve().parent.parent / "android/app/src/main/assets" / entry["asset"]).open("rb")
                  if "asset" in entry else urllib.request.urlopen(entry["url"], timeout=120))
        with source as response, partial.open("wb") as output:
            while data := response.read(1024 * 1024):
                digest.update(data)
                output.write(data)
        if partial.stat().st_size != entry["size"] or digest.hexdigest() != entry["sha256"]:
            raise ValueError("Model checksum mismatch: " + entry["path"])
        partial.replace(target)
        print("Verified " + entry["path"], flush=True)
    finally:
        partial.unlink(missing_ok=True)


with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(fetch, catalog["files"]))
(root / ".ready").write_text(catalog["id"])
