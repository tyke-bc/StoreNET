package com.github.tyke_bc.hht

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tyke_bc.hht.network.*
import kotlinx.coroutines.launch

private val RespondYellow = Color(0xFFFFC107)
private val RespondYellowDark = Color(0xFFFFA000)
private val ReturnRed = Color(0xFFDC2626)
private val OrderGreen = Color(0xFF059669)
private val SlateBg = Color(0xFFF1F5F9)
private val SlateBorder = Color(0xFFE2E8F0)
private val TextMuted = Color(0xFF64748B)

// Reason codes match server-side const
private val VR_REASONS = listOf("DEFECTIVE", "EXPIRED", "MFG_DEFECT", "STALE", "DAMAGED_PKG", "RECALL")

@Composable
fun RespondScreen(storeId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    // View state machine
    var view by remember { mutableStateOf("landing") }  // landing | vendor | return | order

    // Data state
    var vendors by remember { mutableStateOf<List<Vendor>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedVendor by remember { mutableStateOf<Vendor?>(null) }
    var repName by remember { mutableStateOf("") }
    var recentReturns by remember { mutableStateOf<List<VendorReturn>>(emptyList()) }
    var recentOrders by remember { mutableStateOf<List<VendorOrder>>(emptyList()) }
    var vendorInv by remember { mutableStateOf<List<VendorInventoryRow>>(emptyList()) }

    // Active return session
    var currentReturnId by remember { mutableStateOf<Int?>(null) }
    var returnItems by remember { mutableStateOf<List<VendorReturnItem>>(emptyList()) }

    // Active order session
    var currentOrderId by remember { mutableStateOf<Int?>(null) }
    var orderItems by remember { mutableStateOf<List<VendorOrderItem>>(emptyList()) }

    // Scan-triggered add dialogs
    var scannedItem by remember { mutableStateOf<InventoryItem?>(null) }
    var showReturnAdd by remember { mutableStateOf(false) }
    var showOrderAdd by remember { mutableStateOf(false) }
    var addQty by remember { mutableStateOf("1") }
    var addReason by remember { mutableStateOf(VR_REASONS[0]) }
    var addNotes by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }

    // Toast
    var toast by remember { mutableStateOf<String?>(null) }
    var toastOk by remember { mutableStateOf(true) }
    fun showToast(msg: String, ok: Boolean = true) { toast = msg; toastOk = ok }

    // --- Loaders ---
    suspend fun loadLanding() {
        loading = true
        try {
            vendors = try { RetrofitClient.instance.getVendors() } catch (_: Exception) { emptyList() }
            recentReturns = try { RetrofitClient.instance.getVendorReturns(storeId) } catch (_: Exception) { emptyList() }
            recentOrders = try { RetrofitClient.instance.getVendorOrders(storeId) } catch (_: Exception) { emptyList() }
        } finally { loading = false }
    }

    suspend fun loadVendorInv() {
        val v = selectedVendor ?: return
        vendorInv = try { RetrofitClient.instance.getVendorInventory(storeId, v.id) } catch (_: Exception) { emptyList() }
    }

    suspend fun refreshReturn() {
        val id = currentReturnId ?: return
        try {
            val r = RetrofitClient.instance.getVendorReturn(storeId, id)
            if (r.success) returnItems = r.items ?: emptyList()
        } catch (_: Exception) {}
    }

    suspend fun refreshOrder() {
        val id = currentOrderId ?: return
        try {
            val r = RetrofitClient.instance.getVendorOrder(storeId, id)
            if (r.success) orderItems = r.items ?: emptyList()
        } catch (_: Exception) {}
    }

    LaunchedEffect(Unit) { loadLanding() }
    LaunchedEffect(selectedVendor, view) { if (view == "vendor") loadVendorInv() }

    // Scan listener — routes scans to the active view
    LaunchedEffect(view) {
        MainActivity.scanEvents.collect { raw ->
            val upc = raw.trim()
            if (upc.isBlank()) return@collect
            if (view != "return" && view != "order") return@collect
            try {
                val inv = RetrofitClient.instance.getInventoryItem(storeId, upc)
                if (!inv.success || inv.item == null) { showToast("Item not found: $upc", false); return@collect }
                scannedItem = inv.item
                // Default qty: 1. User can adjust in the dialog. (InventoryItem DTO doesn't
                // expose reorder_max; the server /api/vendors/:id/inventory does — if we want
                // a smart suggestion later, fetch from that endpoint instead.)
                addQty = "1"
                addReason = VR_REASONS[0]
                addNotes = ""
                if (view == "return") showReturnAdd = true else showOrderAdd = true
            } catch (e: Exception) { showToast(e.message ?: "Scan error", false) }
        }
    }

    // --- UI ---
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFFFF8E1))) {
        // Header
        Surface(color = RespondYellowDark, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    when (view) {
                        "return", "order" -> { view = "vendor" }
                        "vendor" -> { selectedVendor = null; view = "landing" }
                        else -> onBack()
                    }
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.Black) }
                Column(modifier = Modifier.weight(1f)) {
                    Text("DG RESPOND", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    Text(
                        text = when (view) {
                            "landing" -> "Vendor Check-In · Store #$storeId"
                            "vendor" -> selectedVendor?.name ?: "—"
                            "return" -> "Vendor Return #${currentReturnId ?: "—"}"
                            "order" -> "DSD Order #${currentOrderId ?: "—"}"
                            else -> ""
                        },
                        color = Color.Black, fontSize = 11.sp
                    )
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) { CircularProgressIndicator() }
        } else {
            when (view) {
                "landing" -> LandingView(
                    vendors = vendors.filter { it.active == 1 },
                    recentReturns = recentReturns,
                    recentOrders = recentOrders,
                    onPick = { v -> selectedVendor = v; view = "vendor" },
                    onResumeReturn = { r ->
                        selectedVendor = vendors.find { it.id == r.vendorId }
                        currentReturnId = r.id
                        scope.launch { refreshReturn(); view = "return" }
                    },
                    onResumeOrder = { o ->
                        selectedVendor = vendors.find { it.id == o.vendorId }
                        currentOrderId = o.id
                        scope.launch { refreshOrder(); view = "order" }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                "vendor" -> VendorHubView(
                    vendor = selectedVendor!!,
                    repName = repName,
                    onRepName = { repName = it },
                    vendorInv = vendorInv,
                    onStartReturn = {
                        scope.launch {
                            try {
                                val r = RetrofitClient.instance.createVendorReturn(storeId,
                                    CreateVendorReturnRequest(selectedVendor!!.id, repName.ifBlank { null }, null, null, MainActivity.loggedInEid.ifBlank { null }))
                                if (r.success && r.id != null) {
                                    currentReturnId = r.id
                                    returnItems = emptyList()
                                    view = "return"
                                } else showToast(r.message ?: "Failed to start return", false)
                            } catch (e: Exception) { showToast(e.message ?: "Error", false) }
                        }
                    },
                    onStartOrder = {
                        scope.launch {
                            try {
                                val r = RetrofitClient.instance.createVendorOrder(storeId,
                                    CreateVendorOrderRequest(selectedVendor!!.id, repName.ifBlank { null }, null, MainActivity.loggedInEid.ifBlank { null }))
                                if (r.success && r.id != null) {
                                    currentOrderId = r.id
                                    orderItems = emptyList()
                                    view = "order"
                                } else showToast(r.message ?: "Failed to start order", false)
                            } catch (e: Exception) { showToast(e.message ?: "Error", false) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                "return" -> ReturnSessionView(
                    items = returnItems,
                    onRemove = { itemId ->
                        scope.launch {
                            try {
                                RetrofitClient.instance.removeVendorReturnItem(storeId, currentReturnId!!, itemId)
                                refreshReturn()
                            } catch (e: Exception) { showToast(e.message ?: "Error", false) }
                        }
                    },
                    onClose = {
                        scope.launch {
                            try {
                                val r = RetrofitClient.instance.closeVendorReturn(storeId, currentReturnId!!,
                                    CloseVendorReturnRequest(MainActivity.loggedInEid.ifBlank { null }, null))
                                if (r.success) {
                                    try { RetrofitClient.instance.printVendorReturn(storeId, currentReturnId!!) } catch (_: Exception) {}
                                    showToast("Return closed — memo printing")
                                    currentReturnId = null; returnItems = emptyList()
                                    view = "landing"; loadLanding()
                                } else showToast(r.message ?: "Failed", false)
                            } catch (e: Exception) { showToast(e.message ?: "Error", false) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                "order" -> OrderSessionView(
                    items = orderItems,
                    onRemove = { itemId ->
                        scope.launch {
                            try {
                                RetrofitClient.instance.removeVendorOrderItem(storeId, currentOrderId!!, itemId)
                                refreshOrder()
                            } catch (e: Exception) { showToast(e.message ?: "Error", false) }
                        }
                    },
                    onSubmit = {
                        scope.launch {
                            try {
                                val r = RetrofitClient.instance.submitVendorOrder(storeId, currentOrderId!!,
                                    SubmitVendorOrderRequest(MainActivity.loggedInEid.ifBlank { null }))
                                if (r.success) {
                                    try { RetrofitClient.instance.printVendorOrder(storeId, currentOrderId!!) } catch (_: Exception) {}
                                    showToast("Order submitted — printing")
                                    currentOrderId = null; orderItems = emptyList()
                                    view = "landing"; loadLanding()
                                } else showToast(r.message ?: "Failed", false)
                            } catch (e: Exception) { showToast(e.message ?: "Error", false) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }

        // Toast bar
        toast?.let {
            Surface(
                color = if (toastOk) Color(0xFFECFDF5) else Color(0xFFFEF2F2),
                border = BorderStroke(1.dp, if (toastOk) Color(0xFF10B981) else Color(0xFFEF4444)),
                modifier = Modifier.fillMaxWidth()
            ) { Text(it, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
        }
    }

    // --- Return "add scanned item" dialog ---
    if (showReturnAdd && scannedItem != null && currentReturnId != null) {
        AlertDialog(
            onDismissRequest = { if (!submitting) { showReturnAdd = false; scannedItem = null } },
            title = { Text("Return: ${scannedItem!!.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("SKU ${scannedItem!!.sku}  ·  OH ${scannedItem!!.quantity}", fontSize = 11.sp, color = TextMuted)
                    Spacer(Modifier.height(4.dp))
                    Text("Quantity", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    BasicTextField(value = addQty, onValueChange = { addQty = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(120.dp).height(40.dp).border(1.dp, Color.Gray).padding(8.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("Reason", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Column {
                        VR_REASONS.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { rc ->
                                    val sel = addReason == rc
                                    Surface(
                                        color = if (sel) ReturnRed else Color(0xFFE2E8F0),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.weight(1f).clickable { addReason = rc }
                                    ) {
                                        Text(rc, color = if (sel) Color.White else Color.Black,
                                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 6.dp))
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    Text("Notes (optional)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    BasicTextField(value = addNotes, onValueChange = { addNotes = it },
                        modifier = Modifier.fillMaxWidth().height(40.dp).border(1.dp, Color.Gray).padding(8.dp))
                }
            },
            confirmButton = {
                Button(onClick = {
                    val q = addQty.toIntOrNull() ?: 0
                    if (q <= 0) { showToast("Enter a positive qty", false); return@Button }
                    submitting = true
                    scope.launch {
                        try {
                            val r = RetrofitClient.instance.addVendorReturnItem(storeId, currentReturnId!!,
                                AddVendorReturnItemRequest(scannedItem!!.sku, q, addReason, addNotes.ifBlank { null }, MainActivity.loggedInEid.ifBlank { null }))
                            if (r.success) { showReturnAdd = false; scannedItem = null; refreshReturn() }
                            else showToast(r.message ?: "Failed", false)
                        } catch (e: Exception) { showToast(e.message ?: "Error", false) }
                        finally { submitting = false }
                    }
                }, enabled = !submitting, colors = ButtonDefaults.buttonColors(containerColor = ReturnRed)) {
                    Text(if (submitting) "…" else "ADD")
                }
            },
            dismissButton = { TextButton(onClick = { showReturnAdd = false; scannedItem = null }) { Text("Cancel") } }
        )
    }

    // --- Order "add scanned item" dialog ---
    if (showOrderAdd && scannedItem != null && currentOrderId != null) {
        AlertDialog(
            onDismissRequest = { if (!submitting) { showOrderAdd = false; scannedItem = null } },
            title = { Text("Order: ${scannedItem!!.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("SKU ${scannedItem!!.sku}  ·  OH ${scannedItem!!.quantity}", fontSize = 11.sp, color = TextMuted)
                    Spacer(Modifier.height(4.dp))
                    Text("Qty to order", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    BasicTextField(value = addQty, onValueChange = { addQty = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(120.dp).height(40.dp).border(1.dp, Color.Gray).padding(8.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("Notes (optional)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    BasicTextField(value = addNotes, onValueChange = { addNotes = it },
                        modifier = Modifier.fillMaxWidth().height(40.dp).border(1.dp, Color.Gray).padding(8.dp))
                }
            },
            confirmButton = {
                Button(onClick = {
                    val q = addQty.toIntOrNull() ?: 0
                    if (q <= 0) { showToast("Enter a positive qty", false); return@Button }
                    submitting = true
                    scope.launch {
                        try {
                            val r = RetrofitClient.instance.addVendorOrderItem(storeId, currentOrderId!!,
                                AddVendorOrderItemRequest(scannedItem!!.sku, q, addNotes.ifBlank { null }))
                            if (r.success) { showOrderAdd = false; scannedItem = null; refreshOrder() }
                            else showToast(r.message ?: "Failed", false)
                        } catch (e: Exception) { showToast(e.message ?: "Error", false) }
                        finally { submitting = false }
                    }
                }, enabled = !submitting, colors = ButtonDefaults.buttonColors(containerColor = OrderGreen)) {
                    Text(if (submitting) "…" else "ADD")
                }
            },
            dismissButton = { TextButton(onClick = { showOrderAdd = false; scannedItem = null }) { Text("Cancel") } }
        )
    }
}

// ================== SUB-VIEWS ==================

@Composable
private fun LandingView(
    vendors: List<Vendor>,
    recentReturns: List<VendorReturn>,
    recentOrders: List<VendorOrder>,
    onPick: (Vendor) -> Unit,
    onResumeReturn: (VendorReturn) -> Unit,
    onResumeOrder: (VendorOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.padding(12.dp)) {
        item {
            Text("Select Vendor", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            if (vendors.isEmpty()) {
                Text("No active vendors. Set them up in the backoffice Vendors module.", fontSize = 12.sp, color = TextMuted)
            }
        }
        items(vendors) { v ->
            Surface(
                color = Color.White,
                border = BorderStroke(1.dp, SlateBorder),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onPick(v) }
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = RespondYellow, shape = RoundedCornerShape(6.dp), modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(v.code.take(3), color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("${v.code}${v.deliverySchedule?.let { "  ·  $it" } ?: ""}", fontSize = 11.sp, color = TextMuted)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
                }
            }
        }

        item {
            Spacer(Modifier.height(14.dp))
            Text("Recent Returns", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            if (recentReturns.isEmpty()) Text("No returns yet.", fontSize = 12.sp, color = TextMuted)
        }
        items(recentReturns.take(5)) { r ->
            Surface(color = Color.White, border = BorderStroke(1.dp, SlateBorder), shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onResumeReturn(r) }) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("#${r.id} — ${r.vendorName ?: "—"}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        StatusPill(r.status)
                    }
                    Text("${r.itemCount} lines · ${r.unitCount} units · ${r.createdAt?.replace('T',' ')?.take(16) ?: ""}",
                        fontSize = 11.sp, color = TextMuted)
                }
            }
        }

        item {
            Spacer(Modifier.height(14.dp))
            Text("Recent Orders", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            if (recentOrders.isEmpty()) Text("No orders yet.", fontSize = 12.sp, color = TextMuted)
        }
        items(recentOrders.take(5)) { o ->
            Surface(color = Color.White, border = BorderStroke(1.dp, SlateBorder), shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onResumeOrder(o) }) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("#${o.id} — ${o.vendorName ?: "—"}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        StatusPill(o.status)
                    }
                    Text("${o.lineCount} lines · ${o.unitCount} units · ${o.createdAt?.replace('T',' ')?.take(16) ?: ""}",
                        fontSize = 11.sp, color = TextMuted)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun VendorHubView(
    vendor: Vendor,
    repName: String,
    onRepName: (String) -> Unit,
    vendorInv: List<VendorInventoryRow>,
    onStartReturn: () -> Unit,
    onStartOrder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Surface(color = Color.White, border = BorderStroke(1.dp, SlateBorder), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(vendor.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(vendor.code, fontSize = 11.sp, color = TextMuted, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                if (!vendor.contactName.isNullOrBlank()) Text("Contact: ${vendor.contactName}${vendor.contactPhone?.let { "  ·  $it" } ?: ""}", fontSize = 12.sp)
                if (!vendor.deliverySchedule.isNullOrBlank()) Text("Schedule: ${vendor.deliverySchedule}", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Vendor rep on site (optional)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(value = repName, onValueChange = onRepName,
            modifier = Modifier.fillMaxWidth().height(40.dp).border(1.dp, Color.Gray).padding(8.dp))

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onStartReturn,
                colors = ButtonDefaults.buttonColors(containerColor = ReturnRed),
                modifier = Modifier.weight(1f).height(80.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("VENDOR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("RETURNS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("pull bad product", color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp)
                }
            }
            Button(
                onClick = onStartOrder,
                colors = ButtonDefaults.buttonColors(containerColor = OrderGreen),
                modifier = Modifier.weight(1f).height(80.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DSD", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("ORDER", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("scan → request", color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("This vendor's SKUs in your store", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        if (vendorInv.isEmpty()) {
            Text("No SKUs tagged to this vendor yet. Tag them in the backoffice.", fontSize = 11.sp, color = TextMuted)
        } else {
            vendorInv.forEach { r ->
                val low = r.reorderMin != null && r.quantity <= (r.reorderMin ?: 0)
                Surface(color = Color.White, border = BorderStroke(1.dp, SlateBorder), shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name ?: r.sku, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("SKU ${r.sku} · Reorder ${r.reorderMin ?: "?"}/${r.reorderMax ?: "?"}", fontSize = 10.sp, color = TextMuted)
                        }
                        Text("${r.quantity}", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            color = if (low) Color(0xFFDC2626) else Color.Black)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ReturnSessionView(
    items: List<VendorReturnItem>,
    onRemove: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Scan prompt banner
        Surface(color = Color(0xFFFEF3C7), border = BorderStroke(2.dp, Color(0xFFF59E0B)), modifier = Modifier.fillMaxWidth().padding(8.dp), shape = RoundedCornerShape(8.dp)) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = Color(0xFF92400E))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Scan each bad item", fontWeight = FontWeight.Bold, color = Color(0xFF92400E))
                    Text("A dialog asks for qty + reason per scan.", fontSize = 11.sp, color = Color(0xFF92400E))
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp)) {
            if (items.isEmpty()) {
                item { Text("No items yet — scan to add.", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp)) }
            }
            items(items) { it ->
                Surface(color = Color.White, border = BorderStroke(1.dp, SlateBorder), shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it.name ?: it.sku, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("SKU ${it.sku}  ·  ${it.reasonCode}${it.notes?.let { n -> "  ·  $n" } ?: ""}", fontSize = 11.sp, color = TextMuted)
                        }
                        Text("×${it.quantity}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = ReturnRed)
                        TextButton(onClick = { onRemove(it.id) }) { Text("Remove", color = ReturnRed, fontSize = 11.sp) }
                    }
                }
            }
            item { Spacer(Modifier.height(60.dp)) }
        }

        Surface(color = SlateBg, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onClose,
                    enabled = items.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = ReturnRed),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("CLOSE RETURN & PRINT MEMO", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun OrderSessionView(
    items: List<VendorOrderItem>,
    onRemove: (Int) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Surface(color = Color(0xFFECFDF5), border = BorderStroke(2.dp, OrderGreen), modifier = Modifier.fillMaxWidth().padding(8.dp), shape = RoundedCornerShape(8.dp)) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = OrderGreen)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Scan low-stock items", fontWeight = FontWeight.Bold, color = Color(0xFF065F46))
                    Text("Qty defaults suggested based on reorder max.", fontSize = 11.sp, color = Color(0xFF065F46))
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp)) {
            if (items.isEmpty()) {
                item { Text("No items yet — scan to add.", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp)) }
            }
            items(items) { it ->
                Surface(color = Color.White, border = BorderStroke(1.dp, SlateBorder), shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it.name ?: it.sku, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("SKU ${it.sku}${it.onHand?.let { oh -> "  ·  OH $oh" } ?: ""}${it.notes?.let { n -> "  ·  $n" } ?: ""}",
                                fontSize = 11.sp, color = TextMuted)
                        }
                        Text("×${it.quantityRequested}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OrderGreen)
                        TextButton(onClick = { onRemove(it.id) }) { Text("Remove", color = Color(0xFFEF4444), fontSize = 11.sp) }
                    }
                }
            }
            item { Spacer(Modifier.height(60.dp)) }
        }

        Surface(color = SlateBg, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSubmit,
                    enabled = items.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = OrderGreen),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("SUBMIT ORDER & PRINT", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val (bg, fg) = when (status) {
        "OPEN" -> Color(0xFFDBEAFE) to Color(0xFF1E40AF)
        "CLOSED" -> Color(0xFFFEF3C7) to Color(0xFF92400E)
        "SUBMITTED", "FULFILLED" -> Color(0xFFD1FAE5) to Color(0xFF065F46)
        else -> Color(0xFFE2E8F0) to Color(0xFF475569)
    }
    Surface(color = bg, shape = RoundedCornerShape(10.dp)) {
        Text(status, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
