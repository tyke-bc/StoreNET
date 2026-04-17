package com.github.tyke_bc.hht

import kotlinx.coroutines.launch
import com.github.tyke_bc.hht.network.RetrofitClient
import com.github.tyke_bc.hht.network.InventoryItem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.github.tyke_bc.hht.ui.theme.*

@Composable
fun ScanScreen(storeId: String, onBackToLauncher: () -> Unit) {
    var selectedScreen by remember { mutableStateOf("Home") }
    var selectedTab by remember { mutableIntStateOf(0) }
    
    // Adjustments Tabs
    var selectedAdjustmentsTab by remember { mutableIntStateOf(0) }
    val adjustmentsTabs = listOf("Damages", "Store Use", "Donations")
    
    // Transfers Tabs
    var selectedTransfersTab by remember { mutableIntStateOf(0) }
    val transfersTabs = listOf("Outgoing", "Incoming", "Mis-Ship")
    
    val tabs = listOf("Main", "Sales History", "Locations", "General")

    // Picking State
    var pendingOrders by remember { mutableStateOf<List<com.github.tyke_bc.hht.network.OnlineOrder>>(emptyList()) }
    var selectedOrder by remember { mutableStateOf<com.github.tyke_bc.hht.network.OnlineOrder?>(null) }
    var orderItems by remember { mutableStateOf<List<com.github.tyke_bc.hht.network.OnlineOrderItem>>(emptyList()) }
    var isPickingLoading by remember { mutableStateOf(false) }
    var pickingError by remember { mutableStateOf<String?>(null) }

    // Truck Receiving State
    var manifestList by remember { mutableStateOf<List<com.github.tyke_bc.hht.network.TruckManifest>>(emptyList()) }
    var selectedManifest by remember { mutableStateOf<com.github.tyke_bc.hht.network.TruckManifest?>(null) }
    var manifestItems by remember { mutableStateOf<List<com.github.tyke_bc.hht.network.ManifestItem>>(emptyList()) }
    var isTruckLoading by remember { mutableStateOf(false) }
    var truckError by remember { mutableStateOf<String?>(null) }

    // Stocking State
    var rolltainerList by remember { mutableStateOf<List<com.github.tyke_bc.hht.network.Rolltainer>>(emptyList()) }
    var stockingStatusMessage by remember { mutableStateOf<String?>(null) }
    var stockingError by remember { mutableStateOf<String?>(null) }

    // Markdown / Pricing Event State
    var markdownEvents by remember { mutableStateOf<List<com.github.tyke_bc.hht.network.PricingEvent>>(emptyList()) }
    var markdownItem by remember { mutableStateOf<InventoryItem?>(null) }
    var showMarkdownDialog by remember { mutableStateOf(false) }

    // Tasks State
    var tasksList by remember { mutableStateOf<List<com.github.tyke_bc.hht.network.Task>>(emptyList()) }
    var tasksLoading by remember { mutableStateOf(false) }
    var tasksError by remember { mutableStateOf<String?>(null) }

    // POG Reset Scan State
    var resetStatusMessage by remember { mutableStateOf<String?>(null) }
    var resetStatusSuccess by remember { mutableStateOf(true) }
    var resetAlreadyDoneInfo by remember { mutableStateOf<com.github.tyke_bc.hht.network.ResetScanResponse?>(null) }
    var resetDetailView by remember { mutableStateOf<com.github.tyke_bc.hht.network.Task?>(null) }

    // Cycle Count State
    var cycleCountBarcode by remember { mutableStateOf("") }
    var cycleCountPogId by remember { mutableStateOf("") }
    var cycleCountSection by remember { mutableStateOf("") }
    var cycleCountPogName by remember { mutableStateOf("") }
    var cycleCountItems by remember { mutableStateOf<List<CycleCountItemState>>(emptyList()) }
    var cycleCountLoading by remember { mutableStateOf(false) }
    var cycleCountError by remember { mutableStateOf<String?>(null) }
    val cycleCountCounts = remember { androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>() }
    var showCycleCountDialog by remember { mutableStateOf(false) }
    var cycleCountDialogItem by remember { mutableStateOf<CycleCountItemState?>(null) }
    var cycleCountDialogInput by remember { mutableStateOf("") }
    var cycleCountSubmitting by remember { mutableStateOf(false) }

    var upcInput by remember { mutableStateOf("") }
    var item by remember { mutableStateOf<InventoryItem?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    var adjustmentUpcInput by remember { mutableStateOf("") }
    var receivingBolInput by remember { mutableStateOf("") }
    var countsUpcInput by remember { mutableStateOf("") }
    var prpScannedUpc by remember { mutableStateOf("") }
    var vendorDeliveryScannedUpc by remember { mutableStateOf("") }
    var isMenuOpen by remember { mutableStateOf(false) }

    fun performSearch(input: String) {
        if (input.isNotEmpty()) {
            coroutineScope.launch {
                isLoading = true
                errorMessage = null
                var cleanedInput = input.trim()

                // --- WAREHOUSE LABEL DETECTOR ---
                if (cleanedInput.length == 18 && cleanedInput.startsWith("0000") && cleanedInput.all { it.isDigit() }) {
                    cleanedInput = cleanedInput.substring(4, 16)
                }

                try {
                    var response = RetrofitClient.instance.getInventoryItem(storeId, cleanedInput)
                    if (!response.success && cleanedInput.endsWith("0")) {
                        val fallbackResponse = RetrofitClient.instance.getInventoryItem(storeId, cleanedInput.dropLast(1))
                        if (fallbackResponse.success) response = fallbackResponse
                    }

                    if (response.success) {
                        item = response.item
                        upcInput = item?.upc ?: item?.sku ?: ""
                        // Check for active markdown / pricing events
                        val sku = item?.sku
                        if (sku != null) {
                            try {
                                val evRes = RetrofitClient.instance.checkPricingEvents(storeId, sku)
                                if (evRes.success && !evRes.events.isNullOrEmpty()) {
                                    markdownEvents = evRes.events!!
                                    markdownItem = item
                                    showMarkdownDialog = true
                                }
                            } catch (_: Exception) {}
                        }
                    } else {
                        errorMessage = "Item not found"
                        item = null
                        upcInput = cleanedInput
                    }
                } catch (e: Exception) {
                    errorMessage = "Error: ${e.message}"
                    item = null
                } finally {
                    isLoading = false
                }
            }
        }
    }

    // Dialog States for Smart Scans
    var showReceivingDialog by remember { mutableStateOf(false) }
    var showStockingDialog by remember { mutableStateOf(false) }
    var showRTStockingDialog by remember { mutableStateOf(false) }
    var detectedManifestId by remember { mutableIntStateOf(0) }
    var detectedRTBarcode by remember { mutableStateOf("") }
    var detectedSku by remember { mutableStateOf("") }
    var detectedPackSize by remember { mutableIntStateOf(1) }

    LaunchedEffect(cycleCountBarcode) {
        if (cycleCountBarcode.isNotEmpty()) {
            cycleCountLoading = true
            cycleCountError = null
            cycleCountItems = emptyList()
            try {
                val res = RetrofitClient.instance.resolveCycleBarcode(storeId, cycleCountBarcode)
                if (res.success) {
                    cycleCountPogId = res.pogId ?: ""
                    cycleCountPogName = res.pogName ?: ""
                    cycleCountSection = res.section ?: ""
                    cycleCountItems = res.items?.map {
                        CycleCountItemState(it.sku, it.name, it.upc, it.shelf, it.faces, it.quantity)
                    } ?: emptyList()
                } else {
                    cycleCountError = res.message ?: "Failed to load"
                }
            } catch (e: Exception) {
                cycleCountError = e.message ?: "Network error"
            } finally {
                cycleCountLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        MainActivity.scanEvents.collect { scannedData ->
            val input = scannedData.trim()

            // Section barcodes can be scanned from any screen
            if (input.startsWith("CYCL_")) {
                cycleCountCounts.clear()
                cycleCountBarcode = input.removePrefix("CYCL_")
                selectedScreen = "Cycle Count"
                return@collect
            }

            // Planogram reset tag: 6-digit numeric barcode matches a planogram ID.
            // Try the reset endpoint first; on 'not_found' fall through to normal handling.
            if (input.matches(Regex("^\\d{6}$"))) {
                try {
                    val eidOrNull = MainActivity.loggedInEid.ifBlank { null }
                    val res = RetrofitClient.instance.scanResetTag(
                        storeId,
                        com.github.tyke_bc.hht.network.ResetScanRequest(input, eidOrNull)
                    )
                    when (res.status) {
                        "applied" -> {
                            resetStatusSuccess = true
                            resetStatusMessage = "POG ${res.pogId}${res.pogName?.let { " — $it" } ?: ""} applied. " +
                                "Progress: ${res.completed}/${res.total}. Put up the shelf strips; scanning items will show the new positions."
                            if (selectedScreen == "Tasks") {
                                try { tasksList = RetrofitClient.instance.getTasks(storeId) } catch (_: Exception) {}
                            }
                            return@collect
                        }
                        "completed" -> {
                            resetStatusSuccess = true
                            resetStatusMessage = "Reset complete! Last POG scanned (${res.completed}/${res.total}). " +
                                "Signoff sheet sent to the office printer. Hand to SM when everyone has signed."
                            if (selectedScreen == "Tasks") {
                                try { tasksList = RetrofitClient.instance.getTasks(storeId) } catch (_: Exception) {}
                            }
                            return@collect
                        }
                        "already_done" -> {
                            resetAlreadyDoneInfo = res
                            return@collect
                        }
                        "not_found" -> {
                            // No open reset for this 6-digit code. Fall through to normal scan handling
                            // so the user still sees an inventory search result (or a clear not-found).
                        }
                        else -> {
                            resetStatusSuccess = false
                            resetStatusMessage = res.message ?: "Reset scan failed."
                            return@collect
                        }
                    }
                } catch (e: Exception) {
                    resetStatusSuccess = false
                    resetStatusMessage = "Reset scan error: ${e.message}"
                    return@collect
                }
            }

            if (selectedScreen == "Home") {
                if (input.startsWith("RT-") || (input.length == 15 && input.startsWith("ROL"))) {
                    detectedRTBarcode = input
                    showRTStockingDialog = true
                } else {
                    // Check if it's a warehouse label (18 digits, starts with 0000, UPC at positions 4-16)
                    var cleanedScan = input
                    var isWarehouseLabel = false
                    if (input.length == 18 && input.startsWith("0000") && input.all { it.isDigit() }) {
                        cleanedScan = input.substring(4, 16)
                        isWarehouseLabel = true
                    }
                    
                    // Always perform the search first to show the item
                    performSearch(cleanedScan)

                    // If it was a warehouse label, check for manifest or backstock
                    if (isWarehouseLabel) {
                        coroutineScope.launch {
                            try {
                                // Resolve the actual SKU via inventory lookup
                                val invRes = RetrofitClient.instance.getInventoryItem(storeId, cleanedScan)
                                if (!invRes.success || invRes.item == null) {
                                    errorMessage = "WH: item not found for $cleanedScan"
                                    return@launch
                                }
                                val resolvedSku = invRes.item.sku

                                val manifests = RetrofitClient.instance.getManifests(storeId)
                                val pendingTrk = manifests.find { it.status != "COMPLETED" }
                                if (pendingTrk != null) {
                                    detectedManifestId = pendingTrk.id
                                    detectedSku = resolvedSku
                                    showReceivingDialog = true
                                } else {
                                    val bs = invRes.item.quantityBackstock ?: 0
                                    if (bs > 0) {
                                        detectedSku = resolvedSku
                                        detectedPackSize = invRes.item.packSize ?: 1
                                        showStockingDialog = true
                                    } else {
                                        errorMessage = "WH: $resolvedSku backstock=$bs, no action"
                                    }
                                }
                            } catch (e: Exception) { errorMessage = "WH error: ${e.message}" }
                        }
                    }
                }
            } else if (selectedScreen == "Order Picking" && selectedOrder != null) {
                coroutineScope.launch {
                    try {
                        val cleanedScan = scannedData.trim()
                        val res = RetrofitClient.instance.pickItem(storeId, selectedOrder!!.id, com.github.tyke_bc.hht.network.PickRequest(cleanedScan))
                        if (res.success) {
                            pickingError = null // Clear any previous error
                            val detail = RetrofitClient.instance.getOrderDetails(storeId, selectedOrder!!.id)
                            if (detail.success) {
                                orderItems = detail.items ?: emptyList()
                            }
                        } else {
                            pickingError = res.message ?: "Failed to pick item"
                        }
                    } catch (e: Exception) { 
                        pickingError = "Scan Error: ${e.message}"
                    }
                }
            } else {
                when (selectedScreen) {
                    "Adjustments" -> adjustmentUpcInput = scannedData
                    "Receiving" -> receivingBolInput = scannedData
                    "Counts/Recalls" -> countsUpcInput = scannedData
                    "PRP Returns" -> prpScannedUpc = input
                    "Vendor Delivery" -> vendorDeliveryScannedUpc = input
                    "Cycle Count" -> {
                        val found = cycleCountItems.find { it.upc == input || it.sku == input }
                        if (found != null) {
                            cycleCountDialogItem = found
                            cycleCountDialogInput = cycleCountCounts[found.sku] ?: ""
                            showCycleCountDialog = true
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(selectedScreen, selectedTab) {
        if (selectedScreen == "Order Picking") {
            isPickingLoading = true
            pickingError = null
            try {
                pendingOrders = RetrofitClient.instance.getPendingOrders(storeId)
            } catch (e: Exception) {
                pickingError = "Failed to load orders: ${e.message}"
            } finally { isPickingLoading = false }
        }
        if (selectedScreen == "Home" && selectedTab == 4) {
            isTruckLoading = true
            truckError = null
            try {
                manifestList = RetrofitClient.instance.getManifests(storeId)
            } catch (e: Exception) {
                truckError = "Failed to load manifests: ${e.message}"
            } finally { isTruckLoading = false }
        }
        if (selectedScreen == "Tasks") {
            tasksLoading = true
            tasksError = null
            try { tasksList = RetrofitClient.instance.getTasks(storeId) }
            catch (e: Exception) { tasksError = e.message }
            finally { tasksLoading = false }
        }
        // Keep the Home banner count fresh whenever user lands on Home.
        if (selectedScreen == "Home") {
            try { tasksList = RetrofitClient.instance.getTasks(storeId) } catch (_: Exception) {}
        }
        if (selectedScreen == "Home" && selectedTab == 5) {
            coroutineScope.launch {
                try {
                    rolltainerList = RetrofitClient.instance.getRolltainers(storeId)
                } catch (e: Exception) {
                    stockingError = "Failed to load rolltainers"
                }
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalStoreId provides storeId) {
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            content = { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFE8E8E8))
                        .padding(padding)
                ) {
                    // Top Header Area
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(brush = Brush.verticalGradient(colors = listOf(DGYellow, Color(0xFFFFE082))))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "STORE: $storeId", fontSize = 18.sp, color = Color.Black)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = MainActivity.loggedInUser, fontSize = 18.sp, color = Color.Black)
                                Spacer(modifier = Modifier.width(16.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onBackToLauncher() }) {
                                    Icon(Icons.AutoMirrored.Filled.ExitToApp, "Logout", tint = Color.Black, modifier = Modifier.size(28.dp))
                                    Text("LOGOUT", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp), color = DGBlue,
                                    modifier = Modifier.size(width = 48.dp, height = 40.dp).clickable { isMenuOpen = !isMenuOpen },
                                    shadowElevation = 2.dp
                                ) {
                                    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Icon(imageVector = if (isMenuOpen) Icons.Default.KeyboardArrowUp else Icons.Default.Menu, contentDescription = "Menu", tint = Color.White, modifier = Modifier.size(20.dp))
                                        Text("MENU", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(selectedScreen, fontSize = 20.sp, color = Color.Black)
                            }
                            Icon(Icons.Default.Notifications, "Notifications", tint = DGBlue, modifier = Modifier.size(36.dp))
                        }
                    }

                    when (selectedScreen) {
                        "Home" -> {
                            // Open-task banner: surfaced on Home so employees see new work without hunting for it.
                            val openTasks = tasksList.filter { it.status == "OPEN" }
                            if (openTasks.isNotEmpty()) {
                                val resetCount = openTasks.count { it.taskType == "POG_RESET" }
                                val generalCount = openTasks.size - resetCount
                                val bannerColor = if (resetCount > 0) Color(0xFF8B5CF6) else Color(0xFF2563EB)
                                Surface(
                                    color = bannerColor,
                                    modifier = Modifier.fillMaxWidth().clickable { selectedScreen = "Tasks" }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = "${openTasks.size} open task${if (openTasks.size == 1) "" else "s"}",
                                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp
                                            )
                                            val subtitle = buildString {
                                                if (resetCount > 0) append("$resetCount planogram reset${if (resetCount == 1) "" else "s"}")
                                                if (resetCount > 0 && generalCount > 0) append(" · ")
                                                if (generalCount > 0) append("$generalCount general")
                                            }
                                            if (subtitle.isNotEmpty()) {
                                                Text(text = subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
                                            }
                                        }
                                        Text("View Tasks ›", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFD6D6D6)).border(BorderStroke(1.dp, Color.Gray))) {
                                tabs.forEachIndexed { index, title ->
                                    val isSelected = selectedTab == index
                                    Box(modifier = Modifier.weight(1f).background(if (isSelected) Color(0xFFE8E8E8) else Color.Transparent).clickable { selectedTab = index }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                        Text(text = title, fontSize = 14.sp, color = if (isSelected) Color.Black else DGBlue)
                                    }
                                }
                            }
                            when (selectedTab) {
                                0 -> ProductMainContent(storeId, upcInput, { upcInput = it }, item, isLoading, errorMessage, ::performSearch)
                                1 -> SalesHistoryContent(storeId, item)
                                2 -> LocationsContent(item)
                                3 -> GeneralContent(item)
                                else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Tab $selectedTab coming soon", color = Color.Gray) }
                            }
                        }
                        "Adjustments" -> {
                            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFD6D6D6)).border(BorderStroke(1.dp, Color.Gray))) {
                                adjustmentsTabs.forEachIndexed { index, title ->
                                    val isSelected = selectedAdjustmentsTab == index
                                    Box(modifier = Modifier.weight(1f).background(if (isSelected) Color(0xFFE8E8E8) else Color.Transparent).clickable { selectedAdjustmentsTab = index }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                        Text(text = title, fontSize = 14.sp, color = if (isSelected) Color.Black else DGBlue)
                                    }
                                }
                            }
                            if (selectedAdjustmentsTab == 0) AdjustmentsDamagesContent(adjustmentUpcInput, { adjustmentUpcInput = it })
                            else AdjustmentsStoreUseContent(adjustmentUpcInput, { adjustmentUpcInput = it })
                        }
                        "Receiving" -> {
                            ReceivingBOLContent(
                                storeId = storeId,
                                bolInput = receivingBolInput,
                                onBolChange = { receivingBolInput = it },
                                onMasterReceiveBol = { bol ->
                                    coroutineScope.launch {
                                        truckError = null
                                        isTruckLoading = true
                                        try {
                                            val res = RetrofitClient.instance.masterReceive(storeId, com.github.tyke_bc.hht.network.MasterReceiveRequest(bol, null))
                                            if (res.success) {
                                                stockingStatusMessage = res.message
                                                receivingBolInput = ""
                                                selectedScreen = "Home"
                                            } else {
                                                truckError = res.message
                                            }
                                        } catch (e: Exception) {
                                            truckError = "Network Error"
                                        } finally { isTruckLoading = false }
                                    }
                                },
                                onMasterReceiveId = { manifestId ->
                                    coroutineScope.launch {
                                        truckError = null
                                        isTruckLoading = true
                                        try {
                                            val res = RetrofitClient.instance.masterReceive(storeId, com.github.tyke_bc.hht.network.MasterReceiveRequest(null, manifestId))
                                            if (res.success) {
                                                stockingStatusMessage = res.message
                                                receivingBolInput = ""
                                                selectedScreen = "Home"
                                            } else {
                                                truckError = res.message
                                            }
                                        } catch (e: Exception) {
                                            truckError = "Network Error"
                                        } finally { isTruckLoading = false }
                                    }
                                }
                            )
                        }
                        "Counts / Recalls" -> {
                            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFD6D6D6)).border(BorderStroke(1.dp, Color.Gray))) {
                                Box(modifier = Modifier.weight(1f).background(Color(0xFFE8E8E8)).padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Text("Precount", fontSize = 14.sp, color = Color.Black) }
                                Spacer(modifier = Modifier.weight(3f))
                            }
                            CountsRecallsContent(countsUpcInput, { countsUpcInput = it })
                        }
                        "Transfers" -> {
                            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFD6D6D6)).border(BorderStroke(1.dp, Color.Gray))) {
                                transfersTabs.forEachIndexed { index, title ->
                                    val isSelected = selectedTransfersTab == index
                                    Box(modifier = Modifier.weight(1f).background(if (isSelected) Color(0xFFE8E8E8) else Color.Transparent).clickable { selectedTransfersTab = index }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                        Text(text = title, fontSize = 14.sp, color = if (isSelected) Color.Black else DGBlue)
                                    }
                                }
                            }
                            when (selectedTransfersTab) {
                                0 -> TransfersOutgoingContent()
                                1 -> TransfersIncomingContent()
                                2 -> TransfersMisShipContent()
                            }
                        }
                        "Order Picking" -> {
                            PickingContent(
                                storeId = storeId,
                                pendingOrders = pendingOrders,
                                selectedOrder = selectedOrder,
                                orderItems = orderItems,
                                isLoading = isPickingLoading,
                                errorMessage = pickingError,
                                onOrderSelected = { order ->
                                    selectedOrder = order
                                    coroutineScope.launch {
                                        isPickingLoading = true
                                        pickingError = null
                                        try {
                                            val detail = RetrofitClient.instance.getOrderDetails(storeId, order.id)
                                            if (detail.success) orderItems = detail.items ?: emptyList()
                                            else pickingError = detail.message ?: "Failed to load details"
                                        } catch (e: Exception) {
                                            pickingError = "Error loading details: ${e.message}"
                                        } finally { isPickingLoading = false }
                                    }
                                },
                                onBackToList = { selectedOrder = null; orderItems = emptyList(); pickingError = null },
                                onFinalize = {
                                    coroutineScope.launch {
                                        isPickingLoading = true
                                        pickingError = null
                                        try {
                                            val res = RetrofitClient.instance.finalizeOrder(storeId, selectedOrder!!.id)
                                            if (res.success) {
                                                selectedOrder = null
                                                orderItems = emptyList()
                                                pendingOrders = RetrofitClient.instance.getPendingOrders(storeId)
                                            } else {
                                                pickingError = res.message ?: "Finalization failed"
                                            }
                                        } catch (e: Exception) {
                                            pickingError = "Finalization error: ${e.message}"
                                        } finally { isPickingLoading = false }
                                    }
                                }
                            )
                        }
                        "Tasks" -> {
                            val priorityColor = mapOf("HIGH" to Color(0xFFEF4444), "NORMAL" to Color(0xFFF59E0B), "LOW" to Color(0xFF94A3B8))
                            Column(modifier = Modifier.fillMaxSize()) {
                                Surface(color = Color(0xFF1E3A5F), modifier = Modifier.fillMaxWidth()) {
                                    Text("Open Tasks", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(12.dp))
                                }
                                when {
                                    tasksLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                                    tasksError != null -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Error: $tasksError", color = Color.Red) }
                                    tasksList.filter { it.status == "OPEN" }.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No open tasks.", color = Color.Gray) }
                                    else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                                        items(tasksList.filter { it.status == "OPEN" }) { task ->
                                            val isReset = task.taskType == "POG_RESET"
                                            val children = task.pogItems ?: emptyList()
                                            val doneCount = children.count { it.scannedAt != null }
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = Color.White,
                                                border = BorderStroke(1.dp, if (isReset) Color(0xFF8B5CF6) else (priorityColor[task.priority] ?: Color(0xFFCBD5E1))),
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                                    .then(if (isReset) Modifier.clickable { resetDetailView = task } else Modifier)
                                            ) {
                                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                            Surface(shape = RoundedCornerShape(4.dp), color = priorityColor[task.priority] ?: Color.Gray) {
                                                                Text(task.priority, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                            }
                                                            if (isReset) {
                                                                Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF8B5CF6)) {
                                                                    Text("POG RESET $doneCount/${children.size}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                                }
                                                            }
                                                            Text(task.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                                        }
                                                        if (!task.description.isNullOrBlank()) Text(task.description, fontSize = 12.sp, color = Color(0xFF64748B), modifier = Modifier.padding(top = 2.dp))
                                                        if (isReset) {
                                                            Text(
                                                                children.joinToString("  ·  ") { "${if (it.scannedAt != null) "✓" else "○"} ${it.pogId}" },
                                                                fontSize = 11.sp, color = Color(0xFF64748B), modifier = Modifier.padding(top = 2.dp)
                                                            )
                                                            Text("Scan each POG tag to complete", fontSize = 10.sp, color = Color(0xFF8B5CF6), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                                                        }
                                                        if (task.assignedName != null || task.dueDate != null) {
                                                            Text(listOfNotNull(task.assignedName?.let { "Assigned: $it" }, task.dueDate?.take(10)?.let { "Due: $it" }).joinToString("  ·  "), fontSize = 11.sp, color = Color(0xFF94A3B8), modifier = Modifier.padding(top = 2.dp))
                                                        }
                                                    }
                                                    if (!isReset) {
                                                        Button(
                                                            onClick = {
                                                                coroutineScope.launch {
                                                                    try {
                                                                        RetrofitClient.instance.updateTask(storeId, task.id, com.github.tyke_bc.hht.network.UpdateTaskRequest("DONE"))
                                                                        tasksList = RetrofitClient.instance.getTasks(storeId)
                                                                    } catch (_: Exception) {}
                                                                }
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                                            modifier = Modifier.padding(start = 8.dp)
                                                        ) { Text("Done", fontSize = 12.sp) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "Cycle Count" -> {
                            val listState = rememberLazyListState()
                            val countedCount = cycleCountItems.count { cycleCountCounts.containsKey(it.sku) }
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Header bar
                                Surface(color = Color(0xFF1E3A5F), modifier = Modifier.fillMaxWidth()) {
                                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column {
                                            Text(if (cycleCountSection.isNotEmpty()) "POG $cycleCountPogId · SEC $cycleCountSection" else "POG $cycleCountPogId", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                            if (cycleCountPogName.isNotEmpty()) Text(cycleCountPogName, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                                        }
                                        Text("$countedCount / ${cycleCountItems.size} counted", color = Color(0xFF90CAF9), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                                when {
                                    cycleCountLoading -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                    cycleCountError != null -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Error: $cycleCountError", color = Color.Red) }
                                    cycleCountItems.isEmpty() -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No items in this section", color = Color.Gray) }
                                    else -> LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                        items(cycleCountItems) { ccItem ->
                                            val counted = cycleCountCounts[ccItem.sku]
                                            val isCounted = counted != null
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isCounted) Color(0xFFE8F5E9) else Color.White,
                                                border = BorderStroke(1.dp, if (isCounted) Color(0xFF66BB6A) else Color(0xFFCBD5E1)),
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                                    cycleCountDialogItem = ccItem
                                                    cycleCountDialogInput = cycleCountCounts[ccItem.sku] ?: ""
                                                    showCycleCountDialog = true
                                                }
                                            ) {
                                                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(ccItem.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF1E293B))
                                                        Text("${if (ccItem.shelf != null) "Shelf ${ccItem.shelf}  ·  " else ""}SKU ${ccItem.sku}", fontSize = 11.sp, color = Color(0xFF64748B))
                                                    }
                                                    Column(horizontalAlignment = Alignment.End) {
                                                        if (isCounted) Text(counted!!, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFF2E7D32))
                                                        else Text("—", fontSize = 20.sp, color = Color(0xFFCBD5E1))
                                                        Text("sys: ${ccItem.systemQty}", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                // Submit bar
                                Surface(color = Color(0xFFF8FAFC), shadowElevation = 8.dp) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedButton(onClick = { selectedScreen = "Home"; cycleCountCounts.clear() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    cycleCountSubmitting = true
                                                    try {
                                                        val entries = cycleCountCounts.entries.mapNotNull { (sku, v) ->
                                                            v.toIntOrNull()?.let { com.github.tyke_bc.hht.network.CycleCountEntry(sku, it) }
                                                        }
                                                        if (entries.isNotEmpty()) {
                                                            RetrofitClient.instance.submitCycleCount(storeId, com.github.tyke_bc.hht.network.CycleCountSubmitRequest(entries))
                                                        }
                                                        selectedScreen = "Home"
                                                        cycleCountCounts.clear()
                                                    } catch (e: Exception) {
                                                        cycleCountError = e.message
                                                    } finally { cycleCountSubmitting = false }
                                                }
                                            },
                                            enabled = countedCount > 0 && !cycleCountSubmitting,
                                            modifier = Modifier.weight(2f)
                                        ) {
                                            if (cycleCountSubmitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                            else Text("Submit $countedCount Count${if (countedCount != 1) "s" else ""}")
                                        }
                                    }
                                }
                            }
                        }
                        "Transfers" -> TransfersContent(storeId)
                        "Review" -> ReviewContent(storeId)
                        "Nones & Tons" -> NonesAndTonsContent(storeId)
                        "Cooler Freezer / Safety Walk" -> SafetyWalkContent(storeId, "COOLER_FREEZER_WALK")
                        "Compliance Check" -> ComplianceCheckContent(storeId)
                        "Refrigeration Maintenance" -> RefrigerationMaintenanceContent(storeId)
                        "PRP Returns" -> PrpReturnsContent(storeId, prpScannedUpc) { prpScannedUpc = "" }
                        "Vendor Delivery" -> VendorDeliveryContent(storeId, vendorDeliveryScannedUpc) { vendorDeliveryScannedUpc = "" }
                        else -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("$selectedScreen Screen Coming Soon", color = Color.Gray, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
        )

        if (isMenuOpen) {
            Box(modifier = Modifier.fillMaxSize().clickable { isMenuOpen = false }.background(Color.Black.copy(alpha = 0.1f)).zIndex(10f)) {
                Surface(modifier = Modifier.padding(start = 12.dp, top = 110.dp).width(280.dp).wrapContentHeight(), shape = RoundedCornerShape(4.dp), color = Color(0xFFF0F0F0), border = BorderStroke(1.dp, Color.Gray), shadowElevation = 8.dp) {
                    Column(modifier = Modifier.padding(vertical = 8.dp).verticalScroll(rememberScrollState())) {
                        NavMenuItem(Icons.Default.Home, "Home", DGBlue) { selectedScreen = "Home"; isMenuOpen = false }
                        NavMenuItem(Icons.Default.Settings, "Adjustment", DGBlue) { selectedScreen = "Adjustments"; isMenuOpen = false }
                        NavMenuItem(Icons.Default.LocalShipping, "Receiving", Color(0xFFFFC107)) { selectedScreen = "Receiving"; isMenuOpen = false }
                        NavMenuItem(Icons.Default.Calculate, "Counts / Recalls", Color(0xFF5D4037)) { selectedScreen = "Counts / Recalls"; isMenuOpen = false }
                        NavMenuItem(Icons.AutoMirrored.Filled.Send, "Transfers", DGBlue) { selectedScreen = "Transfers"; isMenuOpen = false }
                        NavMenuItem(Icons.Default.Assignment, "Review", DGBlue) { selectedScreen = "Review"; isMenuOpen = false }
                        NavMenuItem(Icons.Default.Cloud, "Nones & Tons", DGBlue) { selectedScreen = "Nones & Tons"; isMenuOpen = false }
                        NavMenuItem(Icons.Default.CheckBox, "Cooler Freezer / Safety Walk", Color(0xFFFBC02D)) { selectedScreen = "Cooler Freezer / Safety Walk"; isMenuOpen = false }
                        NavMenuItem(Icons.Default.FactCheck, "Compliance Check", Color(0xFFFF6F00)) { selectedScreen = "Compliance Check"; isMenuOpen = false }
                        NavMenuItem(Icons.Default.Build, "Refrigeration Maintenance", DGBlue) { selectedScreen = "Refrigeration Maintenance"; isMenuOpen = false }
                        NavMenuItem(Icons.Default.Inventory, "PRP Returns", Color(0xFF5D4037)) { selectedScreen = "PRP Returns"; isMenuOpen = false }
                        NavMenuItem(Icons.Default.LocalShipping, "Vendor Delivery", Color(0xFF059669)) { selectedScreen = "Vendor Delivery"; isMenuOpen = false }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        NavMenuItem(Icons.Default.ShoppingCart, "Order Picking", Color(0xFF10B981)) { selectedScreen = "Order Picking"; isMenuOpen = false }
                        NavMenuItem(Icons.Default.FactCheck, "Tasks", Color(0xFF6366F1)) { selectedScreen = "Tasks"; isMenuOpen = false }
                    }
                }
            }
        }

        if (showMarkdownDialog && markdownItem != null && markdownEvents.isNotEmpty()) {
            val ev = markdownEvents.first()
            val mi = markdownItem!!
            AlertDialog(
                onDismissRequest = { showMarkdownDialog = false },
                title = { Text("Markdown Available", color = Color(0xFF10B981), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(mi.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        HorizontalDivider()
                        Text(ev.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(ev.type, fontSize = 11.sp, color = Color.Gray)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("$${"%.2f".format(mi.price)}", fontSize = 14.sp, color = Color.Gray, textDecoration = TextDecoration.LineThrough)
                            Text("$${"%.2f".format(ev.price)}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        coroutineScope.launch {
                            try {
                                RetrofitClient.instance.printShelfLabel(storeId, com.github.tyke_bc.hht.network.PrintShelfLabelRequest(
                                    brand = null, name = mi.name, variant = null, size = null,
                                    upc = mi.upc ?: mi.sku, price = ev.price,
                                    unitPriceUnit = mi.unitPriceUnit, taxable = mi.taxable,
                                    pogDate = mi.pogDate, location = mi.location,
                                    faces = mi.faces, pogInfo = mi.pogInfo, regPrice = mi.price
                                ))
                            } catch (_: Exception) {}
                            showMarkdownDialog = false
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))) { Text("Print Label") }
                },
                dismissButton = { TextButton(onClick = { showMarkdownDialog = false }) { Text("Dismiss") } }
            )
        }

        if (showCycleCountDialog && cycleCountDialogItem != null) {
            AlertDialog(
                onDismissRequest = { showCycleCountDialog = false },
                title = { Text(cycleCountDialogItem!!.name, fontSize = 15.sp) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${if (cycleCountDialogItem!!.shelf != null) "Shelf ${cycleCountDialogItem!!.shelf}  ·  " else ""}System qty: ${cycleCountDialogItem!!.systemQty}", fontSize = 12.sp, color = Color.Gray)
                        OutlinedTextField(
                            value = cycleCountDialogInput,
                            onValueChange = { cycleCountDialogInput = it.filter { c -> c.isDigit() } },
                            label = { Text("Count on shelf") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        cycleCountDialogItem?.let { cycleCountCounts[it.sku] = cycleCountDialogInput }
                        showCycleCountDialog = false
                    }, enabled = cycleCountDialogInput.isNotEmpty()) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showCycleCountDialog = false }) { Text("Cancel") }
                }
            )
        }

        if (showReceivingDialog) {
            AlertDialog(onDismissRequest = { showReceivingDialog = false }, title = { Text("Truck Manifest Detected") }, text = { Text("SKU ${detectedSku} is pending on a manifest. Receive 1 Box to Backstock?") }, confirmButton = {
                Button(onClick = {
                    coroutineScope.launch {
                        try {
                            val res = RetrofitClient.instance.receiveItem(storeId, detectedManifestId, com.github.tyke_bc.hht.network.PickRequest(detectedSku))
                            if (res.success) { showReceivingDialog = false; performSearch(detectedSku) }
                        } catch (e: Exception) { /* ignore */ }
                    }
                }) { Text("RECEIVE") }
            }, dismissButton = { Button(onClick = { showReceivingDialog = false }) { Text("CANCEL") } })
        }

        if (showStockingDialog) {
            AlertDialog(onDismissRequest = { showStockingDialog = false }, title = { Text("Item in Backstock") }, text = { Text("Stock 1 Box (+${detectedPackSize} units) to Live Inventory?") }, confirmButton = {
                Button(onClick = {
                    coroutineScope.launch {
                        try {
                            val res = RetrofitClient.instance.stockBox(storeId, com.github.tyke_bc.hht.network.PickRequest(detectedSku))
                            if (res.success) { showStockingDialog = false; performSearch(detectedSku) }
                        } catch (e: Exception) { /* ignore */ }
                    }
                }) { Text("STOCK TO FLOOR") }
            }, dismissButton = { Button(onClick = { showStockingDialog = false }) { Text("CANCEL") } })
        }

        if (showRTStockingDialog) {
            AlertDialog(onDismissRequest = { showRTStockingDialog = false }, title = { Text("Rolltainer Detected") }, text = { Text("Rolltainer ${detectedRTBarcode} detected. Stock all items to live inventory?") }, confirmButton = {
                Button(onClick = {
                    coroutineScope.launch {
                        try {
                            val res = RetrofitClient.instance.stockRolltainer(storeId, com.github.tyke_bc.hht.network.RolltainerRequest(detectedRTBarcode))
                            if (res.success) { showRTStockingDialog = false; stockingStatusMessage = "Rolltainer Stocked!" }
                        } catch (e: Exception) { /* ignore */ }
                    }
                }) { Text("STOCK ALL") }
            }, dismissButton = { Button(onClick = { showRTStockingDialog = false }) { Text("CANCEL") } })
        }

        // Reset scan status toast (applied / completed / error)
        if (resetStatusMessage != null) {
            AlertDialog(
                onDismissRequest = { resetStatusMessage = null },
                title = { Text(if (resetStatusSuccess) "Planogram Reset" else "Reset Scan Failed", color = if (resetStatusSuccess) Color(0xFF10B981) else Color(0xFFEF4444)) },
                text = { Text(resetStatusMessage ?: "") },
                confirmButton = { Button(onClick = { resetStatusMessage = null }) { Text("OK") } }
            )
        }

        // Already-done dialog — offers reprint
        val alreadyDone = resetAlreadyDoneInfo
        if (alreadyDone != null) {
            val who = alreadyDone.scannedByName ?: alreadyDone.scannedByEid ?: "unknown"
            val when_ = alreadyDone.scannedAt?.replace('T', ' ')?.take(19) ?: "earlier"
            AlertDialog(
                onDismissRequest = { resetAlreadyDoneInfo = null },
                title = { Text("Already Completed") },
                text = {
                    Text("POG ${alreadyDone.pogId}${alreadyDone.pogName?.let { " — $it" } ?: ""} was already scanned on $when_ by $who.\n\nReprint the signoff sheet?")
                },
                confirmButton = {
                    Button(onClick = {
                        val taskId = alreadyDone.taskId ?: 0
                        resetAlreadyDoneInfo = null
                        if (taskId > 0) {
                            coroutineScope.launch {
                                try {
                                    val r = RetrofitClient.instance.reprintResetSignoff(storeId, taskId)
                                    resetStatusSuccess = r.success
                                    resetStatusMessage = r.message ?: if (r.success) "Signoff reprinted." else "Reprint failed."
                                } catch (e: Exception) {
                                    resetStatusSuccess = false
                                    resetStatusMessage = "Reprint error: ${e.message}"
                                }
                            }
                        }
                    }) { Text("REPRINT") }
                },
                dismissButton = { Button(onClick = { resetAlreadyDoneInfo = null }) { Text("NO") } }
            )
        }

        // Reset task detail dialog — shown when user taps a POG_RESET task in the list
        val detailTask = resetDetailView
        if (detailTask != null) {
            val children = detailTask.pogItems ?: emptyList()
            val doneCount = children.count { it.scannedAt != null }
            AlertDialog(
                onDismissRequest = { resetDetailView = null },
                title = { Text("${detailTask.title}  ($doneCount/${children.size})") },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(children) { c ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (c.scannedAt != null) "✓" else "○", color = if (c.scannedAt != null) Color(0xFF10B981) else Color(0xFF94A3B8), fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${c.pogId} — ${c.pogName ?: ""}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    if (c.scannedAt != null) {
                                        Text("by ${c.scannedByName ?: c.scannedByEid ?: "unknown"} @ ${c.scannedAt.replace('T', ' ').take(19)}", fontSize = 11.sp, color = Color(0xFF64748B))
                                    } else {
                                        Text("Pending scan", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { Button(onClick = { resetDetailView = null }) { Text("CLOSE") } }
            )
        }
    }
    } // end CompositionLocalProvider
}

@Composable
fun ReceivingBOLContent(
    storeId: String,
    bolInput: String,
    onBolChange: (String) -> Unit,
    onMasterReceiveBol: (String) -> Unit,
    onMasterReceiveId: (Int) -> Unit
) {
    var manifestList by remember { mutableStateOf<List<com.github.tyke_bc.hht.network.TruckManifest>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            manifestList = RetrofitClient.instance.getManifests(storeId).filter { it.status != "COMPLETED" }
        } catch (e: Exception) { /* ignore */ }
        finally { isLoading = false }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Receiving Management", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Scan a 15-digit BOL or select a pending manifest below.", color = Color.Gray, fontSize = 14.sp)

        Spacer(modifier = Modifier.height(16.dp))

        LaunchedEffect(bolInput) {
            val input = bolInput.trim()
            if (input.length == 15) {
                onMasterReceiveBol(input)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = bolInput,
                onValueChange = { 
                    val input = it.trim()
                    if (input.length <= 15) {
                        onBolChange(input)
                    } 
                },
                textStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                modifier = Modifier.weight(1f).height(60.dp).border(2.dp, DGBlue, RoundedCornerShape(8.dp)).padding(12.dp).background(Color.White)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { if (bolInput.length == 15) onMasterReceiveBol(bolInput) },
            enabled = bolInput.length == 15,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DGBlue)
        ) {
            Text("MASTER RECEIVE BY BOL", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Pending Manifests", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading) {
            CircularProgressIndicator(color = DGBlue, modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (manifestList.isEmpty()) {
            Text("No pending shipments found.", color = Color.Gray)
        } else {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                manifestList.forEach { m ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { 
                            if (!m.bolNumber.isNullOrEmpty()) onMasterReceiveBol(m.bolNumber)
                            else onMasterReceiveId(m.id)
                        },
                        color = Color.White, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color.Gray)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalShipping, "Truck", tint = DGBlue)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(m.manifestNumber, fontWeight = FontWeight.Bold)
                                Text("BOL: ${m.bolNumber ?: "None"}", fontSize = 12.sp, color = Color.Gray)
                            }
                            Text(m.status, color = if (m.status == "RECEIVING") Color(0xFFF59E0B) else Color.Gray, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProductMainContent(
storeId: String, upcInput: String, onUpcChange: (String) -> Unit, item: InventoryItem?, isLoading: Boolean, errorMessage: String?, onSearch: (String) -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var localError by remember { mutableStateOf<String?>(null) }
    var showAdjustDialog by remember { mutableStateOf(false) }
    var adjustQty by remember { mutableStateOf("") }
    var adjustEid by remember { mutableStateOf("") }
    var adjustPin by remember { mutableStateOf("") }
    var adjustError by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text = "UPC/SKU:", fontSize = 18.sp, color = DGBlue, modifier = Modifier.width(90.dp))       
            BasicTextField(value = upcInput, onValueChange = onUpcChange, textStyle = TextStyle(color = Color.Black, fontSize = 18.sp), singleLine = true, modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray, RoundedCornerShape(4.dp)).padding(8.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { onSearch(upcInput) }, modifier = Modifier.height(40.dp)) { Text("GO") }
        }
        Spacer(modifier = Modifier.height(24.dp))
        if (isLoading) {
            Text("Loading...", fontSize = 18.sp, color = Color.Gray)
        } else if ((errorMessage != null || localError != null) && item == null) {
            Text(errorMessage ?: localError!!, fontSize = 18.sp, color = Color.Red)
        } else if (item != null) {
            if (localError != null) Text(localError!!, fontSize = 16.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            Text(text = "PSKU: ${item.sku}", fontSize = 18.sp, color = Color.Black)
            Text(text = "UPC: ${item.upc ?: "N/A"}", fontSize = 18.sp, color = Color.Black)
            Text(text = "Desc: ${item.name}", fontSize = 18.sp, color = Color.Black)
            
            if (item.regPrice != null && item.regPrice > 0.0) { 
                Text(text = "Sale Price: $${item.price}", fontSize = 18.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                Text(text = "Reg Price: $${item.regPrice}", fontSize = 18.sp, color = Color.Black, textDecoration = TextDecoration.LineThrough)
            } else {
                Text(text = "Regular Price: $${item.price}", fontSize = 18.sp, color = Color.Black)
            }
            
            Text(text = "Dept: ${item.department}", fontSize = 18.sp, color = Color.Black)
            Text(text = "OHA Qty: ${item.quantity}", fontSize = 18.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            Text(text = "Backstock: ${item.quantityBackstock ?: 0}", fontSize = 18.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            Text(text = "Ship Qty (Pack): ${item.packSize ?: 1}", fontSize = 18.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                coroutineScope.launch {
                    try {
                        localError = "Sending to printer..."
                        val res = RetrofitClient.instance.printSticker(storeId, com.github.tyke_bc.hht.network.PrintRequest(item.name, item.sku, item.upc ?: item.sku, item.location ?: "N/A", item.faces ?: "F1", item.department, item.pogInfo))
                        localError = if(res.success) "Warehouse Label printed!" else res.message ?: "Print failed"
                    } catch(e:Exception) { localError = "Print error: ${e.message}" }
                }
            }, modifier = Modifier.fillMaxWidth().height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))) { Text("PRINT WAREHOUSE LABEL", fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                coroutineScope.launch {
                    try {
                        localError = "Sending shelf label..."
                        val req = com.github.tyke_bc.hht.network.PrintShelfLabelRequest(item.brand ?: "", item.name, item.variant ?: "", item.size ?: "", item.upc ?: item.sku, item.price, item.unitPriceUnit ?: "per each", item.taxable ?: true, item.pogDate ?: "N/A", item.location ?: "N/A", item.faces ?: "F1", item.pogInfo, item.regPrice)
                        val res = RetrofitClient.instance.printShelfLabel(storeId, req)
                        localError = if(res.success) "Shelf Label printed!" else res.message ?: "Print failed"
                    } catch(e:Exception) { localError = "Print error: ${e.message}" }
                }
            }, modifier = Modifier.fillMaxWidth().height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEAB308))) { Text("PRINT SHELF LABEL", fontWeight = FontWeight.Bold, color = Color.Black) }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { adjustQty = item.quantity.toString(); adjustEid = ""; adjustPin = ""; adjustError = null; showAdjustDialog = true }, modifier = Modifier.fillMaxWidth().height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))) { Text("ADJUST OHA (CYCLE COUNT)", fontWeight = FontWeight.Bold, color = Color.Black) }
        }
    }
    if (showAdjustDialog && item != null) {
        AlertDialog(onDismissRequest = { showAdjustDialog = false }, title = { Text("Manager Override Required") }, text = {
            Column {
                Text("New Quantity:"); BasicTextField(value = adjustQty, onValueChange = { adjustQty = it }, modifier = Modifier.fillMaxWidth().background(Color.White).border(1.dp, Color.Gray).padding(8.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Manager EID:"); BasicTextField(value = adjustEid, onValueChange = { adjustEid = it }, modifier = Modifier.fillMaxWidth().background(Color.White).border(1.dp, Color.Gray).padding(8.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Manager PIN:"); BasicTextField(value = adjustPin, onValueChange = { adjustPin = it }, modifier = Modifier.fillMaxWidth().background(Color.White).border(1.dp, Color.Gray).padding(8.dp))
                if (adjustError != null) Text(adjustError!!, color = Color.Red)
            }
        }, confirmButton = {
            Button(onClick = {
                coroutineScope.launch {
                    try {
                        val auth = RetrofitClient.instance.authLocal(storeId, com.github.tyke_bc.hht.network.AuthRequest(adjustEid, adjustPin))
                        if (auth.success && (auth.user?.role in listOf("LSA", "ASM", "SM"))) {
                            val priceToSave = item.regPrice ?: item.price
                            if (showAdjustDialog && item != null && adjustQty.isNotEmpty()) {
                                // If editing pack size
                                if (adjustQty.toIntOrNull() != null) {
                                    val res = RetrofitClient.instance.updatePackSize(storeId, com.github.tyke_bc.hht.network.UpdatePackSizeRequest(item.sku, adjustQty.toInt()))
                                    if (res.success) { showAdjustDialog = false; onSearch(item.sku); localError = "Pack Size Updated!" } else adjustError = res.message
                                }
                            } else {
                                val res = RetrofitClient.instance.updateInventory(storeId, com.github.tyke_bc.hht.network.UpdateInventoryRequest(item.sku, item.sku, item.name, item.department, priceToSave, adjustQty.toIntOrNull() ?: 0))
                                if (res.success) { showAdjustDialog = false; onSearch(item.sku); localError = "OHA Updated!" } else adjustError = res.message
                            }
                        } else adjustError = "Auth failed or unauthorized"
                    } catch(e:Exception) { adjustError = e.message }
                }
            }) { Text("Save") }
        })
    }
}

@Composable
fun LocationsContent(item: InventoryItem?) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Current Locations", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Spacer(modifier = Modifier.height(12.dp))
        if (item?.location != null) LocationCard(item) else Text("No active locations.", color = Color.Gray)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Historical Locations", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Spacer(modifier = Modifier.height(12.dp))
        if (item?.location != null) LocationCard(item) else Text("No historical records.", color = Color.Gray)
    }
}

@Composable
fun LocationCard(item: InventoryItem) {
    val loc = item.location ?: "N/A"
    val isMag = loc.equals("MAG", ignoreCase = true) && item.pogInfo.isNullOrBlank()
    val isStandard = loc.contains("-")
    val pos = item.position ?: 1

    Surface(modifier = Modifier.fillMaxWidth().wrapContentHeight(), color = Color.White, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, if (isMag) Color(0xFF8B5CF6) else Color.Gray)) {
        Box(modifier = Modifier.padding(2.dp).border(1.dp, Color.LightGray, RoundedCornerShape(6.dp)).padding(12.dp)) {
            Column {
                if (isMag) {
                    Text(text = "Item is a MAG item.", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8B5CF6))
                    Text(text = "Not on a planogram — free-floating in the store.", fontSize = 12.sp, color = Color(0xFF64748B))
                    Text(text = item.department.uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.padding(top = 4.dp))
                } else {
                    Text(text = item.pogInfo ?: "PENDING POG DATA", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Text(text = item.department.uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    if (isStandard) {
                        val section = loc.split("-")[0]
                        val shelf = loc.split("-").getOrElse(1) { "N/A" }
                        Text(text = "Sec.${section} Shelf${shelf.padStart(2, '0')} Pos.${pos.toString().padStart(2, '0')} ${item.faces ?: "F1"} $loc", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    } else {
                        Text(text = "Location: $loc  ${item.faces ?: "F1"}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        }
    }
}

@Composable
fun SalesHistoryContent(storeId: String, item: InventoryItem?) {
    var sales by remember { mutableStateOf<List<com.github.tyke_bc.hht.network.SaleRow>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val key = item?.sku ?: item?.upc
    LaunchedEffect(key) {
        if (key.isNullOrBlank()) { sales = emptyList(); return@LaunchedEffect }
        isLoading = true; errorMsg = null
        try {
            val res = RetrofitClient.instance.getSalesHistory(storeId, key)
            sales = res.sales ?: emptyList()
            if (!res.success) errorMsg = res.message
        } catch (e: Exception) { errorMsg = "Error: ${e.message}" }
        finally { isLoading = false }
    }
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        if (item == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Scan an item to see its recent sales.", color = Color.Gray)
            }
            return@Column
        }
        Text("Recent sales — ${item.name}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("SKU ${item.sku}  ·  UPC ${item.upc ?: "-"}", fontSize = 11.sp, color = Color(0xFF64748B))
        Spacer(Modifier.height(8.dp))
        when {
            isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            errorMsg != null -> Text("Error: $errorMsg", color = Color.Red)
            sales.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No sales recorded for this item.", color = Color.Gray) }
            else -> {
                // Quick aggregates
                val totalUnits = sales.sumOf { it.quantity ?: 0 }
                val totalRevenue = sales.sumOf { (it.quantity ?: 0) * (it.price ?: 0.0) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = Color(0xFFEEF2FF), shape = RoundedCornerShape(6.dp), modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(8.dp)) {
                            Text("Units", fontSize = 10.sp, color = Color(0xFF6366F1)); Text("$totalUnits", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                    Surface(color = Color(0xFFECFDF5), shape = RoundedCornerShape(6.dp), modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(8.dp)) {
                            Text("Revenue", fontSize = 10.sp, color = Color(0xFF10B981)); Text("$%.2f".format(totalRevenue), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                    Surface(color = Color(0xFFFEF3C7), shape = RoundedCornerShape(6.dp), modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(8.dp)) {
                            Text("Transactions", fontSize = 10.sp, color = Color(0xFFB45309)); Text("${sales.size}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sales) { s ->
                        val markdown = s.originalPrice != null && s.price != null && s.originalPrice!! > s.price!!
                        Surface(shape = RoundedCornerShape(4.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFE2E8F0)), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(s.timestamp?.replace('T', ' ')?.take(19) ?: "—", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text("${s.tenderType ?: "?"}  ·  Receipt ${s.barcode ?: "—"}", fontSize = 10.sp, color = Color(0xFF64748B))
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("×${s.quantity ?: 0}  @  $%.2f".format(s.price ?: 0.0), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    if (markdown) {
                                        Text("was $%.2f".format(s.originalPrice!!), fontSize = 10.sp, color = Color(0xFFEF4444), textDecoration = TextDecoration.LineThrough)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Shared composable for both Damages and Store Use tabs — only differs by adjustmentType and reason code list.
@Composable
fun AdjustmentForm(
    storeId: String,
    scannedUpc: String,
    onScannedUpcChange: (String) -> Unit,
    adjustmentType: String,
    reasonCodes: List<String>,
    headerLabel: String,
    headerColor: Color
) {
    var currentItem by remember { mutableStateOf<InventoryItem?>(null) }
    var lookupErr by remember { mutableStateOf<String?>(null) }
    var quantity by remember { mutableStateOf("1") }
    var reason by remember { mutableStateOf(reasonCodes.firstOrNull() ?: "") }
    var notes by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastOk by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Look up item when the UPC/SKU changes (debounce-ish: just on each change)
    LaunchedEffect(scannedUpc) {
        if (scannedUpc.isBlank()) { currentItem = null; lookupErr = null; return@LaunchedEffect }
        try {
            val r = RetrofitClient.instance.getInventoryItem(storeId, scannedUpc.trim())
            if (r.success && r.item != null) { currentItem = r.item; lookupErr = null }
            else { currentItem = null; lookupErr = r.message ?: "Not found" }
        } catch (e: Exception) { currentItem = null; lookupErr = e.message }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Surface(color = headerColor, shape = RoundedCornerShape(4.dp)) {
            Text(headerLabel, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(10.dp))

        Text("UPC / SKU", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = scannedUpc, onValueChange = onScannedUpcChange,
                modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray).padding(8.dp),
                textStyle = TextStyle(fontSize = 14.sp)
            )
            Spacer(Modifier.width(8.dp)); BarcodePlaceholder()
        }
        Spacer(Modifier.height(10.dp))

        if (lookupErr != null) {
            Text("⚠ $lookupErr", color = Color(0xFFEF4444), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
        currentItem?.let { item ->
            Surface(color = Color(0xFFF8FAFC), border = BorderStroke(1.dp, Color(0xFFE2E8F0)), shape = RoundedCornerShape(4.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(item.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("SKU ${item.sku}  ·  ${item.department}", fontSize = 11.sp, color = Color(0xFF64748B))
                    Text("Current OH: ${item.quantity}   Regular Price: $%.2f".format(item.price), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    item.location?.let { Text("Location: $it  ${item.faces ?: ""}", fontSize = 11.sp, color = Color(0xFF64748B)) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Text("Reason Code", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            reasonCodes.forEach { code ->
                val selected = reason == code
                Surface(
                    color = if (selected) headerColor else Color(0xFFE2E8F0),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(end = 6.dp).clickable { reason = code }
                ) {
                    Text(code, color = if (selected) Color.White else Color(0xFF334155), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        Text("Adjustment Quantity", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        BasicTextField(
            value = quantity, onValueChange = { quantity = it.filter { c -> c.isDigit() } },
            modifier = Modifier.width(100.dp).height(40.dp).border(1.dp, Color.Gray).padding(8.dp),
            textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Spacer(Modifier.height(10.dp))

        Text("Notes (optional)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        BasicTextField(
            value = notes, onValueChange = { notes = it },
            modifier = Modifier.fillMaxWidth().height(60.dp).border(1.dp, Color.Gray).padding(8.dp),
            textStyle = TextStyle(fontSize = 13.sp)
        )
        Spacer(Modifier.height(14.dp))

        Button(
            onClick = {
                val qty = quantity.toIntOrNull() ?: 0
                val sku = currentItem?.sku ?: scannedUpc.trim()
                if (currentItem == null) { toast = "Scan or enter a valid UPC/SKU first"; toastOk = false; return@Button }
                if (qty <= 0) { toast = "Enter a positive quantity"; toastOk = false; return@Button }
                if (reason.isBlank()) { toast = "Select a reason code"; toastOk = false; return@Button }
                submitting = true
                scope.launch {
                    try {
                        val res = RetrofitClient.instance.submitAdjustment(
                            storeId,
                            com.github.tyke_bc.hht.network.AdjustmentRequest(
                                sku = sku, adjustmentType = adjustmentType, quantity = qty,
                                reasonCode = reason, notes = notes.ifBlank { null },
                                eid = MainActivity.loggedInEid.ifBlank { null }
                            )
                        )
                        toastOk = res.success
                        toast = res.message ?: if (res.success) "Adjusted" else "Failed"
                        if (res.success) {
                            // Refresh local item display
                            try {
                                val r = RetrofitClient.instance.getInventoryItem(storeId, sku)
                                if (r.success) currentItem = r.item
                            } catch (_: Exception) {}
                            quantity = "1"; notes = ""
                        }
                    } catch (e: Exception) { toastOk = false; toast = "Error: ${e.message}" }
                    finally { submitting = false }
                }
            },
            enabled = !submitting,
            colors = ButtonDefaults.buttonColors(containerColor = headerColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (submitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            else Text("SUBMIT ADJUSTMENT", fontWeight = FontWeight.Bold)
        }
        toast?.let {
            Spacer(Modifier.height(10.dp))
            Surface(color = if (toastOk) Color(0xFFECFDF5) else Color(0xFFFEF2F2), border = BorderStroke(1.dp, if (toastOk) Color(0xFF10B981) else Color(0xFFEF4444)), shape = RoundedCornerShape(4.dp)) {
                Text(it, color = if (toastOk) Color(0xFF047857) else Color(0xFF991B1B), fontSize = 13.sp, modifier = Modifier.padding(10.dp))
            }
        }
    }
}

// ---------- TRANSFERS (outbound request) ----------
@Composable
fun TransfersContent(storeId: String) {
    var upc by remember { mutableStateOf("") }
    var currentItem by remember { mutableStateOf<InventoryItem?>(null) }
    var qty by remember { mutableStateOf("1") }
    var otherStore by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(upc) {
        if (upc.isBlank()) { currentItem = null; return@LaunchedEffect }
        try {
            val r = RetrofitClient.instance.getInventoryItem(storeId, upc.trim())
            if (r.success) currentItem = r.item else currentItem = null
        } catch (_: Exception) { currentItem = null }
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Surface(color = Color(0xFF1E3A5F), shape = RoundedCornerShape(4.dp)) {
            Text("OUTBOUND TRANSFER REQUEST", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("UPC / SKU", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value = upc, onValueChange = { upc = it },
                modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray).padding(8.dp))
            Spacer(Modifier.width(8.dp)); BarcodePlaceholder()
        }
        Spacer(Modifier.height(8.dp))
        currentItem?.let {
            Surface(color = Color(0xFFF8FAFC), border = BorderStroke(1.dp, Color(0xFFE2E8F0)), shape = RoundedCornerShape(4.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(it.name, fontWeight = FontWeight.Bold)
                    Text("OH: ${it.quantity}  ·  SKU ${it.sku}", fontSize = 12.sp, color = Color(0xFF64748B))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Text("Destination Store # (optional)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(value = otherStore, onValueChange = { otherStore = it.filter { c -> c.isDigit() } },
            modifier = Modifier.width(120.dp).height(40.dp).border(1.dp, Color.Gray).padding(8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Spacer(Modifier.height(8.dp))
        Text("Quantity", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(value = qty, onValueChange = { qty = it.filter { c -> c.isDigit() } },
            modifier = Modifier.width(100.dp).height(40.dp).border(1.dp, Color.Gray).padding(8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Spacer(Modifier.height(8.dp))
        Text("Notes", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(value = notes, onValueChange = { notes = it },
            modifier = Modifier.fillMaxWidth().height(60.dp).border(1.dp, Color.Gray).padding(8.dp))
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                val n = qty.toIntOrNull() ?: 0
                val sku = currentItem?.sku ?: upc.trim()
                if (sku.isBlank() || n <= 0) { ok = false; toast = "Enter a valid item and qty"; return@Button }
                submitting = true
                scope.launch {
                    try {
                        val r = RetrofitClient.instance.requestTransfer(storeId,
                            com.github.tyke_bc.hht.network.TransferRequest(sku, n, otherStore.toIntOrNull(), notes.ifBlank { null }, MainActivity.loggedInEid.ifBlank { null }))
                        ok = r.success; toast = r.message ?: if (r.success) "Requested" else "Failed"
                        if (r.success) { upc = ""; qty = "1"; notes = "" }
                    } catch (e: Exception) { ok = false; toast = e.message }
                    finally { submitting = false }
                }
            },
            enabled = !submitting, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F)),
            modifier = Modifier.fillMaxWidth()
        ) { if (submitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White) else Text("REQUEST TRANSFER", fontWeight = FontWeight.Bold) }
        toast?.let {
            Spacer(Modifier.height(10.dp))
            Surface(color = if (ok) Color(0xFFECFDF5) else Color(0xFFFEF2F2), border = BorderStroke(1.dp, if (ok) Color(0xFF10B981) else Color(0xFFEF4444))) {
                Text(it, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
            }
        }
    }
}

// ---------- REVIEW ----------
@Composable
fun ReviewContent(storeId: String) {
    var data by remember { mutableStateOf<com.github.tyke_bc.hht.network.ReviewResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { data = RetrofitClient.instance.getReviewPending(storeId, 7) }
        catch (e: Exception) { err = e.message }
        finally { loading = false }
    }
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("Review — last 7 days", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            err != null -> Text("Error: $err", color = Color.Red)
            data == null -> Text("No data.", color = Color.Gray)
            else -> {
                val d = data!!
                LazyColumn {
                    item {
                        Text("Adjustments (${d.adjustments?.size ?: 0})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    items(d.adjustments ?: emptyList()) { a ->
                        Surface(shape = RoundedCornerShape(4.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFE2E8F0)), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Column(Modifier.padding(8.dp)) {
                                Text("${a.adjustmentType} ×${a.quantity}  ·  ${a.itemName ?: a.sku}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("${a.reasonCode ?: ""}  by ${a.eidName ?: a.eid ?: "?"}  ·  ${a.createdAt?.replace('T',' ')?.take(19) ?: ""}", fontSize = 11.sp, color = Color(0xFF64748B))
                                if (!a.notes.isNullOrBlank()) Text(a.notes, fontSize = 11.sp, color = Color(0xFF475569))
                            }
                        }
                    }
                    item { Text("Pending Transfers (${d.transfers?.size ?: 0})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                    items(d.transfers ?: emptyList()) { t ->
                        Surface(shape = RoundedCornerShape(4.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFE2E8F0)), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Column(Modifier.padding(8.dp)) {
                                Text("${t.direction} ${t.quantity} × ${t.itemName ?: t.sku}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("To store #${t.otherStoreId ?: "?"}  ·  ${t.status}", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                        }
                    }
                    item { Text("Failed Compliance Checks (${d.failedCompliance?.size ?: 0})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                    items(d.failedCompliance ?: emptyList()) { c ->
                        Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFFEF2F2), border = BorderStroke(1.dp, Color(0xFFEF4444)), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Column(Modifier.padding(8.dp)) {
                                Text("${c.checkType}  ·  ${c.fixtureId ?: "—"}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF991B1B))
                                if (!c.notes.isNullOrBlank()) Text(c.notes, fontSize = 11.sp, color = Color(0xFF991B1B))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- NONES & TONS ----------
@Composable
fun NonesAndTonsContent(storeId: String) {
    var tab by remember { mutableStateOf(0) } // 0 = Tons, 1 = Nones
    var days by remember { mutableStateOf(30) }
    var data by remember { mutableStateOf<com.github.tyke_bc.hht.network.MoversResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(days) {
        loading = true
        try { data = RetrofitClient.instance.getMovers(storeId, days) } catch (_: Exception) {} finally { loading = false }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFD6D6D6))) {
            listOf("Tons (Top Sellers)", "Nones (Dead Stock)").forEachIndexed { i, t ->
                Box(modifier = Modifier.weight(1f).background(if (tab == i) Color(0xFFE8E8E8) else Color.Transparent).clickable { tab = i }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(t, fontSize = 13.sp, color = if (tab == i) Color.Black else DGBlue)
                }
            }
        }
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Window:", fontSize = 12.sp); Spacer(Modifier.width(6.dp))
            listOf(7, 30, 90).forEach { d ->
                Surface(color = if (days == d) DGBlue else Color(0xFFE2E8F0), shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(end = 4.dp).clickable { days = d }) {
                    Text("${d}d", color = if (days == d) Color.White else Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
        if (loading) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return@Column }
        val rows = if (tab == 0) data?.tons ?: emptyList() else data?.nones ?: emptyList()
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No data.", color = Color.Gray) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(rows) { r ->
                    Surface(shape = RoundedCornerShape(4.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFE2E8F0)), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name ?: r.sku, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("SKU ${r.sku}${r.department?.let { "  ·  $it" } ?: ""}", fontSize = 10.sp, color = Color(0xFF64748B))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                if (tab == 0) {
                                    Text("×${r.unitsSold ?: 0}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF10B981))
                                    Text("$%.2f".format(r.revenue ?: 0.0), fontSize = 11.sp, color = Color(0xFF64748B))
                                } else {
                                    Text("OH ${r.onHand ?: 0}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFFEF4444))
                                    Text("$%.2f".format(r.price ?: 0.0), fontSize = 11.sp, color = Color(0xFF64748B))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- COMPLIANCE CHECKLIST (shared form for walks / inspections) ----------
@Composable
fun ChecklistScreen(storeId: String, checkType: String, title: String, items: List<String>, headerColor: Color) {
    val results = remember { androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>() }
    var notes by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Surface(color = headerColor, shape = RoundedCornerShape(4.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        items.forEach { item ->
            val passed = results[item] ?: true
            Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = RoundedCornerShape(4.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFE2E8F0))) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Surface(color = if (passed) Color(0xFF10B981) else Color(0xFFE2E8F0), shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(end = 4.dp).clickable { results[item] = true }) {
                        Text("PASS", color = if (passed) Color.White else Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    Surface(color = if (!passed) Color(0xFFEF4444) else Color(0xFFE2E8F0), shape = RoundedCornerShape(4.dp), modifier = Modifier.clickable { results[item] = false }) {
                        Text("FAIL", color = if (!passed) Color.White else Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Notes", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(value = notes, onValueChange = { notes = it },
            modifier = Modifier.fillMaxWidth().height(70.dp).border(1.dp, Color.Gray).padding(8.dp))
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                submitting = true
                scope.launch {
                    try {
                        val allPassed = items.all { (results[it] ?: true) }
                        val details = items.joinToString(",") { "${it}=${if (results[it] ?: true) "PASS" else "FAIL"}" }
                        val r = RetrofitClient.instance.submitCompliance(storeId,
                            com.github.tyke_bc.hht.network.ComplianceRequest(checkType, null, details, allPassed, notes.ifBlank { null }, MainActivity.loggedInEid.ifBlank { null }))
                        ok = r.success; toast = r.message ?: if (r.success) "Submitted" else "Failed"
                        if (r.success) { results.clear(); notes = "" }
                    } catch (e: Exception) { ok = false; toast = e.message }
                    finally { submitting = false }
                }
            },
            enabled = !submitting, colors = ButtonDefaults.buttonColors(containerColor = headerColor),
            modifier = Modifier.fillMaxWidth()
        ) { if (submitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White) else Text("SUBMIT CHECK", fontWeight = FontWeight.Bold) }
        toast?.let {
            Spacer(Modifier.height(10.dp))
            Surface(color = if (ok) Color(0xFFECFDF5) else Color(0xFFFEF2F2), border = BorderStroke(1.dp, if (ok) Color(0xFF10B981) else Color(0xFFEF4444))) {
                Text(it, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
            }
        }
    }
}

@Composable
fun SafetyWalkContent(storeId: String, checkType: String) {
    ChecklistScreen(storeId, checkType, "COOLER / FREEZER / SAFETY WALK", listOf(
        "Cooler doors close and seal",
        "Freezer case temps within range",
        "No ice buildup on evaporators",
        "Aisles clear of trip hazards",
        "Spill kit present and stocked",
        "Back-room floor dry",
        "Emergency exits unblocked",
        "Ladder stored safely"
    ), Color(0xFFFBC02D))
}

@Composable
fun ComplianceCheckContent(storeId: String) {
    ChecklistScreen(storeId, "COMPLIANCE", "COMPLIANCE CHECK", listOf(
        "Register drawer locked when unattended",
        "Age-restricted SKUs behind counter / locked",
        "Receipt printer has paper",
        "Employee nametags worn",
        "Tobacco/alcohol signage visible",
        "Price tags match POS pricing (spot check)",
        "No expired product on sales floor"
    ), Color(0xFFFF6F00))
}

// ---------- REFRIGERATION MAINTENANCE (temperature log per fixture) ----------
@Composable
fun RefrigerationMaintenanceContent(storeId: String) {
    val fixtures = listOf("Front Cooler", "MAG Cooler", "Open Air", "Freezer 1", "Freezer 2", "Dairy Case", "Produce Case")
    val temps = remember { androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>() }
    var notes by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Surface(color = DGBlue, shape = RoundedCornerShape(4.dp)) {
            Text("REFRIGERATION TEMP LOG", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("Record current temperature (°F) for each fixture. Coolers should be 34–40°F, freezers -10 to 0°F.", fontSize = 11.sp, color = Color(0xFF64748B))
        Spacer(Modifier.height(6.dp))
        fixtures.forEach { fx ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fx, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                BasicTextField(
                    value = temps[fx] ?: "", onValueChange = { v -> temps[fx] = v.filter { c -> c.isDigit() || c == '-' || c == '.' } },
                    modifier = Modifier.width(80.dp).height(40.dp).border(1.dp, Color.Gray).padding(8.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = TextStyle(fontSize = 14.sp, textAlign = TextAlign.Center)
                )
                Text("°F", modifier = Modifier.padding(start = 4.dp), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Notes (issues, service needed, etc.)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(value = notes, onValueChange = { notes = it },
            modifier = Modifier.fillMaxWidth().height(60.dp).border(1.dp, Color.Gray).padding(8.dp))
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                submitting = true
                scope.launch {
                    try {
                        val recorded = fixtures.mapNotNull { fx -> temps[fx]?.takeIf { it.isNotBlank() }?.let { "${fx}=${it}F" } }
                        if (recorded.isEmpty()) { ok = false; toast = "Enter at least one temp"; submitting = false; return@launch }
                        val anyOutOfRange = fixtures.any { fx ->
                            val t = temps[fx]?.toDoubleOrNull() ?: return@any false
                            if (fx.contains("Freezer", ignoreCase = true)) t > 0 || t < -20
                            else t < 32 || t > 45
                        }
                        val r = RetrofitClient.instance.submitCompliance(storeId,
                            com.github.tyke_bc.hht.network.ComplianceRequest("REFRIGERATION", null, recorded.joinToString(","), !anyOutOfRange, notes.ifBlank { null }, MainActivity.loggedInEid.ifBlank { null }))
                        ok = r.success; toast = r.message ?: if (r.success) "Logged" else "Failed"
                        if (r.success) { temps.clear(); notes = "" }
                    } catch (e: Exception) { ok = false; toast = e.message }
                    finally { submitting = false }
                }
            },
            enabled = !submitting, colors = ButtonDefaults.buttonColors(containerColor = DGBlue),
            modifier = Modifier.fillMaxWidth()
        ) { if (submitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White) else Text("LOG TEMPS", fontWeight = FontWeight.Bold) }
        toast?.let {
            Spacer(Modifier.height(10.dp))
            Surface(color = if (ok) Color(0xFFECFDF5) else Color(0xFFFEF2F2), border = BorderStroke(1.dp, if (ok) Color(0xFF10B981) else Color(0xFFEF4444))) {
                Text(it, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
            }
        }
    }
}

@Composable
fun AdjustmentsDamagesContent(upcInput: String, onUpcChange: (String) -> Unit) {
    AdjustmentFormHost(upcInput, onUpcChange, "DAMAGES",
        listOf("DMG", "SHRINK", "EXPIRED", "RECALL"),
        "DAMAGED / SHRINK / EXPIRED", Color(0xFFEF4444))
}

@Composable
fun AdjustmentsStoreUseContent(upcInput: String, onUpcChange: (String) -> Unit) {
    AdjustmentFormHost(upcInput, onUpcChange, "STORE_USE",
        listOf("CLEANING", "BREAKROOM", "OFFICE", "TRAINING", "OTHER"),
        "STORE USE", Color(0xFF2563EB))
}

// Host needs the current storeId — plumbed via a CompositionLocal so we don't have to rewrite the Adjustments call sites.
val LocalStoreId = androidx.compose.runtime.compositionLocalOf { "" }

@Composable
fun AdjustmentFormHost(
    upcInput: String,
    onUpcChange: (String) -> Unit,
    adjustmentType: String,
    reasons: List<String>,
    headerLabel: String,
    headerColor: Color
) {
    val storeId = LocalStoreId.current
    if (storeId.isBlank()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("No store context — re-open Adjustments from Home.", color = Color.Gray)
        }
    } else {
        AdjustmentForm(storeId, upcInput, onUpcChange, adjustmentType, reasons, headerLabel, headerColor)
    }
}

@Composable
fun ReceivingTruckContent(
    storeId: String,
    manifestList: List<com.github.tyke_bc.hht.network.TruckManifest>,
    selectedManifest: com.github.tyke_bc.hht.network.TruckManifest?,
    manifestItems: List<com.github.tyke_bc.hht.network.ManifestItem>,
    isLoading: Boolean,
    errorMessage: String? = null,
    onManifestSelected: (com.github.tyke_bc.hht.network.TruckManifest) -> Unit,
    onBackToList: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    
    // Refresh manifest list if none selected
    LaunchedEffect(selectedManifest) {
        if (selectedManifest == null) {
            // Trigger refresh in parent if needed, but for now we assume it was triggered by switchScreen
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = DGBlue) }
    } else if (errorMessage != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Error, "Error", tint = Color.Red, modifier = Modifier.size(48.dp))
                Text(errorMessage, color = Color.Red, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                Button(onClick = onBackToList) { Text("RETRY") }
            }
        }
    } else if (selectedManifest == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Active Truck Manifests", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            if (manifestList.isEmpty()) {
                Text("No manifests found for this store.", color = Color.Gray)
            } else {
                manifestList.forEach { trk ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { onManifestSelected(trk) },
                        color = Color.White, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color.Gray)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalShipping, "Truck", tint = DGBlue, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(trk.manifestNumber, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text("Status: ${trk.status}", color = Color.Gray)
                            }
                            Icon(Icons.Default.ChevronRight, "View")
                        }
                    }
                }
            }
        }
    } else {
        ReceivingTruckDetailContent(selectedManifest, manifestItems, onBackToList)
    }
}

@Composable
fun ReceivingTruckDetailContent(
    manifest: com.github.tyke_bc.hht.network.TruckManifest,
    items: List<com.github.tyke_bc.hht.network.ManifestItem>,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ArrowBack, "Back", modifier = Modifier.clickable { onBack() }.size(28.dp), tint = DGBlue)
            Spacer(modifier = Modifier.width(12.dp))
            Text("Receiving ${manifest.manifestNumber}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Surface(color = Color(0xFFDCFCE7), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, Color(0xFF22C55E))) {
            Text("SCAN WAREHOUSE BOX LABELS", modifier = Modifier.padding(8.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = Color(0xFF166534))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            items.forEach { item ->
                val isDone = item.receivedPacks >= item.expectedPacks
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    color = if (isDone) Color(0xFFDCFCE7) else Color.White,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, if (isDone) Color(0xFF22C55E) else Color.Gray)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.Bold)
                            Text("SKU: ${item.sku} | Pack: ${item.packSize}", fontSize = 12.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${item.receivedPacks} / ${item.expectedPacks}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (isDone) Color(0xFF166534) else Color.Black)
                            Text("Boxes", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CountsRecallsContent(upcInput: String, onUpcChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value = upcInput, onValueChange = onUpcChange, modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray).padding(8.dp))
            Spacer(modifier = Modifier.width(8.dp)); BarcodePlaceholder()
        }
        Spacer(modifier = Modifier.height(24.dp))
        listOf("Desc:", "Regular Price:", "Location:", "Current OH:", "Quantity:").forEach { Text(it, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
    }
}

@Composable
fun TransfersOutgoingContent() { Box(modifier = Modifier.fillMaxSize()) { Text("Outgoing Transfers", modifier = Modifier.align(Alignment.Center)) } }
@Composable
fun TransfersIncomingContent() { Box(modifier = Modifier.fillMaxSize()) { Text("Incoming Transfers", modifier = Modifier.align(Alignment.Center)) } }
@Composable
fun TransfersMisShipContent() { Box(modifier = Modifier.fillMaxSize()) { Text("Mis-Ship Transfers", modifier = Modifier.align(Alignment.Center)) } }

@Composable
fun BarcodePlaceholder() {
    Row(modifier = Modifier.height(40.dp).width(50.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        repeat(8) { i -> Box(modifier = Modifier.width(listOf(2.dp, 4.dp, 1.dp, 3.dp, 2.dp, 5.dp, 1.dp, 2.dp)[i]).fillMaxHeight(0.8f).background(Color.Black)) }
    }
}

@Composable
fun NavMenuItem(icon: ImageVector, label: String, iconColor: Color, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, tint = iconColor, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, fontSize = 18.sp, color = DGBlue)
    }
}

@Composable
fun StockingContent(
    storeId: String,
    rolltainers: List<com.github.tyke_bc.hht.network.Rolltainer>,
    statusMessage: String?,
    errorMessage: String?,
    onStockRolltainer: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Backstock Stocking", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Scan a Warehouse Label to stock a single box, or a Rolltainer barcode to stock all items on it.", color = Color.Gray, fontSize = 14.sp)
        
        Spacer(modifier = Modifier.height(16.dp))

        if (statusMessage != null) {
            Surface(color = Color(0xFFDCFCE7), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, Color(0xFF22C55E))) {
                Text(statusMessage, modifier = Modifier.padding(12.dp), color = Color(0xFF166534), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
        if (errorMessage != null) {
            Surface(color = Color(0xFFFEE2E2), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, Color.Red)) {
                Text(errorMessage, modifier = Modifier.padding(12.dp), color = Color.Red, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }

        Text("Active Rolltainers (Pending Stocking)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (rolltainers.isEmpty()) {
                Text("No pending rolltainers found.", color = Color.Gray)
            } else {
                rolltainers.filter { it.status == "PENDING" }.forEach { rt ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        color = Color.White, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color.Gray)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Inventory, "RT", tint = DGBlue)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(rt.barcode, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text("Created: ${rt.createdAt.split("T").getOrNull(0) ?: ""}", fontSize = 12.sp, color = Color.Gray)
                            }
                            Button(onClick = { onStockRolltainer(rt.barcode) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE67E22))) {
                                Text("STOCK ALL")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GeneralContent(item: InventoryItem?) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Box(modifier = Modifier.border(0.5.dp, Color.LightGray).fillMaxWidth()) {
            Column {
                GeneralRow("Sales Speed:", "UNASSIGNED")
                HorizontalDivider(color = Color.LightGray, thickness = 0.5.dp)
                GeneralRow("Discontinued:", "UNASSIGNED")
                HorizontalDivider(color = Color.LightGray, thickness = 0.5.dp)
                GeneralRow("Inventory Type:", "CORE")
            }
        }
    }
}

@Composable
fun GeneralRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(modifier = Modifier.weight(1f).background(Color(0xFFF5F5F5)).padding(12.dp)) {
            Text(text = label, fontSize = 18.sp, color = Color.Black)
        }
        Box(modifier = Modifier.width(0.5.dp).fillMaxHeight().background(Color.LightGray))
        Box(modifier = Modifier.weight(1.5f).background(Color.White).padding(12.dp)) {
            Text(text = value, fontSize = 18.sp, color = Color.Black)
        }
    }
}

@Composable
fun PickingContent(
    storeId: String,
    pendingOrders: List<com.github.tyke_bc.hht.network.OnlineOrder>,
    selectedOrder: com.github.tyke_bc.hht.network.OnlineOrder?,
    orderItems: List<com.github.tyke_bc.hht.network.OnlineOrderItem>,
    isLoading: Boolean,
    errorMessage: String? = null,
    onOrderSelected: (com.github.tyke_bc.hht.network.OnlineOrder) -> Unit,
    onBackToList: () -> Unit,
    onFinalize: () -> Unit
) {
    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = DGBlue)
        }
    } else if (errorMessage != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Error, "Error", tint = Color.Red, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text(errorMessage, color = Color.Red, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onBackToList) { Text("RETRY") }
            }
        }
    } else if (selectedOrder == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Pending Online Orders", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Spacer(modifier = Modifier.height(16.dp))
            if (pendingOrders.isEmpty()) {
                Text("No pending orders for this store.", color = Color.Gray)
            } else {
                pendingOrders.forEach { order ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { onOrderSelected(order) },
                        color = Color.White, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color.Gray), shadowElevation = 2.dp
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Order #${order.id}", fontWeight = FontWeight.Bold, color = DGBlue)
                                Text(order.status, color = if (order.status == "PICKING") Color(0xFFF59E0B) else Color.Gray, fontWeight = FontWeight.Bold)
                            }
                            Text("Customer: ${order.customerName}", fontSize = 16.sp)
                            Text("Total: $${order.total}", fontSize = 14.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    } else {
        PickingDetailContent(selectedOrder, orderItems, onBackToList, onFinalize)
    }
}

@Composable
fun PickingDetailContent(
    order: com.github.tyke_bc.hht.network.OnlineOrder,
    items: List<com.github.tyke_bc.hht.network.OnlineOrderItem>,
    onBack: () -> Unit,
    onFinalize: () -> Unit
) {
    val anyPicked = items.any { it.qtyPicked > 0 }
    val allPicked = items.all { it.qtyPicked >= it.qtyOrdered }

    // Calculate current running subtotal based on picked items
    var currentSubtotal = 0.0
    items.forEach { currentSubtotal += it.qtyPicked * (it.price ?: 0.0) }
    val currentTax = currentSubtotal * 0.055
    val currentTotal = currentSubtotal + currentTax

    val isMock = order.isMock == 1

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ArrowBack, "Back", modifier = Modifier.clickable { onBack() }.size(28.dp), tint = DGBlue)
            Spacer(modifier = Modifier.width(12.dp))
            Text("Picking Order #${order.id}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        
        if (isMock) {
            Surface(
                color = Color(0xFFFEF3C7),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Color(0xFFF59E0B))
            ) {
                Text(
                    "TEST ORDER - NO INVENTORY IMPACT",
                    modifier = Modifier.padding(8.dp),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFB45309),
                    fontSize = 12.sp
                )
            }
        }

        Text("Customer: ${order.customerName}", color = Color.Gray, modifier = Modifier.padding(start = 40.dp))
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Surface(color = Color(0xFFE0F2FE), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp)) {
            Text("SCAN ITEMS TO PICK", modifier = Modifier.padding(8.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = DGBlue)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            items.forEach { item ->
                val isDone = item.qtyPicked >= item.qtyOrdered
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    color = if (isDone) Color(0xFFDCFCE7) else Color.White,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, if (isDone) Color(0xFF22C55E) else Color.Gray)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.Bold)
                            Text("SKU: ${item.sku} | $${String.format("%.2f", item.price ?: 0.0)}", fontSize = 12.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${item.qtyPicked} / ${item.qtyOrdered}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (isDone) Color(0xFF166534) else Color.Black)
                            if (isDone) Icon(Icons.Default.CheckCircle, "Done", tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        // Charges Summary Block
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            color = Color(0xFFF9FAFB),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color(0xFFE5E7EB))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Subtotal", fontSize = 14.sp)
                    Text("$${String.format("%.2f", currentSubtotal)}", fontSize = 14.sp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Tax (5.5%)", fontSize = 14.sp)
                    Text("$${String.format("%.2f", currentTax)}", fontSize = 14.sp)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color.LightGray)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TOTAL CHARGES", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = DGBlue)
                    Text("$${String.format("%.2f", currentTotal)}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = DGBlue)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onFinalize,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            enabled = anyPicked, // Allow partial finalization if needed, or stick to allPicked
            colors = ButtonDefaults.buttonColors(containerColor = if (allPicked) Color(0xFF10B981) else if (anyPicked) Color(0xFFF59E0B) else Color.Gray)
        ) {
            Text(if (allPicked) "FINALIZE & PRINT RECEIPT" else "FINALIZE PARTIAL ORDER", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

data class CycleCountItemState(
    val sku: String,
    val name: String,
    val upc: String?,
    val shelf: String?,
    val faces: String?,
    val systemQty: Int
)

// ---------- VENDOR DELIVERY (HHT scan-in on vendor drop-off) ----------
// Flow: scan the VO-{id} barcode on a DSD order sheet → shows expected items →
// scan each item on the floor → dialog pre-fills with remaining-to-receive →
// strict: items NOT on the order are rejected by the server.
@Composable
fun VendorDeliveryContent(storeId: String, scannedUpc: String, onScanConsumed: () -> Unit) {
    val VendorGreen = Color(0xFF059669)
    val scope = rememberCoroutineScope()

    // Session state
    var order by remember { mutableStateOf<com.github.tyke_bc.hht.network.VendorOrder?>(null) }
    var vendor by remember { mutableStateOf<com.github.tyke_bc.hht.network.Vendor?>(null) }
    var orderItems by remember { mutableStateOf<List<com.github.tyke_bc.hht.network.VendorOrderItem>>(emptyList()) }
    var delivery by remember { mutableStateOf<com.github.tyke_bc.hht.network.VendorDelivery?>(null) }
    var deliveryItems by remember { mutableStateOf<List<com.github.tyke_bc.hht.network.VendorDeliveryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastOk by remember { mutableStateOf(true) }

    // Scan dialog state
    var showQtyDialog by remember { mutableStateOf(false) }
    var scannedItem by remember { mutableStateOf<InventoryItem?>(null) }
    var scanQty by remember { mutableStateOf("1") }
    var expectedQty by remember { mutableStateOf<Int?>(null) }
    var alreadyReceived by remember { mutableStateOf(0) }

    // Received-per-SKU aggregate for variance display
    val receivedBySku: Map<String, Int> = deliveryItems.groupBy { it.sku }.mapValues { (_, list) -> list.sumOf { it.quantityReceived } }

    suspend fun loadDelivery(deliveryId: Int) {
        try {
            val r = RetrofitClient.instance.getVendorDelivery(storeId, deliveryId)
            if (r.success) { delivery = r.delivery; deliveryItems = r.items ?: emptyList() }
        } catch (e: Exception) { toastOk = false; toast = e.message }
    }

    suspend fun resumeIfOpen() {
        loading = true
        try {
            val opens = RetrofitClient.instance.getVendorDeliveries(storeId).filter { it.status == "OPEN" && it.orderId != null }
            val open = opens.firstOrNull() ?: return
            // Rehydrate: fetch the linked order + its items
            val detail = RetrofitClient.instance.getVendorDelivery(storeId, open.id)
            if (!detail.success || detail.delivery == null) return
            val oid = detail.delivery.orderId ?: return
            val resolved = RetrofitClient.instance.resolveVendorOrder(storeId, "VO-$oid")
            if (resolved.success) {
                order = resolved.order
                vendor = resolved.vendor
                orderItems = resolved.items ?: emptyList()
                delivery = detail.delivery
                deliveryItems = detail.items ?: emptyList()
            }
        } catch (_: Exception) {} finally { loading = false }
    }

    LaunchedEffect(Unit) { resumeIfOpen() }

    // Scan handling: in the "landing" (no delivery yet) state, treat scans as order barcodes.
    // In the active state, treat scans as item UPC/SKU lookups against the order's expected list.
    LaunchedEffect(scannedUpc) {
        val raw = scannedUpc.trim()
        if (raw.isBlank()) return@LaunchedEffect

        if (delivery == null) {
            // Treat as a VO-{id} barcode
            loading = true
            try {
                val resolved = RetrofitClient.instance.resolveVendorOrder(storeId, raw)
                if (!resolved.success || resolved.order == null) {
                    toastOk = false; toast = resolved.message ?: "Couldn't open order for \"$raw\""
                } else if (resolved.existingDeliveryId != null) {
                    // Resume existing delivery
                    order = resolved.order; vendor = resolved.vendor; orderItems = resolved.items ?: emptyList()
                    loadDelivery(resolved.existingDeliveryId!!)
                    toastOk = true; toast = "Resumed delivery for ${resolved.order.vendorName ?: "vendor"}"
                } else {
                    // Create a new delivery LINKED to this order (order_id is what the strict server check keys on)
                    val created = RetrofitClient.instance.createVendorDelivery(storeId,
                        com.github.tyke_bc.hht.network.CreateVendorDeliveryRequest(
                            vendorId = resolved.order.vendorId,
                            orderId = resolved.order.id,
                            repName = resolved.order.repName,
                            invoiceNumber = null,
                            notes = null,
                            eid = MainActivity.loggedInEid.ifBlank { null }))
                    if (created.success && created.id != null) {
                        order = resolved.order; vendor = resolved.vendor; orderItems = resolved.items ?: emptyList()
                        loadDelivery(created.id)
                        toastOk = true; toast = "Delivery opened for ${resolved.vendor?.name ?: "vendor"}"
                    } else {
                        toastOk = false; toast = created.message ?: "Failed to start delivery"
                    }
                }
            } catch (e: Exception) { toastOk = false; toast = e.message }
            finally { loading = false; onScanConsumed() }
        } else {
            // Active — treat as item UPC/SKU scan
            try {
                val inv = RetrofitClient.instance.getInventoryItem(storeId, raw)
                if (!inv.success || inv.item == null) { toastOk = false; toast = "Item not found: $raw"; onScanConsumed(); return@LaunchedEffect }
                val sku = inv.item.sku
                val onOrder = orderItems.find { it.sku == sku }
                if (onOrder == null) {
                    toastOk = false; toast = "\"${inv.item.name}\" isn't on this order — vendor must reconcile."
                    onScanConsumed()
                    return@LaunchedEffect
                }
                val alreadyGot = receivedBySku[sku] ?: 0
                val remaining = (onOrder.quantityRequested - alreadyGot).coerceAtLeast(1)
                scannedItem = inv.item
                expectedQty = onOrder.quantityRequested
                alreadyReceived = alreadyGot
                scanQty = remaining.toString()
                showQtyDialog = true
            } catch (e: Exception) { toastOk = false; toast = e.message }
            onScanConsumed()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = VendorGreen, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("VENDOR DELIVERY", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                delivery?.let {
                    Text("${vendor?.code ?: "—"} · Order #${order?.id ?: "?"}", color = Color.White, fontSize = 12.sp)
                }
            }
        }

        if (loading) { Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) { CircularProgressIndicator() }; return@Column }

        if (delivery == null) {
            // Landing — prompt to scan the VO-{id} barcode
            Column(Modifier.fillMaxWidth().weight(1f).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = VendorGreen, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text("Scan the order barcode", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text("Find the Code128 barcode labelled \"VO-###\" at the top of the DSD order sheet the vendor handed you. Scan it to load the expected items.",
                    fontSize = 13.sp, color = Color(0xFF64748B), textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Surface(color = Color(0xFFFEF3C7), border = BorderStroke(1.dp, Color(0xFFF59E0B)), shape = RoundedCornerShape(6.dp)) {
                    Text("Only SUBMITTED orders can be received against. Ad-hoc walk-in deliveries must be entered in the backoffice first.",
                        fontSize = 11.sp, color = Color(0xFF92400E), modifier = Modifier.padding(10.dp))
                }
            }
        } else {
            // Active — expected items list with received-so-far and variance
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp)) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Surface(color = Color(0xFFF8FAFC), border = BorderStroke(1.dp, Color(0xFFE2E8F0)), shape = RoundedCornerShape(4.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(vendor?.name ?: "—", fontWeight = FontWeight.Bold)
                            Text("Order #${order?.id ?: "?"}  ·  Rep: ${order?.repName ?: "—"}", fontSize = 11.sp, color = Color(0xFF64748B))
                            Text("Scan each item on the invoice. Dialog pre-fills with remaining qty.", fontSize = 11.sp, color = Color(0xFF64748B))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val receivedLines = orderItems.count { (receivedBySku[it.sku] ?: 0) > 0 }
                    val totalExp = orderItems.sumOf { it.quantityRequested }
                    val totalRcv = deliveryItems.sumOf { it.quantityReceived }
                    Text("$receivedLines / ${orderItems.size} lines received  ·  $totalRcv / $totalExp units",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VendorGreen)
                    Spacer(Modifier.height(6.dp))
                }
                if (orderItems.isEmpty()) {
                    item { Text("Order has no items (unusual).", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                } else {
                    items(orderItems) { oi ->
                        val rcv = receivedBySku[oi.sku] ?: 0
                        val variance = rcv - oi.quantityRequested
                        val fullyReceived = rcv >= oi.quantityRequested
                        val short = rcv in 1 until oi.quantityRequested
                        val accentColor = when {
                            fullyReceived && variance == 0 -> Color(0xFF10B981)
                            fullyReceived && variance > 0 -> Color(0xFFF59E0B)    // over
                            short -> Color(0xFFF59E0B)
                            else -> Color(0xFF94A3B8)                              // not yet received
                        }
                        Surface(shape = RoundedCornerShape(4.dp), color = Color.White,
                            border = BorderStroke(1.dp, if (fullyReceived) accentColor.copy(alpha = 0.5f) else Color(0xFFE2E8F0)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(oi.name ?: oi.sku, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("SKU ${oi.sku}  ·  ordered ×${oi.quantityRequested}${if (rcv > 0) "  ·  received ×$rcv" else ""}",
                                        fontSize = 11.sp, color = Color(0xFF64748B))
                                    if (variance != 0 && rcv > 0) {
                                        val signed = if (variance > 0) "+$variance over" else "$variance short"
                                        Text(signed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFB45309))
                                    }
                                }
                                if (fullyReceived) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = accentColor)
                                } else {
                                    Text(if (rcv > 0) "$rcv/${oi.quantityRequested}" else "—",
                                        fontWeight = FontWeight.Bold, fontSize = 15.sp, color = accentColor)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }

            // Action bar
            Surface(color = Color(0xFFF1F5F9), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    val r = RetrofitClient.instance.completeVendorDelivery(storeId, delivery!!.id)
                                    if (r.success) {
                                        toastOk = true; toast = "Delivery completed. Order marked FULFILLED."
                                        order = null; vendor = null; orderItems = emptyList()
                                        delivery = null; deliveryItems = emptyList()
                                    } else { toastOk = false; toast = r.message ?: "Failed" }
                                } catch (e: Exception) { toastOk = false; toast = e.message }
                            }
                        },
                        enabled = deliveryItems.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = VendorGreen),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("COMPLETE DELIVERY", fontWeight = FontWeight.Bold) }
                }
            }
        }

        toast?.let {
            Surface(color = if (toastOk) Color(0xFFECFDF5) else Color(0xFFFEF2F2),
                border = BorderStroke(1.dp, if (toastOk) Color(0xFF10B981) else Color(0xFFEF4444)),
                modifier = Modifier.fillMaxWidth()) {
                Text(it, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            }
        }
    }

    // Quantity dialog on scan
    if (showQtyDialog && scannedItem != null) {
        AlertDialog(
            onDismissRequest = { showQtyDialog = false; scannedItem = null },
            title = { Text("Receive: ${scannedItem!!.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("SKU ${scannedItem!!.sku}  ·  OH ${scannedItem!!.quantity}", fontSize = 11.sp, color = Color(0xFF64748B))
                    expectedQty?.let {
                        Text("Ordered: $it  ·  Already received: $alreadyReceived", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VendorGreen)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Units received (this scan)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    BasicTextField(
                        value = scanQty,
                        onValueChange = { scanQty = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(120.dp).height(40.dp).border(1.dp, Color.Gray).padding(8.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val q = scanQty.toIntOrNull() ?: 0
                    if (q <= 0) { toastOk = false; toast = "Enter a positive qty"; return@Button }
                    scope.launch {
                        try {
                            val r = RetrofitClient.instance.scanVendorDelivery(storeId, delivery!!.id,
                                com.github.tyke_bc.hht.network.VendorDeliveryScanRequest(
                                    scannedItem!!.sku, q, MainActivity.loggedInEid.ifBlank { null }))
                            if (r.success) {
                                toastOk = true; toast = "+$q ${r.name ?: ""}  → OH ${r.newQuantity ?: "?"}"
                                showQtyDialog = false; scannedItem = null
                                loadDelivery(delivery!!.id)
                            } else { toastOk = false; toast = r.message ?: "Failed" }
                        } catch (e: Exception) { toastOk = false; toast = e.message }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = VendorGreen)) { Text("RECEIVE") }
            },
            dismissButton = { TextButton(onClick = { showQtyDialog = false; scannedItem = null }) { Text("Cancel") } }
        )
    }
}

// ---------- PRP RETURNS ----------
@Composable
fun PrpReturnsContent(storeId: String, scannedUpc: String, onScanConsumed: () -> Unit) {
    val PrpBrown = Color(0xFF5D4037)
    val reasonCodes = listOf("DEFECTIVE", "RECALL", "EXPIRED", "MFG_DEFECT", "VENDOR_RETURN", "CUSTOMER_RETURN")

    val scope = rememberCoroutineScope()
    var batch by remember { mutableStateOf<com.github.tyke_bc.hht.network.PrpBatch?>(null) }
    var items by remember { mutableStateOf<List<com.github.tyke_bc.hht.network.PrpBatchItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastOk by remember { mutableStateOf(true) }

    var showAddDialog by remember { mutableStateOf(false) }
    var addItem by remember { mutableStateOf<InventoryItem?>(null) }
    var addQty by remember { mutableStateOf("1") }
    var addReason by remember { mutableStateOf(reasonCodes[0]) }
    var addNotes by remember { mutableStateOf("") }
    var addSubmitting by remember { mutableStateOf(false) }

    var showCloseDialog by remember { mutableStateOf(false) }
    var showShipDialog by remember { mutableStateOf(false) }
    var shipCarrier by remember { mutableStateOf("") }
    var shipTracking by remember { mutableStateOf("") }

    suspend fun refreshOpen() {
        loading = true
        try {
            val list = RetrofitClient.instance.getPrpBatches(storeId)
            val open = list.firstOrNull { it.status == "OPEN" }
            if (open != null) {
                val detail = RetrofitClient.instance.getPrpBatch(storeId, open.id)
                if (detail.success) { batch = detail.batch; items = detail.items ?: emptyList() }
            } else { batch = null; items = emptyList() }
        } catch (e: Exception) { toastOk = false; toast = e.message } finally { loading = false }
    }

    LaunchedEffect(Unit) { refreshOpen() }

    // React to scans while in this screen
    LaunchedEffect(scannedUpc) {
        if (scannedUpc.isBlank()) return@LaunchedEffect
        if (batch == null) { toastOk = false; toast = "Start a batch first."; onScanConsumed(); return@LaunchedEffect }
        try {
            val inv = RetrofitClient.instance.getInventoryItem(storeId, scannedUpc.trim())
            if (!inv.success || inv.item == null) { toastOk = false; toast = "Item not found: $scannedUpc" }
            else {
                addItem = inv.item
                addQty = "1"; addReason = reasonCodes[0]; addNotes = ""
                showAddDialog = true
            }
        } catch (e: Exception) { toastOk = false; toast = e.message }
        onScanConsumed()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Surface(color = PrpBrown, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("PRP RETURNS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (batch != null) Text("Batch #${batch!!.id}  ·  ${items.size} lines", color = Color.White, fontSize = 12.sp)
            }
        }

        if (loading) { Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) { CircularProgressIndicator() }; return@Column }

        if (batch == null) {
            // Empty state — offer to start a batch
            Column(Modifier.fillMaxWidth().weight(1f).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("No open PRP batch", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text("Start a new batch to begin scanning items for return.", fontSize = 13.sp, color = Color(0xFF64748B), textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val res = RetrofitClient.instance.createPrpBatch(storeId,
                                    com.github.tyke_bc.hht.network.CreatePrpBatchRequest(null, null, MainActivity.loggedInEid.ifBlank { null }))
                                toastOk = res.success; toast = res.message ?: (if (res.success) "Batch opened" else "Failed")
                                if (res.success) refreshOpen()
                            } catch (e: Exception) { toastOk = false; toast = e.message }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrpBrown)
                ) { Text("START NEW BATCH", fontWeight = FontWeight.Bold) }
            }
        } else {
            // Batch view
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp)) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Surface(color = Color(0xFFF8FAFC), border = BorderStroke(1.dp, Color(0xFFE2E8F0)), shape = RoundedCornerShape(4.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("Scan a UPC/SKU to add it to this batch.", fontSize = 12.sp, color = Color(0xFF64748B))
                            if (batch!!.vendor != null) Text("Vendor: ${batch!!.vendor}", fontSize = 12.sp)
                            if (batch!!.notes != null) Text("Notes: ${batch!!.notes}", fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (items.isEmpty()) {
                    item { Text("No items yet — scan something to get started.", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                } else {
                    items(items) { it ->
                        Surface(shape = RoundedCornerShape(4.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFE2E8F0)), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(it.name ?: it.sku, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("SKU ${it.sku}  ·  ${it.reasonCode}  ·  ×${it.quantity}", fontSize = 11.sp, color = Color(0xFF64748B))
                                    if (!it.notes.isNullOrBlank()) Text(it.notes, fontSize = 11.sp, color = Color(0xFF475569))
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        try {
                                            val r = RetrofitClient.instance.removePrpItem(storeId, batch!!.id, it.id)
                                            toastOk = r.success; toast = r.message ?: "Removed"
                                            if (r.success) refreshOpen()
                                        } catch (e: Exception) { toastOk = false; toast = e.message }
                                    }
                                }) { Text("Remove", color = Color(0xFFEF4444), fontSize = 11.sp) }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) } // breathing room above action bar
            }
        }

        // Action bar for open batch
        if (batch != null) {
            Surface(color = Color(0xFFF1F5F9), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showCloseDialog = true },
                        enabled = items.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text("CLOSE", fontSize = 12.sp) }
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    val r = RetrofitClient.instance.printPrpManifest(storeId, batch!!.id)
                                    toastOk = r.success; toast = r.message ?: "Sent to printer"
                                } catch (e: Exception) { toastOk = false; toast = e.message }
                            }
                        },
                        enabled = items.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = PrpBrown),
                        modifier = Modifier.weight(1f)
                    ) { Text("PRINT", fontSize = 12.sp) }
                }
            }
        }

        toast?.let {
            Surface(color = if (toastOk) Color(0xFFECFDF5) else Color(0xFFFEF2F2), border = BorderStroke(1.dp, if (toastOk) Color(0xFF10B981) else Color(0xFFEF4444)), modifier = Modifier.fillMaxWidth()) {
                Text(it, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            }
        }
    }

    // Add item dialog
    if (showAddDialog && addItem != null) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; addItem = null },
            title = { Text("Add to Batch #${batch?.id ?: "?"}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(addItem!!.name, fontWeight = FontWeight.Bold)
                    Text("SKU ${addItem!!.sku}  ·  OH ${addItem!!.quantity}", fontSize = 11.sp, color = Color(0xFF64748B))
                    Spacer(Modifier.height(4.dp))
                    Text("Quantity", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    BasicTextField(
                        value = addQty,
                        onValueChange = { addQty = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(100.dp).height(40.dp).border(1.dp, Color.Gray).padding(8.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text("Reason", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        reasonCodes.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { rc ->
                                    val selected = addReason == rc
                                    Surface(
                                        color = if (selected) PrpBrown else Color(0xFFE2E8F0),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.weight(1f).clickable { addReason = rc }
                                    ) {
                                        Text(rc, color = if (selected) Color.White else Color.Black, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 6.dp))
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    Text("Notes (optional)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    BasicTextField(
                        value = addNotes, onValueChange = { addNotes = it },
                        modifier = Modifier.fillMaxWidth().height(50.dp).border(1.dp, Color.Gray).padding(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val q = addQty.toIntOrNull() ?: 0
                        if (q <= 0) { toastOk = false; toast = "Enter a positive quantity"; return@Button }
                        addSubmitting = true
                        scope.launch {
                            try {
                                val r = RetrofitClient.instance.addPrpItem(
                                    storeId, batch!!.id,
                                    com.github.tyke_bc.hht.network.AddPrpItemRequest(
                                        addItem!!.sku, q, addReason, addNotes.ifBlank { null },
                                        MainActivity.loggedInEid.ifBlank { null }
                                    )
                                )
                                toastOk = r.success; toast = r.message ?: (if (r.success) "Added" else "Failed")
                                if (r.success) { showAddDialog = false; addItem = null; refreshOpen() }
                            } catch (e: Exception) { toastOk = false; toast = e.message }
                            finally { addSubmitting = false }
                        }
                    },
                    enabled = !addSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = PrpBrown)
                ) { Text(if (addSubmitting) "…" else "ADD") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false; addItem = null }) { Text("Cancel") } }
        )
    }

    // Close dialog
    if (showCloseDialog && batch != null) {
        AlertDialog(
            onDismissRequest = { showCloseDialog = false },
            title = { Text("Close Batch #${batch!!.id}?") },
            text = { Text("Closing locks the batch. You won't be able to add or remove items. Proceed?") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        try {
                            val r = RetrofitClient.instance.closePrpBatch(storeId, batch!!.id,
                                com.github.tyke_bc.hht.network.ClosePrpBatchRequest(MainActivity.loggedInEid.ifBlank { null }))
                            toastOk = r.success; toast = r.message ?: "Closed"
                            if (r.success) {
                                // Auto-print manifest on close
                                try { RetrofitClient.instance.printPrpManifest(storeId, batch!!.id) } catch (_: Exception) {}
                                refreshOpen()
                            }
                        } catch (e: Exception) { toastOk = false; toast = e.message }
                        finally { showCloseDialog = false }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = PrpBrown)) { Text("CLOSE & PRINT") }
            },
            dismissButton = { TextButton(onClick = { showCloseDialog = false }) { Text("Cancel") } }
        )
    }
}
