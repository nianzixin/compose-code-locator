package dev.codelocator.demo

import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.codelocator.demo.feature.FeatureCard

@Composable
fun DemoApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ProfileScreen()
        }
    }
}

@Composable
private fun ProfileScreen() {
    var showPopup by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var showDesignDialog by remember { mutableStateOf(false) }
    var showNestedDialog by remember { mutableStateOf(false) }
    var showBottomSheet by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F1E8)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HeaderCard()
            ActionRow()
            FeatureCard()
            RegressionPanel(
                onShowPopup = { showPopup = true },
                onShowDialog = { showDialog = true },
                onShowDesignDialog = { showDesignDialog = true },
                onShowNestedDialog = { showNestedDialog = true },
                onShowBottomSheet = { showBottomSheet = true },
            )
        }
        DebugPanel(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )
    }
    if (showDialog) {
        DialogRegressionProbe(
            onDismiss = { showDialog = false },
        )
    }
    if (showDesignDialog) {
        DesignSystemDialogProbe(
            onDismiss = { showDesignDialog = false },
        )
    }
    if (showNestedDialog) {
        NestedOverlayDialogProbe(
            onDismiss = { showNestedDialog = false },
        )
    }
    if (showBottomSheet) {
        BottomSheetRegressionProbe(
            onDismiss = { showBottomSheet = false },
        )
    }
    if (showPopup) {
        FloatingProbePopup(
            onDismiss = { showPopup = false },
        )
    }
}

@Composable
private fun FloatingProbePopup(
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(18, 18),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            color = Color(0xFF26343C),
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(20.dp),
        ) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.padding(8.dp),
            ) {
                Text("Popup CTA")
            }
        }
    }
}

@Composable
private fun HeaderCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(24.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Ada Lovelace",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
        )
        AutoDetectedBadge()
    }
}

@Composable
private fun AutoDetectedBadge() {
    Text(
        text = "Auto source probe",
        modifier = Modifier
            .background(Color(0xFFF1E6C8), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = Color(0xFF4B3A15),
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun ActionRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = {},
            modifier = Modifier
                .weight(1f),
        ) {
            Text("Follow")
        }
        Button(
            onClick = {},
            modifier = Modifier
                .weight(1f),
        ) {
            Text("Message")
        }
    }
}

@Composable
private fun RegressionPanel(
    onShowPopup: () -> Unit,
    onShowDialog: () -> Unit,
    onShowDesignDialog: () -> Unit,
    onShowNestedDialog: () -> Unit,
    onShowBottomSheet: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(24.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "P0 regression probes",
            color = Color(0xFF4B3A15),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {},
                modifier = Modifier.weight(1f),
            ) {
                Text("确认")
            }
            Button(
                onClick = {},
                modifier = Modifier.weight(1f),
            ) {
                Text("确认")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryActionButton(
                text = "确认",
                modifier = Modifier.weight(1f),
            )
            PrimaryActionButton(
                text = "确认",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NoModifierConfirmAction(text = "确认")
            NoModifierConfirmAction(text = "确认")
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .background(Color(0xFFF5F1E8), RoundedCornerShape(16.dp))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(listOf(1, 2)) { index ->
                ConfirmListRow(index = index)
            }
        }
        OverlayRegressionControls(
            onShowPopup = onShowPopup,
            onShowDialog = onShowDialog,
        )
        DesignSystemWrapperRegressionProbe(
            onShowDialog = onShowDesignDialog,
        )
        AdvancedWindowRegressionControls(
            onShowNestedDialog = onShowNestedDialog,
            onShowBottomSheet = onShowBottomSheet,
        )
        AndroidViewRegressionProbe()
        LazyGridRegressionProbe()
        NavHostRegressionProbe()
    }
}

@Composable
private fun AdvancedWindowRegressionControls(
    onShowNestedDialog: () -> Unit,
    onShowBottomSheet: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onShowNestedDialog,
            modifier = Modifier.weight(1f),
        ) {
            Text("Nested dialog")
        }
        Button(
            onClick = onShowBottomSheet,
            modifier = Modifier.weight(1f),
        ) {
            Text("Bottom sheet")
        }
    }
}

@Composable
private fun AndroidViewRegressionProbe() {
    AndroidView(
        factory = { context ->
            TextView(context).apply {
                text = "AndroidView probe"
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(android.graphics.Color.rgb(75, 58, 21))
                setBackgroundColor(android.graphics.Color.rgb(235, 247, 226))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
    )
}

@Composable
private fun OverlayRegressionControls(
    onShowPopup: () -> Unit,
    onShowDialog: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.weight(1f)) {
            Button(
                onClick = { menuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open menu")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Menu alpha") },
                    onClick = { menuExpanded = false },
                )
                val dynamicMenuText = remember { "Menu dynamic beta" }
                DropdownMenuItem(
                    text = { Text(dynamicMenuText) },
                    onClick = { menuExpanded = false },
                )
            }
        }
        Button(
            onClick = onShowDialog,
            modifier = Modifier.weight(1f),
        ) {
            Text("Open dialog")
        }
        Button(
            onClick = onShowPopup,
            modifier = Modifier.weight(1f),
        ) {
            Text("Open popup")
        }
    }
}

@Composable
private fun DesignSystemWrapperRegressionProbe(
    onShowDialog: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val designMenuText = remember { "DS menu dynamic" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFECE7FF), RoundedCornerShape(16.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesignSystemPrimaryButton(
                text = "DS primary CTA",
                modifier = Modifier.weight(1f),
            )
            DesignSystemNoModifierButton(text = "DS boundary CTA")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesignSystemDropdownShell(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.weight(1f),
                trigger = {
                    Button(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("DS menu")
                    }
                },
                content = {
                    DropdownMenuItem(
                        text = { Text("DS menu alpha") },
                        onClick = { menuExpanded = false },
                    )
                    DropdownMenuItem(
                        text = { Text(designMenuText) },
                        onClick = { menuExpanded = false },
                    )
                },
            )
            Button(
                onClick = onShowDialog,
                modifier = Modifier.weight(1f),
            ) {
                Text("DS dialog")
            }
        }
    }
}

@Composable
private fun DesignSystemPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = {},
        modifier = modifier,
    ) {
        Text(text)
    }
}

@Composable
private fun DesignSystemNoModifierButton(text: String) {
    Button(onClick = {}) {
        Text(text)
    }
}

@Composable
private fun DesignSystemDropdownShell(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    trigger: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier) {
        trigger()
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            content = content,
        )
    }
}

@Composable
private fun DesignSystemDialogProbe(
    onDismiss: () -> Unit,
) {
    val dynamicDialogText = remember { "DS dialog dynamic body" }
    DesignSystemDialogShell(
        onDismissRequest = onDismiss,
        title = { Text("DS dialog probe") },
        body = { Text(dynamicDialogText) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("DS dialog confirm")
            }
        },
    )
}

@Composable
private fun DesignSystemDialogShell(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    body: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = body,
        confirmButton = confirmButton,
    )
}

@Composable
private fun DialogRegressionProbe(
    onDismiss: () -> Unit,
) {
    val dynamicDialogText = remember { "Dialog dynamic body" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dialog probe") },
        text = { Text(dynamicDialogText) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Dialog confirm")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Dialog dismiss")
            }
        },
    )
}

@Composable
private fun NestedOverlayDialogProbe(
    onDismiss: () -> Unit,
) {
    var nestedMenuExpanded by remember { mutableStateOf(false) }
    val dynamicNestedText = remember { "Nested menu dynamic" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nested overlay dialog") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dynamicNestedText)
                Box {
                    Button(
                        onClick = { nestedMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Nested menu trigger")
                    }
                    DropdownMenu(
                        expanded = nestedMenuExpanded,
                        onDismissRequest = { nestedMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Nested menu item") },
                            onClick = { nestedMenuExpanded = false },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Nested dialog confirm")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomSheetRegressionProbe(
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dynamicSheetText = remember { "Sheet dynamic body" }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(dynamicSheetText)
            Button(onClick = onDismiss) {
                Text("Sheet confirm")
            }
        }
    }
}

@Composable
private fun LazyGridRegressionProbe() {
    val cells = remember { listOf("Grid alpha", "Grid beta", "Grid gamma", "Grid delta") }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(Color(0xFFEAF3F4), RoundedCornerShape(16.dp)),
        contentPadding = PaddingValues(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        gridItems(cells) { label ->
            Button(onClick = {}) {
                Text(label)
            }
        }
    }
}

@Composable
private fun NavHostRegressionProbe() {
    val navController = rememberNavController()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF1E6C8), RoundedCornerShape(16.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { navController.navigate("details") },
                modifier = Modifier.weight(1f),
            ) {
                Text("Nav details")
            }
            Button(
                onClick = { navController.navigate("home") },
                modifier = Modifier.weight(1f),
            ) {
                Text("Nav home")
            }
        }
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
        ) {
            composable("home") {
                NavHomeProbe()
            }
            composable("details") {
                NavDetailsProbe()
            }
        }
    }
}

@Composable
private fun NavHomeProbe() {
    Button(onClick = {}) {
        Text("Home CTA")
    }
}

@Composable
private fun NavDetailsProbe() {
    val dynamicDetailsText = remember { "Details dynamic title" }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = dynamicDetailsText,
            modifier = Modifier.weight(1f),
            color = Color(0xFF4B3A15),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = {}) {
            Text("Details CTA")
        }
    }
}

@Composable
private fun PrimaryActionButton(
    text: String,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = {},
        modifier = modifier,
    ) {
        Text(text)
    }
}

@Composable
private fun NoModifierConfirmAction(text: String) {
    Button(onClick = {}) {
        Text(text)
    }
}

@Composable
private fun ConfirmListRow(index: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Lazy row $index",
            modifier = Modifier.weight(1f),
            color = Color(0xFF4B3A15),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = {}) {
            Text("确认")
        }
    }
}

@Composable
private fun DebugPanel(
    modifier: Modifier = Modifier,
) {
    val probeCount = remember { "auto" }
    val sampleHitSourceSymbol = remember { "none" }
    val currentSourceSymbol = remember { "none" }
    Box(
        modifier = modifier
            .width(210.dp)
            .background(Color(0xFF1D2A32), RoundedCornerShape(24.dp))
            .padding(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Registered nodes: $probeCount",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Hit@60,180: ${sampleHitSourceSymbol ?: "none"}",
                color = Color(0xFFC8D7E1),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Current source: ${currentSourceSymbol ?: "none"}",
                color = Color(0xFFC8D7E1),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
