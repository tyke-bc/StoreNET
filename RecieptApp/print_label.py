import socket
import io
import sys
import argparse
import textwrap
from datetime import datetime

try:
    from PIL import Image, ImageDraw, ImageFont
    import barcode
    from barcode.writer import ImageWriter
    PILLOW_AVAILABLE = True
except ImportError:
    PILLOW_AVAILABLE = False

def send_to_printer(ip, port, data):
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(5.0)
            s.connect((ip, port))
            s.sendall(data)
            print(f"Sent {len(data)} bytes to {ip}:{port}")
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)

# ESC/POS Commands
INIT = b'\x1b\x40'
LEFT = b'\x1b\x61\x00'
CENTER = b'\x1b\x61\x01'
RIGHT = b'\x1b\x61\x02'

def image_to_escpos(img):
    img = img.convert('1')
    width, height = img.size
    
    if width % 8 != 0:
        new_width = width + (8 - (width % 8))
        new_img = Image.new('1', (new_width, height), color=1)
        new_img.paste(img, (0, 0))
        img = new_img
        width = new_width

    bytes_wide = width // 8
    header = b'\x1d\x76\x30\x00' + bytes([bytes_wide % 256, bytes_wide // 256, height % 256, height // 256])
    raster_data = bytearray()
    
    pixels = img.load()
    for y in range(height):
        for x_byte in range(bytes_wide):
            b = 0
            for bit in range(8):
                x = x_byte * 8 + bit
                if pixels[x, y] == 0:
                    b |= (1 << (7 - bit))
            raster_data.append(b)
            
    return header + bytes(raster_data)

def load_font(size, is_bold=False):
    font_names = [
        "arialbd.ttf" if is_bold else "arial.ttf",
        "DejaVuSans-Bold.ttf" if is_bold else "DejaVuSans.ttf",
        "FreeSansBold.ttf" if is_bold else "FreeSans.ttf",
        "LiberationSans-Bold.ttf" if is_bold else "LiberationSans-Regular.ttf",
    ]
    for fn in font_names:
        try:
            return ImageFont.truetype(fn, size)
        except Exception:
            pass
    return ImageFont.load_default()

def get_shelf_label_data(brand, name, variant, size, upc, price, unit_price_str, taxable, pog_date, location, faces):
    if not PILLOW_AVAILABLE:
        return INIT + b"Error: Pillow/barcode not installed\n"

    import textwrap

    width = 384
    height = 240  # Increased slightly so the Unit Price can move down
    img = Image.new("1", (width, height), color=1)
    draw = ImageDraw.Draw(img)

    # --- FONTS ---
    f_item_text = load_font(26, False) 
    f_price_large = load_font(80, True)
    f_price_cents = load_font(35, True)
    f_tiny_bold = load_font(18, True)
    f_tiny_data = load_font(16, False)

    # --- LEFT COLUMN: ITEM INFO ---
    left_margin = 10
    
    full_text = f"{brand} {name} {variant} {size}".strip()
    full_text = " ".join(full_text.split())
    
    wrapped_lines = textwrap.wrap(full_text, width=13)
    
    y_text = 10
    for line in wrapped_lines[:4]:
        draw.text((left_margin, y_text), line, font=f_item_text, fill=0)
        y_text += 28

    # --- BARCODE (Middle Left) ---
    try:
        # Use code128 to encode EXACTLY what is in the UPC string (11 or 12 digits)
        # This prevents the library from adding its own check digits or padding zeros.
        BC_TYPE = barcode.get_barcode_class('code128')
        clean_upc = "".join(filter(str.isdigit, upc))
        bc = BC_TYPE(clean_upc, writer=ImageWriter())
        f_bc = io.BytesIO()
        
        # Keep your exact module height, but reduce width to fit better
        bc.write(f_bc, options={'write_text': False, 'module_height': 5.0, 'module_width': 0.18, 'dpi': 203, 'quiet_zone': 0.05})
        f_bc.seek(0)
        bc_img = Image.open(f_bc).convert("1")
        
        img.paste(bc_img, (5, 125)) 
    except:
        draw.text((5, 125), "[UPC ERR]", font=f_tiny_data, fill=0)

    # Unit Price (Bottom Left)
    # Moved down by 20 pixels so it doesn't touch the barcode
    draw.text((10, 185), "Unit", font=f_tiny_data, fill=0)
    draw.text((10, 205), "Price", font=f_tiny_data, fill=0)
    draw.text((50, 180), f"${price:.2f}", font=load_font(26, True), fill=0) 
    draw.text((50, 210), unit_price_str, font=f_tiny_data, fill=0)


    # --- RIGHT COLUMN: PRICE BOX & DATA ---
    box_x = 185
    box_y = 5
    box_w = 190
    box_h = 125
    draw.rectangle([box_x, box_y, box_x + box_w, box_y + box_h], outline=0, width=3)
    draw.text((box_x + 40, box_y + 5), "Item Price", font=f_tiny_data, fill=0)

    dollars = int(price)
    cents = int(round((price - dollars) * 100))
    draw.text((box_x + 10, box_y + 40), "$", font=f_price_cents, fill=0)
    draw.text((box_x + 35, box_y + 25), str(dollars), font=f_price_large, fill=0)
    draw.text((box_x + 130, box_y + 30), f"{cents:02}", font=f_price_cents, fill=0)

    if taxable:
        draw.text((box_x + 20, box_y + 100), "T", font=f_tiny_bold, fill=0)

    # Data Stack (Under Price Box)
    data_y = box_y + box_h + 10
    
    # Shifted X coordinates right (from +5 to +25)
    draw.text((box_x + 25, data_y), upc, font=f_tiny_data, fill=0)
    draw.text((box_x + 25, data_y + 30), f"{pog_date}       E", font=f_tiny_data, fill=0)

    # Location / Faces Box (Bottom Right)
    # Shifted right to accommodate the data stack shift
    loc_x = box_x + 115 
    loc_y = data_y - 2
    draw.rectangle([loc_x, loc_y, loc_x + 80, loc_y + 50], outline=0, width=1)
    draw.text((loc_x + 10, loc_y + 5), location, font=f_tiny_bold, fill=0)
    draw.text((loc_x + 30, loc_y + 25), faces, font=f_tiny_bold, fill=0)

    return INIT + LEFT + image_to_escpos(img) + b'\x1d\x56\x41\x00'

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument('--brand', type=str, default="Maybelline")
    parser.add_argument('--name', type=str, default="Masc 800 LSens")
    parser.add_argument('--variant', type=str, default="SkyHigh-BlkBlk")
    parser.add_argument('--size', type=str, default="1ct")
    parser.add_argument('--upc', type=str, default="0415-5459-0517")
    parser.add_argument('--price', type=float, default=12.25)
    parser.add_argument('--unit-price', type=str, default="per each")
    parser.add_argument('--taxable', action='store_true', default=True)
    parser.add_argument('--pog-date', type=str, default="03/26")
    parser.add_argument('--location', type=str, default="A-P")
    parser.add_argument('--faces', type=str, default="F1")
    parser.add_argument('--ip', type=str, default="192.168.0.179")
    parser.add_argument('--port', type=int, default=9100)
    args = parser.parse_args()

    print(f"Generating label for {args.brand} {args.name}...")
    label_data = get_shelf_label_data(
        args.brand, args.name, args.variant, args.size, 
        args.upc, args.price, args.unit_price, 
        args.taxable, args.pog_date, args.location, args.faces
    )
    
    send_to_printer(args.ip, args.port, label_data)
