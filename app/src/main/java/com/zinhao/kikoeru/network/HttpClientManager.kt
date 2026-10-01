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
    fun getPacEnabledClient(): OkHttpClient {
        val okHttpClientBuilder = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
//            .useNoSniSSL()
            if(BuildConfig.DEBUG) {
                okHttpClientBuilder.proxySelector(proxySelector)
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

            //3. 国内流量或局域网,直连(原来这里有一段只在 debug 包里生效、硬编码到作者内网
            // 代理 192.168.31.253:7890 的兜底逻辑,已删除:换台设备就是必然超时)
            Log.i(TAG, "select:直连")
            return mutableListOf<Proxy?>(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // 代理连接失败时的回调
            getDefault().connectFailed(uri, sa, ioe)
        }
    }

}