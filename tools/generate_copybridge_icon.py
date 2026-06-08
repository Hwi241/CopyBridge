from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "assets"
RES = ROOT / "android" / "app" / "src" / "main" / "res"

SVG_PATH = ASSETS / "copybridge_icon.svg"

TEXT_TOP = "COPY"
TEXT_BOTTOM = "BRIDGE"

CANVAS = 1024
BG = "#000000"
FG = "#FFFFFF"

# BRIDGE가 검은 네모 가로폭을 거의 채우도록 설정
TARGET_WIDTH = 900

# 두 줄 사이 간격
LINE_GAP = 36

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/freefont/FreeSansBold.ttf",
]

SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def find_font_path():
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            return path
    raise FileNotFoundError("No suitable bold font found")


def text_size(draw, text, font):
    box = draw.textbbox((0, 0), text, font=font)
    return box[2] - box[0], box[3] - box[1], box


def fit_font_size(draw, font_path):
    size = 280
    while size > 20:
        font = ImageFont.truetype(font_path, size)
        width, _, _ = text_size(draw, TEXT_BOTTOM, font)
        if width <= TARGET_WIDTH:
            return size
        size -= 1
    return size


def draw_centered_icon(target_size):
    scale = target_size / CANVAS
    img = Image.new("RGBA", (target_size, target_size), BG)
    img.putalpha(255)
    draw = ImageDraw.Draw(img)

    font_path = find_font_path()

    # 1024 기준에서 실제 박스 계산
    base = Image.new("RGBA", (CANVAS, CANVAS), BG)
    base_draw = ImageDraw.Draw(base)
    font_size = fit_font_size(base_draw, font_path)

    font = ImageFont.truetype(font_path, font_size)

    copy_w, copy_h, copy_box = text_size(base_draw, TEXT_TOP, font)
    bridge_w, bridge_h, bridge_box = text_size(base_draw, TEXT_BOTTOM, font)

    total_h = copy_h + LINE_GAP + bridge_h
    group_top = (CANVAS - total_h) / 2

    copy_x = (CANVAS - copy_w) / 2 - copy_box[0]
    copy_y = group_top - copy_box[1]

    bridge_x = (CANVAS - bridge_w) / 2 - bridge_box[0]
    bridge_y = group_top + copy_h + LINE_GAP - bridge_box[1]

    # 실제 PNG 크기에 맞춰 그림
    scaled_font = ImageFont.truetype(font_path, max(1, round(font_size * scale)))
    scaled_gap = LINE_GAP * scale

    copy_w_s, copy_h_s, copy_box_s = text_size(draw, TEXT_TOP, scaled_font)
    bridge_w_s, bridge_h_s, bridge_box_s = text_size(draw, TEXT_BOTTOM, scaled_font)

    total_h_s = copy_h_s + scaled_gap + bridge_h_s
    group_top_s = (target_size - total_h_s) / 2

    copy_x_s = (target_size - copy_w_s) / 2 - copy_box_s[0]
    copy_y_s = group_top_s - copy_box_s[1]

    bridge_x_s = (target_size - bridge_w_s) / 2 - bridge_box_s[0]
    bridge_y_s = group_top_s + copy_h_s + scaled_gap - bridge_box_s[1]

    draw.text((copy_x_s, copy_y_s), TEXT_TOP, font=scaled_font, fill=FG)
    draw.text((bridge_x_s, bridge_y_s), TEXT_BOTTOM, font=scaled_font, fill=FG)

    return img, font_size


def write_svg(font_size):
    ASSETS.mkdir(parents=True, exist_ok=True)
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
 <rect width="1024" height="1024" fill="#000000"/>
 <text x="512" y="430" text-anchor="middle" dominant-baseline="middle"
 font-family="DejaVu Sans, Arial, Helvetica, sans-serif"
 font-size="{font_size}" font-weight="900" letter-spacing="0" fill="#FFFFFF">COPY</text>
 <text x="512" y="620" text-anchor="middle" dominant-baseline="middle"
 font-family="DejaVu Sans, Arial, Helvetica, sans-serif"
 font-size="{font_size}" font-weight="900" letter-spacing="0" fill="#FFFFFF">BRIDGE</text>
</svg>
'''
    SVG_PATH.write_text(svg, encoding="utf-8")
    print(f"SVG_WRITTEN {SVG_PATH}")


def save_icon_pngs():
    outputs = [
        ("mipmap-mdpi", 48),
        ("mipmap-hdpi", 72),
        ("mipmap-xhdpi", 96),
        ("mipmap-xxhdpi", 144),
        ("mipmap-xxxhdpi", 192),
    ]

    last_font_size = None

    for folder, size in outputs:
        out_dir = RES / folder
        out_dir.mkdir(parents=True, exist_ok=True)

        img, font_size = draw_centered_icon(size)
        last_font_size = font_size

        icon_path = out_dir / "ic_launcher.png"
        round_path = out_dir / "ic_launcher_round.png"

        img.save(icon_path)
        img.save(round_path)

        print(f"PNG_OK {folder} {size}x{size} font_size={font_size}")

    write_svg(last_font_size)
    print(f"SVG_OK {SVG_PATH}")
    print(f"FONT_SIZE_USED {last_font_size}")


if __name__ == "__main__":
    save_icon_pngs()
