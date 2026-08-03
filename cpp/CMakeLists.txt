cmake_minimum_required(VERSION 3.22.1)

project(silence_argon2 C)

# PHC Argon2 release 20190702, commit 62358ba2123abd17fccf2a108a301d4b52c01a7c.
add_library(argon2 SHARED
    argon2_jni.c
    argon2/src/argon2.c
    argon2/src/core.c
    argon2/src/encoding.c
    argon2/src/ref.c
    argon2/src/thread.c
    argon2/src/blake2/blake2b.c)

target_include_directories(argon2 PRIVATE
    argon2/include
    argon2/src
    argon2/src/blake2)

target_compile_options(argon2 PRIVATE
    -O2
    -Wall
    -Wextra
    -Werror)

add_executable(argon2_kat
    argon2_kat.c
    argon2/src/argon2.c
    argon2/src/core.c
    argon2/src/encoding.c
    argon2/src/ref.c
    argon2/src/thread.c
    argon2/src/blake2/blake2b.c)

target_include_directories(argon2_kat PRIVATE
    argon2/include
    argon2/src
    argon2/src/blake2)

target_compile_options(argon2_kat PRIVATE
    -O2
    -Wall
    -Wextra
    -Werror)

add_executable(argon2_bench
    argon2_bench.c
    argon2/src/argon2.c
    argon2/src/core.c
    argon2/src/encoding.c
    argon2/src/ref.c
    argon2/src/thread.c
    argon2/src/blake2/blake2b.c)

target_include_directories(argon2_bench PRIVATE
    argon2/include
    argon2/src
    argon2/src/blake2)

target_compile_options(argon2_bench PRIVATE
    -O2
    -Wall
    -Wextra
    -Werror)