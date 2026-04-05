import json
import requests
import time
import sys

# --- CONFIGURATION ---
DCC_URL = "http://192.168.0.192:3000/api/inventory/master/add"
HAR_FILE = "www.dollargeneral.com.har"

# From your working app.py
DG_SEARCH_URL = "https://dggo.dollargeneral.com/omni/api/v5/search/shoppinglist/product/Provider"
STORE_NUMBER = 22670

# HEADERS - EXACTLY matching your working app.py
HEADERS = {
    "accept": "application/json, text/plain, */*",
    "content-type": "application/json",
    "origin": "https://www.dollargeneral.com",
    "referer": "https://www.dollargeneral.com/",
    "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
    "x-dg-appsessiontoken": "RZ6LQAKWP0EVWYVZ4OQ7JEV3X5MT7BO2",
    "x-dg-apptoken": "6dinqus4908fkssw9h7aa8ldcgkimn3p",
    "x-dg-cloud-service": "true",
    "x-dg-customerguid": "000000000000000000000000002042429537",
    "x-dg-deviceuniqueid": "916cefa3-75bf-4af2-a982-b06ed67665b1",
    "x-dg-digitaltracer": ""
}

BLOOMREACH_ID = "uid=9215490756510:v=12.0:ts=1774585252788:hc=7"

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
        upc = str(it.get('UPC', ''))
        if not upc or upc in seen_upcs:
            continue
        
        seen_upcs.add(upc)
        sku = str(it.get('SKU', upc))
        name = it.get('Description', 'Unknown Item')
        price = it.get('Price', 0.0)
        dept = it.get('Category', 'General').split('|')[0]

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

def crawl_dg_api():
    print(f"[*] Step 2: Live Crawl of DG API using search terms...")
    
    seed_terms = [
        "milk", "bread", "chips", "soda", "water", "soap", "shampoo", "paper", 
        "pet", "toy", "phone", "candy", "cookie", "detergent", "cereal"
    ]
    chars = "abcdefghijklmnopqrstuvwxyz"
    for c1 in chars:
        for c2 in chars:
            seed_terms.append(c1 + c2)

    for term in seed_terms:
        print(f"[*] Searching for '{term}'...")
        start_index = 0
        page_size = 24
        
        # Act like a real browser by updating referer
        session.headers.update({"referer": f"https://www.dollargeneral.com/product-search?q={term}"})

        while True:
            payload = {
                "StoreNbr": STORE_NUMBER,
                "SearchTerm": term,
                "PageSize": page_size,
                "PageStartRecordIndex": start_index,
                "Filters": {
                    "category": [],
                    "brand": [],
                    "dgDelivery": False,
                    "dgPickUp": False,
                    "dgShipTohome": False,
                    "soldAtStore": True,
                    "inStock": True,
                },
                "IncludeSponsored": True,
                "IncludeShipToHome": True,
                "IncludeDeals": True,
                "offerSourceType": 0,
                "SearchType": 0,
                "bloomreachCookieId": BLOOMREACH_ID
            }

            try:
                resp = session.post(DG_SEARCH_URL, json=payload, timeout=15)
                
                if resp.status_code == 403:
                    print("[!] 403 Forbidden - Bot detection triggered. Sleeping for 30s...")
                    time.sleep(30)
                    break

                if not resp.ok:
                    print(f"[!] API Error {resp.status_code} for '{term}'")
                    break
                
                try:
                    data = resp.json()
                except Exception:
                    print(f"[!] Failed to decode JSON. Response was empty or not JSON.")
                    break

                items = data.get("Items", [])
                if not items:
                    break
                
                added = process_dg_items(items)
                total_avail = data.get("PaginationInfo", {}).get("TotalRecords", 0)
                print(f"    [+] Page {start_index//page_size + 1}: Found {len(items)} items ({added} new).")
                
                start_index += page_size
                if start_index >= total_avail or start_index >= 500: # Limit to 500 per term for speed
                    break
                
                time.sleep(1.5) # Increased delay to be safer
                
            except Exception as e:
                print(f"[!] Request failed: {e}")
                break

if __name__ == "__main__":
    print("--- StoreNET DG Master Ingestor ---")
    ingest_from_har()
    crawl_dg_api()
    print(f"\n[*] FINISHED!")
    print(f"[*] Total unique items seen: {len(seen_upcs)}")
    print(f"[*] Total successfully ingested into StoreNET: {total_ingested}")
    print("[!] Action Required: Go to DCC Web and 'Push Master to Stores' to sync your POS/HHT devices.")
