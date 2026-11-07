package com.goooseby.tickmark.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goooseby.tickmark.data.*
import com.goooseby.tickmark.ui.Tickmark
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class Records(val sessions: List<SessionRow> = emptyList(), val spans: List<SpanRow> = emptyList(), val annotations: List<AnnotationRow> = emptyList(), val apps: List<AppRow> = emptyList(), val coverage: List<CoverageRow> = emptyList(), val config: Map<String,String> = emptyMap(), val unlocks: List<Long> = emptyList())
class TickViewModel(val repo: TickRepository) : ViewModel() {
    private val base = combine(repo.dao.sessions(), repo.dao.spans(), repo.dao.annotations(), repo.dao.apps(), repo.dao.coverage()) { s,d,a,m,c -> Records(s,d,a,m,c) }
    val records = combine(base, repo.dao.config(), repo.dao.unlocks()) { r,c,u -> r.copy(config = c.associate { it.key to it.value }, unlocks = u) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Records())
    fun sync() { viewModelScope.launch { repo.sync() } }
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as TickmarkApp).repository
        setContent { Tickmark(repo) }
    }
}
