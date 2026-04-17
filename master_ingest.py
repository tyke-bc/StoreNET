import json
import requests
import time
import sys

# --- CONFIGURATION ---
DCC_URL = "http://192.168.0.192:3000/api/inventory/master/add"
HAR_FILE = "www.dollargeneral.com.har"

# From your working app.py
DG_SEARCH_URL = "https://dggo.dollargeneral.com/omni/api/v5/search/shoppinglist/product/Provider"
STORE_NUMBER = 14302

# HEADERS - updated from HAR capture
HEADERS = {
    "accept": "application/json, text/plain, */*",
    "content-type": "application/json",
    "origin": "https://www.dollargeneral.com",
    "referer": "https://www.dollargeneral.com/",
    "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
    "x-dg-appsessiontoken": "XO99WGCFE3ITC5NP4TY9VB6B0U4740OQ",
    "x-dg-apptoken": "6dinqus4908fkssw9h7aa8ldcgkimn3p",
    "x-dg-cloud-service": "true",
    "x-dg-customerguid": "000000000000000000000000002329219493",
    "x-dg-deviceuniqueid": "03d58dc5-f1a9-4554-a935-1d8f2ea57d71",
    "x-dg-digitaltracer": "",
    "x-dg-partnerapitoken": "11619A82-8E80-4A6F-8AD2-A14F4A8FFD74"
}

BLOOMREACH_ID = "uid=5361382883063:v=12.0:ts=1776058847202:hc=1"

# --- GLOBAL STATE ---
seen_upcs = set()
total_ingested = 0
session = requests.Session()
session.headers.update(HEADERS)

def send_to_storenet(item):
    global total_ingested
    try:
        resp = requests.post(DCC_URL, json=item, timeout=5)
        if resp.status_code == 200:
            total_ingested += 1
            if total_ingested % 50 == 0:
                print(f"[*] Total Ingested: {total_ingested}...")
            return True
    except Exception:
        pass
    return False

def process_dg_items(items):
    new_count = 0
    for it in items:
        upc = str(it.get('UPC', '')).zfill(12)
        if not upc or upc in seen_upcs:
            continue
        
        seen_upcs.add(upc)
        sku = str(it.get('SKU', upc))
        name = it.get('Description', 'Unknown Item')
        price = it.get('Price', 0.0)
        dept = (it.get('Category') or 'General').split('|')[0]

        item_data = {
            "sku": sku,
            "upc": upc,
            "name": name,
            "department": dept,
            "std_price": price
        }
        if send_to_storenet(item_data):
            new_count += 1
    return new_count

def ingest_from_har():
    print(f"[*] Step 1: Parsing HAR file ({HAR_FILE})...")
    try:
        with open(HAR_FILE, 'r', encoding='utf-8') as f:
            har_data = json.load(f)
    except Exception as e:
        print(f"[!] Error loading HAR: {e}")
        return

    for entry in har_data.get('log', {}).get('entries', []):
        text = entry.get('response', {}).get('content', {}).get('text', '')
        if '"Items":[' in text:
            try:
                data = json.loads(text)
                process_dg_items(data.get('Items', []))
            except: continue
    print(f"[+] HAR parsing complete. Currently have {len(seen_upcs)} unique items.")

def api_search(term=None, category_id=None, start_index=0, page_size=24):
    """Single API call. Pass term for keyword search, category_id for category browse."""
    payload = {
        "StoreNbr": STORE_NUMBER,
        "SearchTerm": term or "",
        "PageSize": page_size,
        "PageStartRecordIndex": start_index,
        "Filters": {
            "category": [category_id] if category_id else [],
            "brand": [],
            "dgDelivery": False,
            "dgPickUp": False,
            "dgShipTohome": False,
            "soldAtStore": True,
            "inStock": False,  # False = include items even if currently out of stock
        },
        "IncludeSponsored": True,
        "IncludeShipToHome": True,
        "IncludeDeals": True,
        "offerSourceType": 0,
        "SearchType": 0,
        "bloomreachCookieId": BLOOMREACH_ID
    }
    referer = f"https://www.dollargeneral.com/product-search?q={term}" if term else "https://www.dollargeneral.com/"
    session.headers.update({"referer": referer})
    return session.post(DG_SEARCH_URL, json=payload, timeout=15)

def crawl_term(term):
    """Paginate through all results for a single search term. No cap."""
    start_index = 0
    page_size = 24
    while True:
        try:
            resp = api_search(term=term, start_index=start_index, page_size=page_size)
            if resp.status_code == 403:
                print("[!] 403 Forbidden - sleeping 30s...")
                time.sleep(30)
                break
            if not resp.ok:
                print(f"[!] API Error {resp.status_code} for '{term}'")
                break
            data = resp.json()
            items = data.get("Items", [])
            if not items:
                break

            # Collect new category IDs as we go
            for it in items:
                for cid in str(it.get('CategoryIdList', '')).split('|'):
                    if cid.strip():
                        discovered_categories.add(cid.strip())

            added = process_dg_items(items)
            total_avail = data.get("PaginationInfo", {}).get("TotalRecords", 0)
            print(f"    [+] Page {start_index//page_size + 1}/{-(-total_avail//page_size)}: {len(items)} items ({added} new)")
            start_index += page_size
            if start_index >= total_avail:
                break
            time.sleep(1.5)
        except Exception as e:
            print(f"[!] Request failed: {e}")
            break

def crawl_category(category_id):
    """Paginate through all results for a single category ID."""
    start_index = 0
    page_size = 24
    consecutive_errors = 0
    while True:
        try:
            resp = api_search(category_id=category_id, start_index=start_index, page_size=page_size)
            if resp.status_code == 403:
                print("[!] 403 Forbidden - sleeping 30s...")
                time.sleep(30)
                consecutive_errors += 1
                if consecutive_errors >= 3:
                    print(f"[!] Skipping category {category_id} after repeated 403s.")
                    break
                continue
            if not resp.ok:
                print(f"  [!] Cat {category_id} HTTP {resp.status_code} — skipping.")
                break
            if not resp.text.strip():
                # Empty body — this category ID isn't valid, just skip silently
                break
            consecutive_errors = 0
            try:
                data = resp.json()
            except Exception:
                break  # Unparseable body — skip silently
            items = data.get("Items", [])
            if not items:
                break
            added = process_dg_items(items)
            total_avail = data.get("PaginationInfo", {}).get("TotalRecords", 0)
            if added > 0:
                print(f"    [cat {category_id}] Page {start_index//page_size + 1}: {len(items)} items ({added} new)")
            start_index += page_size
            if start_index >= total_avail:
                break
            time.sleep(1.2)
        except Exception as e:
            print(f"  [!] Cat {category_id} failed: {e} — skipping.")
            break

def lookup_upcs(upc_list):
    """Directly search for specific UPCs that may have been missed."""
    print(f"[*] Direct UPC lookup for {len(upc_list)} item(s)...")
    for upc in upc_list:
        try:
            resp = api_search(term=str(upc))
            if not resp.ok:
                print(f"  [!] Failed for UPC {upc}: {resp.status_code}")
                continue
            items = resp.json().get("Items", [])
            matched = [it for it in items if str(it.get('UPC', '')).zfill(12) == str(upc).zfill(12)]
            if matched:
                process_dg_items(matched)
                print(f"  [+] Found UPC {upc}: {matched[0].get('Description', '?')}")
            else:
                print(f"  [-] UPC {upc} not returned by API (may not be sold at this store)")
            time.sleep(1.0)
        except Exception as e:
            print(f"  [!] UPC {upc} lookup failed: {e}")

# --- GLOBALS for category discovery ---
discovered_categories = set()
CATEGORIES_FILE = "discovered_categories.json"

def save_categories():
    with open(CATEGORIES_FILE, 'w') as f:
        json.dump(sorted(discovered_categories), f)
    print(f"[*] Saved {len(discovered_categories)} category IDs to {CATEGORIES_FILE}")

def load_categories():
    try:
        with open(CATEGORIES_FILE, 'r') as f:
            ids = json.load(f)
        discovered_categories.update(ids)
        print(f"[*] Loaded {len(discovered_categories)} category IDs from {CATEGORIES_FILE}")
    except FileNotFoundError:
        print(f"[!] No saved categories file found ({CATEGORIES_FILE}). Run keyword or two-letter crawl first.")

# --- STEP FUNCTIONS ---

SEED_TERMS = [
    "milk", "bread", "chips", "soda", "water", "soap", "shampoo", "paper",
    "pet", "toy", "phone", "candy", "cookie", "detergent", "cereal",
    "juice", "coffee", "tea", "snack", "cleaning", "battery", "charger",
    "vitamin", "medicine", "bandage", "lotion", "deodorant", "toothpaste",
    "razor", "diaper", "wipe", "trash", "bag", "foil", "wrap", "storage"
]

TWO_LETTER_TERMS = [c1 + c2 for c1 in "abcdefghijklmnopqrstuvwxyz"
                              for c2 in "abcdefghijklmnopqrstuvwxyz"]

MISSING_UPCS = [
    "840797176072",  # Root to End shampoo
]

def run_keyword_crawl():
    print(f"\n[*] Keyword crawl ({len(SEED_TERMS)} terms)...")
    for term in SEED_TERMS:
        print(f"[*] Searching '{term}'...")
        crawl_term(term)
    save_categories()

def run_two_letter_crawl():
    print(f"\n[*] Two-letter crawl (676 terms)...")
    for term in TWO_LETTER_TERMS:
        print(f"[*] Searching '{term}'...")
        crawl_term(term)
    save_categories()

def run_category_crawl():
    load_categories()
    if not discovered_categories:
        return
    print(f"\n[*] Category crawl ({len(discovered_categories)} categories)...")
    for cid in sorted(discovered_categories):
        crawl_category(cid)

def run_upc_lookup():
    print(f"\n[*] Direct UPC lookup...")
    lookup_upcs(MISSING_UPCS)

def print_summary():
    print(f"\n[*] FINISHED!")
    print(f"[*] Total unique items seen this session: {len(seen_upcs)}")
    print(f"[*] Total successfully ingested into StoreNET: {total_ingested}")
    print("[!] Action Required: Go to DCC Web and 'Push Master to Stores' to sync your POS/HHT devices.")

# --- MENU ---

def show_menu():
    print("\n--- StoreNET DG Master Ingestor ---")
    print("  1  HAR import (parse saved HAR file)")
    print("  2  Keyword crawl (seed words)")
    print("  3  Two-letter crawl (aa → zz)")
    print("  4  Category crawl (resume-safe, needs prior keyword/two-letter run)")
    print("  5  Direct UPC lookup (known missing items)")
    print("  6  Full run (all of the above in order)")
    print()
    choice = input("Select mode: ").strip()
    return choice

if __name__ == "__main__":
    choice = show_menu()

    if choice == '1':
        ingest_from_har()
    elif choice == '2':
        ingest_from_har()
        run_keyword_crawl()
    elif choice == '3':
        ingest_from_har()
        run_two_letter_crawl()
    elif choice == '4':
        run_category_crawl()
    elif choice == '5':
        run_upc_lookup()
    elif choice == '6':
        ingest_from_har()
        run_keyword_crawl()
        run_two_letter_crawl()
        run_category_crawl()
        run_upc_lookup()
    else:
        print("[!] Invalid choice. Exiting.")
        sys.exit(1)

    print_summary()
