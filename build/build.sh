#!/usr/bin/env bash
# 用 MinGW gcc 编一个「只含解析能力」的 libdivecomputer 工具。
# 不需要 autoconf/automake/libtool/libusb/bluez/hidapi。
#
#   bash build.sh          # 产出 parse_cr4.exe
#   ./parse_cr4.exe ../raw/*.bin
#
set -e
cd "$(dirname "$0")"

# libdivecomputer 是仓库根的 submodule；也允许用 LDC=... 覆盖成别处的 checkout
LDC=${LDC:-$(cd .. && pwd)/third_party/libdivecomputer}
if [ ! -d "$LDC/src" ]; then
  echo "找不到 libdivecomputer 源码: $LDC" >&2
  echo "如果是新 clone：git submodule update --init --recursive" >&2
  exit 1
fi

# MinGW gcc：优先 $CC，其次本机装过的位置，最后 PATH 里的 gcc
if [ -z "${CC:-}" ]; then
  if [ -x /d/envs/MinGW/bin/gcc.exe ]; then CC=/d/envs/MinGW/bin/gcc.exe; else CC=gcc; fi
fi
CFLAGS="-std=gnu99 -O1 -DHAVE_CONFIG_H -I. -I$LDC/include -I$LDC/src"

# 手写一个空 config.h：所有 HAVE_* 都是可选的，缺省即关闭全部外部后端。
[ -f config.h ] || printf '/* minimal config.h */\n' > config.h

# 基础 + 全部解析器（parser.c 里 dc_parser_new_internal 会引用所有 *_parser_create，
# 所以要么全编，要么自己写桩；全编最简单）。
for f in context array buffer checksum common datetime parser; do
  $CC $CFLAGS -c "$LDC/src/$f.c" -o "$f.o"
done
for f in "$LDC"/src/*_parser.c; do
  b=$(basename "$f" .c)
  $CC $CFLAGS -c "$f" -o "$b.o"
done

# 桩：只被其它厂牌/设备层用到，deepsix 路径不会执行
$CC -std=gnu99 -O1 -c stubs.c -o stubs.o
$CC $CFLAGS -c parse_cr4.c -o parse_cr4.o

$CC parse_cr4.o context.o array.o buffer.o checksum.o common.o datetime.o \
    parser.o *_parser.o stubs.o -o parse_cr4.exe

echo "OK -> $(pwd)/parse_cr4.exe"
