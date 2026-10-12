package net.ldmsys.tcprelay.tailcat

object TailcatBridge {
    init {
        try {
            System.loadLibrary("tailcat")
        } catch (e: UnsatisfiedLinkError) {
            // Expected on local unit tests running on desktop JVM
        }
    }

    @JvmStatic
    external fun nativeStartRelay(clientFd: Int, address: String, port: Int): Int
}
