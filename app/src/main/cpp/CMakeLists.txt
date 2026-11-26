cmake_minimum_required(VERSION 3.22.1)
project("familink_bridge")

add_library(familink_bridge SHARED native_core_bridge.cpp)
find_library(log-lib log)
target_link_libraries(familink_bridge ${log-lib} dl)
