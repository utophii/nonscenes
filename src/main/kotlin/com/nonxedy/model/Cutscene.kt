package com.nonxedy.model

// Represents a cutscene with a name and a list of frames
data class Cutscene(
    val name: String,
    val frames: List<CutsceneFrame>,
    val ticksPerFrame: Int = 1
)
