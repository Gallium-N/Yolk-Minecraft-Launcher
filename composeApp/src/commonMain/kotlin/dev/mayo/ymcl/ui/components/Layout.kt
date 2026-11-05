package dev.mayo.ymcl.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Page title on the left, a short meta line on the same baseline on the right. */
@Composable
fun PageHeader(title: String, meta: String) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .height(88.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 30.dp),
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Text(
            text = meta,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 36.dp),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
fun SectionHeader(title: String, hint: String? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        if (hint != null) {
            Box(Modifier.weight(1f))
            Text(
                text = hint,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
