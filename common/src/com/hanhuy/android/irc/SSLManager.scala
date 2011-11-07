package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.QueueAdapter

import java.security.MessageDigest
import java.security.KeyStore
import java.security.cert.X509Certificate

import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

import AndroidConversions._

object SSLManager {
    def configureSSL(service: IrcService, adapter: QueueAdapter[String]):
            SSLContext = {
        val c = SSLContext.getInstance("TLS")
        c.init(null,
                Array[TrustManager](new EasyTrustManager(service, adapter)),
                null)
        c
    }
}

class EasyTrustManager(service: IrcService, adapter: QueueAdapter[String])
extends X509TrustManager {
    lazy val trustManager = {
        val factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null.asInstanceOf[KeyStore])
        val managers = factory.getTrustManagers()
        if (managers.length == 0)
            throw new IllegalStateException("No trust manager found")
        managers(0).asInstanceOf[X509TrustManager]
    }
    override def checkClientTrusted(
            chain: Array[X509Certificate], authType: String) =
            trustManager.checkClientTrusted(chain, authType)
    override def checkServerTrusted(
            chain: Array[X509Certificate], authType: String) {
        service.runOnUI(() => {
            adapter.add(chain(0).getSubjectX500Principal().toString())
            adapter.add(service.getString(R.string.sha1_fingerprint) +
                    sha1(chain(0)))
            adapter.add(service.getString(R.string.md5_fingerprint) +
                    md5(chain(0)))
        })
        try {
            chain(0).checkValidity()
        } catch {
            case e: Exception => {
                service.runOnUI(() => {
                    adapter.add(service.getString(R.string.ssl_expired))
                    adapter.add(e.getMessage())
                })
            }
        }
    }
    override def getAcceptedIssuers() : Array[X509Certificate] =
            trustManager.getAcceptedIssuers()
    private def sha1(c: X509Certificate) : String = {
        val md = MessageDigest.getInstance("SHA1")
        md.digest(c.getEncoded()).map("%02X" format _).mkString
    }
    private def md5(c: X509Certificate) : String = {
        val md = MessageDigest.getInstance("MD5")
        md.digest(c.getEncoded()).map("%02X" format _).mkString
    }
}
