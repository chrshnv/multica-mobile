package tech.multica.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.util.concurrent.TimeUnit

class MulticaApp : Application() {
    val vault by lazy { Vault(this) }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("inbox", getString(R.string.inbox), NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility =
                    android.app.Notification.VISIBILITY_PRIVATE
            },
        )
        PushWork.schedule(this)
    }
}

fun Context.vault(): Vault = (applicationContext as MulticaApp).vault

object PushWork {
    fun schedule(
        context: Context,
        payload: String? = null,
    ) {
        val builder =
            OneTimeWorkRequestBuilder<PushWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        payload?.let { builder.setInputData(Data.Builder().putString("payload", it).build()) }
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (payload ==
                null
            ) {
                "push-sync"
            } else {
                "push-event-${payload.hashCode()}"
            },
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            builder.build(),
        )
    }

    fun disconnect(context: Context) {
        val instance = context.vault().invalidate()
        if (instance.isNotEmpty()) runCatching { UnifiedPush.unregister(context, instance) }
        NotificationManagerCompat.from(context).cancelAll()
        schedule(context)
    }

    fun stopPush(context: Context) {
        val store = context.vault()
        var instance = ""
        store.update { root ->
            root.optJSONObject("push")?.let {
                instance = it.text("instance")
                store.queueRevoke(root, it)
            }
            root.remove("push")
            root.remove("seen")
            root.remove("pushAvailability")
        }
        if (instance.isNotEmpty()) runCatching { UnifiedPush.unregister(context, instance) }
        NotificationManagerCompat.from(context).cancelAll()
        schedule(context)
    }
}

class MulticaPushService : PushService() {
    override fun onNewEndpoint(
        endpoint: PushEndpoint,
        instance: String,
    ) {
        val keys = endpoint.pubKeySet ?: return
        val url = runCatching { endpoint.url.toHttpUrl() }.getOrNull() ?: return
        if (!url.isHttps || url.host != "ntfy.sh" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.fragment != null ||
            (url.query != null && url.query != "up=1")
        ) {
            setStatus(instance, "ntfy_required")
            return
        }
        vault().update { root ->
            val push = root.optJSONObject("push") ?: return@update
            if (push.text("instance") != instance) return@update
            if (push.text("endpoint") != endpoint.url || push.text("p256dh") != keys.pubKey || push.text("auth") != keys.auth) {
                vault().queueRevoke(root, push)
                push.remove("id")
                push.remove("revoke_token")
                push
                    .put(
                        "generation",
                        Vault.fresh(),
                    ).put("endpoint", endpoint.url)
                    .put("p256dh", keys.pubKey)
                    .put("auth", keys.auth)
                    .put("status", "registering")
            }
        }
        PushWork.schedule(this)
    }

    override fun onMessage(
        message: PushMessage,
        instance: String,
    ) {
        if (!message.decrypted || message.content.size > 4096) return
        val payload = runCatching { JSONObject(String(message.content, Charsets.UTF_8)) }.getOrNull() ?: return
        val push = vault().read().optJSONObject("push") ?: return
        if (push.text("instance") == instance && matches(push, payload)) PushWork.schedule(this, payload.toString())
    }

    override fun onRegistrationFailed(
        reason: FailedReason,
        instance: String,
    ) {
        setStatus(instance, "distributor_error")
    }

    override fun onTempUnavailable(instance: String) {
        setStatus(instance, "temporarily_unavailable")
    }

    override fun onUnregistered(instance: String) {
        vault().update { root ->
            val push = root.optJSONObject("push") ?: return@update
            if (push.text("instance") == instance) {
                vault().queueRevoke(root, push)
                root.remove("push")
            }
        }
        PushWork.schedule(this)
    }

    private fun setStatus(
        instance: String,
        status: String,
    ) {
        vault().update { root ->
            root
                .optJSONObject("push")
                ?.takeIf {
                    it.text("instance") ==
                        instance
                }?.put("status", status)
        }
    }
}

fun validUuid(value: String): Boolean =
    runCatching {
        java.util.UUID
            .fromString(value)
            .toString() == value.lowercase()
    }.getOrDefault(false)

fun matches(
    push: JSONObject,
    payload: JSONObject,
): Boolean =
    payload.optInt("v") == 1 && push.text("id").isNotEmpty() && push.text("id") == payload.text("registration_id") &&
        push.text("generation") == payload.text("registration_generation") &&
        validUuid(payload.text("workspace_id")) &&
        validUuid(payload.text("event_id"))

class PushWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    private val store = context.vault()

    override suspend fun doWork(): Result {
        val expectedAccount = store.account()
        val expectedGeneration = store.read().optJSONObject("push")?.text("generation")
        try {
            if (store.read().optLong("cooldown") > System.currentTimeMillis()) return Result.retry()
            val pendingRevokes = retryRevokes()
            val deferredResult = if (pendingRevokes && runAttemptCount < 7) Result.retry() else Result.success()
            val account = store.account() ?: return deferredResult
            val push = store.read().optJSONObject("push") ?: return deferredResult
            if (push.text("epoch") != account.epoch) return Result.success()
            val rawPayload = inputData.getString("payload")
            if (rawPayload != null) {
                notifyEvent(account, JSONObject(rawPayload))
                return Result.success()
            }
            if (push.text("endpoint").isEmpty()) return deferredResult
            val body =
                JSONObject()
                    .put(
                        "protocol",
                        PUSH_PROTOCOL,
                    ).put(
                        "endpoint",
                        push.text("endpoint"),
                    ).put(
                        "registration_generation",
                        push.text("generation"),
                    ).put("keys", JSONObject().put("p256dh", push.text("p256dh")).put("auth", push.text("auth")))
            val response = Api(account).objectValue("/api/push/subscriptions/${push.text("installation")}", method = "PUT", body = body)
            check(response.text("registration_generation") == push.text("generation") && response.optBoolean("enabled"))
            store.update { root ->
                val active = root.optJSONObject("push")
                if (root.optJSONObject("account")?.text("epoch") == account.epoch &&
                    active?.text("generation") == push.text("generation")
                ) {
                    active.put("id", response.text("id")).put("revoke_token", response.text("revoke_token")).put("status", "connected")
                } else {
                    response.put("server", account.server)
                    store.queueRevoke(root, response)
                }
            }
            if (!store.current(account.epoch)) PushWork.schedule(applicationContext)
            return deferredResult
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is ApiFailure &&
                error.code == 401
            ) {
                if (expectedAccount != null &&
                    store.current(expectedAccount.epoch)
                ) {
                    PushWork.disconnect(applicationContext)
                }
                return Result.failure()
            }
            if (error is ApiFailure && error.code == 429) store.update { it.put("cooldown", error.retryAt) }
            if (inputData.getString("payload") == null && error is ApiFailure && error.code in listOf(400, 403, 404, 503)) {
                store.update { root ->
                    root.optJSONObject("push")?.takeIf { it.text("generation") == expectedGeneration }?.put(
                        "status",
                        if (error.code ==
                            404 ||
                            error.code == 503
                        ) {
                            "server_unavailable"
                        } else {
                            "registration_error"
                        },
                    )
                }
            }
            return if (runAttemptCount < 7 &&
                (error !is ApiFailure || error.code == 429 || error.code >= 500)
            ) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /** A previous server being offline must not prevent the next account from using push. */
    private suspend fun retryRevokes(): Boolean {
        var pending = false
        for (entry in store
            .read()
            .optJSONArray("revokes")
            ?.objects()
            .orEmpty()) {
            try {
                try {
                    Api(null, entry.text("server")).request(
                        "/api/push/subscriptions/revoke",
                        method = "POST",
                        body = JSONObject().put("id", entry.text("id")).put("revoke_token", entry.text("revoke_token")),
                    )
                } catch (failure: ApiFailure) {
                    if (failure.code != 404) throw failure
                }
                store.update { root ->
                    root.put(
                        "revokes",
                        JSONArray(
                            root.optJSONArray("revokes")?.objects().orEmpty().filterNot {
                                it.toString() ==
                                    entry.toString()
                            },
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                pending = true
                if (failure is ApiFailure && failure.code == 429) store.update { it.put("cooldown", failure.retryAt) }
            }
        }
        return pending
    }

    private suspend fun notifyEvent(
        account: Account,
        payload: JSONObject,
    ) {
        val push = store.read().optJSONObject("push") ?: return
        if (!matches(push, payload)) return
        val api = Api(account)
        if (api.objectValue("/api/me").text("id") != account.user) return
        val workspace = payload.text("workspace_id")
        if (api.arrayValue("/api/workspaces/").none { it.text("id") == workspace }) return
        val event = api.objectValue("/api/inbox/${payload.text("event_id")}", workspace)
        if (event.text("id") != payload.text("event_id")) return
        val tag = "${account.server}|${account.user}|${push.text("generation")}|${event.text("id")}"
        synchronized(store) {
            val root = store.read()
            val active = root.optJSONObject("push") ?: return
            if (!store.current(account.epoch) || !matches(active, payload)) return
            if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return
            val seen = root.optJSONArray("seen") ?: JSONArray()
            if ((0 until seen.length()).any { seen.optString(it) == tag }) return
            val intent =
                Intent(
                    applicationContext,
                    MainActivity::class.java,
                ).setData(
                    Uri.parse("multica://event/${Uri.encode(tag)}"),
                ).putExtra("payload", payload.toString())
                    .putExtra("epoch", account.epoch)
            val pending =
                PendingIntent.getActivity(
                    applicationContext,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(
                        applicationContext,
                        "inbox",
                    ).setSmallIcon(
                        R.drawable.ic_multica,
                    ).setContentTitle(
                        applicationContext.getString(R.string.app_name),
                    ).setContentText(
                        applicationContext.getString(R.string.notification_body),
                    ).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build()
            // Stable tag makes a crash between notify and journal commit replace the same notification.
            NotificationManagerCompat.from(applicationContext).notify(tag, 0, notification)
            store.update { it.put("seen", seen.put(tag)) }
        }
    }
}
