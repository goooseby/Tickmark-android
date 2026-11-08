package com.goooseby.tickmark.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.LocalDate

data class ReportEntry(val destination:String,val date:LocalDate)
fun reportIntent(context:Context,destination:String,date:LocalDate):Intent =
    Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW)
        .setData(Uri.parse("tickmark://report/$destination/$date"))
        .putExtra("reportDestination",destination).putExtra("reportDate",date.toString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

internal fun Intent.reportEntry():ReportEntry? {
    val destination=getStringExtra("reportDestination")?.takeIf{it in setOf("today","away","capture")} ?: return null
    val date=runCatching{LocalDate.parse(getStringExtra("reportDate"))}.getOrDefault(LocalDate.now())
        .coerceAtMost(LocalDate.now())
    return ReportEntry(destination,date)
}
