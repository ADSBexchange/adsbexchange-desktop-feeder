package com.adsbexchange.feeder.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class Win32Test {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    /** The gpsd access check: the client end of a loopback connection is found with its owner. */
    @Test
    fun findsTheProcessOnTheClientEnd() {
        ServerSocket(0, 1, loopback).use { server ->
            Socket(loopback, server.localPort).use { client ->
                server.accept().use {
                    val pid = Win32.loopbackClientPid(client.localPort, server.localPort)
                    assertEquals(ProcessHandle.current().pid(), pid)
                }
            }
        }
    }

    /**
     * The MLAT input check, the same lookup with the ports swapped: the listener's end of the
     * connection (local port = the listener's, remote = the client's) is found with its owner.
     */
    @Test
    fun findsTheProcessOnTheListenerEnd() {
        ServerSocket(0, 1, loopback).use { server ->
            Socket(loopback, server.localPort).use { client ->
                server.accept().use {
                    val pid = Win32.loopbackClientPid(server.localPort, client.localPort)
                    assertEquals(ProcessHandle.current().pid(), pid)
                }
            }
        }
    }

    /** Task Manager's "Memory" for this test's own JVM, and nothing for a process that doesn't exist. */
    @Test
    fun readsPrivateWorkingSet() {
        val own = Win32.privateWorkingSet(ProcessHandle.current().pid())!!
        assertTrue("$own bytes", own > (1L shl 20))
        assertNull("no such process", Win32.privateWorkingSet(0x7FFFFFF0))
    }

    @Test
    fun noConnectionNoOwner() {
        ServerSocket(0, 1, loopback).use { server ->
            assertNull(Win32.loopbackClientPid(1, server.localPort))
        }
    }
}
