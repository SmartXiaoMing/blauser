package com.blauser.browser

import android.content.Context
import android.net.http.SslCertificate
import android.os.Build
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * 「这个域名的**这张**证书我认了」的记录。
 *
 * ## 为什么要自己记
 *
 * WebView 自己不做这个决定。Chromium 那套「同一域名不再问」的机制
 * （AwSSLHostStateDelegate）在 WebView 里是空实现，每个证书错误都会重新回调
 * onReceivedSslError —— 不落盘的话，用户每开一次页面就得在弹窗上再点一次「继续访问」。
 *
 * ## 为什么绑证书指纹，而不是只绑域名
 *
 * 只按域名记 = 这个域名以后**任何**证书都放行。用户当初接受的是「那张自签证书」，
 * 不是「这个域名以后可以被随便冒充」。绑上指纹之后，证书一换就会重新问 ——
 * 想借一个已经被放行过的域名做中间人，照样得再骗用户点一次。
 *
 * 指纹取不到时**不记**（宁可下次再问），理由同上：不能退化成整站无条件放行。
 *
 * 记录不会跟着换机走（manifest 里 `allowBackup="false"`）。这正是想要的：
 * 新设备上重新问一遍，比默默继承一份用户看不见的信任清单安全。
 */
object SslExceptionStore {

    private const val PREF = "ssl_exceptions"

    /** 指纹前缀。用 # 分隔是为了将来往这份 prefs 里放别的键时不撞名 */
    private const val KEY_PREFIX = "cert#"

    /**
     * 低版本取 X509 用的 bundle key。
     *
     * 这是 [SslCertificate.saveState] 的内部实现约定，不是公开 API —— 但 API 29 以下
     * 没有别的路可走。取不到时整条链路会返回 null，退化成「本次放行、下次再问」，
     * 不会更糟。
     */
    private const val BUNDLE_KEY_X509 = "x509-certificate"

    /**
     * 域名归一化：去空白、小写、去掉末尾的点。
     *
     * **不做的话会出现「接受一次之后又反复问」**：`Example.com` 与 `example.com`
     * 会存成两条 key，而它们本来就是同一个站点（域名大小写不敏感，末尾那个点是根域的写法）。
     *
     * 刻意用纯字符串处理，不用 android.net.Uri：单测跑在 JVM 上，android.jar 里的
     * Uri 只是返回默认值的桩（见 UrlHelper 里同一条注释）。URL → 域名的提取交给调用方
     * 用 [SiteSettingsManager.hostOf] 完成。
     */
    fun normalizeHost(host: String?): String? =
        host?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun keyOf(host: String) = KEY_PREFIX + host

    /**
     * 这个域名是不是已经接受过**这张**证书。
     *
     * 域名或指纹任一为空都返回 false：没有指纹就无从判断「是不是同一张」，
     * 与其猜，不如再问一次。
     */
    fun isTrusted(context: Context, host: String?, fingerprint: String?): Boolean {
        val h = normalizeHost(host) ?: return false
        val fp = fingerprint?.takeIf { it.isNotEmpty() } ?: return false
        return prefs(context).getString(keyOf(h), null) == fp
    }

    /** 这个域名已接受的那张证书的指纹；没有例外时返回 null */
    fun fingerprintFor(context: Context, host: String?): String? {
        val h = normalizeHost(host) ?: return null
        return prefs(context).getString(keyOf(h), null)?.takeIf { it.isNotEmpty() }
    }

    /** 记下例外。指纹为空则什么都不写 —— 见类注释里「取不到就不记」的理由 */
    fun trust(context: Context, host: String, fingerprint: String) {
        val h = normalizeHost(host) ?: return
        if (fingerprint.isEmpty()) return
        prefs(context).edit().putString(keyOf(h), fingerprint).apply()
    }

    fun revoke(context: Context, host: String) {
        val h = normalizeHost(host) ?: return
        prefs(context).edit().remove(keyOf(h)).apply()
    }

    /** 全部例外，按域名排序。给全局设置里的「证书例外」清单用 */
    fun all(context: Context): List<Pair<String, String>> =
        prefs(context).all
            .mapNotNull { (key, value) ->
                val host = key.removePrefix(KEY_PREFIX).takeIf { key.startsWith(KEY_PREFIX) }
                val fingerprint = value as? String
                if (host.isNullOrEmpty() || fingerprint.isNullOrEmpty()) null
                else host to fingerprint
            }
            .sortedBy { it.first }

    /**
     * 证书指纹：SHA-256(叶子证书 DER) 的十六进制。
     *
     * 任何一步失败都返回 null（拿不到证书、证书本身不合法），调用方据此放弃记忆。
     */
    fun fingerprintOf(certificate: SslCertificate?): String? {
        val x509 = x509Of(certificate) ?: return null
        return runCatching {
            MessageDigest.getInstance("SHA-256")
                .digest(x509.encoded)
                // 必须先 and 0xFF 再格式化：Byte 直接转 Int 会把 -1 变成 ffffffff
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }.getOrNull()
    }

    /**
     * 从 SslCertificate 掏出 X509Certificate。两条路：
     *  - API 29+ 有公开接口
     *  - 更低版本只能从 [SslCertificate.saveState] 的 bundle 里按内部约定掏
     */
    private fun x509Of(certificate: SslCertificate?): X509Certificate? {
        if (certificate == null) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching { certificate.x509Certificate }.getOrNull()
        }

        val state = runCatching { SslCertificate.saveState(certificate) }.getOrNull() ?: return null
        val der = state.getByteArray(BUNDLE_KEY_X509) ?: return null
        return runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
        }.getOrNull()
    }
}
