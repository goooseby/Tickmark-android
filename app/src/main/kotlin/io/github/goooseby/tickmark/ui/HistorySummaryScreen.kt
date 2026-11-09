package io.github.goooseby.tickmark.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.goooseby.tickmark.data.report.Records
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun HistorySummaryScreen(records:Records) {
    val labels=linkedMapOf("day" to "日","week" to "周","month" to "月","year" to "年")
    val rows=remember(records.summaries,records.apps){records.summaries.filter{r->records.apps.none{it.pkg==r.pkg && (it.hidden || it.excluded)}}}
    var interval by rememberSaveable{mutableStateOf(labels.keys.firstOrNull{key->rows.any{it.interval==key}} ?: "day")}
    val groups=remember(rows,interval){rows.filter{it.interval==interval}.groupBy{it.start to it.end}.toList().sortedByDescending{it.first.first}}
    val names=records.apps.associate{it.pkg to it.name}
    fun date(time:Long)=Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    LazyColumn(contentPadding=PaddingValues(bottom=24.dp)) {
        item {FlowRow(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {labels.forEach{(key,label)->
            FilterChip(interval==key,onClick={interval=key},label={Text("${label}汇总")})
        }}}
        item {Text("系统保存的应用前台累计值。各粒度可能覆盖相同时间，请分别查看；不能还原小时分布、使用顺序或手机总用时。",
            Modifier.padding(horizontal=20.dp,vertical=12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(groups.isEmpty()) item {EmptyReport("系统未提供这一粒度的历史汇总")}
        items(groups,key={"${it.first.first}:${it.first.second}"}){(bounds,values)->
            Column(Modifier.padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("${date(bounds.first)}\n至 ${date(bounds.second)}",style=MaterialTheme.typography.titleSmall)
                values.sortedByDescending{it.foregroundMs}.forEach{row->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(names[row.pkg] ?: row.pkg,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                        Text(duration(row.foregroundMs),style=MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal=20.dp))
        }
    }
}
