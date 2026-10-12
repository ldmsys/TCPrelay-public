package net.ldmsys.tcprelay.connector

import android.os.ParcelFileDescriptor
import android.util.Log
import java.net.Socket

object SocketFdUtils {
    private const val TAG = "SocketFdUtils"

    fun getFd(socket: Socket): Int {
        return try {
            val pfd = ParcelFileDescriptor.fromSocket(socket)
            pfd.detachFd()
        } catch (e: Exception) {
            Log.w(TAG, "ParcelFileDescriptor.fromSocket failed, attempting reflection", e)
            extractFdByReflection(socket)
        }
    }

    private fun extractFdByReflection(socket: Socket): Int {
        val implField = Socket::class.java.getDeclaredField("impl").apply { isAccessible = true }
        val impl = implField.get(socket) ?: throw IllegalStateException("SocketImpl is null")
        val fdField = impl.javaClass.getDeclaredField("fd").apply { isAccessible = true }
        val fdObj = fdField.get(impl) as java.io.FileDescriptor
        return try {
            val dupPfd = ParcelFileDescriptor.dup(fdObj)
            dupPfd.detachFd()
        } catch (e: Exception) {
            val descriptorField = java.io.FileDescriptor::class.java.getDeclaredField("descriptor").apply { isAccessible = true }
            descriptorField.getInt(fdObj)
        }
    }
}
