package net.ldmsys.tcprelay.connector

enum class RelayMode(val displayName: String) {
    TCP("TCP"),
    TAILCAT("Tailcat");

    companion object {
        fun fromString(name: String?): RelayMode {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: TCP
        }
    }
}
