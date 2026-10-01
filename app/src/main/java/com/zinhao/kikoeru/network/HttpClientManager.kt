package com.zinhao.kikoeru.network

import android.util.Log
import com.zinhao.kikoeru.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.internal.platform.Platform
import java.io.IOException
import java.net.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


object HttpClientManager {
    val TAG = "HttpClientManager"

    /** 本地 mihomo 的混合端口(HTTP 和 SOCKS5 都吃) */
    private const val LOCAL_PROXY_HOST = "127.0.0.1"
    private const val LOCAL_PROXY_PORT = 7890

    @Volatile
    private var localProxyAlive: Boolean? = null

    /** 探一次端口,活着就一直走它;mihomo 没开就直连 */
    private fun isLocalProxyAlive(): Boolean {
        localProxyAlive?.let { return it }
        val alive = try {
            Socket().use { it.connect(InetSocketAddress(LOCAL_PROXY_HOST, LOCAL_PROXY_PORT), 300) }
            true
        } catch (e: IOException) {
            false
        }
        Log.i(TAG, "本地代理可用: $alive")
        localProxyAlive = alive
        return alive
    }

    fun getPacEnabledClient(): OkHttpClient {
        val okHttpClientBuilder = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
//            .useNoSniSSL()
            .proxySelector(proxySelector)
        if(BuildConfig.DEBUG) {
            okHttpClientBuilder.addInterceptor(LoggingInterceptor())
        }
        return okHttpClientBuilder.build()
    }

    private fun OkHttpClient.Builder.useNoSniSSL(): OkHttpClient.Builder {
        val trustManager: X509TrustManager = Platform.get().platformTrustManager()
        val sslContext = SSLContext.getInstance("TLS")

        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        val noSniFactory = NoSniSSLSocketFactory(sslContext.socketFactory)
        dns(CloudflareDoh())
        sslSocketFactory(noSniFactory, trustManager)
        hostnameVerifier { p0, p1 -> true }
        return this
    }

    val proxySelector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): MutableList<Proxy?> {
            // 1. 如果系统能够正确解析 PAC（部分高版本系统），直接使用系统的
            val systemProxies = getDefault().select(uri)
            if (systemProxies != null && !systemProxies.isEmpty() && systemProxies.get(0)!!
                    .type() != Proxy.Type.DIRECT
            ) {
                Log.i(TAG, "select:正确解析 PAC")
                return systemProxies
            }

            // 2. 本地 mihomo。这就是"只让这个 App 翻"的全部实现:只改 App 自己的出口,
            //    不动系统代理、不影响别的应用
            if (isLocalProxyAlive()) {
                Log.i(TAG, "select:走本地代理 $LOCAL_PROXY_HOST:$LOCAL_PROXY_PORT")
                return mutableListOf<Proxy?>(
                    Proxy(Proxy.Type.HTTP, InetSocketAddress(LOCAL_PROXY_HOST, LOCAL_PROXY_PORT))
                )
            }

            // 3. 直连
            Log.i(TAG, "select:直连")
            return mutableListOf<Proxy?>(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // 代理连接失败时的回调
            getDefault().connectFailed(uri, sa, ioe)
        }
    }

}