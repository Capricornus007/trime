# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.18.0)

project(trime VERSION 3.0.0)

# for reproducible build
add_link_options("LINKER:--hash-style=both,--build-id=none")

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

# general options
option(BUILD_SHARED_LIBS "" OFF)
option(BUILD_TESTING "" OFF)

include(Boost)

option(WITH_GFLAGS "Use gflags" OFF)
option(WITH_GTEST "Use googletest" OFF)
# ⚠️ glog 的 WITH_UNWIND **不是布林**，是字串驅動 `none|unwind|libunwind`
#   （glog CMakeLists.txt:43 `set (WITH_UNWIND libunwind CACHE STRING …)`），而且只有值
#   正好等於 `none` 時它才會 `CMAKE_DISABLE_FIND_PACKAGE_Unwind ON`（同檔 51-53 行）。
#   原本這裡寫 `option(WITH_UNWIND … OFF)` ⇒ 快取值變成字串 "OFF"：既不等於 none、
#   也不是合法驅動，glog 照樣去找 Unwind。NDK r27 僥倖找不到，換到 **r30 就找到了**
#   `toolchains/llvm/prebuilt/linux-x86_64/lib/libunwind.so`（宿主那顆），於是被當成
#   glog 的 INTERFACE 依賴塞進 aarch64 連結：
#   `ld.lld: error: …/libunwind.so is incompatible with aarch64linux`
#   （t9-ime run 39722307748 實測，紅在 build_grammar／rime-octagram 那批可執行檔）。
#   Android 的 _Unwind_* 由 libc 提供，glog 關掉外部 unwinder 只是不出 crash stack trace，
#   不影響 rime 執行。FORCE 是要蓋掉本機 .cxx 可能殘留的舊快取值。（t9-ime 搬入）
set(WITH_UNWIND none CACHE STRING "glog 的 unwind 驅動（Android 一律 none）" FORCE)
add_subdirectory(librime/deps/glog)
target_compile_options(
  glog_internal PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

option(YAML_CPP_BUILD_TOOLS "" OFF)
option(YAML_CPP_BUILD_TESTS "" OFF)
option(YAML_CPP_INSTALL "" OFF)
add_subdirectory(librime/deps/yaml-cpp)

# enable compress for leveldb
option(SNAPPY_BUILD_TESTS "" OFF)
option(SNAPPY_BUILD_BENCHMARKS "" OFF)
option(SNAPPY_INSTALL "" OFF)
add_subdirectory(snappy)

set(HAVE_SNAPPY TRUE)
option(LEVELDB_BUILD_TESTS "" OFF)
option(LEVELDB_BUILD_BENCHMARKS "" OFF)
option(LEVELDB_INSTALL "" OFF)
add_subdirectory(librime/deps/leveldb)

add_subdirectory(librime/deps/marisa-trie)
target_compile_options(
  marisa PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

set(LIBMARISA marisa)
option(USE_SYSTEM_MARISA "" ON)
add_subdirectory(OpenCC)
include(OpenccWorkarounds)

set(X11Keysym "${CMAKE_SOURCE_DIR}/librime/include")
include_directories("${X11Keysym}")

include(Rime)

add_subdirectory(librime_jni)
