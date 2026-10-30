"""把一张方图生成 Android 启动器图标（圆角 / 圆形 / 自适应图标）。

一次性脚本，不参与构建。用法：
    python make_icons.py <源图片> <res 目录>
"""
import os
import sys

from PIL import Image, ImageDraw

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
# 自适应图标前景层是 108dp 画布
FG_SIZES = {k: int(round(v * 108 / 48)) for k, v in SIZES.items()}
SS = 4  # supersampling，最后缩回去拿抗锯齿


def square(img: Image.Image) -> Image.Image:
    w, h = img.size
    side = min(w, h)
    return img.crop(((w - side) // 2, (h - side) // 2,
                     (w - side) // 2 + side, (h - side) // 2 + side))


def rounded_mask(size: int, radius_ratio: float) -> Image.Image:
    big = size * SS
    m = Image.new("L", (big, big), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        (0, 0, big - 1, big - 1), radius=int(big * radius_ratio), fill=255
    )
    return m.resize((size, size), Image.LANCZOS)


def circle_mask(size: int) -> Image.Image:
    big = size * SS
    m = Image.new("L", (big, big), 0)
    ImageDraw.Draw(m).ellipse((0, 0, big - 1, big - 1), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main() -> None:
    src, res = sys.argv[1], sys.argv[2]
    img = square(Image.open(src).convert("RGB"))
    # 背景色取四个角的中位数：图片边缘就是它的"底色"
    corner = img.getpixel((2, 2))
    print("corner color:", "#%02X%02X%02X" % corner)

    for folder, size in SIZES.items():
        out = os.path.join(res, folder)
        os.makedirs(out, exist_ok=True)

        def compose(ratio: float) -> Image.Image:
            """先填底色、再把图缩到 ratio 居中贴进去。

            图案本身贴到画布边上，直接套遮罩会把白色圆环/齿轮齿切掉。
            """
            canvas = Image.new("RGB", (size, size), corner)
            inner = max(1, int(round(size * ratio)))
            canvas.paste(img.resize((inner, inner), Image.LANCZOS),
                         ((size - inner) // 2, (size - inner) // 2))
            return canvas

        rounded = compose(0.90).convert("RGBA")
        rounded.putalpha(rounded_mask(size, 0.22))
        rounded.save(os.path.join(out, "ic_launcher.png"))

        # 圆形图标四面都要切，齿轮齿比白环还外一圈，所以留更多边
        circle = compose(0.74).convert("RGBA")
        circle.putalpha(circle_mask(size))
        circle.save(os.path.join(out, "ic_launcher_round.png"))

        # 自适应前景：只留白色图案、底透明，背景色交给 background 层。
        # 原来是把整张图（含蓝底）贴进去，底色和画布填充色有细微色差，
        # 缩略图上看得出一个方框。改成"亮度当 alpha"的白剪影就干净了。
        fg_size = FG_SIZES[folder]
        inner = max(1, int(round(fg_size * 0.64)))
        scaled = img.resize((inner, inner), Image.LANCZOS)
        gray = scaled.convert("L")
        # 蓝底亮度约 60、白图案约 250：90 以下全透明，200 以上全不透明
        alpha = gray.point(lambda v: 0 if v < 90 else min(255, (v - 90) * 255 // 110))
        fg = Image.new("RGBA", (fg_size, fg_size), (255, 255, 255, 0))
        white = Image.new("RGBA", (inner, inner), (255, 255, 255, 255))
        white.putalpha(alpha)
        fg.paste(white, ((fg_size - inner) // 2, (fg_size - inner) // 2))
        fg.save(os.path.join(out, "ic_launcher_foreground.png"))

    anydpi = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, name), "w", encoding="utf-8", newline="\n") as f:
            f.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_bg" />\n'
                '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
                '</adaptive-icon>\n'
            )

    values = os.path.join(res, "values")
    os.makedirs(values, exist_ok=True)
    with open(os.path.join(values, "ic_launcher_bg.xml"), "w",
              encoding="utf-8", newline="\n") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<resources>\n'
            '    <color name="ic_launcher_background">#%02X%02X%02X</color>\n'
            '</resources>\n' % corner
        )
    drawable = os.path.join(res, "drawable")
    os.makedirs(drawable, exist_ok=True)
    with open(os.path.join(drawable, "ic_launcher_bg.xml"), "w",
              encoding="utf-8", newline="\n") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:shape="rectangle">\n'
            '    <solid android:color="@color/ic_launcher_background" />\n'
            '</shape>\n'
        )
    print("done")


if __name__ == "__main__":
    main()
