package com.github.tyke_bc.hht

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
fun ScanScreen(onBackToLauncher: () -> Unit) {
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
    var adjustmentUpcInput by remember { mutableStateOf("") }
    var receivingSearchInput by remember { mutableStateOf("") }
    var countsUpcInput by remember { mutableStateOf("") }
    var isMenuOpen by remember { mutableStateOf(false) }

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
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(DGYellow, Color(0xFFFFE082))
                                )
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "STORE: 14302", fontSize = 18.sp, color = Color.Black)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Default User", fontSize = 18.sp, color = Color.Black)
                                Spacer(modifier = Modifier.width(16.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { onBackToLauncher() }
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ExitToApp, "Logout", tint = Color.Black, modifier = Modifier.size(28.dp))
                                    Text("LOGOUT", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = DGBlue,
                                    modifier = Modifier
                                        .size(width = 48.dp, height = 40.dp)
                                        .clickable { isMenuOpen = !isMenuOpen },
                                    shadowElevation = 2.dp
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isMenuOpen) Icons.Default.KeyboardArrowUp else Icons.Default.Menu,
                                            contentDescription = "Menu",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFD6D6D6))
                                    .border(BorderStroke(1.dp, Color.Gray))
                            ) {
                                tabs.forEachIndexed { index, title ->
                                    val isSelected = selectedTab == index
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(if (isSelected) Color(0xFFE8E8E8) else Color.Transparent)
                                            .clickable { selectedTab = index }
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = title, fontSize = 14.sp, color = if (isSelected) Color.Black else DGBlue)
                                    }
                                }
                            }
                            if (selectedTab == 0) {
                                ProductMainContent(upcInput = upcInput, onUpcChange = { upcInput = it })
                            }
                        }
                        "Adjustments" -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFD6D6D6))
                                    .border(BorderStroke(1.dp, Color.Gray))
                            ) {
                                adjustmentsTabs.forEachIndexed { index, title ->
                                    val isSelected = selectedAdjustmentsTab == index
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(if (isSelected) Color(0xFFE8E8E8) else Color.Transparent)
                                            .clickable { selectedAdjustmentsTab = index }
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = title, fontSize = 14.sp, color = if (isSelected) Color.Black else DGBlue)
                                    }
                                }
                            }
                            if (selectedAdjustmentsTab == 0) {
                                AdjustmentsDamagesContent(upcInput = adjustmentUpcInput, onUpcChange = { adjustmentUpcInput = it })
                            } else {
                                AdjustmentsStoreUseContent(upcInput = adjustmentUpcInput, onUpcChange = { adjustmentUpcInput = it })
                            }
                        }
                        "Receiving" -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFD6D6D6))
                                    .border(BorderStroke(1.dp, Color.Gray))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(Color(0xFFE8E8E8))
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = "Receiving", fontSize = 14.sp, color = Color.Black)
                                }
                                Spacer(modifier = Modifier.weight(3f))
                            }
                            ReceivingContent(searchInput = receivingSearchInput, onSearchChange = { receivingSearchInput = it })
                        }
                        "Counts/Recalls" -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFD6D6D6))
                                    .border(BorderStroke(1.dp, Color.Gray))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(Color(0xFFE8E8E8))
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = "Precount", fontSize = 14.sp, color = Color.Black)
                                }
                                Spacer(modifier = Modifier.weight(3f))
                            }
                            CountsRecallsContent(upcInput = countsUpcInput, onUpcChange = { countsUpcInput = it })
                        }
                        "Transfers" -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFD6D6D6))
                                    .border(BorderStroke(1.dp, Color.Gray))
                            ) {
                                transfersTabs.forEachIndexed { index, title ->
                                    val isSelected = selectedTransfersTab == index
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(if (isSelected) Color(0xFFE8E8E8) else Color.Transparent)
                                            .clickable { selectedTransfersTab = index }
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
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

        // Navigation Dropdown Overlay
        if (isMenuOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { isMenuOpen = false }
                    .background(Color.Black.copy(alpha = 0.1f))
                    .zIndex(10f)
            ) {
                Surface(
                    modifier = Modifier
                        .padding(start = 12.dp, top = 110.dp)
                        .width(280.dp)
                        .wrapContentHeight(),
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFF0F0F0),
                    border = BorderStroke(1.dp, Color.Gray),
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        NavMenuItem(Icons.Default.Home, "Home", DGBlue) {
                            selectedScreen = "Home"
                            isMenuOpen = false
                        }
                        NavMenuItem(Icons.Default.Settings, "Adjustment", DGBlue) {
                            selectedScreen = "Adjustments"
                            isMenuOpen = false
                        }
                        NavMenuItem(Icons.Default.LocalShipping, "Receiving", Color(0xFFFFC107)) {
                            selectedScreen = "Receiving"
                            isMenuOpen = false
                        }
                        NavMenuItem(Icons.Default.Calculate, "Counts / Recalls", Color(0xFF5D4037)) {
                            selectedScreen = "Counts/Recalls"
                            isMenuOpen = false
                        }
                        NavMenuItem(Icons.AutoMirrored.Filled.Send, "Transfers", DGBlue) {
                            selectedScreen = "Transfers"
                            isMenuOpen = false
                        }
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
fun NavMenuItem(icon: ImageVector, label: String, iconColor: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            fontSize = 18.sp,
            color = DGBlue,
            fontWeight = FontWeight.Normal
        )
    }
}

import kotlinx.coroutines.launch
import com.github.tyke_bc.hht.network.RetrofitClient
import com.github.tyke_bc.hht.network.InventoryItem

@Composable
fun ProductMainContent(upcInput: String, onUpcChange: (String) -> Unit) {
    var item by remember { mutableStateOf<InventoryItem?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text = "UPC/SKU:", fontSize = 18.sp, color = DGBlue, modifier = Modifier.width(90.dp))
            BasicTextField(
                value = upcInput,
                onValueChange = onUpcChange,
                textStyle = TextStyle(color = Color.Black, fontSize = 18.sp),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (upcInput.isNotEmpty()) {
                        coroutineScope.launch {
                            isLoading = true
                            errorMessage = null
                            try {
                                item = RetrofitClient.instance.getInventoryItem(upcInput)
                            } catch (e: Exception) {
                                errorMessage = "Item not found or network error"
                                item = null
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                modifier = Modifier.height(40.dp)
            ) {
                Text("GO")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            Text("Loading...", fontSize = 18.sp, color = Color.Gray)
        } else if (errorMessage != null) {
            Text(errorMessage!!, fontSize = 18.sp, color = Color.Red)
        } else if (item != null) {
            Text(text = "PSKU: ${item!!.sku}", fontSize = 18.sp, color = Color.Black, modifier = Modifier.padding(vertical = 4.dp))
            Text(text = "Desc: ${item!!.name}", fontSize = 18.sp, color = Color.Black, modifier = Modifier.padding(vertical = 4.dp))
            Text(text = "Regular Price: $${item!!.price}", fontSize = 18.sp, color = Color.Black, modifier = Modifier.padding(vertical = 4.dp))
            Text(text = "Dept: ${item!!.department}", fontSize = 18.sp, color = Color.Black, modifier = Modifier.padding(vertical = 4.dp))
            Text(text = "OHA Qty: ${item!!.quantity}", fontSize = 18.sp, color = Color.Black, modifier = Modifier.padding(vertical = 4.dp))
            Text(text = "Status: ${item!!.status}", fontSize = 18.sp, color = Color.Black, modifier = Modifier.padding(vertical = 4.dp))
        } else {
            listOf("PSKU:", "Desc:", "Regular Price:", "Current Day OH:", "Prior Day OH:", "Ship Unit:", "In Transit:", "OHA Qty:").forEach { field ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(text = field, fontSize = 18.sp, color = Color.Black)
                }
            }
        }
    }
}
@Composable
fun AdjustmentsDamagesContent(upcInput: String, onUpcChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                value = upcInput,
                onValueChange = onUpcChange,
                textStyle = TextStyle(color = Color.Gray, fontSize = 16.sp),
                singleLine = true,
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (upcInput.isEmpty()) {
                            Text("Scan or Enter UPC", color = Color.Gray, fontSize = 16.sp)
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(Color(0xFFE8E8E8))
                    .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            BarcodePlaceholder()
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        listOf("Regular\nPrice:", "Location:", "Reason\nCode:", "Current\nOH:", "Adjustment\nQuantity:").forEach { field ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(text = field, fontSize = 18.sp, color = Color.Black, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
            }
        }
    }
}

@Composable
fun AdjustmentsStoreUseContent(upcInput: String, onUpcChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                value = upcInput,
                onValueChange = onUpcChange,
                textStyle = TextStyle(color = Color.Gray, fontSize = 16.sp),
                singleLine = true,
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (upcInput.isEmpty()) {
                            Text("Scan or Enter UPC", color = Color.Gray, fontSize = 16.sp)
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(Color(0xFFE8E8E8))
                    .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            BarcodePlaceholder()
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        listOf("Promo\nPrice:", "Regular\nPrice:", "Current\nOH:", "Quantity:").forEach { field ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(text = field, fontSize = 18.sp, color = Color.Black, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color(0xFFE8E8E8),
            border = BorderStroke(1.dp, Color.Black),
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        ) {
            Text(text = "Print\nLabel", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@Composable
fun ReceivingContent(searchInput: String, onSearchChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Search:", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Spacer(modifier = Modifier.width(12.dp))
            BasicTextField(
                value = searchInput,
                onValueChange = onSearchChange,
                textStyle = TextStyle(color = Color.Black, fontSize = 18.sp),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            BarcodePlaceholder()
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Surface(color = Color(0xFFD6D6D6), modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "DG Trucks: Scan or type the BOL on the Proof of Delivery paperwork. All other deliveries, scan an item from the delivery or enter the vendor name.",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.padding(12.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun CountsRecallsContent(upcInput: String, onUpcChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
        // Search Row
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                value = upcInput,
                onValueChange = onUpcChange,
                textStyle = TextStyle(color = Color.Gray, fontSize = 16.sp),
                singleLine = true,
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (upcInput.isEmpty()) {
                            Text("Scan or Enter UPC", color = Color.Gray, fontSize = 16.sp)
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(Color(0xFFE8E8E8))
                    .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            BarcodePlaceholder()
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Data Fields
        val fields = listOf(
            "Desc:",
            "Regular\nPrice:",
            "Location:",
            "Current\nOH:",
            "Quantity:"
        )
        
        fields.forEach { field ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(
                    text = field,
                    fontSize = 18.sp,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

@Composable
fun TransfersOutgoingContent() {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(text = "Authorization #:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(130.dp))
            BasicTextField(
                value = "", onValueChange = {},
                modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray, RoundedCornerShape(4.dp)).background(Color(0xFFE8E8E8))
            )
            Spacer(modifier = Modifier.width(8.dp))
            BarcodePlaceholder()
        }
        
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(text = "Transfer to\nStore/DC:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(130.dp))
            BasicTextField(
                value = "", onValueChange = {},
                modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray, RoundedCornerShape(4.dp)).background(Color(0xFFE8E8E8))
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(text = "Store Name:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(130.dp))
            Text(text = "216 BELKNAP ST", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
        
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(text = "Store City/State:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(130.dp))
            Text(text = "SUPERIOR, WI", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(text = "UPC:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(130.dp))
            BasicTextField(
                value = "", onValueChange = {},
                decorationBox = { inner -> Box(modifier = Modifier.padding(start=8.dp), contentAlignment=Alignment.CenterStart) { Text("Scan or Enter UPC", color=Color.Gray); inner() } },
                modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray, RoundedCornerShape(4.dp)).background(Color(0xFFE8E8E8))
            )
            Spacer(modifier = Modifier.width(8.dp))
            BarcodePlaceholder()
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(text = "Price:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(130.dp))
            BasicTextField(
                value = "", onValueChange = {},
                modifier = Modifier.width(80.dp).height(40.dp).border(1.dp, Color.Gray, RoundedCornerShape(4.dp)).background(Color(0xFFE8E8E8))
            )
        }
        
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(text = "QTY:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(130.dp))
            BasicTextField(
                value = "", onValueChange = {},
                modifier = Modifier.fillMaxWidth().height(40.dp).border(1.dp, Color.Gray, RoundedCornerShape(4.dp)).background(Color(0xFFE8E8E8))
            )
        }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = Color(0xFFD6D6D6),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.clickable {  }
            ) {
                Text("Clear", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
fun TransfersIncomingContent() {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(text = "Transfer #:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(130.dp))
            BasicTextField(
                value = "", onValueChange = {},
                modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray, RoundedCornerShape(4.dp)).background(Color(0xFFE8E8E8))
            )
            Spacer(modifier = Modifier.width(8.dp))
            BarcodePlaceholder()
        }
        
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(text = "Date:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(130.dp))
            BasicTextField(
                value = "Mar 04, 2026", onValueChange = {},
                modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray, RoundedCornerShape(4.dp)).background(Color(0xFFE8E8E8)).padding(8.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(text = "Send Store #:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(130.dp))
            BasicTextField(
                value = "", onValueChange = {},
                modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray, RoundedCornerShape(4.dp)).background(Color(0xFFE8E8E8))
            )
        }
        
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(text = "Receiving Store #:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(130.dp))
            BasicTextField(
                value = "14302", onValueChange = {},
                modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray, RoundedCornerShape(4.dp)).background(Color(0xFFE8E8E8)).padding(8.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Checkbox(checked = false, onCheckedChange = { }, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Detailed Rcv", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
    }
}

@Composable
fun TransfersMisShipContent() {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(text = "Store that was\ncharged for this Mis-\nShipped item:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.weight(1f))
            BasicTextField(
                value = "", onValueChange = {},
                modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray, RoundedCornerShape(4.dp)).background(Color(0xFFE8E8E8))
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(text = "Store Name:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.weight(1f))
        }
        
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(text = "Store City/State:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.weight(1f))
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(text = "UPC:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(100.dp))
            BasicTextField(
                value = "", onValueChange = {},
                modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray, RoundedCornerShape(4.dp)).background(Color(0xFFE8E8E8))
            )
            Spacer(modifier = Modifier.width(8.dp))
            BarcodePlaceholder()
        }
        
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(text = "Description:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.weight(1f))
        }
        
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(text = "Price:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.weight(1f))
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(text = "QTY:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(100.dp))
            BasicTextField(
                value = "", onValueChange = {},
                modifier = Modifier.weight(1f).height(40.dp).border(1.dp, Color.Gray, RoundedCornerShape(4.dp)).background(Color(0xFFE8E8E8))
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = DGBlue,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.clickable {  }
            ) {
                Text("Add Item", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Surface(
                color = Color(0xFFD6D6D6),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.clickable {  }
            ) {
                Text("Clear", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
fun TransfersPRPContent() {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Prp Type:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(100.dp))
            Surface(
                modifier = Modifier.width(150.dp).height(40.dp),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Color.Gray),
                color = Color(0xFFE8E8E8)
            ) {
                Row(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Dropdown", tint = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun BarcodePlaceholder() {
    Surface(modifier = Modifier.height(40.dp).width(50.dp), color = Color.Transparent) {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            repeat(8) { i ->
                Box(modifier = Modifier.width(listOf(2.dp, 4.dp, 1.dp, 3.dp, 2.dp, 5.dp, 1.dp, 2.dp)[i]).fillMaxHeight(0.8f).background(Color.Black))
            }
        }
    }
}