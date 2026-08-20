cmake_minimum_required(VERSION 3.22.1)
project(umlcore LANGUAGES CXX)

add_library(umlcore SHARED
        native-lib.cpp)
target_compile_features(umlcore PRIVATE cxx_std_20)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(umlcore PRIVATE ${log-lib} ${dl-lib})
