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
    val transfersTabs = listOf("Outgoing", "Incoming", "Mis-Ship", "PRP")
    
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

    // Cycle Count State
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
    var isMenuOpen by remember { mutableStateOf(false) }

    fun performSearch(input: String) {
        if (input.isNotEmpty()) {
            coroutineScope.launch {
                isLoading = true
                errorMessage = null
                var cleanedInput = input.trim()

                // --- WAREHOUSE LABEL DETECTOR ---
                if (cleanedInput.length == 18 && cleanedInput.startsWith("0000") && cleanedInput.endsWith("000")) {
                    cleanedInput = cleanedInput.substring(4, 15)
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

    LaunchedEffect(cycleCountPogId, cycleCountSection) {
        if (cycleCountPogId.isNotEmpty() && cycleCountSection.isNotEmpty()) {
            cycleCountLoading = true
            cycleCountError = null
            cycleCountItems = emptyList()
            try {
                val res = RetrofitClient.instance.getCycleCountSection(storeId, cycleCountPogId, cycleCountSection)
                if (res.success) {
                    cycleCountPogName = res.pogName ?: ""
                    cycleCountItems = res.items?.map {
                        CycleCountItemState(it.sku, it.name, it.upc, it.shelf, it.faces, it.quantity)
                    } ?: emptyList()
                } else {
                    cycleCountError = res.message ?: "Failed to load section"
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
                val stripped = input.removePrefix("CYCL_")
                val lastUnder = stripped.lastIndexOf('_')
                if (lastUnder > 0) {
                    cycleCountCounts.clear()
                    cycleCountPogId = stripped.substring(0, lastUnder)
                    cycleCountSection = stripped.substring(lastUnder + 1)
                    selectedScreen = "Cycle Count"
                }
                return@collect
            }

            if (selectedScreen == "Home") {
                if (input.startsWith("RT-") || (input.length == 15 && input.startsWith("ROL"))) {
                    detectedRTBarcode = input
                    showRTStockingDialog = true
                } else {
                    // Check if it's a warehouse label (18 digits)
                    var cleanedScan = input
                    var isWarehouseLabel = false
                    if (input.length == 18 && input.startsWith("0000") && input.endsWith("000")) {
                        cleanedScan = input.substring(4, 15)
                        isWarehouseLabel = true
                    }
                    
                    // Always perform the search first to show the item
                    performSearch(input)

                    // If it was a warehouse label, check for manifest or backstock
                    if (isWarehouseLabel) {
                        coroutineScope.launch {
                            try {
                                val manifests = RetrofitClient.instance.getManifests(storeId)
                                val pendingTrk = manifests.find { it.status != "COMPLETED" }
                                if (pendingTrk != null) {
                                    detectedManifestId = pendingTrk.id
                                    detectedSku = cleanedScan
                                    showReceivingDialog = true
                                } else {
                                    // Not on manifest, but is it in backstock?
                                    val invRes = RetrofitClient.instance.getInventoryItem(storeId, cleanedScan)
                                    if (invRes.success && (invRes.item?.quantityBackstock ?: 0) > 0) {
                                        detectedSku = cleanedScan
                                        detectedPackSize = invRes.item?.packSize ?: 1
                                        showStockingDialog = true
                                    }
                                }
                            } catch (e: Exception) { /* ignore */ }
                        }
                    }
                }
            } else if (selectedScreen == "Order Picking" && selectedOrder != null) {
                coroutineScope.launch {
                    try {
                        var cleanedScan = scannedData.trim()
                        // --- WAREHOUSE LABEL DETECTOR ---
                        if (cleanedScan.length == 18 && cleanedScan.startsWith("0000") && cleanedScan.endsWith("000")) {
                            cleanedScan = cleanedScan.substring(4, 15)
                        }

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
                                1 -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Sales History Coming Soon", color = Color.Gray) }
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
                                3 -> TransfersPRPContent()
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
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = Color.White,
                                                border = BorderStroke(1.dp, priorityColor[task.priority] ?: Color(0xFFCBD5E1)),
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                            ) {
                                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                            Surface(shape = RoundedCornerShape(4.dp), color = priorityColor[task.priority] ?: Color.Gray) {
                                                                Text(task.priority, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                            }
                                                            Text(task.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                                        }
                                                        if (!task.description.isNullOrBlank()) Text(task.description, fontSize = 12.sp, color = Color(0xFF64748B), modifier = Modifier.padding(top = 2.dp))
                                                        if (task.assignedName != null || task.dueDate != null) {
                                                            Text(listOfNotNull(task.assignedName?.let { "Assigned: $it" }, task.dueDate?.take(10)?.let { "Due: $it" }).joinToString("  ·  "), fontSize = 11.sp, color = Color(0xFF94A3B8), modifier = Modifier.padding(top = 2.dp))
                                                        }
                                                    }
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
                        "Cycle Count" -> {
                            val listState = rememberLazyListState()
                            val countedCount = cycleCountItems.count { cycleCountCounts.containsKey(it.sku) }
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Header bar
                                Surface(color = Color(0xFF1E3A5F), modifier = Modifier.fillMaxWidth()) {
                                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column {
                                            Text("POG $cycleCountPogId · SEC $cycleCountSection", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
                                                        Text("Shelf ${ccItem.shelf}  ·  SKU ${ccItem.sku}", fontSize = 11.sp, color = Color(0xFF64748B))
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
                        Text("Shelf ${cycleCountDialogItem!!.shelf}  ·  System qty: ${cycleCountDialogItem!!.systemQty}", fontSize = 12.sp, color = Color.Gray)
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
    }
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
    val section = item.location?.split("-")?.getOrNull(0) ?: "N/A"
    val shelf = item.location?.split("-")?.getOrNull(1) ?: "N/A"
    val pos = item.position ?: 1
    
    Surface(modifier = Modifier.fillMaxWidth().wrapContentHeight(), color = Color.White, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color.Gray)) {
        Box(modifier = Modifier.padding(2.dp).border(1.dp, Color.LightGray, RoundedCornerShape(6.dp)).padding(12.dp)) {
            Column {
                Text(text = item.pogInfo ?: "PENDING POG DATA", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Text(text = item.department.uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Text(text = "Sec.${section} Shelf${shelf.padStart(2, '0')} Pos.${pos.toString().padStart(2, '0')} ${item.faces ?: "F1"} ${item.location}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}

@Composable
fun AdjustmentsDamagesContent(upcInput: String, onUpcChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value = upcInput, onValueChange = onUpcChange, modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray).padding(8.dp))
            Spacer(modifier = Modifier.width(8.dp)); BarcodePlaceholder()
        }
        Spacer(modifier = Modifier.height(24.dp))
        listOf("Regular Price:", "Location:", "Reason Code:", "Current OH:", "Adjustment Qty:").forEach { Text(it, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
    }
}

@Composable
fun AdjustmentsStoreUseContent(upcInput: String, onUpcChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value = upcInput, onValueChange = onUpcChange, modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray).padding(8.dp))
            Spacer(modifier = Modifier.width(8.dp)); BarcodePlaceholder()
        }
        Spacer(modifier = Modifier.height(24.dp))
        listOf("Promo Price:", "Regular Price:", "Current OH:", "Quantity:").forEach { Text(it, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
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
fun TransfersPRPContent() { Box(modifier = Modifier.fillMaxSize()) { Text("PRP Transfers", modifier = Modifier.align(Alignment.Center)) } }

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
    val shelf: String,
    val faces: String?,
    val systemQty: Int
)
