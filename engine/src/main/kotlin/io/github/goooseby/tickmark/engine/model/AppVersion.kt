package io.github.goooseby.tickmark.engine.model

data class AppVersion(val major:Int,val minor:Int,val patch:Int):Comparable<AppVersion> {
    override fun compareTo(other:AppVersion):Int = compareValuesBy(this,other,{it.major},{it.minor},{it.patch})
    companion object {
        fun parse(value:String):AppVersion? {
            val match=Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value.trim()) ?: return null
            val numbers=match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return AppVersion(numbers[0],numbers[1],numbers[2])
        }
    }
}
