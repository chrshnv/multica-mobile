package tech.multica.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One synchronized encrypted journal. No PAT, endpoint, revocation or identity is stored in plaintext. */
class Vault(
    context: Context,
) {
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private val preferences = context.getSharedPreferences("private_journal", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("multica.v1", null) as? SecretKey)
            ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec
                            .Builder(
                                "multica.v1",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build(),
                    )
                }.generateKey()
    }

    @Synchronized fun read(): JSONObject {
        val encoded = preferences.getString("journal", null) ?: return JSONObject()
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }

    @Synchronized fun update(change: (JSONObject) -> Unit): JSONObject {
        val root = read()
        change(root)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.iv + cipher.doFinal(root.toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("journal", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
        revision.value += 1
        return root
    }

    fun account(): Account? =
        read().optJSONObject("account")?.let {
            Account(it.text("server"), it.text("token"), it.text("user"), it.text("epoch"))
        }

    fun current(epoch: String): Boolean = account()?.epoch == epoch

    fun save(account: Account) {
        update {
            it.put(
                "account",
                JSONObject()
                    .put("server", account.server)
                    .put("token", account.token)
                    .put("user", account.user)
                    .put("epoch", account.epoch),
            )
        }
    }

    /** Local invalidation is committed before unregistering or performing any network operation. */
    fun invalidate(): String {
        var instance = ""
        update { root ->
            root.optJSONObject("push")?.let { push ->
                instance = push.text("instance")
                queueRevoke(root, push)
            }
            listOf("account", "workspace", "push", "seen", "pendingTap", "pushAvailability").forEach(root::remove)
        }
        return instance
    }

    fun queueRevoke(
        root: JSONObject,
        registration: JSONObject,
    ) {
        if (registration.text("id").isEmpty() || registration.text("revoke_token").isEmpty()) return
        val queue = root.optJSONArray("revokes") ?: JSONArray()
        val entry =
            JSONObject()
                .put(
                    "server",
                    registration.text("server"),
                ).put("id", registration.text("id"))
                .put("revoke_token", registration.text("revoke_token"))
        if (queue.objects().none { it.toString() == entry.toString() }) queue.put(entry)
        root.put("revokes", queue)
    }

    companion object {
        fun fresh(): String = UUID.randomUUID().toString()
    }
}
