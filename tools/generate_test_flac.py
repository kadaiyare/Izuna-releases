"""Generate a tiny, original 12-second stereo FLAC fixture using constant subframes."""
import hashlib
import argparse
import struct
from pathlib import Path


def crc(data, width, polynomial):
    value = 0
    for byte in data:
        value ^= byte << (width - 8)
        for _ in range(8):
            value = (value << 1) ^ (polynomial if value & (1 << (width - 1)) else 0)
            value &= (1 << width) - 1
    return value


def generate(destination=None, rate=44100, bit_depth=16, title="Izuna Test FLAC"):
    block = 4096
    samples = rate * 12
    stream_info = struct.pack(">HH", samples % block, block) + bytes(6)
    stream_info += ((rate << 44) | (1 << 41) | ((bit_depth - 1) << 36) | samples).to_bytes(8, "big")
    stream_info += hashlib.md5(bytes(samples * 2 * (bit_depth // 8))).digest()
    comments = [f"TITLE={title}".encode("utf-8"), b"ARTIST=Izuna Tests", b"ALBUM=Integration Tests"]
    vendor = b"Izuna test fixture generator"
    vorbis = struct.pack("<I", len(vendor)) + vendor + struct.pack("<I", len(comments))
    for comment in comments:
        vorbis += struct.pack("<I", len(comment)) + comment
    result = b"fLaC" + b"\x00\x00\x00\x22" + stream_info
    result += b"\x84" + len(vorbis).to_bytes(3, "big") + vorbis
    for index, start in enumerate(range(0, samples, block)):
        count = min(block, samples - start)
        depth_code = {16: 4, 24: 6, 32: 7}[bit_depth]
        header = b"\xff\xf8\x70" + bytes([0x10 | (depth_code << 1)]) + chr(index).encode("utf-8") + struct.pack(">H", count - 1)
        frame = header + bytes([crc(header, 8, 0x07)]) + bytes(2 * (1 + bit_depth // 8))
        result += frame + struct.pack(">H", crc(frame, 16, 0x8005))
    destination = Path(destination) if destination else Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/silence.flac"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(result)
    print(f"Generated {destination.name}: {len(result)} bytes, 12s, {bit_depth}-bit/{rate} Hz stereo")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output")
    parser.add_argument("--sample-rate", type=int, choices=[44100, 48000, 88200, 96000, 176400, 192000], default=44100)
    parser.add_argument("--bit-depth", type=int, choices=[16, 24, 32], default=16)
    parser.add_argument("--title", default="Izuna Test FLAC")
    args = parser.parse_args()
    generate(args.output, args.sample_rate, args.bit_depth, args.title)
