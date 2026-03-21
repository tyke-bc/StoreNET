package com.github.tyke_bc.hht

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tyke_bc.hht.ui.theme.*

@Composable
fun LauncherScreen(onOpenApp: (String) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 2 })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFFFB300), Color(0xFFFF6F00))
                )
            )
    ) {
        // DOLLAR GENERAL Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, bottom = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DGYellow,
                modifier = Modifier.fillMaxWidth(0.85f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
            ) {
                Text(
                    text = "DOLLAR GENERAL",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.Black,
                        letterSpacing = 1.sp
                    ),
                    modifier = Modifier.padding(vertical = 12.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        // Pager for App Grids
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            if (page == 0) {
                Page1(onOpenApp)
            } else {
                Page2(onOpenApp)
            }
        }

        // Bottom Info Bar
        Surface(
            color = Color.Black.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("IP: 10.166.251.202", color = Color.White, fontSize = 10.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("Device Name: HHT24282524703224", color = Color.White, fontSize = 10.sp)
                }
                Text(
                    "POWERED BY SOTI MOBICONTROL", 
                    color = Color.White, 
                    fontSize = 10.sp, 
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
}

@Composable
fun Page1(onOpenApp: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Row 1: Full width HHT
        Row(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            WideHhtButton(onClick = { onOpenApp("HHT") })
        }
        
        // Row 2: DGConnect, Settings Manager, MC
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.weight(1f)) { 
                AppButton("DGCONNECT", DGOrange, onClick = { onOpenApp("DGCONNECT") }) 
            }
            Box(modifier = Modifier.weight(1f)) { 
                AppButton("SETTINGS\nMANAGER", DGBlue, onClick = { onOpenApp("SETTINGS MANAGER") }) 
            }
            Box(modifier = Modifier.weight(1f)) { 
                AppButton("MC", DGGreen, onClick = { onOpenApp("MC") }) 
            }
        }
        
        // Row 3: DGGO, Respond, WalkMe
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.weight(1f)) { 
                AppButton("DGGO", DGGreen, onClick = { onOpenApp("DGGO") }) 
            }
            Box(modifier = Modifier.weight(1f)) { 
                AppButton("RESPOND", DGBlue, onClick = { onOpenApp("RESPOND") }) 
            }
            Box(modifier = Modifier.weight(1f)) { 
                AppButton("WALKME", DGOrange, onClick = { onOpenApp("WALKME") }) 
            }
        }
    }
}

@Composable
fun Page2(onOpenApp: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Row 1: Full width DGTRACKER
        Row(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            WideAppButton("DGTRACKER", DGBlue, onClick = { onOpenApp("DGTRACKER") })
        }
        // Row 2: FedEx, Compass, Pickup
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.weight(1f)) { AppButton("FEDEX", DGOrange, onClick = { onOpenApp("FEDEX") }) }
            Box(modifier = Modifier.weight(1f)) { AppButton("COMPASS", DGBlue, onClick = { onOpenApp("COMPASS") }) }
            Box(modifier = Modifier.weight(1f)) { AppButton("DG PICKUP", DGGreen, onClick = { onOpenApp("DG PICKUP") }) }
        }
        // Row 3: Elevate
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.weight(1f)) { AppButton("ELEVATE", DGGreen, onClick = { onOpenApp("ELEVATE") }) }
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun WideHhtButton(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = DGHhtBlue,
        modifier = Modifier.fillMaxSize(),
        border = BorderStroke(2.dp, Color.White),
        shadowElevation = 2.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DGYellow,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("DG", fontWeight = FontWeight.ExtraBold, color = Color.Black, fontSize = 28.sp)
                }
            }
            Spacer(modifier = Modifier.width(32.dp))
            Text(
                text = "HHT",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
fun AppButton(name: String, bgColor: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        modifier = Modifier
            .aspectRatio(1f)
            .fillMaxWidth(),
        border = BorderStroke(2.dp, Color.White),
        shadowElevation = 2.dp,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ButtonIcon(name)
            
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                lineHeight = 12.sp
            )
        }
    }
}

@Composable
fun ButtonIcon(name: String) {
    when (name) {
        "DGCONNECT" -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black,
                modifier = Modifier.size(48.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("DG", color = DGYellow, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, lineHeight = 16.sp)
                    Text("CONNECT", color = DGYellow, fontWeight = FontWeight.Bold, fontSize = 7.sp)
                }
            }
        }
        "SETTINGS\nMANAGER" -> {
            Surface(
                shape = CircleShape,
                color = Color.Black,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = DGYellow, modifier = Modifier.size(28.dp))
                }
            }
        }
        "MC" -> {
            Surface(
                shape = CircleShape,
                color = Color(0xFF005696),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.TrackChanges, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
        "DGGO" -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DGYellow,
                modifier = Modifier.size(48.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("DG", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, lineHeight = 16.sp)
                    Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                }
            }
        }
        "RESPOND" -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DGYellow,
                modifier = Modifier.size(48.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("DG", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, lineHeight = 16.sp)
                    Text("Respond", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                }
            }
        }
        "WALKME" -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DGPurple,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Work, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
        "FEDEX" -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DGPurple,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("FedEx", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
        "COMPASS" -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.White,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Explore, contentDescription = null, tint = Color.Black, modifier = Modifier.size(32.dp))
                }
            }
        }
        "DG PICKUP" -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.White,
                modifier = Modifier.size(48.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("DG", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, lineHeight = 16.sp)
                    Text("PICKUP", color = DGGreen, fontWeight = FontWeight.ExtraBold, fontSize = 7.sp)
                }
            }
        }
        "ELEVATE" -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF8BC34A),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
        "DGTRACKER_ICON" -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DGPurple,
                modifier = Modifier.size(64.dp),
                border = BorderStroke(2.dp, DGOrange)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.LocalShipping, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                    Text("TRACKER", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 8.sp)
                }
            }
        }
    }
}

@Composable
fun WideAppButton(name: String, bgColor: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        modifier = Modifier.fillMaxSize(),
        border = BorderStroke(2.dp, Color.White),
        shadowElevation = 2.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            ButtonIcon("${name}_ICON")
            Spacer(modifier = Modifier.width(32.dp))
            Text(
                text = name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}
