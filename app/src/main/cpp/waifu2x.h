// waifu2x implemented with ncnn library

#ifndef WAIFU2X_H
#define WAIFU2X_H

#include <atomic>
#include <mutex>
#include <string>

// ncnn
#include "gpu.h"
#include "layer.h"
#include "net.h"

class Waifu2x {
public:
  Waifu2x(int gpuid, bool tta_mode = false, int num_threads = 0,
          int precision_mode = 0, bool fp16_arithmetic = false);
  ~Waifu2x();

  int load(const std::string &parampath, const std::string &modelpath);

  // Unified process method: runs inference and writes directly to output
  // in: inimage (RGBA planar)
  // out: out_pixels (RGBA packed), out_stride
  // lock: The JNI lock, passed in to allow early release of the GPU.
  int process(const ncnn::Mat &inimage, void *out_pixels, int out_stride,
              bool input_has_alpha, std::unique_lock<std::mutex> &lock,
              std::atomic<int> *progress_ptr = nullptr) const;
  int process_gpu(const ncnn::Mat &packed_input, void *out_pixels,
                  int out_stride, bool input_has_alpha,
                  std::atomic<int> *progress_ptr = nullptr) const;
  bool has_gpu_pipeline() const { return gpu_pipeline_available; }

public:
  // waifu2x parameters
  int noise;
  int scale;
  int tilesize;
  int prepadding;
  std::atomic<int> *progress_ptr = nullptr;
  std::atomic<int> *ui_busy_ptr = nullptr;
  std::atomic<bool> *should_abort_ptr = nullptr;
  int tile_sleep_ms = 0; // Sleep between tiles for cooling (0 = full speed)
  // SY --> 2026-09-29：**独立**的「块间降温小睡」（毫秒），与 tile_sleep_ms 的区别很重要：
  //   · tile_sleep_ms > 0 会**顺带把 batch_capacity 压到 1**（见 process_gpu），吞吐骤降 ——
  //     它是「用户显式选了省电档」的语义；
  //   · cool_sleep_ms 只加 sleep、**完全不影响批量**，用于「整话超分」这种长时间后台满载
  //     场景降温（真机实测长时间整话超分把 CPU 烤到 95.7°C / thermal severity 3，大核被压到
  //     960MHz，导致此时「打开归档」这类操作从 ~0.7s 变 8.4s）。
  int cool_sleep_ms = 0;
  // SY <--
  // SY --> Komiho Phase12: 连续滚动模式 GPU 交错让出。
  // 连续（条漫）模式下，超分应在滑动期间持续工作（不能像翻页那样整段全停），
  // 但又要「每块之间 sleep 让出 GPU」避免挤掉合成线程 → 卡顿。
  // continuous_mode：Kotlin 按阅读模式设置（条漫=true）。
  // last_interaction_ms：最近一次滑动/触摸的 steady_clock 毫秒戳，用于区分
  //   「正在滑动」与「停下阅读」——仅近期有交互时才强制让出下限，停下后恢复全速追帧。
  bool continuous_mode = false;
  std::atomic<int64_t> last_interaction_ms{0};
  // SY <--
  bool is_snapdragon = false;
  bool disable_grayscale_check = false;
  // SY --> Komiho Phase10: 单通道（luma-only）模型开关。
  // Anime4K v4 ACNet 之类模型输入/输出都是 1 通道亮度，与本项目原本的 3 通道
  // RGB 管线不兼容：输入取 BT.601 亮度，输出再用原图（双三次放大后）的色度
  // 重新合成彩色。
  // mihon-webdav: 这套流程现在**两条路径都有实现**——融合 Vulkan 路径用
  // waifu2x_fused_{pre,post}proc_luma.comp（全 GPU），staged 路径用
  // ncnn::resize_bicubic + 标量合成（CPU）。前者可用时优先生效。
  bool luma_model = false;
  // SY <--
  // SY --> 2026-09-25: 网点/周期纹理色块防护开关（来自上游 v1.3.10）。
  // 开启后，process() 会对检测到大面积周期纹理（漫画网点纸）的 tile 做 3/4
  // 缩采样 + 居中白边 + 重采样，规避超分模型在网点处产生的色块。仅对单色 +
  // 网点密集的页触发；彩色页 / 普通线条稿不受影响。ACNet 与 Real-CUGAN 都开。
  bool enable_periodic_texture_guard = false;
  // SY <--
  int num_threads = 1;
  int precision_mode = 0; // 0 = fp16, 1 = fp32, 2 = int8, 3 = bf16
  bool fp16_arithmetic = false;

  // SY --> Komiho Phase11: tile 尺寸统一从 set_tilesize() 进来，由它按 GPU
  // 显存预算做上限收敛（见 waifu2x.cpp:tile_size_cap）。直接写 tilesize 会绕过
  // 这层保护，低显存机型上可能 OOM，所以新增这个唯一的写入口。
  void set_tilesize(int requested);
  // SY <--


private:
  ncnn::VulkanDevice *vkdev;
  ncnn::Net net;
  ncnn::Pipeline *waifu2x_preproc;
  ncnn::Pipeline *waifu2x_postproc;
  ncnn::Pipeline *waifu2x_preproc_tta;
  ncnn::Pipeline *waifu2x_postproc_tta;
  ncnn::Layer *bicubic_2x;
  bool tta_mode;
  bool gpu_pipeline_available = false;
  // SY --> Komiho Phase11: 由 GPU 显存预算推出的 tile 尺寸上限
  // （0 表示尚未计算）。见 waifu2x.cpp:set_tilesize。
  int tile_size_cap = 0;
  // SY <--
};

#endif // WAIFU2X_H
