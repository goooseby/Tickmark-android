package io.github.goooseby.tickmark.data.report

import io.github.goooseby.tickmark.data.*
import io.github.goooseby.tickmark.engine.analytics.DeviceReport
import io.github.goooseby.tickmark.engine.analytics.DeviceReportEngine
import io.github.goooseby.tickmark.engine.model.DeviceSpan
import io.github.goooseby.tickmark.engine.model.DeviceState
import java.time.LocalDate
import java.time.ZoneId

/** Read as one Room transaction; shared by screen state and widget publishing. */
data class Records(
    val sessions: List<SessionRow> = emptyList(),
    val spans: List<SpanRow> = emptyList(),
    val annotations: List<AnnotationRow> = emptyList(),
    val apps: List<AppRow> = emptyList(),
    val coverage: List<CoverageRow> = emptyList(),
    val config: Map<String,String> = emptyMap(),
    val unlocks: List<Long> = emptyList(),
    val journal:List<JournalRow> = emptyList(),
) {
    /** Normalize once per immutable database snapshot, including conflicts in restored records. */
    val deviceSpans: List<DeviceSpan> by lazy {
        val source=spans.map { DeviceSpan(it.start,it.end,DeviceState.valueOf(it.state)) }
        val from=source.minOfOrNull { it.start } ?: 0
        val to=source.maxOfOrNull { it.end } ?: from
        DeviceReportEngine.project(source,from,to).spans
    }
    fun deviceReport(from: Long, to: Long, now: Long = System.currentTimeMillis()): DeviceReport =
        DeviceReportEngine.project(deviceSpans,from,to,now)

    fun dayReport(date: LocalDate, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): DeviceReport =
        DeviceReportEngine.day(deviceSpans,date,zone,now)
}
