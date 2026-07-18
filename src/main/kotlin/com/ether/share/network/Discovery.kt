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
                Log.d(TAG, "✅ Service REGISTERED: ${nsdServiceInfo.serviceName} on port $tcpPort")
            }

            override fun onRegistrationFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "❌ Registration FAILED: error code $errorCode")
            }

            override fun onServiceUnregistered(nsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "⏹️ Service unregistered")
            }

            override fun onUnregistrationFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "❌ Unregistration FAILED: error code $errorCode")
            }
        }

        Log.d(TAG, "📢 Advertising service: $instance on port $tcpPort")
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registerListener!!)
    }

    private fun browse() {
        browseListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "✅ Discovery started for $serviceType")
            }

            override fun onServiceFound(nsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "🔎 Service found: ${nsdServiceInfo.serviceName} (my instance: $instance)")
                if (nsdServiceInfo.serviceName != instance) {
                    Log.d(TAG, "   → Resolving ${nsdServiceInfo.serviceName}...")
                    nsd.resolveService(nsdServiceInfo, resolveListener())
                } else {
                    Log.d(TAG, "   → Ignoring self")
                }
            }

            override fun onServiceLost(nsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "❌ Service lost: ${nsdServiceInfo.serviceName}")
                _peers.value = _peers.value - nsdServiceInfo.serviceName
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "⏹️ Discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "❌ Discovery start FAILED for $serviceType: error code $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "❌ Discovery stop FAILED for $serviceType: error code $errorCode")
            }
        }

        Log.d(TAG, "Starting NSD discovery for $SERVICE_TYPE")
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, browseListener!!)
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "❌ Resolve FAILED: error code $errorCode for ${nsdServiceInfo.serviceName}")
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            val host = nsdServiceInfo.host?.hostAddress ?: "unknown"
            Log.d(TAG, "✅ Service RESOLVED: ${nsdServiceInfo.serviceName} @ $host:${nsdServiceInfo.port}")
            if (nsdServiceInfo.host?.hostAddress != null) {
                _peers.value = _peers.value + (nsdServiceInfo.serviceName to Peer(
                    instance = nsdServiceInfo.serviceName,
                    host = host,
                    port = nsdServiceInfo.port,
                ))
                Log.d(TAG, "   → Added to peers. Total peers: ${_peers.value.size}")
            } else {
                Log.e(TAG, "   → ERROR: No host address!")
            }
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
