include(FetchContent)
# Build the same CPU runtime for Android ARM64, x86_64 and host verification.
# Immutable source archives and SHA-256 are checked by CMake before extraction.
set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_CONVERTER OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DEMO OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)
set(MNN_OPENCL OFF CACHE BOOL "" FORCE)
set(MNN_VULKAN OFF CACHE BOOL "" FORCE)
set(MNN_METAL OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "" FORCE)
FetchContent_Declare(bert_mnn
    URL https://codeload.github.com/alibaba/MNN/tar.gz/refs/tags/3.6.2
    URL_HASH SHA256=3470ee82104c3c2b570ef581030e29956bb38f972c5f6cef51805ba8f41a40e5)
FetchContent_MakeAvailable(bert_mnn)
FetchContent_Declare(bert_jieba
    URL https://codeload.github.com/yanyiwu/cppjieba/tar.gz/340de007f96d2a476fa1b2b525c2b1e4fea4b4a7
    URL_HASH SHA256=60813ebc0065fcc3119e8086e7bdf7ef78a3b619031ed4f87d783c977565d150)
FetchContent_GetProperties(bert_jieba)
if(NOT bert_jieba_POPULATED)
    FetchContent_Populate(bert_jieba)
endif()
FetchContent_Declare(bert_limonp
    URL https://codeload.github.com/yanyiwu/limonp/tar.gz/5c82a3f17e4e0adc6a5decfe245054b0ed533d1a
    URL_HASH SHA256=6d5450260f6fbe6c22af7be519627b9606d0d73ef67c07f95df7f27aa43b94cd)
FetchContent_GetProperties(bert_limonp)
if(NOT bert_limonp_POPULATED)
    FetchContent_Populate(bert_limonp)
endif()
add_library(zijian_bertvits SHARED bert_vits2_v23_loader.cpp bert_jni.cpp jieba_jni.cpp)
target_include_directories(zijian_bertvits PRIVATE ${bert_mnn_SOURCE_DIR}/include
    ${bert_jieba_SOURCE_DIR}/include ${bert_limonp_SOURCE_DIR}/include)
target_link_libraries(zijian_bertvits MNN)
if(ANDROID)
    target_link_libraries(zijian_bertvits log)
    target_link_options(zijian_bertvits PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    target_include_directories(zijian_bertvits PRIVATE ${JNI_INCLUDE_DIRS})
endif()
