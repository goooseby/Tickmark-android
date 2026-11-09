package io.github.goooseby.tickmark.data

/** Scan all the way to the permitted floor, including across empty gaps. No arbitrary lookback cap. */
internal object HistoryImport {
    fun windows(now:Long,floor:Long):List<Pair<Long,Long>> {
        require(floor>=0 && now>=floor)
        val result=mutableListOf<Pair<Long,Long>>()
        var until=now
        var width=7L*86_400_000
        while(until>floor) {
            val from=maxOf(floor,until-width)
            result+=from to until
            until=from
            width=(width*4).coerceAtMost(now.coerceAtLeast(1))
        }
        return result
    }
    /** Preserve locally saved events the OS no longer returns, and retain real same-time multiplicity. */
    fun additions(saved:List<EventRow>,queried:List<EventRow>):List<EventRow> {
        fun key(e:EventRow)=listOf(e.time,e.kind,e.pkg,e.activity,e.instance)
        val remaining=saved.filterNot{it.kind.startsWith("OBSERVE_")}.groupingBy(::key).eachCount().toMutableMap()
        return queried.filter {event->
            val k=key(event);val count=remaining[k] ?: 0
            if(count>0) {remaining[k]=count-1;false} else true
        }.map{it.copy(id="history:${java.util.UUID.randomUUID()}")}
    }
}
