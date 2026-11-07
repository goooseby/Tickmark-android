package com.goooseby.tickmark.engine.analytics

import com.goooseby.tickmark.engine.model.Session

data class PurposeRange(val start:Long,val end:Long,val purpose:String)

/** A device millisecond belongs to exactly one purpose, including concurrent apps. */
object PurposeEngine {
    private data class Edge(val time:Long,val layer:Int,val label:String,val delta:Int)
    fun totals(sessions:List<Session>, device:List<Pair<Long,Long>>, manual:List<PurposeRange>, defaults:Map<String,String>, from:Long,to:Long):List<Pair<String,Long>> {
        val edges=mutableListOf<Edge>()
        fun add(a:Long,b:Long,layer:Int,label:String) {
            val start=maxOf(a,from); val end=minOf(b,to)
            if(end>start) { edges+=Edge(start,layer,label,1); edges+=Edge(end,layer,label,-1) }
        }
        device.forEach { add(it.first,it.second,0,"") }
        sessions.forEach { add(it.start,it.end,1,defaults[it.packageName]?.takeIf { p->p.isNotBlank() } ?: "未分类") }
        manual.filter { it.purpose.isNotBlank() }.forEach { add(it.start,it.end,2,it.purpose) }
        val counts=List(3) { mutableMapOf<String,Int>() }
        val totals=mutableMapOf<String,Long>()
        val groups=edges.groupBy { it.time }.toSortedMap().entries.toList()
        for(i in 0 until groups.lastIndex) {
            groups[i].value.forEach { e -> val next=(counts[e.layer][e.label] ?: 0)+e.delta; if(next==0) counts[e.layer].remove(e.label) else counts[e.layer][e.label]=next }
            if(counts[0].isEmpty()) continue
            val label=when {
                counts[2].isNotEmpty()->counts[2].keys.singleOrNull() ?: "并发用途"
                counts[1].size==1->counts[1].keys.single()
                counts[1].size>1->"并发用途"
                else->"未分类"
            }
            totals[label]=(totals[label] ?: 0)+(groups[i+1].key-groups[i].key)
        }
        return totals.toList().sortedByDescending { it.second }
    }
}
