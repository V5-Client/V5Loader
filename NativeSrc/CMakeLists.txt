cmake_minimum_required(VERSION 3.13)
project(V5PathJNI LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

enable_testing()

find_package(JNI REQUIRED)

add_library(V5PathJNI SHARED
  src/jni_bridge.cpp
  src/world_state.cpp
  src/pathfinder.cpp
  src/etherwarp_search.cpp
  src/etherwarp_raymarch.cpp
  src/path_directional_scan.cpp
  src/path_simplifier.cpp
  src/path_annotations.cpp
  src/path_signature.cpp
)

target_include_directories(V5PathJNI PRIVATE ${JNI_INCLUDE_DIRS} src)
target_link_libraries(V5PathJNI PRIVATE ${JNI_LIBRARIES})

set_target_properties(V5PathJNI PROPERTIES
  OUTPUT_NAME "V5PathJNI"
  PREFIX ""
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
)

if (MSVC)
  target_compile_options(V5PathJNI PRIVATE /O2 /EHsc)
else()
  target_compile_options(V5PathJNI PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
endif()

add_executable(V5PathTests
  tests/etherwarp_angle_optimizer_tests.cpp
  src/world_state.cpp
  src/etherwarp_search.cpp
  src/etherwarp_raymarch.cpp
)

target_include_directories(V5PathTests PRIVATE src)
target_compile_definitions(V5PathTests PRIVATE V5PF_ENABLE_TEST_HOOKS=1)

if (MSVC)
  target_compile_options(V5PathTests PRIVATE /O2 /EHsc)
else()
  target_compile_options(V5PathTests PRIVATE -O2)
endif()

add_test(NAME V5PathTests COMMAND V5PathTests)
