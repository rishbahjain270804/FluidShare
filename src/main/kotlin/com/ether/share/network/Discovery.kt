package com.ether.share.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetAddress

const val SERVICE_TYPE = "_ether._tcp."
const val TAG = "EtherDiscovery"

data class Peer(
    val instance: String,
    val host: String,
    val port: Int,
    val seenAt: Long = System.currentTimeMillis(),
)

class EtherDiscovery(private val context: Context, private val instance: String, private val tcpPort: Int) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers

    private var browseListener: NsdManager.DiscoveryListener? = null
    private var registerListener: NsdManager.RegistrationListener? = null

    fun start() {
        advertise()
        browse()
    }

    private fun advertise() {
        val info = NsdServiceInfo().apply {
            serviceName = instance
            serviceType = SERVICE_TYPE
            port = tcpPort
            setAttribute("v", "1")
            setAttribute("role", "peer")
        }

        registerListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${nsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(nsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered")
            }

            override fun onUnregistrationFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }

        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registerListener!!)
    }

    private fun browse() {
        browseListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onServiceFound(nsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${nsdServiceInfo.serviceName}")
                if (nsdServiceInfo.serviceName != instance) {
                    nsd.resolveService(nsdServiceInfo, resolveListener())
                }
            }

            override fun onServiceLost(nsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${nsdServiceInfo.serviceName}")
                _peers.value = _peers.value - nsdServiceInfo.serviceName
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed for $serviceType: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed for $serviceType: $errorCode")
            }
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, browseListener!!)
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "Resolve failed: $errorCode for ${nsdServiceInfo.serviceName}")
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service resolved: ${nsdServiceInfo.serviceName}")
            val host = nsdServiceInfo.host?.hostAddress ?: return
            _peers.value = _peers.value + (nsdServiceInfo.serviceName to Peer(
                instance = nsdServiceInfo.serviceName,
                host = host,
                port = nsdServiceInfo.port,
            ))
        }
    }

    fun addPeerManually(host: String, port: Int, instance: String) {
        _peers.value = _peers.value + (instance to Peer(instance, host, port))
    }

    fun stop() {
        browseListener?.let { nsd.stopServiceDiscovery(it) }
        registerListener?.let { nsd.unregisterService(registerListener!!) }
    }
}
