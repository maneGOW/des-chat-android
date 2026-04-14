package com.manegow.model.mesh

@JvmInline
value class HopCount(val value: Int) {
    init {
        require(value >= 0) { "HopCount must be >= 0" }
        require(value <= 10) { "HopCount must be <= 10" }
    }
}