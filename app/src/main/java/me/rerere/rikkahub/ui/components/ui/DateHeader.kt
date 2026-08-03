package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import java.time.LocalDate

/**
 * 网格/瀑布流里的日期分组头。
 * 相册与文件管理共用：今天/昨天/月日/年月日 四级文案。
 */
@Composable
fun DateHeader(
    date: LocalDate,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    val label = when (date) {
        today -> stringResource(R.string.gallery_page_date_today)
        today.minusDays(1) -> stringResource(R.string.gallery_page_date_yesterday)
        else -> if (date.year == today.year) {
            stringResource(R.string.gallery_page_date_month_day, date.monthValue, date.dayOfMonth)
        } else {
            stringResource(R.string.gallery_page_date_full, date.year, date.monthValue, date.dayOfMonth)
        }
    }
    Row(
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.titleSmall)
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
