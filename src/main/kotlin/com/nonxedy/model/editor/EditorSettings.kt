package com.nonxedy.model.editor

data class EditorSettings(
    val positionTolerance: Double = 0.25,
    val angleTolerance: Double = 3.0,
    val defaultSegmentMs: Long = 2000L
)
