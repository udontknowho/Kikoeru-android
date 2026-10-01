package com.zinhao.kikoeru.network

import android.util.Log
import com.zinhao.kikoeru.App
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

    // 默认代理地址在 App.DEFAULT_PROXY_ADDR,设置页里可以改

    @Volatile
    private var probed = false

    @Volatile
    private var cachedProxy: Proxy? = null

    /** 设置页改了开关或地址就调它,把探测结果作废 */
    fun resetProxyCache() {
        probed = false
        cachedProxy = null
    }

    /**
     * 探一次端口:活着就用它当 HTTP 代理,没开就直连。结果缓存到进程结束,
     * 所以要先把代理开起来再开 App。
     */
    private fun localProxy(): Proxy? {
        if (probed) {
            return cachedProxy
        }
        var result: Proxy? = null
        if (App.getInstance().getValue(App.CONFIG_PROXY_ENABLED, 1L) == 1L) {
            val addr = App.getInstance().getValue(App.CONFIG_PROXY_ADDR, App.DEFAULT_PROXY_ADDR)
                    .ifBlank { App.DEFAULT_PROXY_ADDR }
            try {
                val sep = addr.lastIndexOf(':')
                if (sep <= 0) throw IllegalArgumentException("地址要写成 主机:端口")
                val host = addr.substring(0, sep).trim()
                val port = addr.substring(sep + 1).trim().toInt()
                Socket().use { it.connect(InetSocketAddress(host, port), 300) }
                result = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
                Log.i(TAG, "代理可用: $addr")
            } catch (e: Exception) {
                Log.i(TAG, "代理不可用($addr): ${e.message}")
            }
        } else {
            Log.i(TAG, "代理已在设置里关掉")
        }
        cachedProxy = result
        probed = true
        return result
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

            // 2. 设置里的本地代理(默认 127.0.0.1:7890,也就是 mihomo 的混合端口)。
            //    这就是"只让这个 App 翻"的全部实现:只改 App 自己的出口,
            //    不动系统代理、不影响别的应用
            val local = localProxy()
            if (local != null) {
                Log.i(TAG, "select:走本地代理 ${local.address()}")
                return mutableListOf<Proxy?>(local)
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