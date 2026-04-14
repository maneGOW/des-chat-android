package com.manegow.model.mesh

@JvmInline
value class PacketId(val value: String) {
    init {
        require(value.isNotBlank()) { "PacketId cannot be blank" }
    }
}