package com.traveler.miyou.net

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/**
 * 米哈游 passport 登录接口使用的 RSA 加密（1024 位，PKCS#1 v1.5）。
 */
object Rsa {

    private val MODULUS = BigInteger(
        "c3bde91d3cc1cddc06219bfbe4b494fe609afb708e4372c34aa9db31e43657d200" +
            "ee585b888f377006eb6b2183cd9912751bcc9b0c817ba035b6784a66e6c31b2fd" +
            "cecf44c5709dbeaae7e75a842dbaa3d17c6d3132296821c5488e743df3e94c557" +
            "d5edfe19b2570a24a0e5c59401200a7f900a01ace766c5a1832dca2fb111",
        16
    )
    private val EXPONENT = BigInteger("65537")

    /** 公钥本身不可变，可以安全共享。 */
    private val publicKey: PublicKey by lazy {
        KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(MODULUS, EXPONENT))
    }

    /**
     * Cipher 实例不是线程安全的，而凭证归一化 normalize() 可能被多处并发触发，
     * 因此这里每次调用都新建 Cipher，而不是复用一个单例。
     */
    fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
            init(Cipher.ENCRYPT_MODE, publicKey)
        }
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }
}
