"""Check the packaged fix without starting an Android device or emulator."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("--dexdump", required=True, type=Path)
args = parser.parse_args()
descriptor = "Lio/myreads/app/tts/OfficialText;"
matches = []
with zipfile.ZipFile(args.apk) as apk, tempfile.TemporaryDirectory(prefix="myreads-regex-") as directory:
    for name in apk.namelist():
        if not name.endswith(".dex"):
            continue
        data = apk.read(name)
        if descriptor.encode() not in data:
            continue
        dex = Path(directory) / "classes.dex"
        dex.write_bytes(data)
        # Stream the disassembly: dependencies may make a full dump very large.
        with subprocess.Popen([str(args.dexdump), "-d", str(dex)], stdout=subprocess.PIPE,
                              text=True, encoding="utf-8", errors="replace") as process:
            selected = None
            for line in process.stdout:
                if line.startswith("Class #"):
                    if selected is not None:
                        matches.append("".join(selected))
                    selected = None
                if "Class descriptor" in line and f"'{descriptor}'" in line:
                    selected = []
                if selected is not None:
                    selected.append(line)
            if selected is not None:
                matches.append("".join(selected))
            if process.wait() != 0:
                raise SystemExit("DEX disassembly failed")
if len(matches) != 1:
    raise SystemExit(f"Expected one packaged OfficialText definition, found {len(matches)}")
body = matches[0]
if "Pattern;.compile:(Ljava/lang/String;I)" in body:
    raise SystemExit("OfficialText still calls Pattern.compile(String, int); Android rejects Unicode flag 0x100")
if "Pattern;.compile:(Ljava/lang/String;)" not in body or "OfficialText.pattern:" not in body:
    raise SystemExit("The packaged Unicode regex fix is missing")
print(f"Verified packaged OfficialText uses flag-free regex compilation: {args.apk}")
