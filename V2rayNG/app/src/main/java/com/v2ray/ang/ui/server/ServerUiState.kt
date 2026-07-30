package com.v2ray.ang.ui.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.v2ray.ang.AppConfig.DEFAULT_PORT
import com.v2ray.ang.AppConfig.REALITY
import com.v2ray.ang.AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
import com.v2ray.ang.AppConfig.WIREGUARD_LOCAL_MTU
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.util.JsonUtil

class ServerUiState(
    configType: EConfigType,
    remarks: String = "",
    address: String = "",
    port: String = DEFAULT_PORT.toString(),
    password: String = "",
    method: String = "",
    flow: String = "",
    encryption: String = "",
    username: String = "",
    secretKey: String = "",
    publicKey: String = "",
    preSharedKey: String = "",
    reserved: String = "0,0,0",
    localAddress: String = WIREGUARD_LOCAL_ADDRESS_V4,
    mtu: String = WIREGUARD_LOCAL_MTU,
    obfsPassword: String = "",
    portHopping: String = "",
    portHoppingInterval: String = "",
    bandwidthDown: String = "",
    bandwidthUp: String = "",
    network: String = NetworkType.TCP.type,
    headerType: String = "none",
    host: String = "",
    path: String = "",
    xhttpExtra: String = "",
    finalMask: String = "",
    kcpMtu: String = "",
    kcpTti: String = "",
    browserDialerMode: String = "",
    streamSecurity: String = "",
    sni: String = "",
    allowInsecure: Boolean = false,
    fingerPrint: String = "",
    alpn: String = "",
    publicKeyReality: String = "",
    shortId: String = "",
    spiderX: String = "",
    mldsa65Verify: String = "",
    echConfigList: String = "",
    verifyPeerCertByName: String = "",
    pinnedCA256: String = "",
    naiveTransport: String = "https",
    naiveInsecureConcurrency: String = "1",
    naiveExtraHeaders: Map<String, String> = emptyMap(),
    naiveUdpOverTcp: Boolean = true,
    naiveUdpOverTcpVersion: String = "2",
    naiveQuicCongestionControl: String = "",
    naiveTrustedRootCertificates: String = "",
    naiveEchEnabled: Boolean = false,
    naiveEchConfig: String = "",
    naiveEchQueryServerName: String = "",
    naiveEchDnsServer: String = "",
    isFetchingCert: Boolean = false
) {
    var configType by mutableStateOf(configType)
    var remarks by mutableStateOf(remarks)
    var address by mutableStateOf(address)
    var port by mutableStateOf(port)
    var password by mutableStateOf(password)
    var method by mutableStateOf(method)
    var flow by mutableStateOf(flow)
    var encryption by mutableStateOf(encryption)
    var username by mutableStateOf(username)
    var secretKey by mutableStateOf(secretKey)
    var publicKey by mutableStateOf(publicKey)
    var preSharedKey by mutableStateOf(preSharedKey)
    var reserved by mutableStateOf(reserved)
    var localAddress by mutableStateOf(localAddress)
    var mtu by mutableStateOf(mtu)
    var obfsPassword by mutableStateOf(obfsPassword)
    var portHopping by mutableStateOf(portHopping)
    var portHoppingInterval by mutableStateOf(portHoppingInterval)
    var bandwidthDown by mutableStateOf(bandwidthDown)
    var bandwidthUp by mutableStateOf(bandwidthUp)
    var network by mutableStateOf(network)
    var headerType by mutableStateOf(headerType)
    var host by mutableStateOf(host)
    var path by mutableStateOf(path)
    var xhttpExtra by mutableStateOf(xhttpExtra)
    var finalMask by mutableStateOf(finalMask)
    var kcpMtu by mutableStateOf(kcpMtu)
    var kcpTti by mutableStateOf(kcpTti)
    var browserDialerMode by mutableStateOf(browserDialerMode)
    var streamSecurity by mutableStateOf(streamSecurity)
    var sni by mutableStateOf(sni)
    var allowInsecure by mutableStateOf(allowInsecure)
    var fingerPrint by mutableStateOf(fingerPrint)
    var alpn by mutableStateOf(alpn)
    var publicKeyReality by mutableStateOf(publicKeyReality)
    var shortId by mutableStateOf(shortId)
    var spiderX by mutableStateOf(spiderX)
    var mldsa65Verify by mutableStateOf(mldsa65Verify)
    var echConfigList by mutableStateOf(echConfigList)
    var verifyPeerCertByName by mutableStateOf(verifyPeerCertByName)
    var pinnedCA256 by mutableStateOf(pinnedCA256)
    var naiveTransport by mutableStateOf(naiveTransport)
    var naiveInsecureConcurrency by mutableStateOf(naiveInsecureConcurrency)
    var naiveExtraHeaders by mutableStateOf(naiveExtraHeaders)
    var naiveUdpOverTcp by mutableStateOf(naiveUdpOverTcp)
    var naiveUdpOverTcpVersion by mutableStateOf(naiveUdpOverTcpVersion)
    var naiveQuicCongestionControl by mutableStateOf(naiveQuicCongestionControl)
    var naiveTrustedRootCertificates by mutableStateOf(naiveTrustedRootCertificates)
    var naiveEchEnabled by mutableStateOf(naiveEchEnabled)
    var naiveEchConfig by mutableStateOf(naiveEchConfig)
    var naiveEchQueryServerName by mutableStateOf(naiveEchQueryServerName)
    var naiveEchDnsServer by mutableStateOf(naiveEchDnsServer)
    var isFetchingCert by mutableStateOf(isFetchingCert)

    fun toProfileItem(initialConfig: ProfileItem): ProfileItem {
        val isVmess = configType == EConfigType.VMESS
        val isVless = configType == EConfigType.VLESS
        val isShadowsocks = configType == EConfigType.SHADOWSOCKS
        val isSocksOrHttp = configType == EConfigType.SOCKS || configType == EConfigType.HTTP
        val isWireguard = configType == EConfigType.WIREGUARD
        val isHysteria2 = configType == EConfigType.HYSTERIA2
        val isNaive = configType == EConfigType.NAIVE

        return initialConfig.copy(
            configType = configType,
            remarks = remarks,
            server = address,
            serverPort = port,
            password = password,
            method = when {
                isVmess || isShadowsocks -> method
                isVless -> encryption
                else -> null
            },
            flow = if (isVless) flow else null,
            username = if (isSocksOrHttp || isNaive) username else null,
            secretKey = if (isWireguard) secretKey else null,
            publicKey = when {
                isWireguard -> publicKey
                streamSecurity == REALITY -> publicKeyReality
                else -> null
            },
            preSharedKey = if (isWireguard) preSharedKey else null,
            reserved = if (isWireguard) reserved else null,
            localAddress = if (isWireguard) localAddress else null,
            mtu = if (isWireguard) mtu.toIntOrNull() else null,
            obfsPassword = if (isHysteria2) obfsPassword else null,
            portHopping = if (isHysteria2) portHopping else null,
            portHoppingInterval = if (isHysteria2) portHoppingInterval else null,
            bandwidthDown = if (isHysteria2) bandwidthDown else null,
            bandwidthUp = if (isHysteria2) bandwidthUp else null,
            network = if (isNaive) null else network,
            headerType = if (isNaive) null else headerType,
            host = if (isNaive) null else host,
            path = if (isNaive) null else path,
            xhttpExtra = if (isNaive) null else xhttpExtra.nullIfBlank(),
            finalMask = if (isNaive) null else finalMask.nullIfBlank(),
            kcpMtu = if (isNaive) null else kcpMtu.toIntOrNull(),
            kcpTti = if (isNaive) null else kcpTti.toIntOrNull(),
            browserDialerMode = if (!isNaive && network in listOf(NetworkType.WS.type, NetworkType.XHTTP.type)) {
                browserDialerMode.nullIfBlank()
            } else {
                null
            },
            security = if (isNaive) null else streamSecurity,
            sni = sni,
            insecure = if (isNaive) null else allowInsecure,
            fingerPrint = if (isNaive) null else fingerPrint,
            alpn = if (isNaive) null else alpn,
            shortId = shortId,
            spiderX = spiderX,
            mldsa65Verify = mldsa65Verify,
            echConfigList = echConfigList,
            verifyPeerCertByName = verifyPeerCertByName,
            pinnedCA256 = if (isNaive) null else pinnedCA256,
            naiveTransport = if (isNaive) naiveTransport else null,
            naiveInsecureConcurrency = if (isNaive) naiveInsecureConcurrency.toIntOrNull() else null,
            naiveExtraHeaders = if (isNaive) naiveExtraHeaders else null,
            naiveUdpOverTcp = if (isNaive) naiveUdpOverTcp else null,
            naiveUdpOverTcpVersion = if (isNaive) naiveUdpOverTcpVersion.toIntOrNull() else null,
            naiveQuicCongestionControl = if (isNaive) naiveQuicCongestionControl.nullIfBlank() else null,
            naiveTrustedRootCertificates = if (isNaive) naiveTrustedRootCertificates.nullIfBlank() else null,
            naiveEchEnabled = if (isNaive) naiveEchEnabled else null,
            naiveEchConfig = if (isNaive) naiveEchConfig.nullIfBlank() else null,
            naiveEchQueryServerName = if (isNaive) naiveEchQueryServerName.nullIfBlank() else null,
            naiveEchDnsServer = if (isNaive) naiveEchDnsServer.nullIfBlank() else null
        )
    }

    companion object {
        fun fromProfileItem(
            initialConfig: ProfileItem
        ): ServerUiState =
            ServerUiState(
                configType = initialConfig.configType,
                remarks = initialConfig.remarks,
                address = initialConfig.server ?: "",
                port = initialConfig.serverPort ?: DEFAULT_PORT.toString(),
                password = initialConfig.password ?: "",
                method = initialConfig.method ?: "",
                flow = initialConfig.flow ?: "",
                encryption = initialConfig.method ?: "",
                username = initialConfig.username ?: "",
                secretKey = initialConfig.secretKey ?: "",
                publicKey = initialConfig.publicKey ?: "",
                preSharedKey = initialConfig.preSharedKey ?: "",
                reserved = initialConfig.reserved ?: "0,0,0",
                localAddress = initialConfig.localAddress ?: WIREGUARD_LOCAL_ADDRESS_V4,
                mtu = initialConfig.mtu?.toString() ?: WIREGUARD_LOCAL_MTU,
                obfsPassword = initialConfig.obfsPassword ?: "",
                portHopping = initialConfig.portHopping ?: "",
                portHoppingInterval = initialConfig.portHoppingInterval ?: "",
                bandwidthDown = initialConfig.bandwidthDown ?: "",
                bandwidthUp = initialConfig.bandwidthUp ?: "",
                network = initialConfig.network ?: NetworkType.TCP.type,
                headerType = initialConfig.headerType ?: "none",
                host = initialConfig.host ?: "",
                path = initialConfig.path ?: "",
                xhttpExtra = initialConfig.xhttpExtra ?: "",
                finalMask = initialConfig.finalMask ?: "",
                kcpMtu = initialConfig.kcpMtu?.toString() ?: "",
                kcpTti = initialConfig.kcpTti?.toString() ?: "",
                browserDialerMode = initialConfig.browserDialerMode ?: "",
                streamSecurity = initialConfig.security ?: "",
                sni = initialConfig.sni ?: "",
                allowInsecure = initialConfig.insecure == true,
                fingerPrint = initialConfig.fingerPrint ?: "",
                alpn = initialConfig.alpn ?: "",
                publicKeyReality = initialConfig.publicKey ?: "",
                shortId = initialConfig.shortId ?: "",
                spiderX = initialConfig.spiderX ?: "",
                mldsa65Verify = initialConfig.mldsa65Verify ?: "",
                echConfigList = initialConfig.echConfigList ?: "",
                verifyPeerCertByName = initialConfig.verifyPeerCertByName ?: "",
                pinnedCA256 = initialConfig.pinnedCA256 ?: "",
                naiveTransport = initialConfig.naiveTransport ?: "https",
                naiveInsecureConcurrency = (initialConfig.naiveInsecureConcurrency ?: 1).toString(),
                naiveExtraHeaders = initialConfig.naiveExtraHeaders ?: emptyMap(),
                naiveUdpOverTcp = initialConfig.naiveUdpOverTcp != false,
                naiveUdpOverTcpVersion = (initialConfig.naiveUdpOverTcpVersion ?: 2).toString(),
                naiveQuicCongestionControl = initialConfig.naiveQuicCongestionControl ?: "",
                naiveTrustedRootCertificates = initialConfig.naiveTrustedRootCertificates ?: "",
                naiveEchEnabled = initialConfig.naiveEchEnabled == true,
                naiveEchConfig = initialConfig.naiveEchConfig ?: "",
                naiveEchQueryServerName = initialConfig.naiveEchQueryServerName ?: "",
                naiveEchDnsServer = initialConfig.naiveEchDnsServer ?: ""
            )

        fun from(
            initialConfig: ProfileItem
        ): ServerUiState = fromProfileItem(initialConfig)

        val Saver: Saver<ServerUiState, String> = Saver(
            save = { JsonUtil.toJson(it.toProfileItem(ProfileItem.create(it.configType))) },
            restore = { saved ->
                JsonUtil.fromJsonSafe(saved, ProfileItem::class.java)?.let {
                    fromProfileItem(it)
                }
            }
        )
    }
}
