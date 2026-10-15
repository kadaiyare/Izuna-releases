"""Generate original silent and low-level audible FLACs for manual device checks.

Writes only to the ignored app/build/device-fixtures directory. The 8-second
output fixtures contain a 440 Hz tone at 3% amplitude for the first 3 seconds.
They check audible output, not perceptual quality or bit-perfect playback.
"""
import hashlib
import math
import struct
import zlib
from pathlib import Path

def table(width, polynomial):
    result = []
    for byte in range(256):
        value = byte << (width - 8)
        for _ in range(8):
            value = ((value << 1) ^ (polynomial if value & (1 << (width - 1)) else 0)) & ((1 << width) - 1)
        result.append(value)
    return result

C8, C16 = table(8, 0x07), table(16, 0x8005)
def crc(data, width, values):
    value = 0
    for byte in data:
        value = ((value << 8) ^ values[(value >> (width - 8)) ^ byte]) & ((1 << width) - 1)
    return value

def cover_png():
    # Original deterministic artwork for local UI validation, not a fetched album cover.
    size = 256
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    pixels = bytearray()
    for y in range(size):
        pixels.append(0)
        for x in range(size):
            radius = (x - 128) ** 2 + (y - 128) ** 2
            rgb = (20, 22, 30) if 36 ** 2 < radius < 94 ** 2 else (70 + x // 3, 80 + y // 3, 160)
            pixels.extend(rgb)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')

def fixture(name, title, rate, depth, seconds, tone=False, album='Device Validation', artwork=False, artist='Izuna Tests'):
    block, samples = 4096, rate * seconds
    pcm = bytearray()
    frames = bytearray()
    for index, start in enumerate(range(0, samples, block)):
        count = min(block, samples - start)
        header = b'\xff\xf8\x70' + bytes([0x10 | ({16:4, 24:6}[depth] << 1)]) + chr(index).encode('utf-8') + struct.pack('>H', count - 1)
        frame = bytearray(header + bytes([crc(header, 8, C8)]))
        if tone:
            values = []
            for i in range(start, start + count):
                t = i / rate
                # Low-level 440 Hz sound with short fades, followed by silence.
                fade = min(1.0, t / 0.05, max(0.0, (3.0 - t) / 0.05))
                values.append(round(((1 << (depth - 1)) - 1) * 0.03 * fade * math.sin(2 * math.pi * 440 * t)))
            for value in values:
                pcm.extend(value.to_bytes(depth // 8, 'little', signed=True) * 2)
            channel = b'\x02' + b''.join(value.to_bytes(depth // 8, 'big', signed=True) for value in values)
            frame.extend(channel * 2)
        else:
            pcm.extend(bytes(count * 2 * (depth // 8)))
            frame.extend(bytes(2 * (1 + depth // 8)))
        frames.extend(frame + struct.pack('>H', crc(frame, 16, C16)))
    info = struct.pack('>HH', samples % block or block, block) + bytes(6)
    info += ((rate << 44) | (1 << 41) | ((depth - 1) << 36) | samples).to_bytes(8, 'big')
    info += hashlib.md5(pcm).digest()
    vendor = b'Izuna physical validation'
    comments = [f'TITLE={title}'.encode(), f'ARTIST={artist}'.encode(), f'ALBUM={album}'.encode()]
    tags = struct.pack('<I', len(vendor)) + vendor + struct.pack('<I', len(comments))
    for comment in comments:
        tags += struct.pack('<I', len(comment)) + comment
    metadata = bytes([4 if artwork else 0x84]) + len(tags).to_bytes(3, 'big') + tags
    if artwork:
        image = cover_png()
        mime = b'image/png'
        picture = struct.pack('>II', 3, len(mime)) + mime + struct.pack('>I', 0)
        picture += struct.pack('>IIIII', 256, 256, 24, 0, len(image)) + image
        metadata += b'\x86' + len(picture).to_bytes(3, 'big') + picture
    output = b'fLaC\x00\x00\x00\x22' + info + metadata + frames
    destination = Path(__file__).resolve().parents[1] / 'app/build/device-fixtures'
    destination.mkdir(parents=True, exist_ok=True)
    (destination / name).write_bytes(output)
    print(name, len(output), rate, depth, seconds)

if __name__ == '__main__':
    fixture('izuna-device-a.flac', 'Izuna Device A', 44100, 16, 180)
    fixture('izuna-device-b.flac', 'Izuna Device B', 96000, 24, 180)
    fixture('izuna-output-16.flac', 'Izuna Output 16', 44100, 16, 8, True)
    fixture('izuna-output-24.flac', 'Izuna Output 24', 96000, 24, 8, True)
