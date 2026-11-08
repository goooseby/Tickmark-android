package io.github.goooseby.tickmark.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.goooseby.tickmark.data.AppRow
import io.github.goooseby.tickmark.data.appCategory
import io.github.goooseby.tickmark.engine.analytics.AppCategory

@Composable internal fun CategoryPicker(app:AppRow,onUpdate:(AppRow)->Unit) {
    Column(Modifier.padding(horizontal=20.dp)) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            AppCategory.entries.forEach { category ->
                FilterChip(selected=app.appCategory==category,onClick={onUpdate(app.copy(categoryOverride=category.name))},label={Text(category.title)})
            }
        }
        TextButton(onClick={onUpdate(app.copy(categoryOverride=null))},enabled=app.categoryOverride!=null) {
            Text(if(app.categoryOverride==null) "自动分类" else "恢复自动分类")
        }
    }
}
