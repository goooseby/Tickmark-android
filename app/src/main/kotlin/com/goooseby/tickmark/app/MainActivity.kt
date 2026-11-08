package com.goooseby.tickmark.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.goooseby.tickmark.data.*
import com.goooseby.tickmark.ui.Tickmark
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TickViewModel(val repo: TickRepository) : ViewModel() {
    val records = repo.records.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.goooseby.tickmark.data.report.Records())
    fun sync() { viewModelScope.launch { repo.sync() } }
}
class MainActivity : ComponentActivity() {
    private val reportEntry = MutableStateFlow<ReportEntry?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as TickmarkApp).repository
        // Restored navigation already contains the user's route after leaving the widget entry.
        if(savedInstanceState==null) reportEntry.value=intent.reportEntry()
        setContent {
            val entry by reportEntry.collectAsStateWithLifecycle()
            Tickmark(repo,entry) { reportEntry.value=null }
        }
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reportEntry.value=intent.reportEntry()
    }
}
