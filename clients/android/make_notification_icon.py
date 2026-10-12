"""生成通知栏小图标（白色剪影 PNG，多密度）。

## 为什么不用 VectorDrawable

通知小图标在小米 / HyperOS 上有已知的兼容与缓存问题 —— `setSmallIcon` 指定的矢量图标
经常不生效、一直显示成 `android:icon`（彩色的应用图标），重启手机才可能刷新。
位图是各家 ROM 都走通的老路；另外这里把资源名从 `ic_notification` 换成
`ic_stat_clipbridge`，以绕开可能按资源名做的图标缓存。

## 为什么是"实心 + 挖空"，而不是"描边 + 细线"

24dp 下描边加两条细线会糊成一团。改成实心剪贴板剪影、内容用透明挖空之后，
即使缩到 24px 也能一眼看出"剪贴板里装着内容"，这也是系统通知图标最常见的画法。

## 几何

24x24 坐标系，与 app 图标同一套视觉语言：
  剪贴板主体（实心圆角矩形）→ 顶部夹子（挖一圈空隙后实心）
  → 挖空一条长横线（文本）→ 挖空右下方一个小方框（图片）。

内容只放"一条线 + 一个方框"，而不是 app 图标里的两条线：
24px 下两条线会和方框糊成一团，减到一条之后横线和方框都还认得出来
（三种方案在 24px 放大对比里实地比过）。
"""
import os
from PIL import Image, ImageDraw

# 目标密度 → 边长（dp 24 的对应像素）
DENSITIES = {
    "mdpi": 24,
    "hdpi": 36,
    "xhdpi": 48,
    "xxhdpi": 72,
    "xxxhdpi": 96,
}

SS = 32  # 超采样倍数，降采样后边缘才干净

WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)


def draw_master() -> Image.Image:
    """在 24 单位坐标系下作画（乘上 SS 倍超采样）"""
    side = 24 * SS
    img = Image.new("RGBA", (side, side), CLEAR)
    d = ImageDraw.Draw(img)

    def s(v: float) -> float:
        return v * SS

    def rrect(box, radius, color):
        d.rounded_rectangle(
            [s(box[0]), s(box[1]), s(box[2]), s(box[3])],
            radius=s(radius),
            fill=color,
        )

    # 1) 剪贴板主体（实心）
    rrect((4.4, 3.4, 19.6, 20.6), 2.4, WHITE)

    # 2) 顶部夹子：先在主体上挖出一圈空隙，再放上夹子本身。
    #    有条缝，小尺寸下夹子才不会和板身糊成一整块。
    rrect((7.9, 0.9, 16.1, 5.5), 1.5, CLEAR)
    rrect((9.3, 2.1, 14.7, 5.0), 1.0, WHITE)

    # 3) 挖空内容：一条长横线（文本）+ 右下方一个小方框（图片）
    rrect((7.2, 8.4, 16.8, 10.2), 0.9, CLEAR)
    rrect((13.2, 12.2, 17.2, 16.2), 1.0, CLEAR)

    return img


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.join(here, "app", "src", "main", "res")
    master = draw_master()

    for density, px in DENSITIES.items():
        out_dir = os.path.join(root, f"drawable-{density}")
        os.makedirs(out_dir, exist_ok=True)
        img = master.resize((px, px), Image.LANCZOS)
        path = os.path.join(out_dir, "ic_stat_clipbridge.png")
        img.save(path, "PNG", optimize=True)
        print(f"  {density:<8} {px}x{px}  →  res/drawable-{density}/ic_stat_clipbridge.png")

    # 存一份放大稿，方便肉眼确认形状
    master.resize((240, 240), Image.LANCZOS).save(os.path.join(here, "_icon_preview_240.png"))
    print(f"  预览 240x240 →  {os.path.join(here, '_icon_preview_240.png')}")


if __name__ == "__main__":
    main()
