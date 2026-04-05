from flask import Flask, request, render_template_string, jsonify
import requests
from requests.exceptions import RequestException

app = Flask(__name__)

# ---------------------------------------------------------------------
# Dollar General API config (from your HAR, store 22670)
# ---------------------------------------------------------------------
DG_SEARCH_URL = "https://dggo.dollargeneral.com/omni/api/v5/search/shoppinglist/product/Provider"
STORE_NUMBER = 22670
DCC_URL = "http://192.168.0.192:3000/api/inventory/master/add"

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
    "x-dg-appsessiontoken": "RZ6LQAKWP0EVWYVZ4OQ7JEV3X5MT7BO2",
    "x-dg-apptoken": "6dinqus4908fkssw9h7aa8ldcgkimn3p",
    "x-dg-cloud-service": "true",
    "x-dg-customerguid": "000000000000000000000000002042429537",
    "x-dg-deviceuniqueid": "916cefa3-75bf-4af2-a982-b06ed67665b1",
    # Partner API token was not found in headers for this request, 
    # but digital tracer was present. Added it to ensure session persistence.
    "x-dg-digitaltracer": ""
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
        "bloomreachCookieId": "uid=9215490756510:v=12.0:ts=1774585252788:hc=7",
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
        sku = str(it.get("SKU", upc))
        dept = it.get("Category", "General").split("|")[0]
        if upc and desc:
            cleaned.append({
                "UPC": upc, 
                "Description": desc, 
                "Price": price,
                "SKU": sku,
                "Department": dept
            })
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
        
        .btn-ingest {
            background-color: #28a745;
            color: white;
            border: none;
            padding: 5px 10px;
            border-radius: 4px;
            cursor: pointer;
            margin-top: 5px;
        }
        .btn-ingest:hover { background-color: #218838; }
        .btn-ingest-all {
            background-color: #007bff;
            color: white;
            border: none;
            padding: 5px 10px;
            border-radius: 4px;
            cursor: pointer;
            margin-bottom: 10px;
        }
        .btn-ingest-all:hover { background-color: #0069d9; }
    </style>
</head>
<body>
    <h1>DG UPC Finder (Store {{ store }})</h1>
    <form method="get" action="/">
        <input type="text" name="q" placeholder="Search products (e.g. blanket or UPC)" value="{{ query|default('') }}" autofocus>
        <button type="submit">Search</button>
    </form>

    {% if query %}
        <div class="results-count">
            Showing {{ items|length }} result{{ '' if items|length == 1 else 's' }} for "<strong>{{ query }}</strong>"
            <br>
            {% if items|length > 0 %}
                <button class="btn-ingest-all" onclick="ingestAll()">Add All to StoreNET</button>
            {% endif %}
        </div>
    {% endif %}

    {% for item in items %}
        <div class="item">
            <div class="item-title">{{ item['Description'] }}</div>
            <div>Price: {{ item['Price'] if item['Price'] is not none else 'N/A' }}</div>
            <div class="upc">UPC: {{ item['UPC'] }}</div>
            <button class="btn-ingest" onclick="ingestItem({{ loop.index0 }})">Add to StoreNET</button>
            <br>
            <svg class="barcode"
                 jsbarcode-value="{{ item['UPC'] }}"
                 jsbarcode-format="upc"
                 jsbarcode-display-value="false"
                 jsbarcode-height="60"></svg>
        </div>
    {% endfor %}

    <script src="https://cdn.jsdelivr.net/npm/jsbarcode@3.11.6/dist/JsBarcode.all.min.js"></script>
    <script>
        JsBarcode(".barcode").init();

        const allItems = {{ items|tojson|safe }};

        function ingestItemObj(item) {
            const payload = {
                sku: item.SKU,
                upc: item.UPC,
                name: item.Description,
                department: item.Department,
                std_price: item.Price || 0
            };
            return fetch('/ingest', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(payload)
            }).then(res => res.json());
        }

        function ingestItem(index) {
            const item = allItems[index];
            ingestItemObj(item)
            .then(data => {
                if(data.success) {
                    alert("Successfully added " + item.Description + " to StoreNET!");
                } else {
                    alert("Failed to add: " + (data.error || data.status));
                }
            })
            .catch(err => alert("Error: " + err));
        }

        function ingestAll() {
            if (!allItems || !allItems.length) return;
            
            let successCount = 0;
            let failCount = 0;
            
            Promise.all(allItems.map(item => {
                return ingestItemObj(item)
                    .then(data => {
                        if(data.success) successCount++;
                        else failCount++;
                    })
                    .catch(err => failCount++);
            })).then(() => {
                alert(`Ingest complete. Success: ${successCount}, Failed: ${failCount}`);
            });
        }
    </script>
</body>
</html>
"""

@app.route("/", methods=["GET"])
def index():
    q = request.args.get("q", "").strip()
    items = dg_search(q) if q else []
    return render_template_string(HTML_TEMPLATE, items=items, query=q, store=STORE_NUMBER)

@app.route("/ingest", methods=["POST"])
def ingest():
    item_data = request.json
    try:
        resp = requests.post(DCC_URL, json=item_data, timeout=5)
        return jsonify({"success": resp.status_code == 200, "status": resp.status_code})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)})

if __name__ == "__main__":
    app.run(debug=True)
