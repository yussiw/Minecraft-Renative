# Minecraft Re:Native

Native Android prototype for a future Minecraft Java Edition 1.21.11-compatible client.

**Current milestone:** Android NativeActivity + Vulkan instance/device/surface/swapchain,
a clear-only render pass, frame synchronization, and GitHub Actions APK build.

This is not a playable Minecraft client yet. No Minecraft assets or proprietary source code
are included. The project is intended to grow through a clean implementation based on
documented formats, protocol specifications, and observable game behavior.

## Technology

- C++20
- Android NDK
- Vulkan
- CMake
- Gradle
- GitHub Actions
- ARM64 (`arm64-v8a`)

## Build

The GitHub Actions workflow builds a debug APK on pushes to `main` and via
**Actions → Android Vulkan Build → Run workflow**.

The generated artifact is named `ReNative-Android-ARM64`.

## Current limitations

- The renderer currently clears the swapchain to a solid color.
- No 3D camera, block meshes, chunk system, world loading, or Minecraft gameplay exists yet.
- Vulkan is required by the current manifest.
- The first target is ARM64 Android hardware with Vulkan support.

## Legal

Minecraft is a trademark of Mojang Studios/Microsoft. This independent project is not
affiliated with or endorsed by Mojang or Microsoft. Do not include proprietary Minecraft
code or assets without permission.
