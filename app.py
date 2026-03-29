from flask import Flask, request, render_template_string
import requests
from requests.exceptions import RequestException

app = Flask(__name__)

# ---------------------------------------------------------------------
# Dollar General API config (from your HAR, store 22670)
# ---------------------------------------------------------------------
DG_SEARCH_URL = "https://dggo.dollargeneral.com/omni/api/v5/search/shoppinglist/product/Provider"
STORE_NUMBER = 22670

HEADERS = {
    "accept": "application/json, text/plain, */*",
    "content-type": "application/json",
    "origin": "https://www.dollargeneral.com",
    "referer": "https://www.dollargeneral.com/",
    "user-agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/146.0.0.0 Safari/537.36"
    ),

    # These are from your newest HAR.
    "x-dg-appsessiontoken": "4HR7P0M9LGUTSKPPC5ISQ8KOTFWA6SBR",
    "x-dg-apptoken": "6dinqus4908fkssw9h7aa8ldcgkimn3p",
    "x-dg-cloud-service": "true",
    "x-dg-customerguid": "000000000000000000000000002042429537",
    "x-dg-deviceuniqueid": "916cefa3-75bf-4af2-a982-b06ed67665b1",
    # Partner API token was not found in headers for this request, 
    # but digital tracer was present. Added it to ensure session persistence.
    "x-dg-digitaltracer": "dgtracer://PurCeOrCouRCYiTemRSDEmdEmm3denzTgVrGkZtcGNsGMYtggAyGeYLfG4ztqnDfgqrduITUeiwCePKBiVfdENkhnVSGmvd2mM2HoRzlpBWeUVlsK5tUwnRRF43xSnKOpfRey5dpJZjgqNRXF5JceORCnARcYIrqGA5dKMBNGY4tgnbVgi3s4MBsHI2tiOrRgbkdOmRnGMyc2nrSgAZCEoRcMQRHw"
}

# ---------------------------------------------------------------------
# DG search function (POST → JSON → trimmed list)
# ---------------------------------------------------------------------
def dg_search(term, page_size=24):
    if not term:
        return []

    payload = {
        "StoreNbr": STORE_NUMBER,
        "SearchTerm": term,
        "PageSize": page_size,
        "PageStartRecordIndex": 0,
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
        "bloomreachCookieId": "uid=9215490756510:v=12.0:ts=1774585252788:hc=5",
    }

    try:
        resp = requests.post(DG_SEARCH_URL, json=payload, headers=HEADERS, timeout=10)
    except RequestException as e:
        print(f"[dg_search] Request failed: {e}")
        return []

    print(f"[dg_search] status={resp.status_code} for term={term!r}")

    if not resp.ok:
        print("[dg_search] Non-OK body snippet:")
        print(resp.text[:200])
        return []

    try:
        data = resp.json()
    except ValueError:
        print("[dg_search] JSON decode failed. Snippet:")
        print(resp.text[:200])
        return []

    items = data.get("Items", [])
    cleaned = []
    for it in items:
        upc = it.get("UPC")
        desc = it.get("Description")
        price = it.get("Price")
        if upc and desc:
            cleaned.append({"UPC": upc, "Description": desc, "Price": price})
    return cleaned

# ---------------------------------------------------------------------
# One simple page with a search box + barcodes
# ---------------------------------------------------------------------
HTML_TEMPLATE = """
<!doctype html>
<html>
<head>
    <meta charset="utf-8">
    <title>DG 14302 UPC Finder</title>
    <style>
        body { font-family: sans-serif; margin: 1.5rem; }
        h1 { margin-bottom: 0.5rem; }
        form { margin-bottom: 1rem; }
        input[type="text"] { padding: 0.4rem; width: 250px; }
        button { padding: 0.4rem 0.8rem; cursor: pointer; }
        .results-count { margin-bottom: 0.5rem; font-size: 0.9rem; color: #444; }
        .item { border: 1px solid #ccc; padding: 0.6rem; margin-bottom: 0.6rem; border-radius: 4px; }
        .item-title { font-weight: bold; }
        .upc { font-family: monospace; font-size: 0.9rem; margin-top: 0.2rem; }
        .barcode { margin-top: 0.4rem; }
    </style>
</head>
<body>
    <h1>DG UPC Finder (Store {{ store }})</h1>
    <form method="get" action="/">
        <input type="text" name="q" placeholder="Search products (e.g. blanket)" value="{{ query|default('') }}" autofocus>
        <button type="submit">Search</button>
    </form>

    {% if query %}
        <div class="results-count">
            Showing {{ items|length }} result{{ '' if items|length == 1 else 's' }} for "<strong>{{ query }}</strong>"
        </div>
    {% endif %}

    {% for item in items %}
        <div class="item">
            <div class="item-title">{{ item['Description'] }}</div>
            <div>Price: {{ item['Price'] if item['Price'] is not none else 'N/A' }}</div>
            <div class="upc">UPC: {{ item['UPC'] }}</div>
            <svg class="barcode"
                 jsbarcode-value="{{ item['UPC'] }}"
                 jsbarcode-format="upc"
                 jsbarcode-display-value="false"
                 jsbarcode-height="60"></svg>
        </div>
    {% endfor %}

    <script src="https://cdn.jsdelivr.net/npm/jsbarcode@3.11.6/dist/JsBarcode.all.min.js"></script>
    <script>JsBarcode(".barcode").init();</script>
</body>
</html>
"""

@app.route("/", methods=["GET"])
def index():
    q = request.args.get("q", "").strip()
    items = dg_search(q) if q else []
    return render_template_string(HTML_TEMPLATE, items=items, query=q, store=STORE_NUMBER)

if __name__ == "__main__":
    app.run(debug=True)
