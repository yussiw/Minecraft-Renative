cmake_minimum_required(VERSION 3.22.1)
project(renative LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(android_native_app_glue STATIC
    ${CMAKE_ANDROID_NDK}/sources/android/native_app_glue/android_native_app_glue.c
)
target_include_directories(android_native_app_glue PUBLIC
    ${CMAKE_ANDROID_NDK}/sources/android/native_app_glue
)

add_library(renative SHARED
    main.cpp
    VulkanRenderer.cpp
)

target_include_directories(renative PRIVATE
    ${CMAKE_ANDROID_NDK}/sources/android/native_app_glue
)

find_library(android-lib android REQUIRED)
find_library(log-lib log REQUIRED)
find_library(vulkan-lib vulkan REQUIRED)

target_link_libraries(renative PRIVATE
    android_native_app_glue
    ${android-lib}
    ${log-lib}
    ${vulkan-lib}
)
