package io.github.goooseby.tickmark.data.sync

import io.github.goooseby.tickmark.engine.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.TimeUnit

enum class SyncErrorCode {
    CONFIGURATION,
    NETWORK,
    PROTOCOL,
    VALIDATION,
    AUTH_REQUIRED,
    INVALID_CODE,
    EXPIRED_CODE,
    FORBIDDEN,
    DEVICE_REVOKED,
    REVISION_CONFLICT,
    STALE_REVISION,
    EPOCH_MISMATCH,
    RATE_LIMITED,
    SERVER,
}

class SyncException(
    val code: SyncErrorCode,
    val retryAfterSeconds: Int? = null,
    val dayUtc: LocalDate? = null,
    val serverRevision: Long? = null,
) : Exception(code.name)

class SyncHttpClient(
    baseUrl: String,
    private val allowLocalHttp: Boolean = false,
    private val deviceKind: String = "phone",
) : SyncTransport, SyncAuthenticator {
    private val baseUrl = runCatching { normalizeBaseUrl(baseUrl, allowLocalHttp) }.getOrDefault("")

    val configured: Boolean get() = baseUrl.isNotBlank()

    override suspend fun serverEpoch(accessToken: String): String = withContext(Dispatchers.IO) {
        JSONObject(request("GET", "/v1/sync/status", null, accessToken)).getString("epoch")
    }

    override suspend fun push(accessToken: String, upload: SyncUpload): SyncPushResult = withContext(Dispatchers.IO) {
        if (!upload.shards.isNotEmpty() || upload.shards.size > 25) throw SyncException(SyncErrorCode.VALIDATION)
        val shards = JSONArray()
        upload.shards.forEach { shard ->
            if (shard.revision <= 0 || shard.payloadVersion != SYNC_PAYLOAD_VERSION) throw SyncException(SyncErrorCode.VALIDATION)
            shards.put(JSONObject().apply {
                put("dayUtc", shard.dayUtc.toString())
                put("revision", shard.revision)
                put("payloadVersion", shard.payloadVersion)
                put("payload", shard.payload?.let { JSONObject(SyncPayloadCodec.encode(it)) } ?: JSONObject.NULL)
            })
        }
        val body = JSONObject().put("epoch", upload.epoch).put("shards", shards).toString()
        if (body.toByteArray(Charsets.UTF_8).size > 32_000_000) throw SyncException(SyncErrorCode.VALIDATION)
        try {
            request("PUT", "/v1/devices/${pathSegment(upload.deviceId)}/shards", body, accessToken)
            SyncPushResult.Accepted
        } catch (error: SyncException) {
            when (error.code) {
                SyncErrorCode.REVISION_CONFLICT -> SyncPushResult.Conflict(error.dayUtc, error.serverRevision)
                SyncErrorCode.STALE_REVISION -> SyncPushResult.Stale(error.dayUtc)
                else -> throw error
            }
        }
    }

    override suspend fun pull(
        accessToken: String,
        epoch: String,
        after: String?,
        limit: Int,
        waitSeconds: Int,
    ): SyncPullResult = withContext(Dispatchers.IO) {
        require(limit in 1..200 && waitSeconds in 0..25)
        val query = buildString {
            append("limit=").append(limit)
            append("&wait=").append(waitSeconds)
            after?.let { append("&after=").append(encode(it)) }
        }
        val json = JSONObject(request("GET", "/v1/changes?$query", null, accessToken, waitSeconds))
        val responseEpoch = json.getString("epoch")
        require(responseEpoch == epoch) { throw SyncException(SyncErrorCode.EPOCH_MISMATCH) }
        val changes = parseChanges(json.optJSONArray("changes") ?: JSONArray())
        SyncPullResult(
            epoch = responseEpoch,
            changes = changes,
            devices = parseDevices(json.optJSONArray("devices") ?: JSONArray()),
            nextAfter = cursor(json.opt("nextAfter")),
            hasMore = json.optBoolean("hasMore", changes.size >= limit),
        )
    }

    override suspend fun snapshot(accessToken: String, cursor: String?): SyncSnapshotPage = withContext(Dispatchers.IO) {
        val query = cursor?.let { "?cursor=${encode(it)}" }.orEmpty()
        val json = JSONObject(request("GET", "/v1/snapshot$query", null, accessToken))
        val changes = parseChanges(json.optJSONArray("shards") ?: json.optJSONArray("changes") ?: JSONArray(), requireSequence = false)
        SyncSnapshotPage(
            epoch = json.getString("epoch"),
            changes = changes,
            devices = parseDevices(json.optJSONArray("devices") ?: JSONArray()),
            nextCursor = cursor(json.opt("nextCursor")),
            hasMore = json.optBoolean("hasMore", false),
        )
    }

    override suspend fun challenge(email: String, deviceId: String, deviceName: String): SyncAuthChallenge = withContext(Dispatchers.IO) {
        val normalizedEmail = email.trim().lowercase()
        require(normalizedEmail.length in 3..254 && normalizedEmail.count { it == '@' } == 1 && normalizedEmail.none(Char::isWhitespace)) { "邮箱格式无效" }
        validateDevice(deviceId, deviceName)
        val body = JSONObject().apply {
            put("email", normalizedEmail)
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("platform", "android")
            put("kind", deviceKind)
        }.toString()
        val json = JSONObject(request("POST", "/v1/auth/challenges", body, null))
        SyncAuthChallenge(
            challengeId = json.getString("challengeId"),
            expiresAt = json.optLong("expiresAt").takeIf { it > 0 },
            resendAfterSeconds = json.optInt("resendAfterSeconds", 60).coerceIn(0, 300),
        )
    }

    override suspend fun verify(
        challengeId: String,
        code: String,
        deviceId: String,
        deviceName: String,
    ): SyncAuthSession = withContext(Dispatchers.IO) {
        require(challengeId.isNotBlank() && code.length == 6 && code.all(Char::isDigit)) { "验证码格式无效" }
        validateDevice(deviceId, deviceName)
        val body = JSONObject().apply {
            put("challengeId", challengeId)
            put("code", code)
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("platform", "android")
            put("kind", deviceKind)
        }.toString()
        val json = JSONObject(request("POST", "/v1/auth/verify", body, null))
        val tokens = json.optJSONObject("tokens") ?: json
        val device = json.optJSONObject("device") ?: json
        SyncAuthSession(
            accountId = json.optString("accountId").ifBlank { json.optJSONObject("account")?.getString("id").orEmpty() },
            deviceId = device.optString("deviceId").ifBlank { device.optString("id") },
            epoch = json.getString("epoch"),
            accessToken = tokens.getString("accessToken"),
            refreshToken = tokens.getString("refreshToken"),
            accessTokenExpiresAt = tokenExpiry(tokens),
        ).also {
            require(it.accountId.isNotBlank() && it.deviceId.isNotBlank() && it.accessToken.isNotBlank() && it.refreshToken.isNotBlank()) { "登录响应无效" }
        }
    }

    override suspend fun refresh(refreshToken: String, requestId: String): SyncTokenPair = withContext(Dispatchers.IO) {
        require(refreshToken.isNotBlank() && requestId.isNotBlank())
        val body = JSONObject().put("refreshToken", refreshToken).toString()
        val json = JSONObject(requestWithHeaders("POST", "/v1/auth/refresh", body, null, mapOf("Idempotency-Key" to requestId)))
        val tokens = json.optJSONObject("tokens") ?: json
        SyncTokenPair(
            accessToken = tokens.getString("accessToken"),
            refreshToken = tokens.getString("refreshToken"),
            accessTokenExpiresAt = tokenExpiry(tokens),
        )
    }

    override suspend fun revoke(accessToken: String, deviceId: String): Unit = withContext(Dispatchers.IO) {
        request("POST", "/v1/devices/${pathSegment(deviceId)}/revoke", "{}", accessToken)
    }

    private fun request(
        method: String,
        path: String,
        body: String?,
        accessToken: String?,
        waitSeconds: Int = 0,
    ): String = requestWithHeaders(method, path, body, accessToken, emptyMap(), waitSeconds)

    private fun requestWithHeaders(
        method: String,
        path: String,
        body: String?,
        accessToken: String?,
        headers: Map<String, String>,
        waitSeconds: Int = 0,
    ): String {
        if (!configured) throw SyncException(SyncErrorCode.CONFIGURATION)
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000 + waitSeconds * 1_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Tickmark-Android")
        accessToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            try {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            } catch (error: IOException) {
                connection.disconnect()
                throw SyncException(SyncErrorCode.NETWORK)
            }
        }
        try {
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readTextLimited(16_000_000) }.orEmpty()
            if (status !in 200..299) throw parseError(status, text, connection.getHeaderField("Retry-After"))
            return text
        } catch (error: SyncException) {
            throw error
        } catch (error: IOException) {
            throw SyncException(SyncErrorCode.NETWORK)
        } catch (error: IllegalArgumentException) {
            throw SyncException(SyncErrorCode.PROTOCOL)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseError(status: Int, body: String, retryAfter: String?): SyncException {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val rawCode = json?.optString("code").orEmpty().uppercase()
        val code = when {
            rawCode == "EPOCH_MISMATCH" -> SyncErrorCode.EPOCH_MISMATCH
            rawCode == "INVALID_CODE" -> SyncErrorCode.INVALID_CODE
            rawCode in setOf("CHALLENGE_EXPIRED", "CHALLENGE_EXHAUSTED") -> SyncErrorCode.EXPIRED_CODE
            rawCode in setOf("STALE_REVISION", "OUTDATED_REVISION") -> SyncErrorCode.STALE_REVISION
            rawCode in setOf("REVISION_CONFLICT", "SHARD_CONFLICT") || status == 409 -> SyncErrorCode.REVISION_CONFLICT
            rawCode == "DEVICE_REVOKED" -> SyncErrorCode.DEVICE_REVOKED
            status == 401 -> SyncErrorCode.AUTH_REQUIRED
            status == 403 -> SyncErrorCode.FORBIDDEN
            status == 429 -> SyncErrorCode.RATE_LIMITED
            status in 400..499 -> SyncErrorCode.VALIDATION
            else -> SyncErrorCode.SERVER
        }
        val day = json?.optString("dayUtc")?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val revision = json?.optLong("serverRevision", -1)?.takeIf { it >= 0 }
        val retry = retryAfter?.toIntOrNull()?.coerceIn(0, 86_400)
        return SyncException(code, retry, day, revision)
    }

    private fun parseChanges(array: JSONArray, requireSequence: Boolean = true): List<SyncChange> = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        val payload = item.optJSONObject("payload")
        val version = item.optInt("payloadVersion", SYNC_PAYLOAD_VERSION)
        require(version == SYNC_PAYLOAD_VERSION) { throw SyncException(SyncErrorCode.PROTOCOL) }
        val day = LocalDate.parse(item.getString("dayUtc"))
        var sequence = item.optLong("seq", item.optLong("sequence", -1))
        if (sequence < 0 && !requireSequence) sequence = index.toLong()
        require(sequence >= 0) { throw SyncException(SyncErrorCode.PROTOCOL) }
        SyncChange(
            sequence = sequence,
            deviceId = item.getString("deviceId"),
            utcDate = day,
            revision = item.getLong("revision"),
            payload = payload?.let { SyncPayloadCodec.decode(it.toString(), day) },
            deleted = item.optBoolean("deleted", payload == null),
        )
    }.sortedBy { it.sequence }

    private fun parseDevices(array: JSONArray): List<SyncRemoteDevice> = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        SyncRemoteDevice(
            deviceId = item.optString("deviceId").ifBlank { item.getString("id") },
            name = item.optString("name").ifBlank { item.optString("deviceId").ifBlank { item.getString("id") } },
            lastSeenAt = item.optLong("lastSeenAt", item.optLong("updatedAtMs", -1)).takeIf { it >= 0 },
            revoked = item.optBoolean("revoked", false),
        )
    }

    private fun tokenExpiry(json: JSONObject): Long {
        val explicit = json.optLong("accessTokenExpiresAt", -1)
        if (explicit > 0) return explicit
        val expiresIn = json.optLong("expiresIn", 900).coerceIn(1, 86_400)
        return System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(expiresIn)
    }

    private fun validateDevice(deviceId: String, deviceName: String) {
        require(Regex("[A-Za-z0-9_-]{1,80}").matches(deviceId))
        require(deviceName.isNotBlank() && deviceName.length <= 80)
    }

    private fun pathSegment(value: String): String {
        require(Regex("[A-Za-z0-9_-]{1,80}").matches(value))
        return value
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun cursor(value: Any?): String = when (value) {
        null -> ""
        else -> value.toString()
    }

    private fun normalizeBaseUrl(value: String, allowLocalHttp: Boolean): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: throw SyncException(SyncErrorCode.CONFIGURATION)
        val local = uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        val validScheme = uri.scheme == "https" || (allowLocalHttp && local && uri.scheme == "http")
        if (!validScheme || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            throw SyncException(SyncErrorCode.CONFIGURATION)
        }
        return trimmed
    }
}

private fun java.io.Reader.readTextLimited(max: Int): String {
    val output = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.length + count <= max) { "同步响应过大" }
        output.append(buffer, 0, count)
    }
    return output.toString()
}
