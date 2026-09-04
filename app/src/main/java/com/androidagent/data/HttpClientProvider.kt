package com.androidagent.data

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 统一 OkHttpClient 提供者
 * 根据 AppPreferences.skipSslVerification 动态构建：
 *  - false（默认）：系统默认 SSL 验证
 *  - true：跳过证书验证（信任所有证书），适用于自签名 HTTPS
 */
object HttpClientProvider {

    @Volatile
    private var client: OkHttpClient = buildClient()

    @Volatile
    private var lastSkipSsl: Boolean = AppPreferences.skipSslVerification

    fun get(): OkHttpClient {
        val current = AppPreferences.skipSslVerification
        if (current != lastSkipSsl) {
            synchronized(this) {
                if (current != lastSkipSsl) {
                    client = buildClient()
                    lastSkipSsl = current
                }
            }
        }
        return client
    }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)

        if (AppPreferences.skipSslVerification) {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }

        return builder.build()
    }
}
