cmake_minimum_required(VERSION 3.22.1)

project("aether_dsp" LANGUAGES CXX)

add_library(
    aether_dsp
    SHARED
    aether_dsp.cpp
    jni_bridge.cpp
)

target_compile_features(aether_dsp PRIVATE cxx_std_17)
target_compile_options(aether_dsp PRIVATE -O3 -Wall -Wextra)

find_library(log-lib log)

target_link_libraries(
    aether_dsp
    ${log-lib}
)
