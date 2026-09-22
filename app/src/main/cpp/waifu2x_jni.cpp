#include "anime4k.h"
#include "waifu2x.h"
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <jni.h>
#include <mutex>
#include <vector>

#define TAG "Waifu2xJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static Waifu2x *g_waifu2x = nullptr;
static Anime4K *g_anime4k = nullptr;
static std::mutex g_lock;
static std::atomic<int> g_progress{0};
static std::atomic<int> g_current_id{-1};
static std::atomic<int> g_ui_busy{0};
static std::atomic<bool> g_abort_processing{false};

// SY --> Komiho Phase11: sudo UltraCompact（来自 TachiyomiX 官方 modelpack）。
//
// 取代原先的 Waifu2x(CUnet)。选它的原因：
//  - 是 3 通道 RGB 直通网络（含「最近邻 2x + 残差」的全局跳连），与本项目原本的
//    3 通道管线天然兼容，因此能走融合 Vulkan 路径，完全不碰 CPU 侧的 staged 路径；
//  - 结构只有 10 层 3x3 卷积 / 24 个 layer，而 CUnet 是 59 个 layer，且带
//    global pooling + InnerProduct + Crop + Eltwise 这类会打断 GPU 流水线的算子。
//    Vulkan 上每层都要一次 dispatch，层数几乎直接决定小 tile 的开销；
//  - 权重只有 1.19MB（CUnet 是 22MB）。
// 模型没有去噪档位，也不支持除 2x 以外的倍率。
extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitSudoUltra(
    JNIEnv *env, jobject thiz, jstring model_dir, jint precision,
    jboolean fp16_arithmetic) {
  g_abort_processing = true;
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false;

  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  std::string model_path = std::string(model_dir_str);

  std::string param_file = model_path + "/2x-sudo-UltraCompact.param";
  std::string bin_file = model_path + "/2x-sudo-UltraCompact.bin";

  g_waifu2x = new Waifu2x(0, false, 0, precision,
                          fp16_arithmetic == JNI_TRUE); // GPU 0
  // 与另两款模型保持一致：不做灰度检测、不强制灰度输出（保持既有输出语义）
  g_waifu2x->disable_grayscale_check = true;
  g_waifu2x->noise = 0;
  g_waifu2x->scale = 2; // PixelShuffle(2) + nearest 2x 跳连，结构固定 2x
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);
  // prepadding 不再手写：Waifu2x::load() 会按 param 推导感受野半径

  int ret = g_waifu2x->load(param_file, bin_file);
  LOGD("sudo UltraCompact init: param=%s ret=%d", param_file.c_str(), ret);

  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}
// SY <--

// SY --> mihon-webdav 双引擎版：Anime4K v4 ACNet（单通道 luma 超分模型）。
//
// 权重是 C:\Users\saber\Desktop\mox\anime4k_acnet.onnx（md5 0cb304fe2714abb02a9f9b4d34c2ae47）
// 经 tools 侧「手写 ncnn param/bin + onnxruntime 数值对齐」转换而来（anime4k_acnet.param/.bin，
// validate_acnet.py 比对 max|diff| = 1.2e-06 MATCH）。
// 结构：9 层 3x3 卷积（ReLU）+ 1 层 2x2/stride2 反卷积，输入 1 通道亮度、输出 1 通道 2 倍亮度；
// 彩色由 waifu2x.cpp 的 luma 分支用原图色度（BT.601 Cb/Cr）合成。
//
// 单通道模型也有**专属的融合 Vulkan 管线**：waifu2x_fused_{preproc,postproc}_luma.comp
// 负责 RGBA8 ↔ 单通道亮度的转换，所以 ACNet 和 3 通道模型一样**全程走 GPU**
// （见 waifu2x.cpp 的 luma_model 分支）。只有着色器创建失败 / 老驱动不支持时
// 才会退回 CPU staged 路径。
extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitAnime4kAcnet(
    JNIEnv *env, jobject thiz, jstring model_dir, jint precision,
    jboolean fp16_arithmetic) {
  g_abort_processing = true;
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false;

  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  std::string model_path = std::string(model_dir_str);

  std::string param_file = model_path + "/anime4k_acnet.param";
  std::string bin_file = model_path + "/anime4k_acnet.bin";

  g_waifu2x = new Waifu2x(0, false, 0, precision,
                          fp16_arithmetic == JNI_TRUE); // GPU 0
  g_waifu2x->luma_model = true;
  g_waifu2x->disable_grayscale_check = true;
  g_waifu2x->noise = 0;
  g_waifu2x->scale = 2; // 模型结构固定 2x（ConvTranspose stride 2）
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);
  // prepadding 不手写：Waifu2x::load() 按 param 推导感受野半径（9 层 3x3 → 9）

  int ret = g_waifu2x->load(param_file, bin_file);
  LOGD("Anime4K ACNet init: param=%s ret=%d luma=1", param_file.c_str(), ret);

  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}
// SY <--

// SY --> 原 nativeInitWaifu2xUpconv7。
//
// 改名成 Cunet 是因为 2026-09-22 起本工程内置的 waifu2x 权重由 UpConv7 换成 CUnet
// （可达阅读器的 waifu2x 支经考证最吻合 CUnet：强降噪、网点涂抹）。
//
// 两者来自同一个上游 nihui/waifu2x-ncnn-vulkan，**文件命名完全一致**
// （noise{0..3}_scale2.0x_model.param/.bin），所以这段路径拼接逻辑一字未改即可复用；
// 感受野差异由 Waifu2x::load() 的 derive_prepadding_from_param() 自动推导，
// 不需要为 CUnet 的更大 receptive field 做特调。
// SY <--
extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitWaifu2xCunet(
    JNIEnv *env, jobject thiz, jstring model_dir, jint noise_level,
    jint scale_level, jint precision, jboolean fp16_arithmetic) {
  g_abort_processing = true;
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false;

  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  std::string model_path = std::string(model_dir_str);

  // waifu2x 命名约定（UpConv7 与 CUnet 通用）：noise0_scale2.0x_model.param
  // noise: 0, 1, 2, 3
  // scale: 2 (only support 2x for now based on files seen)

  std::string param_file = model_path + "/noise" + std::to_string(noise_level) +
                           "_scale2.0x_model.param";
  std::string bin_file = model_path + "/noise" + std::to_string(noise_level) +
                         "_scale2.0x_model.bin";

  // Handle scale=2 only for now, or if scale=1 (denoise only)
  if (scale_level != 2) {
    // UpConv7 models I saw were all scale2.0x
    // If user selected 1x (denoise only), we might need to use a different
    // model or just fail/fallback For now assume 2x.
  }

  g_waifu2x = new Waifu2x(0, false, 0, precision,
                          fp16_arithmetic == JNI_TRUE); // GPU 0
  g_waifu2x->disable_grayscale_check = true;
  g_waifu2x->noise = noise_level;
  g_waifu2x->scale = scale_level;
  // SY --> Komiho Phase11: prepadding 交给 Waifu2x::load() 按 param 推导
  // （原先这里写死 7，是 6 层 3x3 卷积 + 4x4/stride2 反卷积的感受野半径）
  // SY <--
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);

  int ret = g_waifu2x->load(param_file, bin_file);

  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

// SY --> 新增：Real-CUGAN 2x Conservative（3 通道 RGB，来自 TachiyomiX modelpack）。
// 同样是 3 通道模型，固定 2x，走融合 Vulkan 路径，速度远快于单通道 ACNet。
extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitRealcugan(
    JNIEnv *env, jobject thiz, jstring model_dir, jint precision,
    jboolean fp16_arithmetic) {
  g_abort_processing = true;
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false;

  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  std::string model_path = std::string(model_dir_str);

  std::string param_file = model_path + "/up2x-conservative.param";
  std::string bin_file = model_path + "/up2x-conservative.bin";

  g_waifu2x = new Waifu2x(0, false, 0, precision,
                          fp16_arithmetic == JNI_TRUE); // GPU 0
  g_waifu2x->disable_grayscale_check = true;
  g_waifu2x->noise = 0;
  g_waifu2x->scale = 2;       // 模型结构固定 2x
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);

  int ret = g_waifu2x->load(param_file, bin_file);
  LOGD("Real-CUGAN init: param=%s ret=%d", param_file.c_str(), ret);

  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}
// SY <--

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeProcess(JNIEnv *env,
                                                            jobject thiz,
                                                            jobject bitmap,
                                                            jint id) {
  int ret = -1;
  jobject outBitmap = nullptr;

  // Inference Scope (GPU) - Holds Lock for entire duration of incremental
  // process
  {
    std::unique_lock<std::mutex> lock(g_lock);

    // Update ID only after acquiring lock (now we are truly the active process)
    g_current_id.store(id);

    if (!g_waifu2x)
      return bitmap;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0)
      return bitmap;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
      return bitmap;

    void *pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0)
      return bitmap;

    int w = info.width;
    int h = info.height;
    int stride = info.stride;

    // Keep a packed RGBA copy for the fused Vulkan upload. This also lets the
    // staged path reconstruct its planar input without keeping Bitmap locked.
    ncnn::Mat packed_input(w, h, (size_t)4u, 1);
    if (packed_input.empty()) {
      AndroidBitmap_unlockPixels(env, bitmap);
      return bitmap;
    }
    // SY --> Komiho Phase11: stride 与 w*4 相等时一次整块 memcpy 搞定。
    // 原来无条件按行拷贝，1600x2400 的页面就是 2400 次 memcpy；Bitmap 的行距
    // 在绝大多数情况下本来就等于 width*4，所以快路径基本总能命中。
    if (stride == w * 4) {
      memcpy(packed_input.data, pixels, (size_t)w * h * 4);
    } else {
      for (int y = 0; y < h; y++) {
        memcpy((unsigned char *)packed_input.data + (size_t)y * w * 4,
               (const unsigned char *)pixels + (size_t)y * stride,
               (size_t)w * 4);
      }
    }
    // SY <--
    AndroidBitmap_unlockPixels(env, bitmap);

    if (g_waifu2x) {
      int out_w = w * g_waifu2x->scale;
      int out_h = h * g_waifu2x->scale;

      // Create result bitmap
      jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
      jmethodID createBitmapMethod = env->GetStaticMethodID(
          bitmapClass, "createBitmap",
          "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");

      jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
      jfieldID configField = env->GetStaticFieldID(
          configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
      jobject config = env->GetStaticObjectField(configClass, configField);

      outBitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod,
                                              out_w, out_h, config);

      if (outBitmap) {
        void *outPixels;
        if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) == 0) {
          AndroidBitmapInfo outInfo{};
          AndroidBitmap_getInfo(env, outBitmap, &outInfo);

          g_waifu2x->progress_ptr = &g_progress;
          g_waifu2x->should_abort_ptr = &g_abort_processing;

          bool input_has_alpha =
              (info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) !=
              ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE;
          if (input_has_alpha) {
            input_has_alpha = false;
            const unsigned char *packed_pixels =
                static_cast<const unsigned char *>(packed_input.data);
            for (int i = 0; i < w * h; i++) {
              if (packed_pixels[i * 4 + 3] != 255) {
                input_has_alpha = true;
                break;
              }
            }
          }

          // SY --> 原先此处会在 QNN 初始化后优先走 NPU 推理。该后端随
          // Real-CUGAN / Real-ESRGAN / W2xEX / SPAN 一并移除，现在只走 Vulkan，
          // ret 保持初始值即可进入下面的 GPU 融合路径。
          if (ret != 0 && !g_abort_processing.load() &&
              g_waifu2x->has_gpu_pipeline()) {
            const auto fused_start = std::chrono::steady_clock::now();
            ret = g_waifu2x->process_gpu(packed_input, outPixels,
                                         outInfo.stride, input_has_alpha,
                                         &g_progress);
            const auto fused_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                      std::chrono::steady_clock::now() - fused_start)
                                      .count();
            LOGD("Fused Vulkan processing %s in %lld ms",
                 ret == 0 ? "completed" : "failed",
                 static_cast<long long>(fused_ms));
          }

          if (ret != 0 && !g_abort_processing.load()) {
            LOGD("Fused GPU pipeline unavailable for this image; retrying staged path");
            const auto staged_start = std::chrono::steady_clock::now();
            ncnn::Mat in = ncnn::Mat::from_pixels(
                (const unsigned char *)packed_input.data,
                ncnn::Mat::PIXEL_RGBA, w, h);
            ret = g_waifu2x->process(in, outPixels, outInfo.stride,
                                     input_has_alpha, lock, &g_progress);
            const auto staged_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                       std::chrono::steady_clock::now() - staged_start)
                                       .count();
            LOGD("Staged processing %s in %lld ms",
                 ret == 0 ? "completed" : "failed",
                 static_cast<long long>(staged_ms));
          }

          g_waifu2x->progress_ptr = nullptr;
          g_waifu2x->should_abort_ptr = nullptr;

          AndroidBitmap_unlockPixels(env, outBitmap);
        }
      }
    }
  }

  if (ret != 0 || !outBitmap) {
    LOGE("Waifu2x process failed or aborted");
    return bitmap; // Return original on failure
  }

  return outBitmap;
}

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeScaleBitmap(
    JNIEnv *env, jobject thiz, jobject bitmap, jint target_width,
    jint target_height) {
  if (!bitmap || target_width <= 0 || target_height <= 0) {
    return bitmap;
  }

  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
    return bitmap;
  }
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    return bitmap;
  }

  void *pixels = nullptr;
  if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
    return bitmap;
  }

  ncnn::Mat in =
      ncnn::Mat::from_pixels((const unsigned char *)pixels, ncnn::Mat::PIXEL_RGBA,
                             info.width, info.height, info.stride);
  AndroidBitmap_unlockPixels(env, bitmap);

  if (in.empty()) {
    return bitmap;
  }

  ncnn::Mat out;
  ncnn::resize_bicubic(in, out, target_width, target_height);
  if (out.empty()) {
    return bitmap;
  }

  jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
  jmethodID createBitmapMethod = env->GetStaticMethodID(
      bitmapClass, "createBitmap",
      "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");

  jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
  jfieldID configField = env->GetStaticFieldID(
      configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
  jobject config = env->GetStaticObjectField(configClass, configField);

  jobject outBitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod,
                                                  target_width, target_height,
                                                  config);
  if (!outBitmap) {
    return bitmap;
  }

  void *outPixels = nullptr;
  if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) < 0) {
    return bitmap;
  }

  AndroidBitmapInfo outInfo;
  AndroidBitmap_getInfo(env, outBitmap, &outInfo);
  out.to_pixels((unsigned char *)outPixels, ncnn::Mat::PIXEL_RGBA,
                outInfo.stride);
  AndroidBitmap_unlockPixels(env, outBitmap);

  return outBitmap;
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeDestroy(JNIEnv *env,
                                                            jobject thiz) {
  g_abort_processing.store(true);
  std::lock_guard<std::mutex> lock(g_lock);
  if (g_waifu2x) {
    delete g_waifu2x;
    g_waifu2x = nullptr;
  }
  if (g_anime4k) {
    delete g_anime4k;
    g_anime4k = nullptr;
  }
  g_progress.store(0);
  g_current_id.store(-1);
  g_abort_processing.store(false);
  // DO NOT call destroy_gpu_instance here. It should be global.
  // Repeatedly calling it on exit/init is slow and can cause hangs.
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeAbortProcessing(
    JNIEnv *env, jobject thiz) {
  g_abort_processing.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeClearAbortProcessing(
    JNIEnv *env, jobject thiz) {
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing.store(false);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitAnime4K(
    JNIEnv *env, jobject thiz, jobjectArray shaders, jobjectArray names) {
  std::lock_guard<std::mutex> lock(g_lock);
  if (g_anime4k)
    delete g_anime4k;
  g_anime4k = new Anime4K();

  std::vector<std::string> v_shaders;
  std::vector<std::string> v_names;
  jsize len = env->GetArrayLength(shaders);
  for (jsize i = 0; i < len; i++) {
    jstring s = (jstring)env->GetObjectArrayElement(shaders, i);
    jstring n = (jstring)env->GetObjectArrayElement(names, i);
    const char *cs = env->GetStringUTFChars(s, 0);
    const char *cn = env->GetStringUTFChars(n, 0);
    v_shaders.push_back(cs);
    v_names.push_back(cn);
    env->ReleaseStringUTFChars(s, cs);
    env->ReleaseStringUTFChars(n, cn);
  }

  int ret = g_anime4k->load(v_shaders, v_names);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeProcessAnime4K(
    JNIEnv *env, jobject thiz, jobject bitmap) {
  std::lock_guard<std::mutex> lock(g_lock);
  if (!g_anime4k)
    return bitmap;

  AndroidBitmapInfo info;
  AndroidBitmap_getInfo(env, bitmap, &info);

  int out_w, out_h;
  g_anime4k->get_output_size(info.width, info.height, out_w, out_h);

  // Create result bitmap
  jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
  jmethodID createBitmapMethod = env->GetStaticMethodID(
      bitmapClass, "createBitmap",
      "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
  jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
  jfieldID configField = env->GetStaticFieldID(
      configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
  jobject config = env->GetStaticObjectField(configClass, configField);
  jobject outBitmap = env->CallStaticObjectMethod(
      bitmapClass, createBitmapMethod, out_w, out_h, config);

  if (!outBitmap) {
    LOGE("Failed to create output bitmap for Anime4K");
    return bitmap;
  }

  void *pixels;
  AndroidBitmap_lockPixels(env, bitmap, &pixels);
  void *outPixels;
  AndroidBitmap_lockPixels(env, outBitmap, &outPixels);

  int actual_out_w, actual_out_h;
  g_anime4k->process(info.width, info.height, (unsigned char *)pixels,
                     actual_out_w, actual_out_h, (unsigned char *)outPixels);

  AndroidBitmap_unlockPixels(env, bitmap);
  AndroidBitmap_unlockPixels(env, outBitmap);

  return outBitmap;
}

// SY --> Anime4K(GLSL) 是**全图 FBO** 管线（不分 tile），输入尺寸直接受
// GL_MAX_TEXTURE_SIZE 限制。Kotlin 侧必须先拿这个上限做尺寸守卫，
// 超限就回退到 ncnn 模型；否则长条图（webtoon 常见，高度动辄上万）会直接失败。
extern "C" JNIEXPORT jint JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeGetAnime4KMaxTextureSize(
    JNIEnv *env, jobject thiz) {
  if (!g_anime4k)
    return 0;
  return (jint)g_anime4k->get_max_texture_size();
}
// SY <--
// SY --> 通用推理入口：waifu2x CUnet(7)、Anime4K ACNet(6) 两者共用这一段，
// 区别只在于各自的 nativeInit* 加载不同权重与是否走 luma 分支。
// Anime4K GLSL(8) **不走这里**：它有独立的执行器与 nativeProcessAnime4K。
// 原名 nativeProcessRealCugan，随 Real-CUGAN 一并改名。
extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeProcessUpscale(
    JNIEnv *env, jobject thiz, jobject bitmap, jint id) {
  return Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeProcess(
      env, thiz, bitmap, id);
}

extern "C" JNIEXPORT jlong JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeGetProgress(JNIEnv *env,
                                                                jobject thiz) {
  // Return packed long: [ID (32)] [Progress (32)]
  jlong id = (jlong)g_current_id.load();
  jlong progress = (jlong)g_progress.load();
  return (id << 32) | (progress & 0xFFFFFFFF);
}
extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeSetUiBusy(JNIEnv *env,
                                                              jobject thiz,
                                                              jboolean busy) {
  g_ui_busy.store(busy ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeUpdatePerformanceConfig(
    JNIEnv *env, jobject thiz, jint sleep_ms, jint tile_size) {
  std::lock_guard<std::mutex> lock(g_lock);
  if (g_waifu2x) {
    g_waifu2x->tile_sleep_ms = sleep_ms;
    // SY --> Komiho Phase11: 走 set_tilesize 这个唯一入口，由它按 GPU 显存预算
    // 收敛上限（低显存机型上用户手选的 256 会被压回，避免 OOM）。
    g_waifu2x->set_tilesize(tile_size);
    // SY <--
    LOGD("Updated performance config: sleep=%dms, tilesize=%d", sleep_ms,
         g_waifu2x->tilesize);
  }
}
