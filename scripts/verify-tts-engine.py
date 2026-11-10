"""Check the production backend and 16KiB ELF alignment without running Android."""
import argparse
import struct
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("apk")
args = parser.parse_args()
with zipfile.ZipFile(args.apk) as apk:
    dex = b"".join(apk.read(n) for n in apk.namelist() if n.endswith(".dex"))
    for name in (b"Lio/myreads/app/tts/BertVitsEngine;", b"Lio/myreads/app/tts/BertFrontend;"):
        if name not in dex:
            raise SystemExit("Missing production speech class: " + name.decode())
    for name in (b"Lio/myreads/app/tts/MossOnnxDemoEngine;", b"Lai/onnxruntime/OrtEnvironment;"):
        if name in dex:
            raise SystemExit("Retired backend still packaged: " + name.decode())
    if any("onnxruntime" in n for n in apk.namelist()):
        raise SystemExit("Retired ONNX Runtime native library is still packaged")
    if any(n.startswith("assets/tts/normalizer/") or n.endswith("silero_vad_16k_op15.onnx") for n in apk.namelist()):
        raise SystemExit("Retired model resources are still packaged")
    abis = set()
    for name in apk.namelist():
        if not name.startswith("lib/") or not name.endswith(".so"):
            continue
        data = apk.read(name)
        if data[:5] != b"\x7fELF\x02" or data[5] != 1:
            raise SystemExit("Expected little-endian ELF64: " + name)
        phoff = struct.unpack_from("<Q", data, 32)[0]
        entsize, count = struct.unpack_from("<HH", data, 54)
        for i in range(count):
            kind, _, offset, address, _, _, _, alignment = struct.unpack_from("<IIQQQQQQ", data, phoff + i * entsize)
            if kind == 1 and (alignment < 16384 or offset % 16384 != address % 16384):
                raise SystemExit("ELF load segment is not 16KiB-aligned: " + name)
        if name.endswith("/libzijian_bertvits.so"):
            abis.add(name.split("/")[1])
    if abis != {"arm64-v8a", "x86_64"}:
        raise SystemExit("Missing speech ABI: " + str(abis))
print("Verified Bert-VITS2 backend, retired backend exclusion, dual ABI and ELF 16KiB alignment")
