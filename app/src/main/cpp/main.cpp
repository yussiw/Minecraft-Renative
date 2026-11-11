#include "VulkanRenderer.h"

#include <android/log.h>
#include <android_native_app_glue.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ReNative", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ReNative", __VA_ARGS__)

struct AppState {
    VulkanRenderer renderer;
};

static void handleAppCommand(android_app* app, int32_t command) {
    auto* state = static_cast<AppState*>(app->userData);
    if (!state) return;

    switch (command) {
        case APP_CMD_INIT_WINDOW:
            if (app->window && !state->renderer.isInitialized()) {
                if (!state->renderer.initialize(app->window)) {
                    LOGE("Vulkan initialization failed.");
                } else {
                    LOGI("Vulkan renderer initialized.");
                }
            }
            break;

        case APP_CMD_TERM_WINDOW:
            state->renderer.shutdown();
            break;

        case APP_CMD_WINDOW_RESIZED:
        case APP_CMD_CONFIG_CHANGED:
            // Swapchain recreation is handled when acquisition reports an outdated surface.
            break;

        default:
            break;
    }
}

void android_main(android_app* app) {
    app_dummy();

    AppState state;
    app->userData = &state;
    app->onAppCmd = handleAppCommand;

    while (!app->destroyRequested) {
        int events = 0;
        android_poll_source* source = nullptr;

        while (ALooper_pollOnce(state.renderer.isInitialized() ? 0 : -1,
                                nullptr, &events,
                                reinterpret_cast<void**>(&source)) >= 0) {
            if (source) source->process(app, source);
            if (app->destroyRequested) break;
        }

        if (state.renderer.isInitialized()) {
            if (!state.renderer.drawFrame()) {
                LOGE("A frame could not be rendered.");
            }
        }
    }

    state.renderer.shutdown();
}
