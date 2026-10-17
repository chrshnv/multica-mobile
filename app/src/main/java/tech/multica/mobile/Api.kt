package tech.multica.mobile

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val DEFAULT_SERVER = "https://multica-api.eblans.tech"
const val PUSH_PROTOCOL = "unifiedpush-webpush-v1"

fun normalizeServer(raw: String): String {
    val url = raw.trim().toHttpUrl()
    require(
        url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null &&
            url.fragment == null,
    )
    return url.toString().removeSuffix("/")
}

fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun List<JSONObject>.unique(): List<JSONObject> = distinctBy { it.text("id") }

class ApiFailure(
    val code: Int,
    val retryAt: Long = 0,
) : IOException()

data class ApiPage(
    val value: Any,
    val before: String = "",
    val beforeId: String = "",
    val truncated: Boolean = false,
)

data class Account(
    val server: String,
    val token: String,
    val user: String,
    val epoch: String,
)

class Api(
    private val account: Account?,
    private val origin: String = account?.server ?: DEFAULT_SERVER,
) {
    companion object {
        // Redirects are rejected: credentials never follow an untrusted origin. Writes never auto-retry.
        private val client =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()
    }

    suspend fun request(
        path: String,
        workspace: String? = null,
        method: String = "GET",
        body: JSONObject? = null,
        query: Map<String, String> = emptyMap(),
    ): ApiPage {
        val url =
            (origin + path)
                .toHttpUrl()
                .newBuilder()
                .apply {
                    query.forEach { (key, value) ->
                        if (value.isNotEmpty()) addQueryParameter(key, value)
                    }
                }.build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply {
                    account?.let { header("Authorization", "Bearer ${it.token}") }
                    workspace?.let { header("X-Workspace-ID", it) }
                    if (method !=
                        "GET"
                    ) {
                        method(
                            method,
                            if (method ==
                                "DELETE"
                            ) {
                                null
                            } else {
                                (body?.toString() ?: "").toRequestBody("application/json; charset=utf-8".toMediaType())
                            },
                        )
                    }
                }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        exception: IOException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(exception)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        response.use {
                            try {
                                if (!it.isSuccessful) {
                                    val retry = it.header("Retry-After")
                                    val retryAt =
                                        retry?.toLongOrNull()?.let { seconds -> System.currentTimeMillis() + seconds * 1000 }
                                            ?: runCatching {
                                                ZonedDateTime
                                                    .parse(
                                                        retry,
                                                        DateTimeFormatter.RFC_1123_DATE_TIME,
                                                    ).toInstant()
                                                    .toEpochMilli()
                                            }.getOrDefault(0)
                                    throw ApiFailure(it.code, retryAt)
                                }
                                val raw =
                                    it.body
                                        ?.string()
                                        .orEmpty()
                                        .trim()
                                val value: Any =
                                    when {
                                        raw.startsWith("[") -> JSONArray(raw)
                                        raw.isBlank() -> JSONObject()
                                        else -> JSONObject(raw)
                                    }
                                if (continuation.isActive) {
                                    continuation.resume(
                                        ApiPage(
                                            value,
                                            it.header("X-Multica-Next-Before").orEmpty(),
                                            it.header("X-Multica-Next-Before-Id").orEmpty(),
                                            it.header("X-Comments-Truncated") == "true",
                                        ),
                                    )
                                }
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }
                    }
                },
            )
        }
    }

    suspend fun objectValue(
        path: String,
        workspace: String? = null,
        method: String = "GET",
        body: JSONObject? = null,
        query: Map<String, String> = emptyMap(),
    ): JSONObject = request(path, workspace, method, body, query).value as JSONObject

    suspend fun arrayValue(
        path: String,
        workspace: String? = null,
        query: Map<String, String> = emptyMap(),
    ): List<JSONObject> = (request(path, workspace, query = query).value as JSONArray).objects()
}
