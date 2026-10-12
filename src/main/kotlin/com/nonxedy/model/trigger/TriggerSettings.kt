package com.nonxedy.model.trigger

data class TriggerSettings(
    val firstJoinEnabled: Boolean = false,
    val firstJoinCutscene: String = "",
    val firstJoinDelayTicks: Long = 40L,
    val commands: Map<String, String> = emptyMap(),
    val regions: List<RegionTrigger> = emptyList()
)

data class RegionTrigger(
    val id: String,
    val world: String,
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
    val cutscene: String,
    val once: Boolean = true
) {
    fun contains(worldName: String, x: Double, y: Double, z: Double): Boolean {
        if (!world.equals(worldName, ignoreCase = true)) return false
        return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }
}
