"""生成 Windows 客户端的应用图标 assets/clipbridge.ico。

图案与运行时绘制的托盘图标（UI/TrayIcon.cs）保持一致：
  圆角矩形底板（连接状态的绿色）+ 白色"夹子" + 两条白色内容线。
按 32 倍超采样绘制再缩小，保证边缘平滑；输出多尺寸 ico，Windows 会按 DPI 自己挑。

可重复执行：改配色 / 形状改这个脚本，不要手改 ico 二进制。
"""
import os
from PIL import Image, ImageDraw

ACCENT = (58, 122, 92, 255)  # 与 TrayIcon 的 Color.FromArgb(58, 122, 92) 一致
WHITE = (255, 255, 255, 255)
SS = 32  # 超采样倍率

OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "assets", "clipbridge.ico",
)


def px(v: float) -> float:
    """把 32x32 设计稿上的坐标换算到当前画布"""
    return v * SS


def render() -> Image.Image:
    """按 32x32 的设计稿绘制，画布是 32*SS 见方"""
    side = 32 * SS
    img = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 底板：RoundedRect(3,3,26,26) radius=7
    d.rounded_rectangle(
        [px(3), px(3), px(3 + 26), px(3 + 26)], radius=px(7), fill=ACCENT
    )

    # 夹子：RoundedRect(11,1,10,8) radius=2
    d.rounded_rectangle(
        [px(11), px(1), px(11 + 10), px(1 + 8)], radius=px(2), fill=WHITE
    )

    # 两条内容线，线宽 2.4、圆头
    w = px(2.4)
    for y, x2 in ((15, 22), (21, 18)):
        d.line(
            [px(10), px(y), px(x2), px(y)],
            fill=WHITE,
            width=int(round(w)),
        )
        r = w / 2
        for x in (10, x2):
            d.ellipse([px(x) - r, px(y) - r, px(x) + r, px(y) + r], fill=WHITE)

    return img


def main():
    big = render()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)

    sizes = [16, 24, 32, 48, 64, 128, 256]
    # Pillow 的 ICO 保存：给一张大图 + sizes，它会自己逐级缩放
    big.resize((256, 256), Image.LANCZOS).save(
        OUT, format="ICO", sizes=[(s, s) for s in sizes]
    )

    print(f"已生成 {OUT}")
    print(f"  尺寸: {', '.join(f'{s}x{s}' for s in sizes)}")
    print(f"  大小: {os.path.getsize(OUT)} bytes")


if __name__ == "__main__":
    main()
