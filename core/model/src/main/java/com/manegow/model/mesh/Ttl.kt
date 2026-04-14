package com.manegow.model.mesh

@JvmInline
value class Ttl(val value: Int) {
    init {
        require(value >= 0) { "TTL must be >= 0" }
        require(value <= 10) { "TTL must be <= 10" }
    }
}