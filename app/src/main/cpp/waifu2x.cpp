#include "waifu2x.h"
#include "periodic_texture_guard.h"
#include "shaders.h"
#include "command.h"
#include "cpu.h"
#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <functional>
#include <queue>
#include <sstream>
#include <sys/resource.h>
#include <sys/system_properties.h>
#include <thread>
#include <vector>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

#define TAG "Waifu2xNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// SY --> Komiho Phase12: 连续滚动模式 GPU 交错让出的参数。
// kContinuousYieldMs：连续滑动时，若用户没显式选性能档（tile_sleep_ms==0），
//   每块之间强制让出 GPU 的下限（毫秒）。把一部分 GPU 时间留给合成线程，避免超分
//   霸占队列导致滚动卡顿 / 长时间冻结；同时超分仍在持续工作（边滑边超分）。
// kContinuousRecentMs：距上次交互超过此时长（毫秒）即视为「已停下阅读」，不再强制
//   让出、恢复全速，让超分快速追帧。800ms 足以盖住手指抬起后的惯性滑动尾巴。
namespace {
constexpr int kContinuousYieldMs = 10;
constexpr int64_t kContinuousRecentMs = 800;

// SY --> 2026-09-23 方案D：滚动期让出量改为运行时可调（一次编译即可扫参数定阈值）。
//   真机实测：单次 fused batch 的 submit_and_wait 阻塞 40~163ms，而原来只让出 10ms
//   ⇒ GPU 占空比 ~88%，UI 帧被饿死到驱动 watchdog（4950ms）。所以要能按负载比例让出。
//   取值来源（系统属性，非法值/不存在则回落编译期默认）：
//     debug.waifu2x.scroll_yield_pct = N  → 让出 = 上次 submit 耗时 × N%
//                                          （N=100 即 GPU 占空比 50%；0=不用此模式）
//     debug.waifu2x.scroll_yield_ms  = M  → 固定让出 M 毫秒（pct=0 时生效；0=不让出）
//     debug.waifu2x.scroll_batch1    = 1  → 滚动期强制 batch=1，缩短单次 submit 阻塞
int scroll_yield_prop(const char *key, int fallback) {
  char value[PROP_VALUE_MAX + 1] = {0};
  const int len = __system_property_get(key, value);
  if (len <= 0) return fallback;
  const int parsed = atoi(value);
  if (parsed < 0 || parsed > 1000) return fallback;
  return parsed;
}

// 返回本次 batch 之后应让出的毫秒数（0 = 不让出）。
// last_submit_us 为上一次 submit_and_wait 的实测阻塞耗时；按比例模式下用它做基准。
int scroll_yield_ms_for(int64_t last_submit_us) {
  const int pct = scroll_yield_prop("debug.waifu2x.scroll_yield_pct", 0);
  const int fixed =
      scroll_yield_prop("debug.waifu2x.scroll_yield_ms", kContinuousYieldMs);
  int effective = fixed;
  if (pct > 0 && last_submit_us > 0) {
    const int scaled = static_cast<int>((last_submit_us / 1000) * pct / 100);
    effective = scaled > 0 ? scaled : 1;
  }
  // 只在参数变化时打一行，便于真机确认属性是否生效。
  static int logged_pct = -2;
  static int logged_fixed = -2;
  if (pct != logged_pct || fixed != logged_fixed) {
    logged_pct = pct;
    logged_fixed = fixed;
    LOGD("Scroll yield: pct=%d fixed=%dms -> effective=%dms (last_submit=%lldus)",
         pct, fixed, effective, static_cast<long long>(last_submit_us));
  }
  return effective;
}

bool scroll_force_batch1() {
  return scroll_yield_prop("debug.waifu2x.scroll_batch1", 0) == 1;
}

// SY --> 2026-09-23 诊断：与 scroll_yield_prop 的区别是允许 -1 这个哨兵值
//   （-1 = 跟随用户/编译期设置；0 / 正数 = 强制取值）。用于精度与 ncnn opt 的
//   真机 A/B —— 这些开关原本只能靠改 Kotlin 设置触发重载，实测改完不保证重载。
//   取值来源（系统属性，缺省/非法则回落 fallback）：
//     debug.waifu2x.arm_precision       = 0 允许 ARM 走 FP16 融合（默认 1 = 强制 FP32）
//     debug.waifu2x.fp16_arith          = 0/1 覆盖「FP16 运算」（默认 -1 = 跟随设置）
//     debug.waifu2x.packing_layout      = 0/1 覆盖 use_packing_layout（默认 -1 = 开）
//     debug.waifu2x.shader_local_memory = 0/1 覆盖 use_shader_local_memory（默认 -1 = 开）
int waifu2x_prop(const char *key, int fallback) {
  char value[PROP_VALUE_MAX + 1] = {0};
  const int len = __system_property_get(key, value);
  if (len <= 0) return fallback;
  const int parsed = atoi(value);
  if (parsed < -1 || parsed > 1000) return fallback;
  return parsed;
}
// SY <--

// SY --> 2026-09-23 方案A（**出厂默认**）：把「连续模式下的近期交互」也当作全停让路。
//
//   【为什么默认开 · 真机实测结论】天玑9400 / Immortalis-G925 上，楔死帧数 ∝ 超分
//   GPU **提交次数**，与单次提交耗时无关 —— 只要滚动期间仍在提交，就有 ~48~52 帧
//   被卡到驱动 watchdog 上限（4950ms）。把让出量一路放大到 1000ms（GPU 占空比约 5%）
//   仍残留 11~13% 掉帧，且单页耗时从 3.2s 劣化到 ~70s（慢 22 倍）；而**同一支二进制**
//   段间交替切换本开关时，全停把掉帧压到 0.2~0.4%、4950ms 冻结帧从 48/52 降到 4/10。
//   ⇒ 方案 D（边滑边超分）没有任何可用工作点，A 严格占优。实测数据见
//     output\方案D实测-天玑9400-20260923.md。
//
//   语义：滑动 / 触摸期间超分**完全暂停**（与 ui_busy 同语义，「交错让出」不再生效）；
//   手指停下超过 kContinuousRecentMs 后立即恢复全速追帧。
//   ⚠️ 「滑动中看不到超分、停下才继续」是本方案的**预期取舍**，不是 bug ——
//   它与「滚动不卡」在这台机器上物理不可兼得。
//
//   回退到 D 行为（**仅供对照实验**，非交付配置）：
//     adb shell setprop debug.waifu2x.ui_stop_ms 0
//
//   之所以做成原生属性而非 Kotlin 侧 Settings.Global：该 ROM 上 Kotlin 读
//   Settings.Global 实测无效（设置后冷却仍是 350ms），而原生
//   __system_property_get 已验证可用。

// SY --> 交互窗口可调（2026-09-23）：A 模式下「手指停下多久后恢复超分」。
//   默认 kContinuousRecentMs = 800ms（已验证：掉帧 0.41%、楔死 12 帧）。
//   缩短到 300~400ms 能让「停手立刻出图」的体感更接近「超分一直在跑」——
//   这是在不牺牲「滚动中不提交」的前提下，对 A 体验损失最直接的补偿。
//   但窗口太短会让慢速拖拽时频繁「暂停-恢复」，反而可能掉帧，故做成属性便于扫参数。
//   注意：Kotlin 侧抬起时还会额外 setUiBusy(true) 350ms，故实际恢复时间
//   取两者较大值；把本值设得比 350 更小不会更早恢复。
//     debug.waifu2x.ui_recent_ms = N   (0~10000，非法/缺省回落 800)
int64_t recent_interaction_window_ms() {
  char value[PROP_VALUE_MAX + 1] = {0};
  const int len = __system_property_get("debug.waifu2x.ui_recent_ms", value);
  if (len <= 0) return kContinuousRecentMs;
  const int parsed = atoi(value);
  if (parsed < 0 || parsed > 10000) return kContinuousRecentMs;
  return static_cast<int64_t>(parsed);
}

// SY --> 2026-09-24 用户要求「滑动时继续进行超分」：**交付默认改为不停**（ui_stop_ms=0）。
//   interaction_full_stop() 从而恒为 false ⇒ 条漫滚动全程持续提交，滑动中就能看到超分。
//   代价：滚动期掉帧会回涨（这正是当初选 A 全停的原因，实测见
//   output\方案D实测-天玑9400-20260923.md）。
//   回退到 A 全停（仅供对照 / 用户反悔）：
//     adb shell setprop debug.waifu2x.ui_stop_ms 1
bool interaction_full_stop(bool continuous, int64_t last_interaction_ms) {
  const int stop_ms = scroll_yield_prop("debug.waifu2x.ui_stop_ms", 0);
  if (stop_ms <= 0 || !continuous) return false;
  const int64_t now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
      std::chrono::steady_clock::now().time_since_epoch()).count();
  return (now_ms - last_interaction_ms) < recent_interaction_window_ms();
}
// SY <--
} // namespace
// SY <--
// SY --> Komiho Phase9: 兼容性降级提示用
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
inline uint8x8_t floats_to_u8(float32x4_t low, float32x4_t high) {
  const float32x4_t zero = vdupq_n_f32(0.f);
  const float32x4_t max_value = vdupq_n_f32(255.f);
  low = vmaxq_f32(zero, vminq_f32(max_value, low));
  high = vmaxq_f32(zero, vminq_f32(max_value, high));
  const uint16x4_t low_u16 = vmovn_u32(vcvtq_u32_f32(low));
  const uint16x4_t high_u16 = vmovn_u32(vcvtq_u32_f32(high));
  return vmovn_u16(vcombine_u16(low_u16, high_u16));
}
#endif

class BoundedTaskPool {
public:
  BoundedTaskPool(size_t worker_count, size_t capacity)
      : capacity_(std::max<size_t>(1, capacity)) {
    for (size_t i = 0; i < std::max<size_t>(1, worker_count); i++) {
      workers_.emplace_back([this]() { run(); });
    }
  }

  ~BoundedTaskPool() {
    wait();
    {
      std::lock_guard<std::mutex> lock(mutex_);
      stopping_ = true;
    }
    work_available_.notify_all();
    for (std::thread &worker : workers_) {
      if (worker.joinable())
        worker.join();
    }
  }

  int64_t submit(std::function<void()> task) {
    const auto wait_start = std::chrono::steady_clock::now();
    std::unique_lock<std::mutex> lock(mutex_);
    capacity_available_.wait(lock, [this]() { return pending_ < capacity_; });
    const int64_t wait_us =
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - wait_start)
            .count();
    tasks_.push(std::move(task));
    pending_++;
    work_available_.notify_one();
    return wait_us;
  }

  bool add_second_worker() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopping_ || workers_.size() >= 2)
      return false;
    capacity_ = std::max<size_t>(capacity_, 3);
    workers_.emplace_back([this]() { run(); });
    capacity_available_.notify_all();
    work_available_.notify_one();
    return true;
  }

  void wait() {
    std::unique_lock<std::mutex> lock(mutex_);
    all_done_.wait(lock, [this]() { return pending_ == 0; });
  }

private:
  void run() {
    // Keep tile conversion behind Android's render and input threads.
    setpriority(PRIO_PROCESS, 0, 10);
    while (true) {
      std::function<void()> task;
      {
        std::unique_lock<std::mutex> lock(mutex_);
        work_available_.wait(lock,
                             [this]() { return stopping_ || !tasks_.empty(); });
        if (stopping_ && tasks_.empty())
          return;
        task = std::move(tasks_.front());
        tasks_.pop();
      }

      task();

      {
        std::lock_guard<std::mutex> lock(mutex_);
        pending_--;
      }
      capacity_available_.notify_one();
      all_done_.notify_all();
    }
  }

  size_t capacity_;
  std::vector<std::thread> workers_;
  std::queue<std::function<void()>> tasks_;
  std::mutex mutex_;
  std::condition_variable work_available_;
  std::condition_variable capacity_available_;
  std::condition_variable all_done_;
  size_t pending_ = 0;
  bool stopping_ = false;
};

// SY --> Komiho Phase11: 从 ncnn param 推导「感受野半径」，用作 tile 的 prepadding。
//
// 为什么值得算：tile 化推理时，每块输入 tile 必须向外多取一圈上下文，否则块与块
// 之间会留下接缝；这一圈的大小就等于网络的感受野半径。半径取小了会有接缝，取大了
// 则是纯粹的浪费 —— 每块 tile 的真实计算量按 ((tile + 2*padding) / tile)^2 放大。
//
// 旧代码把 prepadding 写死 18（源自早期 CUnet 时代的保守值）。而本分支保留的三款
// 模型半径都远小于此：
//   sudo UltraCompact = 10   （10 层 3x3 卷积，stride 全 1）
//   Anime4K ACNet     =  9   （9 层 3x3 卷积；2x2/stride2 反卷积不扩张半径）
//   waifu2x UpConv7   =  7   （6 层 3x3 卷积 + 4x4/stride2 反卷积）
// 于是 128 的 tile 每块要多算 (164/128)^2 - (148/128)^2 ≈ 18% 的像素，纯浪费。
//
// 做法：逐行解析 param 文本，按层类型累加半径，最后加 1 像素余量。解析不出层
// （文件损坏、出现无法定界的 global pooling 等）时退回调用方给的默认值。
int derive_prepadding_from_param(const std::string &parampath, int fallback) {
  std::ifstream ifs(parampath);
  if (!ifs.is_open()) {
    LOGW("Cannot open param for prepadding derivation: %s",
         parampath.c_str());
    return fallback;
  }

  std::string line;
  // 第 1 行是 magic(7767517)，第 2 行是 "layer_count blob_count"，跳过
  if (!std::getline(ifs, line) || !std::getline(ifs, line)) {
    return fallback;
  }

  int radius = 0;
  int conv_layers = 0;
  while (std::getline(ifs, line)) {
    if (line.empty())
      continue;
    std::istringstream iss(line);
    std::string type;
    std::string name;
    int bottom_count = 0;
    int top_count = 0;
    if (!(iss >> type >> name >> bottom_count >> top_count))
      continue;

    // 跳过 bottom / top 的 blob 名
    for (int i = 0; i < bottom_count + top_count; i++) {
      std::string blob;
      if (!(iss >> blob))
        break;
    }

    // 收集 "key=value" 参数
    int kernel = 1;
    int dilation = 1;
    int stride = 1;
    bool global_pooling = false;
    std::string kv;
    while (iss >> kv) {
      const size_t eq = kv.find('=');
      if (eq == std::string::npos)
        continue;
      const int key = std::atoi(kv.substr(0, eq).c_str());
      const int value = std::atoi(kv.substr(eq + 1).c_str());
      if (key == 1) {
        kernel = value;
      } else if (key == 2) {
        dilation = value;
      } else if (key == 3) {
        stride = value;
      } else if (key == 4 && type == "Pooling") {
        global_pooling = value != 0;
      }
    }

    if (type == "Convolution" || type == "ConvolutionDepthWise") {
      const int reach = (kernel - 1) * dilation; // 单边扩张量
      // stride > 1 时该层输出栅格变粗，折算回输入像素尺度要除以 stride
      radius += (reach / 2 + (reach % 2)) / std::max(1, stride);
      conv_layers++;
    } else if (type == "Deconvolution") {
      // 反卷积把 (k, stride) 拆成 4x4/stride2 这种形式时，输出一个像素只依赖
      // ceil(k/stride) 个输入像素，因此半径约为 (k - stride) / 2
      const int reach = std::max(0, kernel - std::max(1, stride));
      radius += reach / 2 + (reach % 2);
      conv_layers++;
    } else if (type == "Pooling") {
      if (global_pooling) {
        // 全局池化把整张特征图压成一个值，半径无法用常数界定，放弃推导
        LOGW("Global pooling found in %s; keeping default prepadding",
             parampath.c_str());
        return fallback;
      }
      const int reach = (kernel - 1) * dilation;
      radius += (reach / 2 + (reach % 2)) / std::max(1, stride);
      conv_layers++;
    }
    // Interp / PixelShuffle 只做重采样，不扩张感受野，忽略
  }

  if (conv_layers == 0) {
    LOGW("No convolution layers found in %s; keeping default prepadding",
         parampath.c_str());
    return fallback;
  }

  const int derived = radius + 1; // 1 像素余量，容忍实现上的取整差异
  if (derived < 4 || derived > 18) {
    LOGW("Derived prepadding %d out of range for %s; keeping default %d",
         derived, parampath.c_str(), fallback);
    return fallback;
  }

  LOGD("Derived prepadding=%d (radius=%d over %d conv layers) from %s", derived,
       radius, conv_layers, parampath.c_str());
  return derived;
}

} // namespace

Waifu2x::Waifu2x(int gpuid, bool _tta_mode, int _num_threads,
                 int _precision_mode, bool _fp16_arithmetic) {
  vkdev = gpuid == -1 ? 0 : ncnn::get_gpu_device(gpuid);
  const int detected_threads = ncnn::get_physical_big_cpu_count();
  num_threads = _num_threads > 0 ? std::clamp(_num_threads, 1, 4)
                                 : std::clamp(detected_threads - 1, 1, 3);
  LOGD("CPU helper threads: requested=%d detected_big=%d selected=%d",
       _num_threads, detected_threads, num_threads);
  precision_mode = _precision_mode;
  fp16_arithmetic = _fp16_arithmetic;
  net.opt.num_threads = num_threads;
  waifu2x_preproc = 0;
  waifu2x_postproc = 0;
  waifu2x_preproc_tta = 0;
  waifu2x_postproc_tta = 0;
  bicubic_2x = 0;
  tta_mode = _tta_mode;
  noise = 0;
  scale = 2;
  tilesize = 192;  // SY --> Komiho Phase11: 见 set_tilesize，192 是折中点
  // SY --> Komiho Phase11: 这里只是占位，load() 会按模型结构重新推导
  // （derive_prepadding_from_param），不再沿用写死的 18。
  prepadding = 18;
  // SY <--
  progress_ptr = nullptr;
  set_tilesize(tilesize);
}

// SY --> Komiho Phase11: tile 尺寸的唯一写入口，按 GPU 显存预算收敛上限。
//
// 收益来自「重叠冗余」：每块 tile 要多算 2*prepadding 圈上下文，冗余系数是
// ((tile + 2p) / tile)^2。p 固定时 tile 越大冗余越小 —— 128→192 能把冗余系数
// 从 1.40 压到 1.27，同时 tile 数量减半（1600x2400 的图：247 块 → 117 块），
// 每块的 dispatch / extractor 开销也随之摊薄。
//
// 代价是显存：单块中间特征图 ≈ (tile + 2p)^2 * channels * elemsize。
// 以 sudo UltraCompact（64 通道、ARM 上强制 fp32）为例，tile=256 时单块约
// (256+22)^2 * 64 * 4B ≈ 21MB，lightmode 下并存两三块就要 60MB 以上，
// 低显存机型会直接 OOM。所以用 heap budget 分档，宁可比用户选的更小。
void Waifu2x::set_tilesize(int requested) {
  if (tile_size_cap <= 0) {
    const uint32_t heap_budget_mb = vkdev ? vkdev->get_heap_budget() : 0;
    // SY --> 2026-09-22：ARM GPU（vendor 0x13B5 = ARM Ltd，含 Mali/Immortalis）
    // FP32 融合的显存带宽已是 fp16 两倍，大 tile 会让阅读器 UI 合成抢不到 GPU 而卡顿。
    // 故 ARM 上 tile 上限压到 128（输入 tile ≈164²，正好等于自适应批量阈值的标定基准，
    // 阈值缩放比 = 1.0，与上游调参意图一致）；同时峰值显存仅为 256 档的 1/4。
    const bool is_arm_gpu = vkdev && vkdev->info.vendor_id() == 0x13B5u;
    if (is_arm_gpu) {
      tile_size_cap = 128;
    } else if (heap_budget_mb >= 768) {
      tile_size_cap = 256;
    } else if (heap_budget_mb >= 384) {
      tile_size_cap = 192;
    } else {
      tile_size_cap = 128;
    }
    LOGD("Tile size cap=%d (heap_budget=%uMB arm=%d)", tile_size_cap,
         heap_budget_mb, is_arm_gpu ? 1 : 0);
  }

  tilesize = std::clamp(requested, 32, tile_size_cap);
  if (tilesize != requested) {
    LOGD("Tile size %d clamped to %d (cap=%d)", requested, tilesize,
         tile_size_cap);
  }
}
// SY <--

Waifu2x::~Waifu2x() {
  delete waifu2x_preproc;
  delete waifu2x_postproc;
  delete waifu2x_preproc_tta;
  delete waifu2x_postproc_tta;
  if (bicubic_2x) {
    bicubic_2x->destroy_pipeline(net.opt);
    delete bicubic_2x;
  }
}

int Waifu2x::load(const std::string &parampath, const std::string &modelpath) {
  net.opt.use_vulkan_compute = vkdev ? true : false;
  net.opt.use_fp16_packed = false;
  net.opt.use_fp16_storage = false;
  net.opt.use_fp16_arithmetic = false;
  net.opt.use_int8_inference = false;
  net.opt.use_int8_packed = false;
  net.opt.use_int8_storage = false;
  net.opt.use_int8_arithmetic = false;
  net.opt.use_bf16_storage = false;
  net.opt.use_bf16_packed = false;

  // SY --> Komiho Phase9（2026-09-22 修正，MmihonZ2）：
  // Immortalis-G925（天玑9400+）驱动上两条「快路径」都会出事：
  //   · fp16 融合着色器 → 规则网格花屏（已实测，R/G=0.36 均匀青偏）← MmihonY
  //   · BF16 staged（ncnn 标准路径）→ 直接 GPU fault，进程被杀 = 闪退回桌面 ← MmihonZ
  // 唯一在该驱动上**既正确又不崩**的是 FP32 融合（MmihonX 同款路径；用户只报它「卡」，
  // 从没报崩/花屏）。故 ARM 上**无条件 FP32 融合**（luma 与 3 通道都走 FP32）。
  // 治「卡」靠下面把 tile/batch 收敛（给 UI 合成留 GPU 空隙），而非换精度——
  // 换精度在 Immortalis 上只会更糟。非 ARM（Adreno 等）fp16 融合本就正常，保持 0 不变。
  // SY <--
  // SY --> 2026-09-23 诊断开关：debug.waifu2x.force_fp16=1 时跳过下面的 ARM FP32 收敛，
  //   保留 precision_mode=0（FP16 融合）。用途只有一个：在真机上 A/B 实测
  //   「FP16 融合在这台 Immortalis-G925 上到底对不对、快多少」。默认 0 = 出厂仍 FP32。
  const bool force_fp16_on_arm =
      scroll_yield_prop("debug.waifu2x.force_fp16", 0) == 1;
  // SY --> 2026-09-23 定稿：ARM 默认精度回到 **1（FP32 融合）**。
  //
  // 当天曾把默认值改成 0 以吃 FP16 的带宽/ALU 收益（实测 44.6 → 31 ms/tile，
  // 1.4~1.5×），但**画质不可接受**，真机取证（天玑9400 / Immortalis-G925 MC12）：
  //   · 白纸撒灰点率：FP16 融合 0.28~0.29（8 页中位 0.29） vs FP32 0.09；
  //     第三方基准 TX（同机型）0.0635 —— FP32 与 TX 同级，FP16 是异常。
  //   · 目视：整页被规则噪点场覆盖（用户报「所有的图都是这样，彩图就是方格形态」）。
  //   · 已排除的开关嫌疑：debug.waifu2x.packing_layout=0 → 0.286、
  //     debug.waifu2x.shader_local_memory=0 → 0.277，**都没救**。
  //     ⇒ 花屏是 FP16 融合实现本身的问题，不是打包布局/着色器本地内存配置。
  //   · TX 那套引擎跑在 precision=fp16 + fp16_arithmetic=0 上却完全干净，
  //     说明差距在**实现**，不在能力位（两者 ncnn 能力串逐项相同，含
  //     VK_KHR_cooperative_matrix）。想拿这份速度只能移植 TX 的实现。
  // 结论：本机默认 FP32。FP16 仅作诊断（arm_precision=0 可现场复现上述花屏）。
  const int arm_precision_prop = waifu2x_prop("debug.waifu2x.arm_precision", 1);
  const bool allow_fp16_on_arm = force_fp16_on_arm || arm_precision_prop == 0;
  if (force_fp16_on_arm) {
    LOGW("debug.waifu2x.force_fp16=1: skipping ARM FP32 convergence "
         "(experimental FP16 fused path)");
  }
  // SY <--
  if (vkdev && precision_mode == 0 && !allow_fp16_on_arm) {
    const ncnn::GpuInfo &gpu_info = vkdev->info;
    const char *gpu_name = gpu_info.device_name();
    std::string gpu_name_lower;
    if (gpu_name) {
      gpu_name_lower.reserve(std::strlen(gpu_name));
      for (const char *p = gpu_name; *p != '\0'; ++p) {
        gpu_name_lower.push_back(
            static_cast<char>((*p >= 'A' && *p <= 'Z') ? (*p - 'A' + 'a') : *p));
      }
    }
    // ARM Ltd. 的 Vulkan vendorID 是 0x13B5；设备名再做一层兜底匹配。
    const bool is_arm_gpu =
        gpu_info.vendor_id() == 0x13B5u ||
        gpu_name_lower.find("mali") != std::string::npos ||
        gpu_name_lower.find("immortalis") != std::string::npos;
    if (is_arm_gpu) {
      LOGW("ARM GPU '%s' (vendor=0x%x): forcing FP32 fused storage "
           "(fp16 fused = Immortalis artifact; bf16 staged = driver crash)",
           gpu_name ? gpu_name : "unknown", gpu_info.vendor_id());
      precision_mode = 1; // FP32 fused: correct + stable on Immortalis
    }
  }

  switch (precision_mode) {
  case 1: // FP32
    break;
  case 2: // INT8
    net.opt.use_int8_inference = true;
    net.opt.use_int8_packed = true;
    net.opt.use_int8_storage = true;
    net.opt.use_int8_arithmetic = true;
    break;
  case 3: // BF16
    net.opt.use_bf16_storage = true;
    net.opt.use_bf16_packed = true;
    break;
  case 0: // FP16
  default:
    net.opt.use_fp16_packed = true;
    net.opt.use_fp16_storage = true;
    {
      // SY --> 2026-09-23 诊断：可被 debug.waifu2x.fp16_arith 覆盖。
      const int fp16_arith_prop = waifu2x_prop("debug.waifu2x.fp16_arith", -1);
      const bool want_arith =
          fp16_arith_prop >= 0 ? fp16_arith_prop == 1 : fp16_arithmetic;
      net.opt.use_fp16_arithmetic =
          want_arith && vkdev && vkdev->info.support_fp16_arithmetic();
      // SY <--
    }
    break;
  }
  const bool fp16_arithmetic_supported =
      vkdev && vkdev->info.support_fp16_arithmetic();
  LOGD("Precision=%d FP16 arithmetic requested=%d supported=%d enabled=%d",
       precision_mode, fp16_arithmetic ? 1 : 0,
       fp16_arithmetic_supported ? 1 : 0,
       net.opt.use_fp16_arithmetic ? 1 : 0);

  // Packed RGBA8 is transported as one uint per pixel, so the fused path does
  // not require 8-bit shader storage or alter the model precision.
  gpu_pipeline_available = vkdev &&
                           (precision_mode == 1 ||
                            (precision_mode == 0 &&
                             vkdev->info.support_fp16_storage()));

  // SY --> mihon-webdav: luma（单通道）模型现在也有自己的融合管线。
  //
  // 原先这里是把 gpu_pipeline_available 直接置 false —— 3 通道版着色器
  // （waifu2x_fused_preproc/postproc.comp）把通道数写死在 SPIR-V 里，单通道
  // 模型确实走不了，于是 ACNet 只能退回 CPU staged 路径（卷积虽仍在 GPU，
  // 但切 tile、双三次色度放大、YCbCr 合成与写回全在 CPU 上，慢一个量级）。
  //
  // 现在补了 waifu2x_fused_*_luma.comp 两支 1 通道变体：
  //   preproc  —— RGBA8 → BT.601 亮度 Y（1 平面）
  //   postproc —— Y' + 全图双三次色度 → 打包 RGBA8
  // 于是 luma 模型也全程走 GPU。着色器创建失败时（老驱动/不支持）下面的
  // 创建分支会把 gpu_pipeline_available 收回 false，自动退回 staged 路径。
  // SY <--

  // SY --> 2026-09-23 诊断：packing_layout / shader_local_memory 也做成可覆盖，
  //   用来在真机上单独定位「剩余的性能差距」是打包布局还是着色器本地内存。
  const int packing_prop = waifu2x_prop("debug.waifu2x.packing_layout", -1);
  net.opt.use_packing_layout =
      packing_prop < 0 ? true : (packing_prop == 1); // packing for performance
  // SY <--

  // Additional optimizations (safe for all devices)
  net.opt.use_sgemm_convolution = true;    // Use SGEMM for convolution
  net.opt.use_winograd_convolution = true; // Winograd convolution
  net.opt.use_local_pool_allocator = true; // Better memory allocation
  // SY --> 2026-09-23 诊断：可覆盖
  const int slm_prop = waifu2x_prop("debug.waifu2x.shader_local_memory", -1);
  net.opt.use_shader_local_memory =
      slm_prop < 0 ? true : (slm_prop == 1); // Use shader local memory
  // SY <--

  // SY --> 2026-09-23 诊断：融合路径正确性直接取决于这几个开关落定后的取值 ——
  //   尤其 use_packing_layout + use_fp16_storage 会让 ncnn 的 VkMat 变成 pack4
  //   （elemsize = 2*4 = 8），而 VkMat::cstep 是以 **elemsize 为单位** 计数的。
  //   融合着色器若按 pack1（cstep 以 fp16 元素计）寻址，就会整体错位。
  LOGD("Net options: precision=%d fp16_storage=%d fp16_packed=%d fp16_arith=%d "
       "packing_layout=%d shader_local_memory=%d (props: arm_precision=%d "
       "fp16_arith=%d packing=%d slm=%d)",
       precision_mode, net.opt.use_fp16_storage ? 1 : 0,
       net.opt.use_fp16_packed ? 1 : 0, net.opt.use_fp16_arithmetic ? 1 : 0,
       net.opt.use_packing_layout ? 1 : 0,
       net.opt.use_shader_local_memory ? 1 : 0, arm_precision_prop,
       waifu2x_prop("debug.waifu2x.fp16_arith", -1), packing_prop, slm_prop);
  // SY <--

  net.opt.num_threads = num_threads;

  // Hardware-specific optimizations are already set in constructor
  // (use_subgroup_ops, use_cooperative_matrix, num_threads)
  // No need to override them here

  net.set_vulkan_device(vkdev);

  if (net.load_param(parampath.c_str()) != 0) {
    LOGE("Failed to load param: %s", parampath.c_str());
    return -1;
  }
  if (net.load_model(modelpath.c_str()) != 0) {
    LOGE("Failed to load model: %s", modelpath.c_str());
    return -1;
  }

  // SY --> Komiho Phase11: 拿到 param 之后按模型结构推导 prepadding，
  // 覆盖 JNI 侧设置的占位值。这一步必须在 load_param 之后、任何推理之前。
  prepadding = derive_prepadding_from_param(parampath, prepadding);
  LOGD("Tile config resolved: tilesize=%d prepadding=%d", tilesize, prepadding);
  // SY <--

  if (gpu_pipeline_available) {
    const bool fp16_shader = precision_mode == 0;
    // SY --> luma（单通道）模型用 1 通道变体着色器，其余用 3 通道版。
    const uint32_t *preproc_spirv = luma_model
        ? (fp16_shader ? waifu2x_fused_preproc_luma_fp16_spv
                       : waifu2x_fused_preproc_luma_fp32_spv)
        : (fp16_shader ? waifu2x_fused_preproc_fp16_spv
                       : waifu2x_fused_preproc_fp32_spv);
    const size_t preproc_spirv_size = luma_model
        ? (fp16_shader ? sizeof(waifu2x_fused_preproc_luma_fp16_spv)
                       : sizeof(waifu2x_fused_preproc_luma_fp32_spv))
        : (fp16_shader ? sizeof(waifu2x_fused_preproc_fp16_spv)
                       : sizeof(waifu2x_fused_preproc_fp32_spv));
    const uint32_t *postproc_spirv = luma_model
        ? (fp16_shader ? waifu2x_fused_postproc_luma_fp16_spv
                       : waifu2x_fused_postproc_luma_fp32_spv)
        : (fp16_shader ? waifu2x_fused_postproc_fp16_spv
                       : waifu2x_fused_postproc_fp32_spv);
    const size_t postproc_spirv_size = luma_model
        ? (fp16_shader ? sizeof(waifu2x_fused_postproc_luma_fp16_spv)
                       : sizeof(waifu2x_fused_postproc_luma_fp32_spv))
        : (fp16_shader ? sizeof(waifu2x_fused_postproc_fp16_spv)
                       : sizeof(waifu2x_fused_postproc_fp32_spv));
    // SY <--

    const std::vector<ncnn::vk_specialization_type> specializations;
    // SY --> luma 模型的 1 通道着色器 local_size_z = 1（3 通道版是 4，
    // 与它 dispatcher.c = 3 的写法配套，这里不要改）。
    waifu2x_preproc = new ncnn::Pipeline(vkdev);
    waifu2x_preproc->set_optimal_local_size_xyz(8, 8, luma_model ? 1 : 4);
    const int preproc_pipeline_result = waifu2x_preproc->create(
        preproc_spirv, preproc_spirv_size, specializations);

    waifu2x_postproc = new ncnn::Pipeline(vkdev);
    waifu2x_postproc->set_optimal_local_size_xyz(8, 8, 1);
    const int postproc_pipeline_result = waifu2x_postproc->create(
        postproc_spirv, postproc_spirv_size, specializations);
    // SY <--
    LOGD("Embedded fused pipeline create: precision=%s luma=%d preproc=%d postproc=%d",
         fp16_shader ? "fp16" : "fp32", luma_model ? 1 : 0,
         preproc_pipeline_result, postproc_pipeline_result);
    gpu_pipeline_available =
        preproc_pipeline_result == 0 && postproc_pipeline_result == 0;

    if (!gpu_pipeline_available) {
      delete waifu2x_preproc;
      delete waifu2x_postproc;
      waifu2x_preproc = nullptr;
      waifu2x_postproc = nullptr;
      LOGE("GPU pre/post pipeline unavailable; using staged fallback");
    }
  }

  LOGD("Fused Vulkan pipeline %s",
       gpu_pipeline_available ? "enabled" : "disabled");

  // Create interp layer for bicubic alpha scaling
  bicubic_2x = ncnn::create_layer("Interp");
  if (!bicubic_2x) {
    LOGE("Failed to create Interp layer!");
    return -1;
  }

  bicubic_2x->vkdev = vkdev;
  ncnn::ParamDict pd;
  pd.set(0, 3); // bicubic
  pd.set(1, (float)scale);
  pd.set(2, (float)scale);
  bicubic_2x->load_param(pd);
  if (bicubic_2x->create_pipeline(net.opt) != 0) {
    LOGE("Failed to create Interp pipeline");
    return -1;
  }

  return 0;
}

int Waifu2x::process_gpu(const ncnn::Mat &packed_input, void *out_pixels,
                         int out_stride, bool input_has_alpha,
                         std::atomic<int> *progress_ptr) const {
  if (!gpu_pipeline_available || !vkdev || packed_input.empty() ||
      packed_input.elemsize != 4u) {
    return -1;
  }

  const int w = packed_input.w;
  const int h = packed_input.h;
  const int target_w = w * scale;
  const int target_h = h * scale;
  if (out_stride != target_w * 4)
    return -1;

  const auto t_start = std::chrono::steady_clock::now();

  bool is_grayscale = !disable_grayscale_check;
  int gray_color_pixels = 0;
  int gray_threshold = 0;
  if (is_grayscale) {
    const unsigned char *pixels =
        static_cast<const unsigned char *>(packed_input.data);
    int color_pixels = 0;
    // SY --> 2026-09-23 判决阈值重标定（真机截图实测，天玑9400 / sudo-UltraCompact）：
    //   旧判据「色差>5 的像素超过 w*h/200（0.5%）即算彩色」在扫描版黑白漫画上
    //   **永远判成彩色** —— 实测黑白页有 2.2~2.7% 的像素色差>5（网点/JPEG 振铃），
    //   于是下方的「灰度强制输出」从不生效，FP16 路径的色度噪声全部暴露成绿点。
    //   新判据用「色差>24 的像素占比 < 3%」判黑白，实测分离度：
    //     黑白页 0.00%  ← 判黑白
    //     彩色封面 93.9% / 彩色插页 8.8%  ← 判彩色
    //   与 TX 行为一致（TX 的 "Grayscale image detected, forcing pure grayscale
    //   output." 在这些黑白页上会命中，输出色差恒为 0）。
    const int color_threshold = w * h * 3 / 100;
    for (int i = 0; i < w * h; i++) {
      const unsigned char *pixel = pixels + i * 4;
      const int bb = (int)pixel[0];
      const int gg = (int)pixel[1];
      const int rr = (int)pixel[2];
      const int spread = std::max(std::abs(rr - gg),
                                  std::max(std::abs(gg - bb), std::abs(rr - bb)));
      if (spread > 24) {
        if (++color_pixels > color_threshold) {
          is_grayscale = false;
          break;
        }
      }
    }
    gray_color_pixels = color_pixels;
    gray_threshold = color_threshold;
  }
  // SY --> 2026-09-23 灰度判定可被 debug.waifu2x.grayscale 覆盖
  //   （-1/缺省 = 自动检测；0 = 强制按彩色处理；1 = 强制按灰阶处理）。
  const int gray_prop = waifu2x_prop("debug.waifu2x.grayscale", -1);
  if (gray_prop >= 0) {
    is_grayscale = gray_prop == 1;
    LOGD("Grayscale override: prop=%d -> gray=%d", gray_prop,
         is_grayscale ? 1 : 0);
  }
  LOGD("Grayscale check: color_pixels=%d threshold=%d(spread>24, 3%%) -> gray=%d",
       gray_color_pixels, gray_threshold, is_grayscale ? 1 : 0);
  // SY <--

  ncnn::VkAllocator *blob_vkallocator = vkdev->acquire_blob_allocator();
  ncnn::VkAllocator *staging_vkallocator = vkdev->acquire_staging_allocator();
  if (!blob_vkallocator || !staging_vkallocator) {
    if (blob_vkallocator)
      vkdev->reclaim_blob_allocator(blob_vkallocator);
    if (staging_vkallocator)
      vkdev->reclaim_staging_allocator(staging_vkallocator);
    return -1;
  }

  struct AllocatorGuard {
    const ncnn::VulkanDevice *device;
    ncnn::VkAllocator *blob;
    ncnn::VkAllocator *staging;
    ~AllocatorGuard() {
      device->reclaim_blob_allocator(blob);
      device->reclaim_staging_allocator(staging);
    }
  } allocator_guard{vkdev, blob_vkallocator, staging_vkallocator};

  ncnn::Option opt = net.opt;
  opt.blob_vkallocator = blob_vkallocator;
  opt.workspace_vkallocator = blob_vkallocator;
  opt.staging_vkallocator = staging_vkallocator;

  ncnn::VkMat input_gpu;
  {
    ncnn::VkCompute upload(vkdev);
    upload.record_clone(packed_input, input_gpu, opt);
    const int upload_result = upload.submit_and_wait();
    if (upload_result != 0 || input_gpu.empty()) {
      LOGE("Fused upload failed: result=%d empty=%d", upload_result,
           input_gpu.empty() ? 1 : 0);
      return -1;
    }
  }

  ncnn::VkMat output_gpu;
  output_gpu.create(target_w, target_h, (size_t)4u, 1, blob_vkallocator);
  if (output_gpu.empty())
    return -1;

  const int xtiles = (w + tilesize - 1) / tilesize;
  const int ytiles = (h + tilesize - 1) / tilesize;
  const int tile_count = xtiles * ytiles;
  const size_t tile_elemsize =
      (opt.use_fp16_storage || opt.use_fp16_packed) ? 2u : 4u;
  const uint32_t heap_budget_mb = vkdev->get_heap_budget();
  int batch_capacity = 2;
  if (tile_sleep_ms > 0) {
    batch_capacity = 1;
  } else if (heap_budget_mb >= 768) {
    batch_capacity = 4;
  } else if (heap_budget_mb >= 384) {
    batch_capacity = 3;
  }
  batch_capacity = std::min(batch_capacity, tile_count);
  // SY --> 2026-09-23：ARM 上的批量上限/起始批量改为运行时可调
  //   （debug.waifu2x.arm_batch = 1..4；0/缺省 = 保持 2026-09-22 的保守值 2/1）。
  //
  //   为什么要放开：2026-09-22 把 ARM 批量压到 capacity=2、target=1，前提是
  //   「滚动期间仍在持续提交」——那时长批量会把 UI 合成饿死。但方案 A（滚动期全停）
  //   落地后，提交前有关卡（pre-submit 等待循环）会先等 UI 空闲，于是长批量
  //   **只影响静止时的追帧速度，不再威胁滚动流畅度**；而 target=1 等于让 77 块 tile
  //   做 77 次 submit_and_wait，每次都付 vkQueueSubmit + fence 等待的固定开销。
  const bool is_arm_gpu = vkdev && vkdev->info.vendor_id() == 0x13B5u;
  const int arm_batch_prop = scroll_yield_prop("debug.waifu2x.arm_batch", 0);
  if (is_arm_gpu && arm_batch_prop <= 0) {
    batch_capacity = std::min(batch_capacity, 2);
  }
  // SY <--

  // SY --> Komiho Phase11: 自适应批量的阈值改为随 tile 面积缩放。
  // 原有 12ms/24ms 是按 128 的 tile、prepadding=18（即输入 164x164）标定的。
  // 现在 tile 变大（默认 192、prepadding 降为按模型推导），单块 GPU 工作量按
  // 面积增长到 ~1.7 倍，若沿用老阈值，控制器会把「一次批量提交 20ms」误判成
  // 「太慢」而把 batch 缩回 1，反而丢掉批量摊销 —— 等于白改。这里以旧的
  // 164x164 为基准按面积等比放大，保持原来的调参意图。
  const double tile_area_ratio =
      static_cast<double>(tilesize + 2 * prepadding) *
      static_cast<double>(tilesize + 2 * prepadding) / (164.0 * 164.0);
  const int64_t grow_threshold_us =
      static_cast<int64_t>(12000.0 * tile_area_ratio);
  const int64_t shrink_threshold_us =
      static_cast<int64_t>(24000.0 * tile_area_ratio);

  // Start with a short command buffer so the first UI interaction cannot be
  // trapped behind several tiles.
  // SY --> Komiho Phase11: 起始批量从 1 提到 2。实测每块 tile 的 GPU 时间远大于
  // 一次 submit 的固定开销，批量 1 等于把 GPU 反复饿停；从 2 起步能立刻拿到
  // 摊销收益，同时首次提交仍然只有两块，不至于拖住首帧交互。
  // SY --> 2026-09-22：ARM 上起始批量降到 1——FP32 融合本就更吃带宽，首帧不被
  // 多块 tile 拖住，UI 更跟手、不卡。
  // SY --> 2026-09-23：改为可调（debug.waifu2x.arm_batch）；见上面 batch_capacity 处。
  int batch_target;
  if (is_arm_gpu) {
    batch_target = arm_batch_prop > 0 ? std::min(arm_batch_prop, batch_capacity)
                                      : 1;
  } else {
    batch_target = std::min(2, batch_capacity);
  }
  // SY <--
  LOGD("Fused Vulkan scheduling: heap_budget=%uMB batch_capacity=%d "
       "initial_batch=%d tiles=%d grow_threshold=%lldus shrink_threshold=%lldus",
       heap_budget_mb, batch_capacity, batch_target, tile_count,
       static_cast<long long>(grow_threshold_us),
       static_cast<long long>(shrink_threshold_us));

  const int input_tile_w = tilesize + prepadding * 2;
  const int input_tile_h = tilesize + prepadding * 2;
  std::vector<ncnn::VkMat> input_tile_slots(batch_capacity);
  const ncnn::Extractor empty_extractor = net.create_extractor();
  std::vector<ncnn::Extractor> extractor_slots;
  extractor_slots.reserve(batch_capacity);
  for (int i = 0; i < batch_capacity; i++) {
    // SY --> luma 模型的网络输入只有 1 通道，tile 也按 1 通道分配。
    input_tile_slots[i].create(input_tile_w, input_tile_h,
                               luma_model ? 1 : 3, tile_elemsize, 1,
                               blob_vkallocator);
    if (input_tile_slots[i].empty())
      return -1;
    extractor_slots.push_back(empty_extractor);
  }

  // SY --> 2026-09-23 诊断：融合路径上各 blob 的真实打包形态。
  //   注意 VkMat::cstep 的单位是 elemsize（= 标量字节数 × elempack），
  //   而不是标量元素个数 —— 着色器按 storage_t[] 线性寻址时，只有 pack1 时两者相等。
  LOGD("Fused blob packings: src(elemsize=%zu pack=%d cstep=%zu %dx%dx%d) "
       "tile_in(elemsize=%zu pack=%d cstep=%zu %dx%dx%d)",
       input_gpu.elemsize, input_gpu.elempack, (size_t)input_gpu.cstep,
       input_gpu.w, input_gpu.h, input_gpu.c,
       input_tile_slots[0].elemsize, input_tile_slots[0].elempack,
       (size_t)input_tile_slots[0].cstep, input_tile_slots[0].w,
       input_tile_slots[0].h, input_tile_slots[0].c);
  // SY <--

  int batch_size = 0;
  int completed_tiles = 0;
  bool pack_logged = false; // SY --> 2026-09-23 诊断：打包形态只打一次
  ncnn::VkCompute command(vkdev);
  std::vector<ncnn::VkMat> retained_mats;
  retained_mats.reserve(batch_capacity);

  if (net.input_indexes().empty() || net.output_indexes().empty())
    return -1;
  const int input_index = net.input_indexes()[0];
  const int output_index = net.output_indexes().back();

  for (int yi = 0; yi < ytiles; yi++) {
    for (int xi = 0; xi < xtiles; xi++) {
      bool paused_for_ui = false;
      while ((ui_busy_ptr && ui_busy_ptr->load()) ||
             interaction_full_stop(continuous_mode, last_interaction_ms.load())) {
        paused_for_ui = true;
        if (should_abort_ptr && should_abort_ptr->load())
          return -1;
        std::this_thread::sleep_for(std::chrono::milliseconds(8));
      }
      if (paused_for_ui) {
        // SY --> Komiho Phase11: 原来是直接归 1，等于每次触摸都把已经爬上去的
        // 批量知识清零、下一轮再从 1 重新爬；触摸在阅读器里非常频繁
        // （dispatchTouchEvent 每个事件都触发），于是批量长期停在 1~2。
        // 退一步而不是清零，既让出 GPU 给 UI，又保住摊销收益。
        batch_target = std::max(1, batch_target - 1);
        // SY <--
      }
      if (should_abort_ptr && should_abort_ptr->load()) {
        // SY --> 诊断（2026-09-23）：抢占会让整页作废、重排后从第 0 块重算。
        // 这里记录白扔了多少块，用来客观验证 ImageEnhancer 的「进度感知抢占」
        // （PREEMPT_MIN_PROGRESS_PCT）是否真的减少了白算 —— Kotlin 侧 logcat()
        // 在 release 下不进系统日志，只有原生这个 tag 可 grep。
        const int total_tiles = ytiles * xtiles;
        const int done_tiles = yi * xtiles + xi;
        LOGD("Abort: page discarded after %d/%d tiles (~%d%% wasted)",
             done_tiles, total_tiles,
             total_tiles > 0 ? done_tiles * 100 / total_tiles : 0);
        // SY <--
        return -1;
      }

      const int x = xi * tilesize;
      const int y = yi * tilesize;
      const int tile_w = std::min(tilesize, w - x);
      const int tile_h = std::min(tilesize, h - y);
      ncnn::VkMat &input_tile_gpu = input_tile_slots[batch_size];

      {
        std::vector<ncnn::VkMat> bindings(2);
        bindings[0] = input_gpu;
        bindings[1] = input_tile_gpu;
        std::vector<ncnn::vk_constant_type> constants(13);
        constants[0].i = input_gpu.w;
        constants[1].i = input_gpu.h;
        constants[2].i = input_gpu.cstep;
        constants[3].i = input_tile_gpu.w;
        constants[4].i = input_tile_gpu.h;
        constants[5].i = input_tile_gpu.cstep;
        constants[6].i = prepadding;
        constants[7].i = prepadding;
        constants[8].i = x;
        constants[9].i = y;
        // SY --> 3 通道版靠 dispatcher.c=3 + channels=4 的写法（local_size_z=4），
        // luma 版 local_size_z=1，只跑 1 个通道。这里不要「顺手统一」两边。
        constants[10].i = luma_model ? 1 : 4;
        // SY <--
        constants[11].i = 0;
        constants[12].i = 0;
        ncnn::VkMat dispatcher;
        dispatcher.w = input_tile_gpu.w;
        dispatcher.h = input_tile_gpu.h;
        dispatcher.c = luma_model ? 1 : 3;
        command.record_pipeline(waifu2x_preproc, bindings, constants,
                                dispatcher);
      }

      ncnn::VkMat output_tile_gpu;
      {
        ncnn::Extractor &extractor = extractor_slots[batch_size];
        extractor.set_blob_vkallocator(blob_vkallocator);
        extractor.set_workspace_vkallocator(blob_vkallocator);
        extractor.set_staging_vkallocator(staging_vkallocator);
        const int input_result = extractor.input(input_index, input_tile_gpu);
        const int extract_result =
            input_result == 0
                ? extractor.extract(output_index, output_tile_gpu, command)
                : -1;
        // SY --> 2026-09-23 诊断：网络输出 blob 的打包形态（postproc 的寻址基准）。
        if (!pack_logged && extract_result == 0 && !output_tile_gpu.empty()) {
          pack_logged = true;
          LOGD("Fused net output packing: elemsize=%zu pack=%d cstep=%zu "
               "%dx%dx%d",
               output_tile_gpu.elemsize, output_tile_gpu.elempack,
               (size_t)output_tile_gpu.cstep, output_tile_gpu.w,
               output_tile_gpu.h, output_tile_gpu.c);
        }
        // SY <--
        if (input_result != 0 || extract_result != 0 ||
            output_tile_gpu.empty()) {
          LOGE("Fused inference record failed: tile=%d slot=%d input=%d "
               "extract=%d empty=%d",
               yi * xtiles + xi, batch_size, input_result, extract_result,
               output_tile_gpu.empty() ? 1 : 0);
          return -1;
        }
      }

      const int output_tile_w = tile_w * scale;
      const int output_tile_h = tile_h * scale;
      const int expected_w = input_tile_w * scale;
      const int expected_h = input_tile_h * scale;
      const int full_output_tile_w = tilesize * scale;
      const int full_output_tile_h = tilesize * scale;
      int source_offset_x = 0;
      int source_offset_y = 0;
      if (output_tile_gpu.w >= expected_w &&
          output_tile_gpu.h >= expected_h) {
        source_offset_x = prepadding * scale;
        source_offset_y = prepadding * scale;
      } else if (output_tile_gpu.w >= full_output_tile_w &&
                 output_tile_gpu.h >= full_output_tile_h) {
        source_offset_x = 0;
        source_offset_y = 0;
      } else if (output_tile_gpu.w >= output_tile_w &&
                 output_tile_gpu.h >= output_tile_h) {
        source_offset_x = (output_tile_gpu.w - output_tile_w) / 2;
        source_offset_y = (output_tile_gpu.h - output_tile_h) / 2;
      }

      const int copy_w =
          std::min(output_tile_w, output_tile_gpu.w - source_offset_x);
      const int copy_h =
          std::min(output_tile_h, output_tile_gpu.h - source_offset_y);
      if (copy_w != output_tile_w || copy_h != output_tile_h) {
        LOGE("Fused tile shape mismatch: tile=%d output=%dx%d copy=%dx%d "
             "wanted=%dx%d offset=%d,%d",
             yi * xtiles + xi, output_tile_gpu.w, output_tile_gpu.h, copy_w,
             copy_h, output_tile_w, output_tile_h, source_offset_x,
             source_offset_y);
        return -1;
      }

      {
        std::vector<ncnn::VkMat> bindings(3);
        bindings[0] = output_tile_gpu;
        bindings[1] = output_gpu;
        bindings[2] = input_gpu;
        std::vector<ncnn::vk_constant_type> constants(16);
        constants[0].i = output_tile_gpu.w;
        constants[1].i = output_tile_gpu.h;
        constants[2].i = output_tile_gpu.cstep;
        constants[3].i = output_gpu.w;
        constants[4].i = output_gpu.h;
        constants[5].i = source_offset_x;
        constants[6].i = source_offset_y;
        constants[7].i = x * scale;
        constants[8].i = y * scale;
        constants[9].i = copy_w;
        constants[10].i = copy_h;
        // SY --> 3 通道版这个槽位是 channels(=4)；luma 版把它当 scale 用
        // （要按 1/scale 反推色度的源坐标），见 waifu2x_fused_postproc_luma.comp。
        constants[11].i = luma_model ? scale : 4;
        // SY <--
        constants[12].i = input_gpu.w;
        constants[13].i = input_gpu.h;
        constants[14].i = input_has_alpha ? 1 : 0;
        constants[15].i = is_grayscale ? 1 : 0;
        ncnn::VkMat dispatcher;
        dispatcher.w = copy_w;
        dispatcher.h = copy_h;
        dispatcher.c = 1;
        command.record_pipeline(waifu2x_postproc, bindings, constants,
                                dispatcher);
      }

      retained_mats.push_back(output_tile_gpu);
      batch_size++;

      const bool is_last_tile = xi == xtiles - 1 && yi == ytiles - 1;
      if (batch_size >= batch_target || is_last_tile) {
        bool paused_before_submit = false;
        while ((ui_busy_ptr && ui_busy_ptr->load()) ||
             interaction_full_stop(continuous_mode, last_interaction_ms.load())) {
          paused_before_submit = true;
          if (should_abort_ptr && should_abort_ptr->load())
            return -1;
          std::this_thread::sleep_for(std::chrono::milliseconds(8));
        }
        if (paused_before_submit) {
          // SY --> Komiho Phase11: 同上，退一步而非清零
          batch_target = std::max(1, batch_target - 1);
          // SY <--
        }

        const auto submit_start = std::chrono::steady_clock::now();
        const int submit_result = command.submit_and_wait();
        const int64_t submit_us =
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - submit_start)
                .count();
        if (submit_result != 0) {
          LOGE("Fused batch submit failed: completed=%d batch=%d result=%d",
               completed_tiles, batch_size, submit_result);
          return -1;
        }
        completed_tiles += batch_size;
        if (progress_ptr)
          progress_ptr->store(completed_tiles * 99 / tile_count);
        command.reset();
        retained_mats.clear();
        for (int i = 0; i < batch_size; i++)
          extractor_slots[i] = empty_extractor;
        batch_size = 0;

        const int previous_batch_target = batch_target;
        if ((ui_busy_ptr && ui_busy_ptr->load()) ||
            interaction_full_stop(continuous_mode, last_interaction_ms.load())) {
          // SY --> Komiho Phase11: 同上，退一步而非清零
          batch_target = std::max(1, batch_target - 1);
          // SY <--
        } else if (submit_us >= shrink_threshold_us && batch_target > 1) {
          batch_target = std::max(1, batch_target / 2);
        } else if (submit_us <= grow_threshold_us &&
                   batch_target < batch_capacity) {
          batch_target++;
        }
        if (batch_target != previous_batch_target) {
          LOGD("Fused Vulkan adaptive batch: %d -> %d after %lldus submit",
               previous_batch_target, batch_target,
               static_cast<long long>(submit_us));
        }

        if (should_abort_ptr && should_abort_ptr->load())
          return -1;
        // SY --> Komiho Phase12: 连续滚动模式 GPU 交错让出。
        // 滑动期间（连续模式 + 近期有交互）强制一个让出下限，把 GPU 留一部分给合成
        // 线程；停下阅读（>kContinuousRecentMs 无交互）后恢复全速追帧。用户显式选了
        // perfMode=1/2（tile_sleep_ms>0）时以更大的 sleep 为准。
        const int64_t now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        const bool recent_interaction =
            continuous_mode &&
            (now_ms - last_interaction_ms.load()) < recent_interaction_window_ms();
        const int effective_sleep =
            tile_sleep_ms > 0
                ? tile_sleep_ms
                : (recent_interaction ? scroll_yield_ms_for(submit_us) : 0);
        // SY --> 2026-09-23 方案D 可选档：滚动期强制 batch=1（对下一次提交生效），
        // 把单次 submit 阻塞时长压到最小 —— 一次提交阻塞 40ms+ 本身就够吃掉好几个帧预算。
        if (recent_interaction && scroll_force_batch1()) {
          batch_target = 1;
        }
        if (effective_sleep > 0 && !is_last_tile) {
          std::this_thread::sleep_for(
              std::chrono::milliseconds(effective_sleep));
        }
        // SY <--
      }
    }
  }

  ncnn::Mat packed_output(target_w, target_h, out_pixels, (size_t)4u, 1);
  {
    while ((ui_busy_ptr && ui_busy_ptr->load()) ||
           interaction_full_stop(continuous_mode, last_interaction_ms.load())) {
      if (should_abort_ptr && should_abort_ptr->load())
        return -1;
      std::this_thread::sleep_for(std::chrono::milliseconds(8));
    }
    ncnn::VkCompute download(vkdev);
    download.record_clone(output_gpu, packed_output, opt);
    const int download_result = download.submit_and_wait();
    if (download_result != 0) {
      LOGE("Fused download failed: result=%d", download_result);
      return -1;
    }
  }

  if (progress_ptr)
    progress_ptr->store(100);

  const auto t_end = std::chrono::steady_clock::now();
  const int64_t elapsed_ms =
      std::chrono::duration_cast<std::chrono::milliseconds>(t_end - t_start)
          .count();
  LOGD("Processing complete: %dx%d (fused Vulkan) elapsed=%lldms",
       target_w, target_h, (long long)elapsed_ms);
  return 0;
}

int Waifu2x::process(const ncnn::Mat &inimage, void *out_pixels, int out_stride,
                     bool input_has_alpha, std::unique_lock<std::mutex> &lock,
                     std::atomic<int> *progress_ptr) const {
  // Input: planar RGBA float Mat with values 0-255 from from_pixels
  // inimage has dims=3, w=width, h=height, c=4 (RGBA)

  int orig_w = inimage.w;
  int orig_h = inimage.h;

  // Use original resolution for full quality per user request
  const ncnn::Mat &work_img = inimage;

  int w = work_img.w;
  int h = work_img.h;
  int target_w = w * scale;
  int target_h = h * scale;

  const auto t_start = std::chrono::steady_clock::now();

  LOGD("Processing image %dx%d (orig %dx%d) -> %dx%d luma=%d", w, h, orig_w, orig_h,
       target_w, target_h, luma_model ? 1 : 0);

  // Normalization using work_img
  ncnn::Mat rgb_normalized(w, h, 3);
  bool is_grayscale = true;

  // Channel mapping from work_img
  const float *in_r = work_img.channel(0);
  const float *in_g = work_img.channel(1);
  const float *in_b = work_img.channel(2);

  float *out_b = rgb_normalized.channel(0); // B goes to channel 0
  float *out_g = rgb_normalized.channel(1); // G stays at channel 1
  float *out_r = rgb_normalized.channel(2); // R goes to channel 2

  const float norm = 1.0f / 255.0f;

  // SY --> 2026-09-23 判决阈值与融合路径保持一致（spread>24 占比 <3% 判黑白）。
  //   理由见上面 fused 路径处的长注释（旧 0.5% / 色差>5 在扫描版黑白漫画上永不命中）。
  int color_pixel_count = 0;
  int color_threshold_count = w * h * 3 / 100; // 3%
  const bool detect_grayscale = !disable_grayscale_check;

  for (int i = 0; i < w * h; i++) {
    // Detect if pixel has significant color
    if (detect_grayscale) {
      const float spread =
          std::max(std::abs(in_r[i] - in_g[i]),
                   std::max(std::abs(in_g[i] - in_b[i]),
                            std::abs(in_r[i] - in_b[i])));
      if (spread > 24.0f)
        color_pixel_count++;
    }

    out_b[i] = in_b[i] * norm;
    out_g[i] = in_g[i] * norm;
    out_r[i] = in_r[i] * norm;
  }

  if (!detect_grayscale || color_pixel_count > color_threshold_count) {
    is_grayscale = false;
  }

  // SY --> Komiho Phase10: luma 模型（Anime4K v4 ACNet）只需要亮度通道。
  // 输入 1 通道 Y（BT.601，0-1），推理完再用双三次放大后的原图色度合成回彩色。
  ncnn::Mat luma_normalized;
  if (luma_model) {
    luma_normalized.create(w, h, 1);
    if (luma_normalized.empty()) {
      LOGE("Failed to allocate luma plane for luma-only model");
      return -1;
    }
    float *out_l = luma_normalized.channel(0);
    for (int i = 0; i < w * h; i++) {
      out_l[i] = (0.299f * in_r[i] + 0.587f * in_g[i] + 0.114f * in_b[i]) * norm;
    }
  }
  // SY <--

  if (is_grayscale)
    LOGD("Grayscale image detected, forcing pure grayscale output.");

  // PRE-PROCESS ALPHA CHANNEL (moved to start)
  // We need full alpha map to merge tiles on the fly
  ncnn::Mat alpha_out;
  bool has_alpha = input_has_alpha && inimage.c >= 4;
  const float *alpha_data = nullptr;

  if (has_alpha) {
    ncnn::Mat alpha_in = inimage.channel_range(3, 1);
    ncnn::Option alpha_opt;
    alpha_opt.num_threads = num_threads;
    ncnn::resize_bicubic(alpha_in, alpha_out, target_w, target_h, alpha_opt);
    alpha_data = (const float *)alpha_out.data;
  }

  // Tiling parameters
  const int TILE_SIZE_X = tilesize;
  const int TILE_SIZE_Y = tilesize;

  // Create padded input to handle borders easily
  ncnn::Mat padded_input;
  ncnn::copy_make_border(rgb_normalized, padded_input, prepadding, prepadding,
                         prepadding, prepadding, ncnn::BORDER_REPLICATE, 0.f,
                         net.opt);

  // SY --> Komiho Phase10: luma 模型的单通道 padded 输入（几何与上者完全一致，
  // 保证 tile 偏移、prepadding 补偿逻辑可以原样复用）
  ncnn::Mat padded_luma;
  if (luma_model) {
    ncnn::copy_make_border(luma_normalized, padded_luma, prepadding, prepadding,
                           prepadding, prepadding, ncnn::BORDER_REPLICATE, 0.f,
                           net.opt);
    if (padded_luma.empty()) {
      LOGE("Failed to build padded luma input");
      return -1;
    }
  }
  // SY <--

  // NO huge model_out allocation needed anymore!

  // SY --> Komiho Phase11: 这里只预取一次输入/输出 blob 下标。
  // 注意：**不能**把 Extractor 提到循环外复用。ncnn 的 Mat::create 在形状相同时
  // 会原地复用缓冲（mat.cpp:294 的早退分支），而本函数的 tile 输出是被
  // BoundedTaskPool 的后处理线程按引用持有的（下面 [=, out_tile_captured] 捕获）；
  // 共用同一个 extractor 会让下一块 tile 的推理直接覆写上一块还在被读取的显存，
  // 结果就是随机花块。每块 tile 用一个新 Extractor，各自持有独立的 blob 分配器，
  // 才能真正隔离。这条约束同样适用于 process_gpu —— 那边是靠
  // extractor_slots（一块一个）+ retained_mats 延迟释放来实现的。
  if (net.input_indexes().empty() || net.output_indexes().empty()) {
    LOGE("Model has no inputs or outputs!");
    return -1;
  }
  const int shared_input_index = net.input_indexes()[0];
  const int shared_output_index = net.output_indexes().back();
  // SY <--

  const int xtiles = (w + TILE_SIZE_X - 1) / TILE_SIZE_X;
  const int ytiles = (h + TILE_SIZE_Y - 1) / TILE_SIZE_Y;

  // One fixed post-processing worker overlaps CPU conversion with GPU inference
  // without creating a new thread for every tile. A second worker is enabled
  // only when queue backpressure shows that conversion is the bottleneck.
  BoundedTaskPool postprocess(1, 2);
  int64_t postprocess_wait_us = 0;
  bool second_postprocess_worker = false;
  int guarded_texture_tiles = 0;

  for (int yi = 0; yi < ytiles; yi++) {
    for (int xi = 0; xi < xtiles; xi++) {
      while ((ui_busy_ptr && ui_busy_ptr->load()) ||
             interaction_full_stop(continuous_mode, last_interaction_ms.load())) {
        if (should_abort_ptr && should_abort_ptr->load()) {
          postprocess.wait();
          return -1;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(8));
      }
      if (should_abort_ptr && should_abort_ptr->load()) {
        postprocess.wait();
        return -1;
      }

      int x = xi * TILE_SIZE_X;
      int y = yi * TILE_SIZE_Y;

      int w_tile = std::min(TILE_SIZE_X, w - x);
      int h_tile = std::min(TILE_SIZE_Y, h - y);

      int in_tile_w = w_tile + 2 * prepadding;
      int in_tile_h = h_tile + 2 * prepadding;

      // Extract tile from padded_input
      ncnn::Mat in_tile(in_tile_w, in_tile_h, 3);
      for (int c = 0; c < 3; c++) {
        const float *ptr = padded_input.channel(c).row(y) + x;
        float *outptr = in_tile.channel(c);

        for (int i = 0; i < in_tile_h; i++) {
          memcpy(outptr, ptr, in_tile_w * sizeof(float));
          ptr += padded_input.w;
          outptr += in_tile.w;
        }
      }

      // SY --> Komiho Phase10: luma 模型额外抽一份单通道亮度 tile 喂给网络。
      // 3 通道的 in_tile 仍然保留 —— 它的双三次放大结果用来提供色度。
      ncnn::Mat in_luma_tile;
      if (luma_model) {
        in_luma_tile.create(in_tile_w, in_tile_h, 1);
        const float *ptr = padded_luma.channel(0).row(y) + x;
        float *outptr = in_luma_tile.channel(0);
        for (int i = 0; i < in_tile_h; i++) {
          memcpy(outptr, ptr, in_tile_w * sizeof(float));
          ptr += padded_luma.w;
          outptr += in_luma_tile.w;
        }
      }
      const int min_out_channels = luma_model ? 1 : 3;
      // SY <--

      // SY --> 2026-09-25: 网点色块防护（上游 v1.3.10）。
      // 检测本 tile 是否含大面积周期纹理（漫画网点纸）。命中则把 tile 缩到 3/4、
      // 居中白边后喂给网络，输出时再用 restored_output_coordinate 重采样回原尺寸，
      // 规避超分模型在网点处产生的色块。仅对单色 + 网点密集的页触发（由 JNI 侧
      // 的 enable_periodic_texture_guard + 单色判定共同把关）。
      int risky_cells = 0;
      int total_cells = 0;
      const bool guard_tile = enable_periodic_texture_guard &&
          periodic_texture_guard::occupies_too_much(
              w_tile, h_tile,
              [&](int sample_x, int sample_y) {
                const float value = in_tile.channel(0)
                                        .row(prepadding + sample_y)
                                        [prepadding + sample_x];
                return static_cast<int>(value * 255.0f + 0.5f);
              },
              &risky_cells, &total_cells);
      int guard_border_x = 0;
      int guard_border_y = 0;
      if (guard_tile) {
        const int scaled_width =
            in_tile_w * periodic_texture_guard::kShrinkNumerator /
            periodic_texture_guard::kShrinkDenominator;
        const int scaled_height =
            in_tile_h * periodic_texture_guard::kShrinkNumerator /
            periodic_texture_guard::kShrinkDenominator;
        guard_border_x = (in_tile_w - scaled_width) / 2;
        guard_border_y = (in_tile_h - scaled_height) / 2;
        // 3 通道 RGB tile：双线性缩采样后居中白边（0-1 归一化下的「白」= 1.0）
        ncnn::Mat scaled_tile;
        ncnn::resize_bilinear(in_tile, scaled_tile, scaled_width, scaled_height,
                              net.opt);
        ncnn::Mat guarded_input(in_tile_w, in_tile_h, 3);
        guarded_input.fill(1.0f);
        for (int c = 0; c < 3; ++c) {
          const ncnn::Mat source = scaled_tile.channel(c);
          ncnn::Mat destination = guarded_input.channel(c);
          for (int row = 0; row < scaled_height; ++row) {
            memcpy(destination.row(guard_border_y + row) + guard_border_x,
                   source.row(row), scaled_width * sizeof(float));
          }
        }
        in_tile = guarded_input;
        // 单通道 luma tile：同步缩采样 + 居中白边（白 = 亮度 1.0）
        if (luma_model) {
          ncnn::Mat scaled_luma;
          ncnn::resize_bilinear(in_luma_tile, scaled_luma, scaled_width,
                                scaled_height, net.opt);
          ncnn::Mat guarded_luma(in_tile_w, in_tile_h, 1);
          guarded_luma.fill(1.0f);
          {
            const ncnn::Mat source = scaled_luma.channel(0);
            ncnn::Mat destination = guarded_luma.channel(0);
            for (int row = 0; row < scaled_height; ++row) {
              memcpy(destination.row(guard_border_y + row) + guard_border_x,
                     source.row(row), scaled_width * sizeof(float));
            }
          }
          in_luma_tile = guarded_luma;
        }
        ++guarded_texture_tiles;
        LOGD("Guarding periodic texture tile %d,%d (%d/%d cells)", xi, yi,
             risky_cells, total_cells);
      }
      // SY <--

      // Run inference on tile (GPU WORK)
      ncnn::Mat out_tile;
      {
        // 每块 tile 必须新建 extractor（隔离 blob 显存），理由见循环外的注释
        ncnn::Extractor ex = net.create_extractor();
        ex.set_light_mode(true);
        // SY --> 单通道模型喂亮度，其余喂 RGB
        ex.input(shared_input_index, luma_model ? in_luma_tile : in_tile);
        // SY <--
        ex.extract(shared_output_index, out_tile);
      }

      if (out_tile.empty() || out_tile.c < min_out_channels) {
        LOGE("Inference tile failed or invalid channels (c=%d) at %d,%d",
             out_tile.c, xi, yi);
        continue;
      }

      // Debug logging for first tile to diagnose x3/x4 issues
      if (xi == 0 && yi == 0) {
        int expected_w = (std::min(TILE_SIZE_X, w) + 2 * prepadding) * scale;
        int expected_h = (std::min(TILE_SIZE_Y, h) + 2 * prepadding) * scale;
        LOGD("Tile debug: scale=%d, prepadding=%d", scale, prepadding);
        LOGD("  in_tile: %dx%dx%d", in_tile.w, in_tile.h, in_tile.c);
        LOGD("  out_tile: %dx%dx%d (expected ~%dx%d)", out_tile.w, out_tile.h,
             out_tile.c, expected_w, expected_h);
      }

      // Update progress IMMEDIATELY after GPU inference to show activity
      if (progress_ptr) {
        int p =
            (xi + yi * xtiles) * 99 / (xtiles * ytiles) + 1; // Slight offset
        progress_ptr->store(p);
      }

      // Capture by value [=] ensures all local variables needed for conversion
      // are copied. ncnn::Mat out_tile is ref-counted, so copy is fast.
      const int64_t queue_wait_us =
          postprocess.submit([=, out_tile_captured = out_tile]() {
        int out_x = x * scale;
        int out_y = y * scale;
        int out_w_tile = w_tile * scale;
        int out_h_tile = h_tile * scale;
        int out_pad = prepadding * scale;

        // Calculate source offset based on actual model output
        // Models typically output: input_tile * scale (without additional
        // padding) For full tiles, this equals (tilesize + 2*prepadding) *
        // scale For edge tiles, the output may be smaller
        int expected_out_w = in_tile_w * scale;
        int expected_out_h = in_tile_h * scale;

        int src_offset_x, src_offset_y;

        if (out_tile_captured.w >= expected_out_w &&
            out_tile_captured.h >= expected_out_h) {
          // Model output includes padding - use standard offset
          src_offset_x = out_pad;
          src_offset_y = out_pad;
        } else if (out_tile_captured.w >= out_w_tile &&
                   out_tile_captured.h >= out_h_tile) {
          // Model output is content-only (stripped padding) - calculate
          // center offset
          src_offset_x = (out_tile_captured.w - out_w_tile) / 2;
          src_offset_y = (out_tile_captured.h - out_h_tile) / 2;
        } else {
          // Output smaller than content - use from beginning (edge case)
          src_offset_x = 0;
          src_offset_y = 0;
        }

        // SY --> Komiho Phase10: luma 模型输出只有亮度，需要自己把颜色补回来。
        // 做法：对同一块输入 tile 做一次双三次放大（与模型同样的输出尺寸），
        // 用它的色度（Cb/Cr）配上模型给出的亮度 Y'，再转回 RGB。
        // 色度本身是低频信息，双三次放大已经绰绰有余；tile 两侧各带了
        // prepadding 的上下文，所以相邻 tile 在重叠区得到的色度几乎一致。
        const float *tile_b = nullptr;
        const float *tile_g = nullptr;
        const float *tile_r = nullptr;
        const float *tile_l = nullptr;
        ncnn::Mat chroma_bicubic;
        const float *bic_b = nullptr;
        const float *bic_g = nullptr;
        const float *bic_r = nullptr;
        if (luma_model) {
          tile_l = out_tile_captured.channel(0);
          ncnn::Option resize_opt;
          resize_opt.num_threads = num_threads;
          resize_opt.use_vulkan_compute = false;
          resize_opt.use_packing_layout = false;
          ncnn::resize_bicubic(in_tile, chroma_bicubic, out_tile_captured.w,
                               out_tile_captured.h, resize_opt);
          if (!chroma_bicubic.empty() && chroma_bicubic.c >= 3) {
            // in_tile 的通道顺序是 B,G,R（见 process() 开头的归一化）
            bic_b = chroma_bicubic.channel(0);
            bic_g = chroma_bicubic.channel(1);
            bic_r = chroma_bicubic.channel(2);
          } else {
            LOGE("Failed to bicubic-upscale chroma for luma model tile");
            tile_l = nullptr;
          }
        } else {
          tile_b = out_tile_captured.channel(0);
          tile_g = out_tile_captured.channel(1);
          tile_r = out_tile_captured.channel(2);
        }
        // SY <--

        // Iterate over valid output rows for this tile
        for (int i = 0; i < out_h_tile; i++) {
          int dst_y = out_y + i;
          int src_y = src_offset_y + i;

          if (dst_y >= target_h)
            break;
          if (!guard_tile && src_y >= out_tile_captured.h)
            break;

          unsigned char *dst_row =
              (unsigned char *)out_pixels + dst_y * out_stride;

          // Pointers into the tile data
          int src_row_offset = src_y * out_tile_captured.w;
          // SY --> 色度缺失时（双三次放大失败）本行干脆不写，避免整块花屏
          if (luma_model && (!tile_l || !bic_b))
            continue;
          // SY <--
          const float *ptr_b = nullptr;
          const float *ptr_g = nullptr;
          const float *ptr_r = nullptr;
          const float *ptr_l = nullptr;
          if (luma_model) {
            ptr_l = tile_l + src_row_offset + src_offset_x;
            ptr_b = bic_b + src_row_offset + src_offset_x;
            ptr_g = bic_g + src_row_offset + src_offset_x;
            ptr_r = bic_r + src_row_offset + src_offset_x;
          } else {
            ptr_b = tile_b + src_row_offset + src_offset_x;
            ptr_g = tile_g + src_row_offset + src_offset_x;
            ptr_r = tile_r + src_row_offset + src_offset_x;
          }

          // Pointer into global alpha data
          const float *ptr_a = nullptr;
          if (alpha_data) {
            ptr_a = alpha_data + dst_y * target_w + out_x;
          }

          int copy_w = out_w_tile;
          if (out_x + copy_w > target_w)
            copy_w = target_w - out_x;
          if (!guard_tile && src_offset_x + copy_w > out_tile_captured.w)
            copy_w = out_tile_captured.w - src_offset_x;

          int j = 0;
          // SY --> 单通道模型走标量 YCbCr 合成路径（NEON 快路径只覆盖 3 通道直出）
          if (luma_model) {
            for (; j < copy_w; j++) {
              float y, b0, g0, r0;
              if (guard_tile) {
                // SY --> 2026-09-25: 网点防护命中时，目标像素映射回 3/4 缩采样
                // 输出空间做双线性重采样（与上面的 guarded_input 对称）。
                const float sample_x =
                    periodic_texture_guard::restored_output_coordinate(
                        j, guard_border_x, prepadding, scale);
                const float sample_y =
                    periodic_texture_guard::restored_output_coordinate(
                        i, guard_border_y, prepadding, scale);
                const int x0 = std::clamp(static_cast<int>(std::floor(sample_x)),
                                          0, out_tile_captured.w - 1);
                const int y0 = std::clamp(static_cast<int>(std::floor(sample_y)),
                                          0, out_tile_captured.h - 1);
                const int x1 = std::min(out_tile_captured.w - 1, x0 + 1);
                const int y1 = std::min(out_tile_captured.h - 1, y0 + 1);
                const float fx = sample_x - std::floor(sample_x);
                const float fy = sample_y - std::floor(sample_y);
                const auto sample = [&](const float *channel) {
                  const float top =
                      channel[y0 * out_tile_captured.w + x0] * (1.0f - fx) +
                      channel[y0 * out_tile_captured.w + x1] * fx;
                  const float bottom =
                      channel[y1 * out_tile_captured.w + x0] * (1.0f - fx) +
                      channel[y1 * out_tile_captured.w + x1] * fx;
                  return (top * (1.0f - fy) + bottom * fy);
                };
                y = sample(tile_l);
                b0 = sample(bic_b);
                g0 = sample(bic_g);
                r0 = sample(bic_r);
              } else {
                y = ptr_l[j];
                b0 = ptr_b[j];
                g0 = ptr_g[j];
                r0 = ptr_r[j];
              }
              const float y_base = 0.299f * r0 + 0.587f * g0 + 0.114f * b0;
              // BT.601 色度差（B'-Y' / R'-Y'）；逆变换里
              // G' = Y' - 0.7141*Cr - 0.3441*Cb（等价于 (Y'-0.299R'-0.114B')/0.587）
              const float cb = (b0 - y_base) * 0.564334f;  // 1/1.772
              const float cr = (r0 - y_base) * 0.713267f;  // 1/1.402
              const float r = y + 1.402f * cr;
              const float b = y + 1.772f * cb;
              const float g = y - 0.7141f * cr - 0.3441f * cb;

              const int dst_idx = (out_x + j) * 4;
              dst_row[dst_idx + 0] = (unsigned char)std::max(
                  0.0f, std::min(255.0f, r * 255.0f));
              dst_row[dst_idx + 1] = (unsigned char)std::max(
                  0.0f, std::min(255.0f, g * 255.0f));
              dst_row[dst_idx + 2] = (unsigned char)std::max(
                  0.0f, std::min(255.0f, b * 255.0f));
              float a = ptr_a ? ptr_a[j] : 255.0f;
              dst_row[dst_idx + 3] =
                  (unsigned char)std::max(0.0f, std::min(255.0f, a));
            }
          } else {
          // SY <--
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
          const float32x4_t scale_255 = vdupq_n_f32(255.f);
          const float32x4_t gray_scale = vdupq_n_f32(1.f / 3.f);
          for (; !guard_tile && j + 8 <= copy_w; j += 8) {
            float32x4_t r0 = vmulq_f32(vld1q_f32(ptr_r + j), scale_255);
            float32x4_t r1 = vmulq_f32(vld1q_f32(ptr_r + j + 4), scale_255);
            float32x4_t g0 = vmulq_f32(vld1q_f32(ptr_g + j), scale_255);
            float32x4_t g1 = vmulq_f32(vld1q_f32(ptr_g + j + 4), scale_255);
            float32x4_t b0 = vmulq_f32(vld1q_f32(ptr_b + j), scale_255);
            float32x4_t b1 = vmulq_f32(vld1q_f32(ptr_b + j + 4), scale_255);

            if (is_grayscale) {
              r0 = vmulq_f32(vaddq_f32(vaddq_f32(r0, g0), b0), gray_scale);
              r1 = vmulq_f32(vaddq_f32(vaddq_f32(r1, g1), b1), gray_scale);
              g0 = b0 = r0;
              g1 = b1 = r1;
            }

            uint8x8x4_t rgba;
            rgba.val[0] = floats_to_u8(r0, r1);
            rgba.val[1] = floats_to_u8(g0, g1);
            rgba.val[2] = floats_to_u8(b0, b1);
            if (ptr_a) {
              rgba.val[3] = floats_to_u8(vld1q_f32(ptr_a + j),
                                          vld1q_f32(ptr_a + j + 4));
            } else {
              rgba.val[3] = vdup_n_u8(255);
            }
            vst4_u8(dst_row + (out_x + j) * 4, rgba);
          }
#endif
          for (; j < copy_w; j++) {
            float r, g, b;
            if (!guard_tile) {
              r = ptr_r[j] * 255.0f;
              g = ptr_g[j] * 255.0f;
              b = ptr_b[j] * 255.0f;
            } else {
              // SY --> 2026-09-25: 网点防护命中 → 回 3/4 缩采样空间双线性重采样
              const float sample_x =
                  periodic_texture_guard::restored_output_coordinate(
                      j, guard_border_x, prepadding, scale);
              const float sample_y =
                  periodic_texture_guard::restored_output_coordinate(
                      i, guard_border_y, prepadding, scale);
              const int x0 = std::clamp(static_cast<int>(std::floor(sample_x)),
                                        0, out_tile_captured.w - 1);
              const int y0 = std::clamp(static_cast<int>(std::floor(sample_y)),
                                        0, out_tile_captured.h - 1);
              const int x1 = std::min(out_tile_captured.w - 1, x0 + 1);
              const int y1 = std::min(out_tile_captured.h - 1, y0 + 1);
              const float fx = sample_x - std::floor(sample_x);
              const float fy = sample_y - std::floor(sample_y);
              const auto sample = [&](const float *channel) {
                const float top =
                    channel[y0 * out_tile_captured.w + x0] * (1.0f - fx) +
                    channel[y0 * out_tile_captured.w + x1] * fx;
                const float bottom =
                    channel[y1 * out_tile_captured.w + x0] * (1.0f - fx) +
                    channel[y1 * out_tile_captured.w + x1] * fx;
                return (top * (1.0f - fy) + bottom * fy) * 255.0f;
              };
              b = sample(tile_b);
              g = sample(tile_g);
              r = sample(tile_r);
            }

            if (is_grayscale) {
              float gray = (r + g + b) * 0.333333f;
              r = g = b = gray;
            }

            int dst_x = out_x + j;
            int dst_idx = dst_x * 4;

            dst_row[dst_idx + 0] =
                (unsigned char)std::max(0.0f, std::min(255.0f, r));
            dst_row[dst_idx + 1] =
                (unsigned char)std::max(0.0f, std::min(255.0f, g));
            dst_row[dst_idx + 2] =
                (unsigned char)std::max(0.0f, std::min(255.0f, b));
            float a = ptr_a ? ptr_a[j] : 255.0f;
            dst_row[dst_idx + 3] =
                (unsigned char)std::max(0.0f, std::min(255.0f, a));
          }
          // SY --> luma 分支收尾
          }
          // SY <--
        }

        // Update progress after this tile is fully written to UI
        if (progress_ptr) {
          int p = (xi + yi * xtiles + 1) * 99 / (xtiles * ytiles);
          progress_ptr->store(p);
        }
      });

      if (!second_postprocess_worker && queue_wait_us >= 1000) {
        postprocess_wait_us += queue_wait_us;
        if (postprocess_wait_us >= 4000 && postprocess.add_second_worker()) {
          second_postprocess_worker = true;
          LOGD("Staged postprocess expanded to 2 workers after %lldus queue "
               "wait",
               static_cast<long long>(postprocess_wait_us));
        }
      }

      // Check for abort signal
      if (should_abort_ptr && should_abort_ptr->load()) {
        LOGD("Waifu2x process aborted by signal");
        postprocess.wait();
        return -1;
      }

      // Skip sleep for the last few tiles
      bool is_near_end = (xi + yi * xtiles) > (xtiles * ytiles - 5);
      // SY --> Komiho Phase12: 与 process_gpu 同源的让出下限（连续模式 + 近期交互）。
      const int64_t now_ms_staged = std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::steady_clock::now().time_since_epoch()).count();
      const bool recent_interaction_staged =
          continuous_mode &&
          (now_ms_staged - last_interaction_ms.load()) < recent_interaction_window_ms();
      const int effective_sleep_staged =
          tile_sleep_ms > 0
              ? tile_sleep_ms
              : (recent_interaction_staged ? scroll_yield_ms_for(0) : 0);
      if (effective_sleep_staged > 0 && !is_near_end) {
        std::this_thread::sleep_for(std::chrono::milliseconds(effective_sleep_staged));
      }
      // SY <--
    }
  }

  postprocess.wait();

  if (guarded_texture_tiles > 0) {
    LOGD("Periodic texture occupancy guard applied to %d/%d tiles",
         guarded_texture_tiles, xtiles * ytiles);
  }

  if (progress_ptr) {
    progress_ptr->store(100);
  }

  const auto t_end = std::chrono::steady_clock::now();
  const int64_t elapsed_ms =
      std::chrono::duration_cast<std::chrono::milliseconds>(t_end - t_start)
          .count();
  LOGD("Processing complete: %dx%d (staged path) luma=%d elapsed=%lldms",
       target_w, target_h, luma_model ? 1 : 0, (long long)elapsed_ms);

  return 0;
}
