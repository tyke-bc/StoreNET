package com.github.tyke_bc.hht

import kotlinx.coroutines.launch
import com.github.tyke_bc.hht.network.RetrofitClient
import com.github.tyke_bc.hht.network.InventoryItem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    var upcInput by remember { mutableStateOf("") }
    var item by remember { mutableStateOf<InventoryItem?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    var adjustmentUpcInput by remember { mutableStateOf("") }
    var receivingSearchInput by remember { mutableStateOf("") }
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
                        upcInput = item?.upc ?: item?.sku ?: "" // Auto-update input field with result
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

    LaunchedEffect(Unit) {
        MainActivity.scanEvents.collect { scannedData ->
            if (selectedScreen == "Home") {
                performSearch(scannedData) // POINT AND SHOOT - no GO required
            } else {
                when (selectedScreen) {
                    "Adjustments" -> adjustmentUpcInput = scannedData
                    "Receiving" -> receivingSearchInput = scannedData
                    "Counts/Recalls" -> countsUpcInput = scannedData
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
                                Text(text = "Default User", fontSize = 18.sp, color = Color.Black)
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
                            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFD6D6D6)).border(BorderStroke(1.dp, Color.Gray))) {
                                Box(modifier = Modifier.weight(1f).background(Color(0xFFE8E8E8)).padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Text("Receiving", fontSize = 14.sp, color = Color.Black) }
                                Spacer(modifier = Modifier.weight(3f))
                            }
                            ReceivingContent(receivingSearchInput, { receivingSearchInput = it })
                        }
                        "Counts/Recalls" -> {
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
                        NavMenuItem(Icons.Default.Calculate, "Counts / Recalls", Color(0xFF5D4037)) { selectedScreen = "Counts/Recalls"; isMenuOpen = false }
                        NavMenuItem(Icons.AutoMirrored.Filled.Send, "Transfers", DGBlue) { selectedScreen = "Transfers"; isMenuOpen = false }
                        NavMenuItem(Icons.Default.Assignment, "Review", DGBlue) { isMenuOpen = false }
                        NavMenuItem(Icons.Default.Cloud, "Nones & Tons", DGBlue) { isMenuOpen = false }
                        NavMenuItem(Icons.Default.CheckBox, "Cooler Freezer / Safety Walk", Color(0xFFFBC02D)) { isMenuOpen = false }
                        NavMenuItem(Icons.Default.FactCheck, "Compliance Check", Color(0xFFFF6F00)) { isMenuOpen = false }
                        NavMenuItem(Icons.Default.Build, "Refrigeration Maintenance", DGBlue) { isMenuOpen = false }
                        NavMenuItem(Icons.Default.Inventory, "PRP Returns", Color(0xFF5D4037)) { isMenuOpen = false }
                    }
                }
            }
        }
    }
}

@Composable
fun ProductMainContent(storeId: String, upcInput: String, onUpcChange: (String) -> Unit, item: InventoryItem?, isLoading: Boolean, errorMessage: String?, onSearch: (String) -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var localError by remember { mutableStateOf<String?>(null) }
    var showAdjustDialog by remember { mutableStateOf(false) }
    var adjustQty by remember { mutableStateOf("") }
    var adjustEid by remember { mutableStateOf("") }
    var adjustPin by remember { mutableStateOf("") }
    var adjustError by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
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
            Text(text = "Regular Price: $${item.price}", fontSize = 18.sp, color = Color.Black)
            Text(text = "Dept: ${item.department}", fontSize = 18.sp, color = Color.Black)
            Text(text = "OHA Qty: ${item.quantity}", fontSize = 18.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = {
                coroutineScope.launch {
                    try {
                        localError = "Sending to printer..."
                        val res = RetrofitClient.instance.printSticker(storeId, com.github.tyke_bc.hht.network.PrintRequest(item.name, item.sku, item.upc ?: item.sku))
                        localError = if(res.success) "Warehouse Label printed!" else res.message ?: "Print failed"
                    } catch(e:Exception) { localError = "Print error: ${e.message}" }
                }
            }, modifier = Modifier.fillMaxWidth().height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))) { Text("PRINT WAREHOUSE LABEL", fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                coroutineScope.launch {
                    try {
                        localError = "Sending shelf label..."
                        val req = com.github.tyke_bc.hht.network.PrintShelfLabelRequest(item.brand ?: "", item.name, item.variant ?: "", item.size ?: "", item.upc ?: item.sku, item.price, item.unitPriceUnit ?: "per each", item.taxable ?: true, item.pogDate ?: "N/A", item.location ?: "N/A", item.faces ?: "F1")
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
                            val res = RetrofitClient.instance.updateInventory(storeId, com.github.tyke_bc.hht.network.UpdateInventoryRequest(item.sku, item.sku, item.name, item.department, item.price, adjustQty.toIntOrNull() ?: 0))
                            if (res.success) { showAdjustDialog = false; onSearch(item.sku); localError = "OHA Updated!" } else adjustError = res.message
                        } else adjustError = "Auth failed or unauthorized"
                    } catch(e:Exception) { adjustError = e.message }
                }
            }) { Text("Save") }
        })
    }
}

@Composable
fun LocationsContent(item: InventoryItem?) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
fun ReceivingContent(searchInput: String, onSearchChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Search:", fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.width(12.dp))
            BasicTextField(value = searchInput, onValueChange = onSearchChange, modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray).padding(8.dp))
            Spacer(modifier = Modifier.width(8.dp)); BarcodePlaceholder()
        }
        Spacer(modifier = Modifier.height(24.dp))
        Surface(color = Color(0xFFD6D6D6), modifier = Modifier.fillMaxWidth()) { Text("DG Trucks: Scan BOL. Others: Scan item or vendor name.", modifier = Modifier.padding(12.dp), textAlign = TextAlign.Center) }
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
fun NavMenuItem(icon: ImageVector, label: String, iconColor: Color, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, tint = iconColor, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, fontSize = 18.sp, color = DGBlue)
    }
}

@Composable
fun BarcodePlaceholder() {
    Row(modifier = Modifier.height(40.dp).width(50.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        repeat(8) { i -> Box(modifier = Modifier.width(listOf(2.dp, 4.dp, 1.dp, 3.dp, 2.dp, 5.dp, 1.dp, 2.dp)[i]).fillMaxHeight(0.8f).background(Color.Black)) }
    }
}

@Composable
fun GeneralContent(item: InventoryItem?) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
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
            Text(text = label, fontSize = 16.sp, color = Color.Black)
        }
        Box(modifier = Modifier.width(0.5.dp).fillMaxHeight().background(Color.LightGray))
        Box(modifier = Modifier.weight(1.5f).background(Color.White).padding(12.dp)) {
            Text(text = value, fontSize = 16.sp, color = Color.Black)
        }
    }
}
