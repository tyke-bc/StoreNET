const fs = require('fs');

let code = fs.readFileSync('handheld_app/app/src/main/java/com/github/tyke_bc/hht/ScanScreen.kt', 'utf8');

const startIdx = code.indexOf('@Composable\r\nfun ProductMainContent');
const startIdx2 = code.indexOf('@Composable\nfun ProductMainContent');
const actualStart = startIdx !== -1 ? startIdx : startIdx2;

const endIdx = code.indexOf('@Composable\r\nfun AdjustmentsDamagesContent');
const endIdx2 = code.indexOf('@Composable\nfun AdjustmentsDamagesContent');
const actualEnd = endIdx !== -1 ? endIdx : endIdx2;

if (actualStart !== -1 && actualEnd !== -1) {
    const newProductMainContent = `@Composable
fun ProductMainContent(upcInput: String, onUpcChange: (String) -> Unit) {
    var item by remember { mutableStateOf<com.github.tyke_bc.hht.network.InventoryItem?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val storeId = "14302" // Hardcoded for local store context

    var showAdjustDialog by remember { mutableStateOf(false) }
    var adjustQty by remember { mutableStateOf("") }
    var adjustEid by remember { mutableStateOf("") }
    var adjustPin by remember { mutableStateOf("") }
    var adjustError by remember { mutableStateOf<String?>(null) }

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
                                val response = com.github.tyke_bc.hht.network.RetrofitClient.instance.getInventoryItem(storeId, upcInput)
                                if (response.success) {
                                    item = response.item
                                } else {
                                    errorMessage = "Item not found"
                                    item = null
                                }
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
        } else if (errorMessage != null && item == null) {
            Text(errorMessage!!, fontSize = 18.sp, color = Color.Red)
        } else if (item != null) {
            if (errorMessage != null) {
                Text(errorMessage!!, fontSize = 16.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(text = "PSKU: \${item!!.sku}", fontSize = 18.sp, color = Color.Black, modifier = Modifier.padding(vertical = 4.dp))
            Text(text = "UPC: \${item!!.upc ?: "N/A"}", fontSize = 18.sp, color = Color.Black, modifier = Modifier.padding(vertical = 4.dp))
            Text(text = "Desc: \${item!!.name}", fontSize = 18.sp, color = Color.Black, modifier = Modifier.padding(vertical = 4.dp))
            Text(text = "Regular Price: $\${item!!.price}", fontSize = 18.sp, color = Color.Black, modifier = Modifier.padding(vertical = 4.dp))
            Text(text = "Dept: \${item!!.department}", fontSize = 18.sp, color = Color.Black, modifier = Modifier.padding(vertical = 4.dp))
            Text(text = "OHA Qty: \${item!!.quantity}", fontSize = 18.sp, color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    coroutineScope.launch {
                        try {
                            errorMessage = "Sending to printer..."
                            val req = com.github.tyke_bc.hht.network.PrintRequest(
                                name = item!!.name,
                                sku = item!!.sku,
                                upc = item!!.upc ?: item!!.sku
                            )
                            val res = com.github.tyke_bc.hht.network.RetrofitClient.instance.printSticker(storeId, req)
                            if(res.success) {
                                errorMessage = "Label sent to printer!"
                            } else {
                                errorMessage = res.message ?: "Print failed"
                            }
                        } catch(e:Exception) { errorMessage = "Print error: \${e.message}" }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(55.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
            ) {
                Text("PRINT WAREHOUSE LABEL", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { 
                    adjustQty = item!!.quantity.toString()
                    adjustEid = ""
                    adjustPin = ""
                    adjustError = null
                    showAdjustDialog = true 
                },
                modifier = Modifier.fillMaxWidth().height(55.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEAB308))
            ) {
                Text("ADJUST OHA (CYCLE COUNT)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }

        } else {
            listOf("PSKU:", "Desc:", "Regular Price:", "Current Day OH:", "Prior Day OH:", "Ship Unit:", "In Transit:", "OHA Qty:").forEach { field ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(text = field, fontSize = 18.sp, color = Color.Black)
                }
            }
        }
    }

    if (showAdjustDialog) {
        AlertDialog(
            onDismissRequest = { showAdjustDialog = false },
            title = { Text("Manager Override Required", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("New Quantity:", color = Color.Black)
                    BasicTextField(
                        value = adjustQty,
                        onValueChange = { adjustQty = it },
                        textStyle = TextStyle(color = Color.Black, fontSize = 18.sp),
                        modifier = Modifier.fillMaxWidth().background(Color.White).border(1.dp, Color.Gray).padding(8.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Manager EID:", color = Color.Black)
                    BasicTextField(
                        value = adjustEid,
                        onValueChange = { adjustEid = it },
                        textStyle = TextStyle(color = Color.Black, fontSize = 18.sp),
                        modifier = Modifier.fillMaxWidth().background(Color.White).border(1.dp, Color.Gray).padding(8.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Manager PIN:", color = Color.Black)
                    BasicTextField(
                        value = adjustPin,
                        onValueChange = { adjustPin = it },
                        textStyle = TextStyle(color = Color.Black, fontSize = 18.sp),
                        modifier = Modifier.fillMaxWidth().background(Color.White).border(1.dp, Color.Gray).padding(8.dp)
                    )
                    if (adjustError != null) {
                        Text(adjustError!!, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    coroutineScope.launch {
                        try {
                            val authRes = com.github.tyke_bc.hht.network.RetrofitClient.instance.authLocal(
                                storeId, 
                                com.github.tyke_bc.hht.network.AuthRequest(adjustEid, adjustPin)
                            )
                            if (authRes.success && authRes.user != null) {
                                val role = authRes.user.role
                                if (role == "LSA" || role == "ASM" || role == "SM") {
                                    // Update inventory
                                    val updReq = com.github.tyke_bc.hht.network.UpdateInventoryRequest(
                                        oldSku = item!!.sku,
                                        newSku = item!!.sku,
                                        name = item!!.name,
                                        department = item!!.department,
                                        price = item!!.price,
                                        quantity = adjustQty.toIntOrNull() ?: 0
                                    )
                                    val updRes = com.github.tyke_bc.hht.network.RetrofitClient.instance.updateInventory(storeId, updReq)
                                    if (updRes.success) {
                                        showAdjustDialog = false
                                        // Refresh item
                                        item = com.github.tyke_bc.hht.network.RetrofitClient.instance.getInventoryItem(storeId, item!!.sku).item
                                        errorMessage = "OHA Updated by \${authRes.user.name}"
                                    } else {
                                        adjustError = updRes.message ?: "Failed to update"
                                    }
                                } else {
                                    adjustError = "Keyholder required (LSA, ASM, SM)"
                                }
                            } else {
                                adjustError = "Invalid EID or PIN"
                            }
                        } catch(e:Exception) { adjustError = "Error: \${e.message}" }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))) {
                    Text("Authorize & Save", color = Color.White)
                }
            },
            dismissButton = {
                Button(onClick = { showAdjustDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)) { 
                    Text("Cancel", color = Color.White) 
                }
            }
        )
    }
}
\n`;

    code = code.substring(0, actualStart) + newProductMainContent + code.substring(actualEnd);
    fs.writeFileSync('handheld_app/app/src/main/java/com/github/tyke_bc/hht/ScanScreen.kt', code);
    console.log('Success via exact index.');
} else {
    console.log('Failed to find start or end index.');
}
