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

option(ENABLE_LOGGING "Enable logging with spdlog library" ON)

# 上游 fd21b63485 把 native logging 从 glog 迁到 spdlog；隔壁 bump 的
# librime f71eac9a 已移除 deps/glog submodule，旧 add_subdirectory 会报
# "source ... is not an existing directory"。spdlog 由 librime 拥有并静态
# 链进 rime-static；以下选项照上游设定。
if(ENABLE_LOGGING)
  set(SPDLOG_BUILD_SHARED OFF CACHE BOOL "" FORCE)
  set(SPDLOG_BUILD_PIC ON CACHE BOOL "" FORCE)
  set(SPDLOG_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
  set(SPDLOG_BUILD_TESTS OFF CACHE BOOL "" FORCE)
  set(SPDLOG_INSTALL OFF CACHE BOOL "" FORCE)
  set(SPDLOG_FMT_EXTERNAL OFF CACHE BOOL "" FORCE)
  add_subdirectory(librime/deps/spdlog EXCLUDE_FROM_ALL)
  target_compile_options(
    spdlog PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")
endif()

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
# OpenCC's data/ builds dictionaries by running opencc_dict, an Android
# executable that cannot run on the build host. Dictionaries are generated
# by a host build instead (see OpenCCDataPlugin), so exclude data/ here.
if(EXISTS "${CMAKE_CURRENT_BINARY_DIR}/OpenCC/data")
  set_property(
    DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/OpenCC/data"
    PROPERTY EXCLUDE_FROM_ALL TRUE)
endif()
include(OpenccWorkarounds)

set(X11Keysym "${CMAKE_SOURCE_DIR}/librime/include")
include_directories("${X11Keysym}")

include(Rime)

add_subdirectory(librime_jni)
