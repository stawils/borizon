#!/usr/bin/env python3
"""
Realign ELF LOAD segments to 16KB page size for Android 15+ compatibility.

Android 15+ devices with 16KB kernel page size (Pixel 9+) require that ELF
shared libraries have LOAD segment alignment (p_align) >= 16384 (0x4000).
Older libraries like SQLCipher 4.5.4 ship with 4KB-aligned (0x1000) segments.

This script modifies the ELF program header p_align field for PT_LOAD segments,
changing them from 4KB to 16KB alignment. This is safe because:
- Increasing alignment is always valid (4KB-aligned data is also 16KB-aligned)
- The actual data layout doesn't change, only the alignment promise
- The linker uses p_align for mmap() calls, which need to match kernel page size
"""

import struct
import sys
from pathlib import Path


def realign_elf_16kb(so_path: str) -> bool:
    path = Path(so_path)
    if not path.exists():
        print(f"  SKIP: {so_path} not found")
        return False

    data = bytearray(path.read_bytes())

    # Check ELF magic
    if data[:4] != b'\x7fELF':
        print(f"  SKIP: {so_path} is not an ELF file")
        return False

    is_64bit = data[4] == 2  # EI_CLASS: ELFCLASS64
    is_little_endian = data[5] == 1  # EI_DATA: ELFDATA2LSB

    endian = '<' if is_little_endian else '>'
    e_phoff: int
    e_phentsize: int
    e_phnum: int

    if is_64bit:
        # ELF64 header
        e_phoff = struct.unpack_from(endian + 'Q', data, 32)[0]
        e_phentsize = struct.unpack_from(endian + 'H', data, 54)[0]
        e_phnum = struct.unpack_from(endian + 'H', data, 56)[0]
        # Program header entry layout (ELF64):
        # p_type:   4 bytes at offset 0
        # p_flags:  4 bytes at offset 4
        # p_offset: 8 bytes at offset 8
        # p_vaddr:  8 bytes at offset 16
        # p_paddr:  8 bytes at offset 24
        # p_filesz: 8 bytes at offset 32
        # p_memsz:  8 bytes at offset 40
        # p_align:  8 bytes at offset 48
        ALIGN_OFFSET_IN_PHDR = 48
        ALIGN_FMT = endian + 'Q'
    else:
        # ELF32 header
        e_phoff = struct.unpack_from(endian + 'I', data, 28)[0]
        e_phentsize = struct.unpack_from(endian + 'H', data, 42)[0]
        e_phnum = struct.unpack_from(endian + 'H', data, 44)[0]
        # Program header entry layout (ELF32):
        # p_type:   4 bytes at offset 0
        # p_offset: 4 bytes at offset 4
        # p_vaddr:  4 bytes at offset 8
        # p_paddr:  4 bytes at offset 12
        # p_filesz: 4 bytes at offset 16
        # p_memsz:  4 bytes at offset 20
        # p_flags:  4 bytes at offset 24
        # p_align:  4 bytes at offset 28
        ALIGN_OFFSET_IN_PHDR = 28
        ALIGN_FMT = endian + 'I'

    PT_LOAD = 1
    PAGE_4KB = 0x1000
    PAGE_16KB = 0x4000

    modified = False
    for i in range(e_phnum):
        phdr_offset = e_phoff + i * e_phentsize
        p_type = struct.unpack_from(endian + 'I', data, phdr_offset)[0]

        if p_type != PT_LOAD:
            continue

        align_offset = phdr_offset + ALIGN_OFFSET_IN_PHDR
        p_align = struct.unpack_from(ALIGN_FMT, data, align_offset)[0]

        if p_align == PAGE_4KB:
            struct.pack_into(ALIGN_FMT, data, align_offset, PAGE_16KB)
            modified = True
            print(f"  FIXED: LOAD segment {i}: p_align 0x{PAGE_4KB:x} -> 0x{PAGE_16KB:x}")
        elif p_align < PAGE_16KB and p_align > 0:
            struct.pack_into(ALIGN_FMT, data, align_offset, PAGE_16KB)
            modified = True
            print(f"  FIXED: LOAD segment {i}: p_align 0x{p_align:x} -> 0x{PAGE_16KB:x}")

    if modified:
        path.write_bytes(data)
        print(f"  OK: {so_path} realigned for 16KB page size")
    else:
        print(f"  OK: {so_path} already compatible (no changes needed)")

    return modified


def main():
    if len(sys.argv) < 2:
        print("Usage: realign_elf_16kb.py <file.so> [file2.so ...]")
        print("  or:  realign_elf_16kb.py --scan <directory>")
        sys.exit(1)

    if sys.argv[1] == '--scan':
        directory = Path(sys.argv[2])
        so_files = list(directory.rglob('*.so'))
        if not so_files:
            print(f"No .so files found in {directory}")
            sys.exit(0)
        print(f"Scanning {len(so_files)} .so files in {directory}...")
        changed = 0
        for f in so_files:
            if realign_elf_16kb(str(f)):
                changed += 1
        print(f"\nDone: {changed}/{len(so_files)} files realigned")
    else:
        for f in sys.argv[1:]:
            realign_elf_16kb(f)


if __name__ == '__main__':
    main()
