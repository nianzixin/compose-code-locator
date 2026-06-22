package dev.codelocator.demo.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun FeatureCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFE9F7EF), RoundedCornerShape(24.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Feature module",
            color = Color(0xFF145A32),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = {}) {
            Text("Feature CTA")
        }
    }
}
