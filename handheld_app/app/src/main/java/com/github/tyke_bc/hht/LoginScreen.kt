package com.github.tyke_bc.hht

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.github.tyke_bc.hht.ui.theme.*

@Composable
fun LoginScreen(onLoginSuccess: (String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPesLogin by remember { mutableStateOf(false) }
    
    var storeNumber by remember { mutableStateOf("14302") }
    var showStoreDialog by remember { mutableStateOf(false) }
    var inputStoreNumber by remember { mutableStateOf(storeNumber) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // Yellow header and form container background
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DGYellow)
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text("DG", color = DGYellow, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "UHHT Login",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Store #", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.White,
                        border = BorderStroke(2.dp, DGBlue),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = storeNumber,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Form Box
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFEFEFEF), // Light grey
                border = BorderStroke(3.dp, DGBlue),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("User Name:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = Color.White,
                                focusedContainerColor = Color.White,
                                unfocusedBorderColor = Color.Gray,
                                focusedBorderColor = DGBlue
                            ),
                            shape = RoundedCornerShape(4.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text("Password:", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = Color.White,
                                focusedContainerColor = Color.White,
                                unfocusedBorderColor = Color.Gray,
                                focusedBorderColor = DGBlue
                            ),
                            shape = RoundedCornerShape(4.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Checkbox(
                                checked = isPesLogin,
                                onCheckedChange = { isPesLogin = it },
                                colors = CheckboxDefaults.colors(checkedColor = DGBlue)
                            )
                            Text("PE&S Login", fontSize = 16.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = { onLoginSuccess(storeNumber) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE0E0E0)),
                            border = BorderStroke(1.dp, Color.Gray)
                        ) {
                            Text("Login", color = Color.Black, fontSize = 18.sp)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { 
                                inputStoreNumber = storeNumber
                                showStoreDialog = true 
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE0E0E0)),
                            border = BorderStroke(1.dp, Color.Gray)
                        ) {
                            Text("Change Store", color = Color.Black, fontSize = 18.sp)
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "You are connected to: DG Network",
                            fontSize = 16.sp,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "All Other Data and apps will be disabled",
                            fontSize = 16.sp,
                            color = Color.Red
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Go to Wi-Fi Settings",
                            fontSize = 16.sp,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "DG HHT v21.25.4.23.1",
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )
                    }
                    
                    if (showStoreDialog) {
                        Dialog(onDismissRequest = { showStoreDialog = false }) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DGYellow,
                                border = BorderStroke(1.dp, Color(0xFF4DB6AC)), // light cyan/blue inner border look
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                shadowElevation = 8.dp
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Enter Store Number",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = Color.Black
                                    )
                                    
                                    Spacer(modifier = Modifier.height(16.dp))
                                    
                                    OutlinedTextField(
                                        value = inputStoreNumber,
                                        onValueChange = { inputStoreNumber = it },
                                        modifier = Modifier.fillMaxWidth(0.8f).height(50.dp),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedContainerColor = Color.White,
                                            focusedContainerColor = Color.White,
                                            unfocusedBorderColor = Color.Gray,
                                            focusedBorderColor = Color.Gray
                                        ),
                                        textStyle = TextStyle(
                                            color = Color.Red, 
                                            fontWeight = FontWeight.Bold, 
                                            fontSize = 18.sp,
                                            textAlign = TextAlign.Center
                                        ),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    
                                    Spacer(modifier = Modifier.height(16.dp))
                                    
                                    Button(
                                        onClick = {
                                            storeNumber = inputStoreNumber
                                            showStoreDialog = false
                                        },
                                        modifier = Modifier.fillMaxWidth(0.8f).height(48.dp),
                                        shape = RoundedCornerShape(4.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEFEFEF)),
                                        border = BorderStroke(1.dp, Color.Gray)
                                    ) {
                                        Text("Save Store", color = Color.Black, fontSize = 16.sp)
                                    }
                                    
                                    Spacer(modifier = Modifier.height(24.dp))
                                    
                                    Button(
                                        onClick = { showStoreDialog = false },
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        shape = RoundedCornerShape(4.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE0E0E0)),
                                        border = BorderStroke(1.dp, Color.Gray)
                                    ) {
                                        Text("Cancel", color = Color.Black, fontSize = 16.sp)
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
