package com.nvandervoort.mathathon

import android.util.Log
import java.net.*
import java.util.*

/**
 * ClientUdpSocketSocket.kt
 * Created by nathanvandervoort on 2/8/18.
 *
 * Singleton class used for maintaining an open UDP socket
 * also contains utilities for sending and waiting for responses
 */

class ClientUdpSocket private constructor() {
    private var socket: DatagramSocket = DatagramSocket()
    var targetIp: String? = null
    private var port: Int? = null

    @Volatile private var waiting = false
    private  val random = Random()

    init {
        socket.reuseAddress = true
    }

    private object Holder {
        val INSTANCE = ClientUdpSocket()
    }

    companion object {
        val instance: ClientUdpSocket by lazy { Holder.INSTANCE }
        const val UDP_PACKET_LEN = 256
    }

    fun changeTargetAddress(newIp: String?, newPort: Int?) {
        targetIp = newIp; port = newPort
    }

    fun send(msg: String, dropRate: Int = 0) {
        Log.d("SOCKET", "sending packet")
        if (targetIp == null || port == null || random.nextInt(100) < dropRate) return
        val bytes = msg.toByteArray()
        val dg = DatagramPacket(bytes, bytes.size, InetAddress.getByName(targetIp), port ?: 0)
        socket.send(dg)
    }

    /**
     * Wait for response; if [header] specified then only
     * return when packet arrives with that header.
     * Catch and rethrow exception when optional [timeout] happens
     * Returns empty string if waiting is cancelled
     * Note: Cannot be run from the main thread
     */
    @Throws(SocketTimeoutException::class)
    fun waitForResponse(header: String = "", timeout: Int = 0): String {
        waiting = true
        socket.soTimeout = timeout

        val recvdPacket = DatagramPacket(ByteArray(UDP_PACKET_LEN), UDP_PACKET_LEN)

        try {
            while (true) {
                socket.receive(recvdPacket)
                val data = recvdPacket.dataAsString()
                if (data.startsWith(header)) return data
                else Log.d("SOCKET", "Response starts with something besides $header: $data")
            }
        } catch (e: SocketTimeoutException) {
            throw e
        } catch (ignored: SocketException) {
            Log.d("SOCKET", "SocketException")
            return ""
        } finally {
            if (!socket.isClosed) socket.soTimeout = 0
            waiting = false
        }
    }

    /** Forces [waitForResponse] to exit and return empty string */
    fun cancelWait() {
        if (!waiting) return
        val addr = socket.localSocketAddress
        socket.close()
        socket = DatagramSocket(addr)
        socket.reuseAddress = true
    }

}