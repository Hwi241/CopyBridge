package com.hwiject.copybridge

import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

internal object CopyBridgeRelayReportClient {

    private const val BASE_URL =
        "https://copybridge-report-relay.phsang241.workers.dev"

    private const val USER_AGENT =
        "CopyBridge-Android-Relay/1.0"

    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 15000
    private const val MAX_RESPONSE_BYTES = 768 * 1024
    private const val MAX_BODY_CHARS = 300_000

    private val JOB_ID_REGEX =
        Regex("^J\\d{4,}-\\d{6}-\\d{2}$")

    private val REQUEST_ID_REGEX = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    )

    private val SEQUENCE_REGEX =
        Regex("^R[1-9]\\d*$")

    private val SHA256_REGEX =
        Regex("^[0-9a-fA-F]{64}$")

    private val LOWERCASE_SHA256_REGEX =
        Regex("^[0-9a-f]{64}$")

    sealed class FetchResult {
        data class Ready(
            val jobId: String,
            val sequence: String,
            val body: String,
            val createdAtSeconds: Long
        ) : FetchResult()

        object Pending : FetchResult()
        object Unauthorized : FetchResult()
        object Invalid : FetchResult()
        object RetryableFailure : FetchResult()
    }

    private fun readLimitedBytes(
        connection: HttpsURLConnection
    ): ByteArray? {
        val declaredLength = connection.contentLengthLong

        if (declaredLength > MAX_RESPONSE_BYTES) {
            return null
        }

        return connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0

            while (true) {
                val count = input.read(buffer)

                if (count < 0) break
                if (count == 0) continue

                total += count

                if (total > MAX_RESPONSE_BYTES) {
                    return@use null
                }

                output.write(buffer, 0, count)
            }

            output.toByteArray()
        }
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))

        val alphabet = "0123456789abcdef"
        val hex = StringBuilder(64)

        for (byte in digest) {
            val value = byte.toInt() and 0xFF

            hex.append(alphabet[value ushr 4])
            hex.append(alphabet[value and 0x0F])
        }

        return hex.toString()
    }

    fun fetchLatest(
        jobId: String,
        readToken: String
    ): FetchResult {
        if (!JOB_ID_REGEX.matches(jobId)) {
            return FetchResult.Invalid
        }

        if (readToken.isBlank()) {
            return FetchResult.Unauthorized
        }

        var connection: HttpsURLConnection? = null

        return try {
            val url = URL("$BASE_URL/report/$jobId/latest")

            connection = (
                url.openConnection() as HttpsURLConnection
            ).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false

                setRequestProperty(
                    "User-Agent",
                    USER_AGENT
                )

                setRequestProperty(
                    "Accept",
                    "application/json"
                )

                setRequestProperty(
                    "Cache-Control",
                    "no-store"
                )

                setRequestProperty(
                    "Authorization",
                    "Bearer $readToken"
                )
            }

            val response = connection.responseCode

            when (response) {
                404 -> return FetchResult.Pending

                401, 403 ->
                    return FetchResult.Unauthorized

                200 -> Unit

                else ->
                    return FetchResult.RetryableFailure
            }

            val bytes = readLimitedBytes(connection)
                ?: return FetchResult.Invalid

            val json = JSONObject(
                String(bytes, Charsets.UTF_8)
            )

            if (!json.optBoolean("ok", false)) {
                return FetchResult.Invalid
            }

            val returnedJobId =
                json.optString("job_id", "")

            val sequence =
                json.optString("sequence", "")

            val body =
                json.optString("body", "")

            val expectedSha =
                json.optString("sha256", "")

            val createdAtSeconds =
                json.optLong("created_at", -1L)

            val expiresAtSeconds =
                json.optLong("expires_at", -1L)

            val nowSeconds =
                System.currentTimeMillis() / 1000L

            if (
                createdAtSeconds <= 0L ||
                createdAtSeconds > nowSeconds + 120L ||
                expiresAtSeconds <= nowSeconds ||
                expiresAtSeconds <= createdAtSeconds
            ) {
                return FetchResult.Invalid
            }

            if (returnedJobId != jobId) {
                return FetchResult.Invalid
            }

            if (!SEQUENCE_REGEX.matches(sequence)) {
                return FetchResult.Invalid
            }

            if (
                body.isBlank() ||
                body.length > MAX_BODY_CHARS
            ) {
                return FetchResult.Invalid
            }

            if (!SHA256_REGEX.matches(expectedSha)) {
                return FetchResult.Invalid
            }

            if (
                !sha256Hex(body).equals(
                    expectedSha,
                    ignoreCase = true
                )
            ) {
                return FetchResult.Invalid
            }

            FetchResult.Ready(
                jobId = returnedJobId,
                sequence = sequence,
                body = body,
                createdAtSeconds = createdAtSeconds
            )

        } catch (_: Exception) {
            FetchResult.RetryableFailure
        } finally {
            connection?.disconnect()
        }
    }

    fun fetchLatestByRequestId(
        requestId: String,
        readToken: String
    ): FetchResult {
        if (!REQUEST_ID_REGEX.matches(requestId)) {
            return FetchResult.Invalid
        }

        if (readToken.isBlank()) {
            return FetchResult.Unauthorized
        }

        var connection: HttpsURLConnection? = null

        return try {
            val url = URL(
                "$BASE_URL/request/$requestId/latest"
            )

            connection = (
                url.openConnection() as HttpsURLConnection
            ).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false

                setRequestProperty(
                    "User-Agent",
                    USER_AGENT
                )

                setRequestProperty(
                    "Accept",
                    "application/json"
                )

                setRequestProperty(
                    "Cache-Control",
                    "no-store"
                )

                setRequestProperty(
                    "Authorization",
                    "Bearer $readToken"
                )
            }

            val response = connection.responseCode

            when (response) {
                404 -> return FetchResult.Pending

                401, 403 ->
                    return FetchResult.Unauthorized

                200 -> Unit

                else ->
                    return FetchResult.RetryableFailure
            }

            val bytes = readLimitedBytes(connection)
                ?: return FetchResult.Invalid

            val json = JSONObject(
                String(bytes, Charsets.UTF_8)
            )

            if (!json.optBoolean("ok", false)) {
                return FetchResult.Invalid
            }

            val returnedRequestId =
                json.optString("request_id", "")

            val returnedJobId =
                json.optString("job_id", "")

            val sequence =
                json.optString("sequence", "")

            val body =
                json.optString("body", "")

            val expectedSha =
                json.optString("sha256", "")

            val createdAtSeconds =
                json.optLong("created_at", -1L)

            val expiresAtSeconds =
                json.optLong("expires_at", -1L)

            val nowSeconds =
                System.currentTimeMillis() / 1000L

            if (
                createdAtSeconds <= 0L ||
                createdAtSeconds > nowSeconds + 120L ||
                expiresAtSeconds <= nowSeconds ||
                expiresAtSeconds <= createdAtSeconds
            ) {
                return FetchResult.Invalid
            }

            if (returnedRequestId != requestId) {
                return FetchResult.Invalid
            }

            if (!JOB_ID_REGEX.matches(returnedJobId)) {
                return FetchResult.Invalid
            }

            if (!SEQUENCE_REGEX.matches(sequence)) {
                return FetchResult.Invalid
            }

            if (
                body.isBlank() ||
                body.length > MAX_BODY_CHARS
            ) {
                return FetchResult.Invalid
            }

            if (!LOWERCASE_SHA256_REGEX.matches(expectedSha)) {
                return FetchResult.Invalid
            }

            if (sha256Hex(body) != expectedSha) {
                return FetchResult.Invalid
            }

            FetchResult.Ready(
                jobId = returnedJobId,
                sequence = sequence,
                body = body,
                createdAtSeconds = createdAtSeconds
            )

        } catch (_: Exception) {
            FetchResult.RetryableFailure
        } finally {
            connection?.disconnect()
        }
    }
}
