cmake_minimum_required(VERSION 3.22.1)
project(chessai_engine CXX)

# Stockfish 18, the very sources the iOS EngineKit package builds, plus the same stdio bridge.
set(SF_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../Packages/EngineKit/Sources/StockfishCpp)
file(GLOB SF_SOURCES ${SF_ROOT}/stockfish/*.cpp ${SF_ROOT}/stockfish/nnue/*.cpp
     ${SF_ROOT}/stockfish/nnue/features/*.cpp ${SF_ROOT}/stockfish/syzygy/*.cpp)
list(FILTER SF_SOURCES EXCLUDE REGEX ".*/stockfish/main\\.cpp$")

add_library(chessai_engine SHARED ${SF_SOURCES} ${SF_ROOT}/CMStockfishBridge.cpp engine_jni.cpp)
target_include_directories(chessai_engine PRIVATE ${SF_ROOT} ${SF_ROOT}/include)
set_target_properties(chessai_engine PROPERTIES CXX_STANDARD 17 CXX_EXTENSIONS ON)
target_compile_definitions(chessai_engine PRIVATE NNUE_EMBEDDING_OFF NDEBUG IS_64BIT USE_POPCNT)
# Engine speed matters even in debug app builds.
target_compile_options(chessai_engine PRIVATE -O3 -fno-exceptions -w)
if(ANDROID_ABI STREQUAL "arm64-v8a")
  # Plain ARMv8 NEON: unlike iPhones, many Android phones lack the dot-product extension.
  target_compile_definitions(chessai_engine PRIVATE USE_NEON=8)
elseif(ANDROID_ABI STREQUAL "x86_64")
  # The Android x86_64 ABI guarantees SSE4.2 and POPCNT.
  target_compile_definitions(chessai_engine PRIVATE USE_SSE2 USE_SSSE3 USE_SSE41)
  target_compile_options(chessai_engine PRIVATE -msse4.2 -mpopcnt)
endif()
target_link_libraries(chessai_engine log)
