package com.manegow.model.nearby

@JvmInline
value class SignalStrength(val rssi: Int) {
    init {
        require(rssi in -127..20) { "RSSI out of expected range: $rssi" }
    }
}