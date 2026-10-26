cmake_minimum_required(VERSION 3.22.1)
project(dcbridge C CXX)

set(CMAKE_C_STANDARD 99)

# -----------------------------------------------------------------------------
# libdivecomputer 源码定位（三级回退，仓库自包含 + 本地开发两不误）
#   1. -DLDC_ROOT=<path>           显式指定（CI / 特殊目录）
#   2. ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libdivecomputer
#      仓库内的 submodule（.gitmodules，指向 upstream，pin 到固定 commit）
#   3. 工作区同级的 libdivecomputer/  本机开发时已有的独立 clone
#
# 为什么用 submodule 而不是把 132 个 .c 拷进仓库：
#   第三方代码跟着 upstream 走，diff 里永远不出现它的噪声；构建靠 pin 的 commit 复现。
# -----------------------------------------------------------------------------
if(DEFINED LDC_ROOT AND EXISTS "${LDC_ROOT}/src/parser.c")
    set(LDC "${LDC_ROOT}")
else()
    # cpp -> main -> src -> app -> android -> dive
    get_filename_component(LDC_LOCAL "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libdivecomputer" ABSOLUTE)
    get_filename_component(LDC_SIBLING "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../libdivecomputer" ABSOLUTE)
    if(EXISTS "${LDC_LOCAL}/src/parser.c")
        set(LDC "${LDC_LOCAL}")
    else()
        set(LDC "${LDC_SIBLING}")
    endif()
endif()

message(STATUS "libdivecomputer dir = ${LDC}")

if(NOT EXISTS "${LDC}/src/parser.c")
    message(FATAL_ERROR
        "找不到 libdivecomputer 源码：${LDC}/src/parser.c\n"
        "修复方式（任选其一）：\n"
        "  git submodule update --init --recursive\n"
        "  或 -DLDC_ROOT=/path/to/libdivecomputer")
endif()

# 全部厂商解析器（parser.c 的 dc_parser_new_internal 会引用所有 *_parser_create）
file(GLOB PARSER_SOURCES "${LDC}/src/*_parser.c")
list(LENGTH PARSER_SOURCES N_PARSER)
message(STATUS "parser sources = ${N_PARSER}")

add_library(dcbridge SHARED
    dc_bridge.cpp
    stubs.c
    ${LDC}/src/context.c
    ${LDC}/src/array.c
    ${LDC}/src/buffer.c
    ${LDC}/src/checksum.c
    ${LDC}/src/common.c
    ${LDC}/src/datetime.c
    ${LDC}/src/parser.c
    ${PARSER_SOURCES}
)

target_include_directories(dcbridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}      # config.h
    ${LDC}/include                   # <libdivecomputer/*.h>
    ${LDC}/src                       # 内部头（deepsix_excursion.h 等）
)

# 源文件用 #ifdef HAVE_CONFIG_H -> #include "config.h"，config.h 在本目录（空）
target_compile_definitions(dcbridge PRIVATE HAVE_CONFIG_H=1)

find_library(log-lib log)
target_link_libraries(dcbridge ${log-lib})

# Android 15+（16 KB 页大小）要求原生库的 LOAD 段按 16 KB 对齐，
# 否则在 16 KB 页设备上无法加载，安装时也会弹「应用兼容性」警告。
# NDK r27 默认仍是 4 KB（0x1000），必须显式指定 0x4000。
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(dcbridge PRIVATE "-Wl,-z,max-page-size=16384")
endif()
