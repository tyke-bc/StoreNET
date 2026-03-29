import socket
import io
import sys
import argparse
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
            s.settimeout(5.0) # 5-second timeout to prevent hanging
            s.connect((ip, port))
            s.sendall(data)
            print(f"Sent {len(data)} bytes to {ip}:{port}")
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1) # Explicitly exit with error code so Node catches it

# ESC/POS Commands
INIT = b'\x1b\x40'
LEFT = b'\x1b\x61\x00'
CENTER = b'\x1b\x61\x01'
RIGHT = b'\x1b\x61\x02'
BOLD_ON = b'\x1b\x45\x01'
BOLD_OFF = b'\x1b\x45\x00'
DOUBLE_SIZE = b'\x1d\x21\x11'
QUAD_SIZE = b'\x1d\x21\x33'
NORMAL_SIZE = b'\x1d\x21\x00'
FEED_1 = b'\x1b\x64\x01'
FEED_3 = b'\x1b\x64\x03'
CUT = b'\x1d\x56\x41\x03' # Partial cut

def get_receipt_data():
    data = INIT
    data += CENTER + BOLD_ON + b"DOLLAR GENERAL STORE #14302\n"
    data += b"216 BELKNAP ST\n"
    data += b"SUPERIOR, WI 54880\n"
    data += b"(715) 718-6650\n"
    data += b"SALE TRANSACTION\n" + BOLD_OFF + b"\n"
    
    data += LEFT
    data += b"S CONAIR BRUSH-CUSH 74108800541      $5.75\n"
    data += b"S MONSTER VICE GUAV 70847898146      $2.80\n"
    data += b"\n"
    
    data += RIGHT
    data += b"Tax:  $8.55 @ 5.5%   $0.47\n"
    data += BOLD_ON + b"Balance to pay       $9.02\n" + BOLD_OFF
    data += b"MasterCard           $9.02\n\n"
    
    data += LEFT
    data += b"US DEBIT ************1234\n"
    data += b"Type: Chip Read    Auth Code: 1XMUX4\n"
    data += b"AID:  A0000000042109   PAN Seq: \n"
    data += b"TVR:  7000041000       TSI: 6G01\n"
    data += b"IAD:  0110B00101220000000000000000000000EF\n"
    data += b"MID:  *******27096     TID: ***8000\n"
    data += BOLD_ON + b"TOTAL PURCHASE       $9.02\n" + BOLD_OFF + b"\n"
    
    data += CENTER
    data += b"Save Time. Save Money.\n"
    data += b"Every Day! At Dollar General\n\n"
    
    data += LEFT
    data += b"STORE 14302   TILL 2   TRANS. 120101\n"
    data += b"DATE 03-25-26 8:00 PM\n\n"
    data += b"Your cashier was: COLETON\n\n"
    
    # Barcode: Code 39 (m=4) - Matching coupon properties
    data += b"\n" + CENTER
    data += b"\x1d\x68\x50" # Height 80
    data += b"\x1d\x77\x01" # Width 1
    data += b"\x1d\x48\x00" # HRI text disabled
    data += b"\x1d\x6b\x04" + b"99902143020021201013" + b"\x00"
    data += b"\n" + CENTER + b"*99902143020021201013*\n\n"
    
    data += FEED_3 + CUT
    return data

def get_coupon_data():
    data = INIT
    data += CENTER + b"------ CUT HERE ------\n\n"
    
    data += CENTER + BOLD_ON + b"SATURDAY MAR. 28TH!\n"
    data += b"Valid 3/28/2026\n"
    data += DOUBLE_SIZE + b"$5 OFF $25\n" + NORMAL_SIZE
    data += b"$5 off your purchase of\n"
    data += b"$25 or more (pretax)\n"
    data += b"OR SHOP ONLINE AT DOLLARGENERAL.COM\n" + BOLD_OFF
    
    data += LEFT + b"\x1b\x33\x18" # Set line spacing
    data += b"$25 or more (pretax) after all other DG\n"
    data += b"discounts. Limit one DG $2, $3, or $5\n"
    data += b"off store coupon per customer.\n"
    data += b"Excludes: phone, gift and prepaid\n"
    data += b"financial cards, prepaid wireless\n"
    data += b"handsets, Rug Doctor rental, milk,\n"
    data += b"propane, tobacco and alcohol.\n"
    data += b"\x1b\x32" # Reset line spacing
    
    # Barcode: Code 39 (m=4)
    data += CENTER + b"\n"
    data += b"\x1d\x68\x50" # Height 80
    data += b"\x1d\x77\x01" # Width 1 (Conservative to ensure centering)
    data += b"\x1d\x48\x00" # HRI text disabled
    data += b"\x1d\x6b\x04" + b"X0541532241400431" + b"\x00"
    data += b"\n" + CENTER + b"*X0541532241400431*\n\n"
    
    data += CENTER + b"NEXT TIME TRY\n"
    data += BOLD_ON + b"SAME DAY DELIVERY\n" + BOLD_OFF
    data += b"FIRST ORDER FREE\n"
    data += b"WITH MYDG. SIGN UP NOW.\n\n"
    
    # QR Code
    qr_data = b"https://www.dollargeneral.com/mydg"
    data += b"\x1d\x28\x6b\x03\x00\x31\x43\x03" # QR Model 2
    data += b"\x1d\x28\x6b" + bytes([len(qr_data)+3, 0]) + b"\x31\x50\x30" + qr_data # Store data
    data += b"\x1d\x28\x6b\x03\x00\x31\x51\x30" # Print QR
    
    data += b"\n" + CENTER + BOLD_ON + b"DOLLAR GENERAL\n" + BOLD_OFF
    data += b"******************************\n"
    data += b"1421-1000-8993-113\n"
    data += b"******************************\n"
    
    data += FEED_3 + CUT
    return data

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
                # '1' mode has 0 for black, non-zero for white
                if pixels[x, y] == 0:
                    b |= (1 << (7 - bit))
            raster_data.append(b)
            
    return header + bytes(raster_data)

def get_sticker_data(item_name="Heartland Farm - 24lb", item_sku="2082-9801", item_upc="542992475853"):
    if not PILLOW_AVAILABLE:
        print("Error: Pillow or python-barcode is not installed.")
        print("Please run: pip install pillow python-barcode")
        return INIT + b"Please install Pillow and python-barcode\n" + FEED_3 + CUT

    # Reduced width to 590 to remove gap at the front (ROTATE_90 makes original X the vertical axis)
    width = 590
    height = 384
    img = Image.new("1", (width, height), color=1)
    
    def add_shading(box):
        x0, y0, x1, y1 = box
        pixels = img.load()
        for y in range(int(y0), int(y1)):
            for x in range(int(x0), int(x1)):
                if (x % 2 == 0) if (y % 2 == 0) else (x % 4 == 0):
                    pixels[x, y] = 0

    # Shading layout
    add_shading([120, 5, 260, 60])    # Behind 14302
    add_shading([280, 120, 520, 175]) # Behind BSR_CGO
    add_shading([5, 175, 520, 235])   # Behind Heartland Farm
    add_shading([5, 255, 460, 290])   # Behind 15998 DOG FOOD...
    add_shading([5, 290, 120, 350])   # Behind A-2 R

    draw = ImageDraw.Draw(img)
    
    def load_font(size, is_bold=False):
        font_names = [
            "arialbd.ttf" if is_bold else "arial.ttf",
            "/usr/share/fonts/truetype/msttcorefonts/Arial_Bold.ttf" if is_bold else "/usr/share/fonts/truetype/msttcorefonts/Arial.ttf",
            "DejaVuSans-Bold.ttf" if is_bold else "DejaVuSans.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if is_bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "FreeSansBold.ttf" if is_bold else "FreeSans.ttf",
            "/usr/share/fonts/truetype/freefont/FreeSansBold.ttf" if is_bold else "/usr/share/fonts/truetype/freefont/FreeSans.ttf",
            "LiberationSans-Bold.ttf" if is_bold else "LiberationSans-Regular.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf" if is_bold else "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"
        ]
        for fn in font_names:
            try:
                return ImageFont.truetype(fn, size)
            except Exception:
                pass
        try:
            return ImageFont.load_default(size=size)
        except Exception:
            return ImageFont.load_default()

    font_large = load_font(50, is_bold=True)
    font_medium_bold = load_font(36, is_bold=True)
    font_medium = load_font(36, is_bold=False)
    font_small = load_font(20, is_bold=False)

    # 144
    draw.text((10, 10), "144", font=font_large, fill=0)
    draw.rectangle([5, 5, 120, 75], outline=0, width=4)
    
    # 14302
    draw.text((130, 10), "14302", font=font_medium, fill=0)
    
    # 24 Pack 1
    draw.text((270, 10), "24", font=font_medium, fill=0)
    draw.text((320, 10), "Pack", font=font_small, fill=0)
    draw.text((340, 35), "1", font=font_small, fill=0)
    
    # 758
    draw.rectangle([440, 5, 580, 75], outline=0, width=4)
    draw.text((450, 10), "758", font=font_large, fill=0)
    
    # 00-71-12-01-01
    draw.text((130, 45), "00-71-12-01-01", font=font_medium, fill=0)
    
    # 5429924 - 75853
    draw.text((10, 90), "5429924 - 75853", font=font_small, fill=0)
    
    # Current Date (MM/DD/YY)
    current_date = datetime.now().strftime("%m/%d/%y")
    draw.text((440, 90), current_date, font=font_small, fill=0)
    
    # Barcode 1 (Switching to Code 128)
    Code128 = barcode.get_barcode_class('code128')
    # Use UPC for barcode 1 if valid length, else pad/truncate or just use SKU if upc is missing
    bc_str = item_upc if (item_upc and item_upc != "null") else item_sku
    if not bc_str: bc_str = "000000000000"
    
    try:
        bc1 = Code128(bc_str, writer=ImageWriter())
        f1 = io.BytesIO()
        bc1.write(f1, options={'write_text': False, 'module_height': 5.0, 'module_width': 0.30, 'dpi': 203, 'quiet_zone': 1.0})
        f1.seek(0)
        bc1_img = Image.open(f1).convert("1")
        img.paste(bc1_img, (10, 120))
    except Exception as e:
        draw.text((10, 120), f"[BARCODE ERROR: {bc_str}]", font=font_small, fill=0)
    
    # BSR_CGO
    draw.text((290, 130), "BSR_CGO", font=font_medium, fill=0)
    
    # Item Name
    # Truncate if too long to fit (Max 25 characters)
    display_name = item_name[:25] if item_name else "Unknown Item"
    draw.text((10, 180), display_name, font=font_medium, fill=0) 
    
    # Large F
    draw.text((500, 170), "F", font=font_large, fill=0)
    
    # SKU
    draw.text((10, 235), f"SKU: {item_sku}", font=font_small, fill=0)
    
    # 15998 DOG FOOD...
    draw.text((10, 262), "15998  DOG FOOD-PET GOODS-R", font=font_small, fill=0)
    
    # A-2 R
    draw.text((10, 295), "A-2", font=font_small, fill=0)
    draw.text((10, 315), "F1", font=font_small, fill=0)
    draw.text((80, 305), "R", font=font_medium, fill=0)
    
    # Barcode 2 (18-digit padded UPC)
    raw_upc = (item_upc if (item_upc and item_upc != "null") else "000000000000").strip()
    padded_upc = "0000" + raw_upc
    if len(padded_upc) < 18:
        padded_upc = padded_upc.ljust(18, '0')
    else:
        padded_upc = padded_upc[:18]
        
    try:
        bc2 = Code128(padded_upc, writer=ImageWriter())
        f2 = io.BytesIO()
        bc2.write(f2, options={'write_text': False, 'module_height': 5.0, 'module_width': 0.38, 'dpi': 203, 'quiet_zone': 1.0})
        f2.seek(0)
        bc2_img = Image.open(f2).convert("1")
        img.paste(bc2_img, (130, 295))
    except Exception:
        pass
    
    # Bottom Text (barcode numbers)
    draw.text((130, 355), padded_upc, font=font_small, fill=0)
    
    # Crop exactly to drawn bounds. Leftmost drawing is at x=5, rightmost is at x=580.
    # This removes the remaining blank lines from the top and bottom of the final print.
    img = img.crop((5, 0, 582, height))
    
    rotated = img.transpose(Image.ROTATE_90)
    data = INIT + LEFT
    data += image_to_escpos(rotated)
    
    # Custom cut without extra feed (GS V 'A' 0) to prevent excess paper after print
    STICKER_CUT = b'\x1d\x56\x41\x00'
    data += STICKER_CUT
    
    return data



if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument('--name', type=str, default="Heartland Farm - 24lb")
    parser.add_argument('--sku', type=str, default="2082-9801")
    parser.add_argument('--upc', type=str, default="542992475853")
    parser.add_argument('--print-sticker', action='store_true')
    parser.add_argument('--ip', type=str, default="192.168.0.179")
    parser.add_argument('--port', type=int, default=9100)
    args, unknown = parser.parse_known_args()

    if args.print_sticker:
        print(f"Printing Warehouse Sticker for {args.name}...")
        send_to_printer(args.ip, args.port, get_sticker_data(args.name, args.sku, args.upc))
        sys.exit(0)

    print("Select Option:")
    print("1. Print Receipt & Coupon")
    print("2. Print Warehouse Sticker (IMG_1600)")
    
    choice = input("Choice: ")
    
    if choice == "1":
        print("Printing Receipt...")
        send_to_printer(args.ip, args.port, get_receipt_data())
        
        input("\nPress Enter to print the coupon (after tearing off the receipt)...")
        
        print("Printing Coupon...")
        send_to_printer(args.ip, args.port, get_coupon_data())
    elif choice == "2":
        print("Printing Warehouse Sticker...")
        send_to_printer(args.ip, args.port, get_sticker_data())
    else:
        print("Invalid choice.")
