"""
主机侧数值校验：把 waifu2x_fused_{preproc,postproc}_luma.comp 的数学在 numpy 里复现，
用可判定的构造输入去验证「坐标映射 / 插值核 / YCbCr 合成 / 通道顺序」四件事。

不依赖 GPU，只依赖 numpy（+ 可选 onnxruntime 跑端到端）。

判定依据（都与实现无关的解析事实）：
  T1 线性斜坡 —— 三次插值能精确重现线性函数，所以 2x 放大后的像素值必须等于
     在 (dx+0.5)/scale-0.5 处取到的原值。映射若差一格，这里立刻暴露。
  T2 常量图 —— 任意位置都必须等于常量，边界也不能有偏差。
  T3 恒等合成 —— 把 Y' 用该像素色度自身的亮度 y_base 代入，输出必须精确等于
     双三次放大后的原 RGB。这同时验证了 cb/cr 的系数、逆变换系数与通道顺序。
"""
import sys

import numpy as np

A = -0.75  # ncnn interp.cpp:interpolate_cubic


def cubic_weights(f):
    """照 ncnn interpolate_cubic(A=-0.75) 生成 4 个权重（tap 顺序 sx-1..sx+2）。"""
    fx0, fx1, fx2 = f + 1.0, f, 1.0 - f
    c0 = A * fx0**3 - 5 * A * fx0**2 + 8 * A * fx0 - 4 * A
    c1 = (A + 2) * fx1**3 - (A + 3) * fx1**2 + 1.0
    c2 = (A + 2) * fx2**3 - (A + 3) * fx2**2 + 1.0
    return np.array([c0, c1, c2, 1.0 - c0 - c1 - c2], dtype=np.float64)


def sample_bicubic(src, x_f, y_f):
    """src: (h,w,4) float。x_f/y_f 是连续坐标（可越界，按边缘复制）。"""
    h, w = src.shape[:2]
    ix, iy = int(np.floor(x_f)), int(np.floor(y_f))
    wx = cubic_weights(x_f - ix)
    wy = cubic_weights(y_f - iy)
    acc = np.zeros(4, dtype=np.float64)
    for j in range(4):
        yy = min(max(iy - 1 + j, 0), h - 1)
        row = np.zeros(4, dtype=np.float64)
        for i in range(4):
            xx = min(max(ix - 1 + i, 0), w - 1)
            row += wx[i] * src[yy, xx]
        acc += wy[j] * row
    return np.clip(acc, 0.0, 255.0)


def preproc_luma(src):
    """shader: (0.299R+0.587G+0.114B)/255，R=byte0/G=byte1/B=byte2。"""
    r = src[..., 0].astype(np.float64)
    g = src[..., 1].astype(np.float64)
    b = src[..., 2].astype(np.float64)
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0


def postproc_luma(y_out, src, scale=2, alpha_on=False):
    """shader: Y' + 全图双三次色度 -> 打包 RGBA8。y_out: (H,W) float 0..1。"""
    H, W = y_out.shape
    out = np.zeros((H, W, 4), dtype=np.uint8)
    for oy in range(H):
        for ox in range(W):
            y = float(y_out[oy, ox])
            x_f = (ox + 0.5) / scale - 0.5
            y_f = (oy + 0.5) / scale - 0.5
            ch = sample_bicubic(src, x_f, y_f)
            r0, g0, b0 = ch[0] / 255.0, ch[1] / 255.0, ch[2] / 255.0
            y_base = 0.299 * r0 + 0.587 * g0 + 0.114 * b0
            cb = (b0 - y_base) * 0.564334
            cr = (r0 - y_base) * 0.713267
            r = y + 1.402 * cr
            b = y + 1.772 * cb
            g = y - 0.7141 * cr - 0.3441 * cb
            rgb = np.clip(np.floor(np.array([r, g, b]) * 255.0 + 0.5), 0, 255)
            a = np.clip(np.floor(ch[3] + 0.5), 0, 255) if alpha_on else 255.0
            out[oy, ox] = [rgb[0], rgb[1], rgb[2], a]
    return out


def make_test_image(h=64, w=64):
    """彩色 + 水平 R 斜坡 + 垂直 G 斜坡 + 对角 B，专门用来暴露通道换位与相位错误。"""
    yy, xx = np.mgrid[0:h, 0:w]
    img = np.zeros((h, w, 4), dtype=np.float64)
    img[..., 0] = 40 + 175 * xx / (w - 1)          # R 水平斜坡
    img[..., 1] = 30 + 200 * yy / (h - 1)          # G 垂直斜坡
    img[..., 2] = 60 + 120 * ((xx + yy) / (w + h - 2))  # B 对角
    img[..., 3] = 255
    return img


def t1_ramp():
    """线性斜坡：2x 双三次必须精确重现，映射若差一格立即失败。"""
    h = w = 32
    src = np.zeros((h, w, 4), dtype=np.float64)
    src[..., 0] = np.arange(w)[None, :] * (255.0 / (w - 1))
    src[..., 1] = 128.0
    src[..., 2] = 200.0
    src[..., 3] = 255
    out_h, out_w = h * 2, w * 2
    err = 0.0
    for oy in range(2, out_h - 2):      # 跳过最外圈（边缘复制与 ncnn 边界重排不同）
        for ox in range(2, out_w - 2):
            x_f = (ox + 0.5) / 2 - 0.5
            want = max(0.0, min(w - 1.0, x_f)) * (255.0 / (w - 1))
            got = sample_bicubic(src, x_f, (oy + 0.5) / 2 - 0.5)[0]
            err = max(err, abs(got - want))
    print(f"T1 斜坡重现 max|err| = {err:.4f} (单位 0-255)")
    return err < 0.01


def t2_constant():
    src = np.full((24, 24, 4), [37.0, 191.0, 83.0, 255.0], dtype=np.float64)
    y = np.full((48, 48), preproc_luma(src)[0, 0], dtype=np.float64)
    out = postproc_luma(y, src)
    d = np.abs(out[..., :3].astype(np.float64) - np.array([37, 191, 83])).max()
    print(f"T2 常量图最大偏差 = {d} (应 <= 1)")
    return d <= 1


def t3_identity_composition():
    """Y' 取该像素色度自身的亮度时，输出必须精确等于双三次放大后的原 RGB。"""
    src = make_test_image(40, 40)
    y_base = np.zeros((80, 80), dtype=np.float64)
    for oy in range(80):
        for ox in range(80):
            ch = sample_bicubic(src, (ox + 0.5) / 2 - 0.5, (oy + 0.5) / 2 - 0.5)
            r0, g0, b0 = ch[0] / 255.0, ch[1] / 255.0, ch[2] / 255.0
            y_base[oy, ox] = 0.299 * r0 + 0.587 * g0 + 0.114 * b0
    out = postproc_luma(y_base, src)
    ref = np.zeros((80, 80, 3), dtype=np.uint8)
    for oy in range(80):
        for ox in range(80):
            ch = sample_bicubic(src, (ox + 0.5) / 2 - 0.5, (oy + 0.5) / 2 - 0.5)
            ref[oy, ox] = np.clip(np.floor(ch[:3] + 0.5), 0, 255)
    d = np.abs(out[..., :3].astype(int) - ref.astype(int)).max()
    print(f"T3 恒等合成最大偏差 = {d} (应 <= 1，>1 说明系数/通道顺序错了)")
    return d <= 1


def t4_real_model(onnx_path):
    """端到端：真模型跑 Y'，与 waifu2x.cpp 的 CPU staged 公式对比。"""
    try:
        import onnxruntime as ort
    except ImportError:
        print("T4 跳过：没有 onnxruntime")
        return True
    src = make_test_image(48, 48)
    luma = preproc_luma(src).astype(np.float32)[None, None]
    sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    y_out = sess.run(None, {sess.get_inputs()[0].name: luma})[0][0, 0]
    print(f"T4 网络输入 {luma.shape} -> 输出 {y_out.shape}")

    gpu = postproc_luma(y_out.astype(np.float64), src)

    # CPU staged 路径（waifu2x.cpp luma 分支）逐像素复算
    cpu = np.zeros_like(gpu)
    for oy in range(y_out.shape[0]):
        for ox in range(y_out.shape[1]):
            y = float(y_out[oy, ox])
            ch = sample_bicubic(src, (ox + 0.5) / 2 - 0.5, (oy + 0.5) / 2 - 0.5)
            r0, g0, b0 = ch[0] / 255.0, ch[1] / 255.0, ch[2] / 255.0
            y_base = 0.299 * r0 + 0.587 * g0 + 0.114 * b0
            cb = (b0 - y_base) * 0.564334
            cr = (r0 - y_base) * 0.713267
            vals = [y + 1.402 * cr, y - 0.7141 * cr - 0.3441 * cb, y + 1.772 * cb]
            cpu[oy, ox, :3] = np.clip(np.floor(np.array(vals) * 255.0), 0, 255)
    diff = np.abs(gpu[..., :3].astype(int) - cpu[..., :3].astype(int))
    print(f"T4 GPU 仿真 vs CPU staged：max={diff.max()} mean={diff.mean():.3f} (单位 0-255)")
    print(f"   输出范围 GPU [{gpu[...,:3].min()},{gpu[...,:3].max()}]  原图 [0,255]")
    return diff.max() <= 1


if __name__ == "__main__":
    onnx = sys.argv[1] if len(sys.argv) > 1 else "G:/code/venera-research/models/anime4k_acnet.onnx"
    ok = [t1_ramp(), t2_constant(), t3_identity_composition(), t4_real_model(onnx)]
    print("\nRESULT:", "ALL PASS" if all(ok) else "FAILED " + str(ok))
