cmake_minimum_required(VERSION 3.18.1)
project(genui-jni C)

# ═══════════════════════════════════════════════════════════════
# pybridge — CPython 3.14 嵌入桥（libquropybridge.so）
# 运行时 dlopen libpython3.14.so（jniLibs/arm64-v8a），链接期不依赖 Python。
# 16KB 页大小合规（Android 15+ 设备必需）。
# ═══════════════════════════════════════════════════════════════
add_library(quropybridge SHARED
    pybridge.c
)

target_compile_options(quropybridge PRIVATE
    -std=c11
    -O2
    -Wall
    -Wextra
)

target_link_options(quropybridge PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(quropybridge log dl)
