#pragma once

#include <android/native_window.h>
#include <vulkan/vulkan.h>
#include <cstdint>
#include <vector>

class VulkanRenderer {
public:
    bool initialize(ANativeWindow* window);
    void shutdown();
    bool drawFrame();
    bool isInitialized() const { return initialized; }

private:
    bool createInstance();
    bool createSurface(ANativeWindow* window);
    bool selectPhysicalDevice();
    bool createDevice();
    bool createSwapchain();
    bool createRenderPass();
    bool createFramebuffers();
    bool createCommandPool();
    bool createCommandBuffers();
    bool createSyncObjects();
    void destroySwapchain();
    bool recreateSwapchain();
    void recordCommandBuffer(VkCommandBuffer commandBuffer, uint32_t imageIndex);

    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue graphicsQueue = VK_NULL_HANDLE;
    VkQueue presentQueue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;

    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat swapchainFormat = VK_FORMAT_UNDEFINED;
    VkExtent2D swapchainExtent{};
    std::vector<VkImage> swapchainImages;
    std::vector<VkImageView> imageViews;
    VkRenderPass renderPass = VK_NULL_HANDLE;
    std::vector<VkFramebuffer> framebuffers;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> commandBuffers;

    static constexpr uint32_t MAX_FRAMES_IN_FLIGHT = 2;
    VkSemaphore imageAvailable[MAX_FRAMES_IN_FLIGHT]{};
    VkSemaphore renderFinished[MAX_FRAMES_IN_FLIGHT]{};
    VkFence inFlight[MAX_FRAMES_IN_FLIGHT]{};
    std::vector<VkFence> imagesInFlight;
    uint32_t currentFrame = 0;
    bool initialized = false;
    bool recreateRequested = false;
};
