package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.MessageLike.SslInfo
import com.hanhuy.android.irc.model.MessageLike.SslError

import java.security.MessageDigest
import java.security.KeyStore
import java.security.cert.X509Certificate

import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

import com.hanhuy.android.common.UiBus

object SSLManager {
    def configureSSL(server: Server):
            SSLContext = {
        val c = SSLContext.getInstance("TLS")
        c.init(null,
                Array[TrustManager](new EasyTrustManager(server)),
                null)
        c
    }
}

class EasyTrustManager(server: Server)
extends X509TrustManager {
  val context = Application.context
    lazy val trustManager = {
        val factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm)
        factory.init(null.asInstanceOf[KeyStore])
        val managers = factory.getTrustManagers
        if (managers.isEmpty)
            throw new IllegalStateException("No trust manager found")
        managers(0).asInstanceOf[X509TrustManager]
    }
    override def checkClientTrusted(
            chain: Array[X509Certificate], authType: String) =
            trustManager.checkClientTrusted(chain, authType)
    override def checkServerTrusted(
            chain: Array[X509Certificate], authType: String) {
        UiBus.run {
            server += SslInfo(
                    chain(0).getSubjectX500Principal.toString)
            server += SslInfo(
                    context.getString(R.string.sha1_fingerprint) +
                    sha1(chain(0)))
            server += SslInfo(
                    context.getString(R.string.md5_fingerprint) +
                    md5(chain(0)))
        }
        try {
            chain(0).checkValidity()
        } catch {
            case e: Exception =>
                UiBus.run {
                    server += SslError(
                            context.getString(R.string.ssl_expired))
                    server += SslError(e.getMessage)
                }
        }
    }
    override def getAcceptedIssuers : Array[X509Certificate] =
            trustManager.getAcceptedIssuers
    private def sha1(c: X509Certificate) : String = {
        val md = MessageDigest.getInstance("SHA1")
        md.digest(c.getEncoded).map("%02X" format _).mkString
    }
    private def md5(c: X509Certificate) : String = {
        val md = MessageDigest.getInstance("MD5")
        md.digest(c.getEncoded).map("%02X" format _).mkString
    }
}
