package com.cesia.input.engine.rime.trime

data class SchemaItem(
    val id: String,
    val name: String = "",
)

data class CandidateItem(
    val text: String,
    val comment: String = "",
)
