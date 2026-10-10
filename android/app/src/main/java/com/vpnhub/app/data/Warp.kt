package com.vpnhub.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/** This phone's own Cloudflare WARP account (registered once, kept on the device). */
@Serializable
data class WarpAccount(
    val privateKey: String,
    val peerPublicKey: String,
    val v4: String,
    val v6: String? = null,
    val reserved: List<Int> = listOf(0, 0, 0),
)

object Warp {
    private const val DEFAULT_PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    private const val ENDPOINT = "162.159.192.1"
    private val lock = Mutex()

    /** Returns the saved account, registering a new anonymous one with Cloudflare the first time. */
    suspend fun account(): WarpAccount = lock.withLock {
        Prefs.warpAccount?.let { return@withLock it }
        val acct = register()
        Prefs.warpAccount = acct
        acct
    }

    private suspend fun register(): WarpAccount = withContext(Dispatchers.IO) {
        val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        clamp(priv)
        val pub = X25519.publicKey(priv)
        val enc = Base64.getEncoder()
        val body = buildJsonObject {
            put("install_id", "")
            put("fcm_token", "")
            put("key", enc.encodeToString(pub))
            put("type", "Android")
            put("model", "PC")
            put("locale", "en_US")
            put("warp_enabled", true)
            put("tos", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.000'Z'").withZone(ZoneOffset.UTC).format(Instant.now()))
        }.toString()
        val conn = URL("https://api.cloudflareclient.com/v0a2158/reg").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "okhttp/3.12.1")
            conn.setRequestProperty("CF-Client-Version", "a-6.10-2158")
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) error("WARP 註冊失敗（HTTP ${conn.responseCode}）")
            val root = json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
            val config = (root["config"] ?: root["result"]?.jsonObject?.get("config"))?.jsonObject
                ?: error("WARP 註冊回覆格式唔啱")
            val addresses = config["interface"]!!.jsonObject["addresses"]!!.jsonObject
            val peer = config["peers"]!!.jsonArray[0].jsonObject
            val clientId = config["client_id"]?.jsonPrimitive?.content ?: "AAAA"
            val reserved = Base64.getDecoder().decode(clientId).take(3).map { it.toInt() and 0xff }
            WarpAccount(
                privateKey = enc.encodeToString(priv),
                peerPublicKey = peer["public_key"]?.jsonPrimitive?.content ?: DEFAULT_PEER_KEY,
                v4 = addresses["v4"]!!.jsonPrimitive.content,
                v6 = addresses["v6"]?.jsonPrimitive?.content,
                reserved = (reserved + listOf(0, 0, 0)).take(3),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** sing-box WireGuard endpoint for WARP; [detour] makes it connect through another node. */
    fun endpoint(acct: WarpAccount, tag: String, detour: String?): JsonObject = buildJsonObject {
        put("type", "wireguard")
        put("tag", tag)
        putJsonArray("address") {
            add("${acct.v4}/32")
            acct.v6?.let { add("$it/128") }
        }
        put("private_key", acct.privateKey)
        put("mtu", 1280)
        putJsonArray("peers") {
            addJsonObject {
                put("address", ENDPOINT)
                put("port", Prefs.warpPort)
                put("public_key", acct.peerPublicKey)
                putJsonArray("allowed_ips") {
                    add("0.0.0.0/0")
                    add("::/0")
                }
                putJsonArray("reserved") { acct.reserved.forEach { add(it) } }
            }
        }
        if (detour != null) put("detour", detour)
    }

    private fun clamp(k: ByteArray) {
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
    }
}

/** X25519 (RFC 7748) for WireGuard key generation; pure Kotlin so it works on every device. */
private object X25519 {
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)

    private fun fromLE(b: ByteArray) = BigInteger(1, b.reversedArray())

    private fun toLE(x: BigInteger): ByteArray {
        val be = x.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        val out = ByteArray(32)
        for (i in be.indices) out[i] = be[be.size - 1 - i]
        return out
    }

    fun publicKey(priv: ByteArray): ByteArray = scalarMult(priv, ByteArray(32).also { it[0] = 9 })

    private fun scalarMult(kIn: ByteArray, u: ByteArray): ByteArray {
        val kb = kIn.copyOf()
        kb[0] = (kb[0].toInt() and 248).toByte()
        kb[31] = ((kb[31].toInt() and 127) or 64).toByte()
        val k = fromLE(kb)
        val ub = u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = fromLE(ub).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) {
                x2 = x3.also { x3 = x2 }
                z2 = z3.also { z3 = z2 }
            }
            swap = kt
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) {
            x2 = x3.also { x3 = x2 }
            z2 = z3.also { z3 = z2 }
        }
        return toLE(x2.multiply(z2.modPow(P.subtract(BigInteger.TWO), P)).mod(P))
    }
}
