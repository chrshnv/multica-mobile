package tech.multica.mobile

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.unifiedpush.android.connector.UnifiedPush
import java.io.IOException

class AppModel(
    application: Application,
) : AndroidViewModel(application) {
    val store = application.vault()
    var loginToken by mutableStateOf("")
    var account by mutableStateOf<Account?>(null)
        private set
    var workspaces by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var workspace by mutableStateOf("")
        private set
    var screen by mutableStateOf("home")
    var tab by mutableStateOf("tasks")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf(0)
        private set
    var retryAt by mutableStateOf(0L)
        private set
    var uncertain by mutableStateOf(false)
        private set
    var tasks by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var taskTotal by mutableStateOf(0)
        private set
    private var taskOffset = 0
    var statusFilter by mutableStateOf("")
    var assigneeFilter by mutableStateOf("")
    var statuses by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var people by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var projects by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var inbox by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var inboxLimit by mutableStateOf(40)
    var archive by mutableStateOf(false)
    var unread by mutableStateOf(false)
    var archiveCursor by mutableStateOf("")
        private set
    var archiveMore by mutableStateOf(false)
        private set
    var issue by mutableStateOf<JSONObject?>(null)
        private set
    var comments by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var nextBefore by mutableStateOf("")
        private set
    private var nextBeforeId = ""
    var commentsTruncated by mutableStateOf(false)
        private set
    private val threadCursors = mutableMapOf<String, Pair<String, String>>()
    var event by mutableStateOf<JSONObject?>(null)
        private set
    var focusComment by mutableStateOf("")
    var replyParent by mutableStateOf("")
    var replyText by mutableStateOf("")
    var title by mutableStateOf("")
    var description by mutableStateOf("")
    var priority by mutableStateOf("none")
    var project by mutableStateOf("")
    var assignee by mutableStateOf("")
    var pushStatus by mutableStateOf("off")
        private set
    private var returnScreen = "home"
    private var sessionJob: Job = SupervisorJob(viewModelScope.coroutineContext[Job])
    private var tap: Pair<String, String>? = null

    init {
        viewModelScope.launch {
            store.changes.collect {
                val localAccount = account
                if (localAccount != null && !store.current(localAccount.epoch)) {
                    account = null
                    workspaces = emptyList()
                    workspace = ""
                    tap = null
                    loginToken = ""
                    resetScope()
                    error = 401
                    pushStatus = "off"
                }
                readPushStatus()
            }
        }
        restore()
    }

    private fun api(): Api = Api(checkNotNull(account))

    private fun launchAction(
        write: Boolean = false,
        action: suspend () -> Unit,
    ) {
        if (busy || System.currentTimeMillis() < retryAt) return
        busy = true
        error = 0
        uncertain = false
        val operationJob = sessionJob
        CoroutineScope(Dispatchers.Main.immediate + operationJob).launch {
            try {
                action()
                if (tap != null && account != null) consumeTap()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error =
                    if (failure is ApiFailure) {
                        failure.code
                    } else if (failure is IOException) {
                        -1
                    } else {
                        -2
                    }
                if (failure is ApiFailure) retryAt = failure.retryAt
                uncertain = write && (failure !is ApiFailure || failure.code >= 500)
                if (failure is ApiFailure && failure.code == 401) expire()
            } finally {
                if (sessionJob === operationJob) busy = false
            }
        }
    }

    fun clearError() {
        error = 0
        uncertain = false
    }

    private fun resetScope() {
        sessionJob.cancel()
        sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        busy = false
        error = 0
        uncertain = false
        retryAt = 0
        tasks = emptyList()
        taskTotal = 0
        taskOffset = 0
        inbox = emptyList()
        issue = null
        event = null
        comments = emptyList()
        statuses = emptyList()
        people = emptyList()
        projects = emptyList()
        nextBefore = ""
        nextBeforeId = ""
        threadCursors.clear()
        statusFilter = ""
        assigneeFilter = ""
        archiveCursor = ""
        archiveMore = false
        inboxLimit = 40
        replyText = ""
        replyParent = ""
        focusComment = ""
        title = ""
        description = ""
        priority = "none"
        project = ""
        assignee = ""
        screen = "home"
        tab = "tasks"
    }

    private fun restore() {
        launchAction {
            val saved =
                try {
                    store.account()
                } catch (failure: Exception) {
                    error = -3
                    return@launchAction
                }
            if (saved != null) {
                val identity = Api(saved).objectValue("/api/me")
                if (identity.text("id") != saved.user) {
                    expire()
                    return@launchAction
                }
                account = saved
                workspaces = api().arrayValue("/api/workspaces/")
                val previous = store.read().text("workspace")
                workspace =
                    workspaces.firstOrNull { it.text("id") == previous }?.text("id") ?: workspaces.singleOrNull()?.text("id").orEmpty()
                if (workspace.isNotEmpty()) loadHome()
                reconnectSavedPush()
                readPushStatus()
                consumeTap()
            }
        }
    }

    fun retrySession() {
        restore()
    }

    fun login(
        server: String,
        token: String,
    ) {
        if (!token.trim().startsWith("mul_") || token.trim().any { it.isWhitespace() }) {
            error = -4
            return
        }
        val normalized =
            runCatching { normalizeServer(server) }.getOrElse {
                error = -5
                return
            }
        launchAction {
            val candidate = Account(normalized, token.trim(), "", Vault.fresh())
            val identity = Api(candidate).objectValue("/api/me")
            val available = Api(candidate).arrayValue("/api/workspaces/")
            val active = candidate.copy(user = identity.getString("id"))
            PushWork.disconnect(getApplication())
            store.save(active)
            account = active
            loginToken = ""
            workspaces = available
            workspace = available.singleOrNull()?.text("id").orEmpty()
            if (workspace.isNotEmpty()) loadHome()
            consumeTap()
        }
    }

    private fun expire() {
        PushWork.disconnect(getApplication())
        account = null
        workspaces = emptyList()
        workspace = ""
        tap = null
        loginToken = ""
        resetScope()
        error = 401
        pushStatus = "off"
    }

    fun logout() {
        expire()
        error = 0
    }

    fun selectWorkspace(id: String) {
        if (workspaces.none { it.text("id") == id } || id == workspace) {
            if (id == workspace) screen = "home"
            return
        }
        resetScope()
        workspace = id
        store.update { it.put("workspace", id) }
        launchAction { loadHome() }
    }

    private suspend fun loadCatalogs() {
        statuses = api().arrayValue("/api/issue-statuses/", workspace)
        projects = api().arrayValue("/api/projects/", workspace)
        val members =
            api().arrayValue("/api/workspaces/$workspace/members", workspace).map {
                JSONObject().put("id", it.text("user_id")).put("name", it.text("name")).put("type", "member")
            }
        people = members
        val agents =
            api()
                .arrayValue("/api/agents/", workspace)
                .filter {
                    it.text("archived_at").isEmpty()
                }.map { JSONObject().put("id", it.text("id")).put("name", it.text("name")).put("type", "agent") }
        people = members + agents
        val squads =
            api()
                .arrayValue("/api/squads/", workspace)
                .filter {
                    it.text("archived_at").isEmpty()
                }.map { JSONObject().put("id", it.text("id")).put("name", it.text("name")).put("type", "squad") }
        people = members + agents + squads
    }

    private suspend fun loadHome() {
        store.update { it.put("workspace", workspace) }
        loadTasks(false)
        loadCatalogs()
    }

    fun refresh() {
        launchAction {
            if (tab == "tasks") {
                loadTasks(false)
                loadCatalogs()
            } else {
                loadInbox(false)
            }
        }
    }

    fun filterTasks(
        status: String = statusFilter,
        person: String = assigneeFilter,
    ) {
        if (busy) return
        statusFilter = status
        assigneeFilter = person
        tasks = emptyList()
        taskOffset = 0
        launchAction { loadTasks(false) }
    }

    fun moreTasks() {
        launchAction { loadTasks(true) }
    }

    private suspend fun loadTasks(more: Boolean) {
        val offset = if (more) taskOffset else 0
        val response =
            api().objectValue(
                "/api/issues/",
                workspace,
                query =
                    mapOf(
                        "limit" to "40",
                        "offset" to offset.toString(),
                        "status" to statusFilter,
                        "assignee_id" to assigneeFilter,
                    ),
            )
        val rows = response.optJSONArray("issues")?.objects().orEmpty()
        if (more && rows.any { row -> tasks.any { it.text("id") == row.text("id") } }) {
            loadTasks(false)
            return
        }
        taskTotal = response.optInt("total")
        taskOffset = offset + rows.size
        tasks = ((if (more) tasks else emptyList()) + rows).unique()
        if (rows.isEmpty()) taskOffset = taskTotal
    }

    fun hasMoreTasks(): Boolean = taskOffset < taskTotal

    fun chooseTab(value: String) {
        if (busy) return
        tab = value
        if (value == "inbox" && inbox.isEmpty()) refresh()
    }

    fun inboxMode(
        archived: Boolean,
        unreadOnly: Boolean,
    ) {
        if (busy) return
        archive = archived
        unread = unreadOnly
        inbox = emptyList()
        inboxLimit = 40
        launchAction { loadInbox(false) }
    }

    fun moreInbox() {
        if (!archive) {
            inboxLimit += 40
        } else {
            launchAction {
                loadInbox(true)
                inboxLimit += 40
            }
        }
    }

    private suspend fun loadInbox(more: Boolean) {
        if (!archive) {
            inbox = api().arrayValue("/api/inbox/", workspace).unique()
            archiveMore = false
            return
        }
        val response =
            api().objectValue(
                "/api/inbox/archived/page",
                workspace,
                query =
                    mapOf(
                        "limit" to "40",
                        "cursor" to if (more) archiveCursor else "",
                        "unread_only" to unread.toString(),
                    ),
            )
        inbox = ((if (more) inbox else emptyList()) + response.optJSONArray("items")?.objects().orEmpty()).unique()
        archiveCursor = response.text("next_cursor")
        archiveMore = response.optBoolean("has_more") && archiveCursor.isNotEmpty()
    }

    fun openIssue(
        id: String,
        anchor: String = "",
    ) {
        returnScreen = screen
        launchAction { loadIssue(id, anchor) }
    }

    private suspend fun loadIssue(
        id: String,
        anchor: String = "",
    ) {
        val loaded = api().objectValue("/api/issues/$id/", workspace)
        issue = loaded
        comments = emptyList()
        nextBefore = ""
        nextBeforeId = ""
        threadCursors.clear()
        replyText = ""
        replyParent = ""
        focusComment = anchor
        screen = "detail"
        loadComments(false)
        if (anchor.isNotEmpty() && comments.none { it.text("id") == anchor }) loadThread(anchor)
    }

    fun refreshDetail() {
        launchAction {
            issue = api().objectValue("/api/issues/${issue!!.text("id")}/", workspace)
            loadComments(false)
        }
    }

    fun moreComments() {
        launchAction { loadComments(true) }
    }

    private suspend fun loadComments(more: Boolean) {
        val page =
            api().request(
                "/api/issues/${issue!!.text("id")}/comments",
                workspace,
                query =
                    mapOf(
                        "recent" to "20",
                        "before" to if (more) nextBefore else "",
                        "before_id" to if (more) nextBeforeId else "",
                    ),
            )
        comments = ((if (more) comments else emptyList()) + (page.value as JSONArray).objects()).unique().sortedBy { it.text("created_at") }
        nextBefore = page.before
        nextBeforeId = page.beforeId
        commentsTruncated = page.truncated
    }

    fun moreThread(id: String) {
        launchAction { loadThread(id) }
    }

    private suspend fun loadThread(id: String) {
        val cursor = threadCursors[id]
        val page =
            api().request(
                "/api/issues/${issue!!.text("id")}/comments",
                workspace,
                query =
                    mapOf(
                        "thread" to id,
                        "tail" to "30",
                        "before" to cursor?.first.orEmpty(),
                        "before_id" to cursor?.second.orEmpty(),
                    ),
            )
        comments = (comments + (page.value as JSONArray).objects()).unique().sortedBy { it.text("created_at") }
        threadCursors[id] = page.before to page.beforeId
        commentsTruncated = page.truncated
    }

    fun threadHasMore(id: String): Boolean = threadCursors[id]?.first?.isNotEmpty() ?: true

    fun sendReply() {
        if (replyText.isBlank() || issue == null) return
        launchAction(write = true) {
            val body = JSONObject().put("content", replyText).put("type", "comment")
            if (replyParent.isNotEmpty()) body.put("parent_id", replyParent)
            val created = api().objectValue("/api/issues/${issue!!.text("id")}/comments", workspace, "POST", body)
            comments = (comments + created).unique()
            replyText = ""
            replyParent = ""
        }
    }

    fun create() {
        if (title.isBlank()) {
            error = -6
            return
        }
        launchAction(write = true) {
            val body = JSONObject().put("title", title.trim()).put("description", description).put("priority", priority)
            if (project.isNotEmpty()) body.put("project_id", project)
            people
                .firstOrNull {
                    it.text(
                        "id",
                    ) == assignee
                }?.let { body.put("assignee_type", it.text("type")).put("assignee_id", it.text("id")) }
            val created = api().objectValue("/api/issues/", workspace, "POST", body)
            // A confirmed create is never retried if a subsequent read fails.
            title = ""
            description = ""
            project = ""
            assignee = ""
            priority = "none"
            issue = created
            comments = emptyList()
            screen = "detail"
            returnScreen = "home"
            tasks = (listOf(created) + tasks).unique()
            taskTotal += 1
        }
    }

    fun openEvent(item: JSONObject) {
        launchAction {
            event = item
            screen = "event"
            val updated = api().objectValue("/api/inbox/${item.text("id")}/read", workspace, "POST")
            event = updated
            inbox = inbox.map { if (it.text("id") == updated.text("id")) updated else it }
        }
    }

    fun markRead(id: String) {
        launchAction {
            val updated = api().objectValue("/api/inbox/$id/read", workspace, "POST")
            inbox = inbox.map { if (it.text("id") == id) updated else it }
        }
    }

    fun openEventTarget() {
        event?.let {
            if (it.text("issue_id").isNotEmpty()) openIssue(it.text("issue_id"), it.optJSONObject("details")?.text("comment_id").orEmpty())
        }
    }

    fun receiveTap(
        payload: String,
        epoch: String,
    ) {
        tap = payload to epoch
        if (account != null && !busy) launchAction { consumeTap() }
    }

    private suspend fun consumeTap() {
        val target = tap ?: return
        tap = null
        val activeAccount = account ?: return
        if (target.second != activeAccount.epoch) return
        val payload = JSONObject(target.first)
        val push = store.read().optJSONObject("push") ?: return
        if (!matches(push, payload)) return
        val available = api().arrayValue("/api/workspaces/")
        val targetWorkspace = payload.text("workspace_id")
        if (available.none { it.text("id") == targetWorkspace }) throw ApiFailure(404)
        workspaces = available
        if (workspace != targetWorkspace) {
            tasks = emptyList()
            inbox = emptyList()
            people = emptyList()
            projects = emptyList()
            statuses = emptyList()
            title = ""
            description = ""
            replyText = ""
            replyParent = ""
            project = ""
            assignee = ""
            workspace = targetWorkspace
            store.update { it.put("workspace", workspace) }
            loadHome()
        }
        val resolved = api().objectValue("/api/inbox/${payload.text("event_id")}", workspace)
        event = resolved
        screen = "event"
        tab = "inbox"
        // Only a deliberate notification tap reaches here
        // Receiving never marks read.
        event = api().objectValue("/api/inbox/${resolved.text("id")}/read", workspace, "POST")
    }

    fun back() {
        screen =
            when (screen) {
                "detail" -> returnScreen.takeIf { it == "event" } ?: "home"
                else -> "home"
            }
        clearError()
    }

    fun readPushStatus() {
        pushStatus =
            runCatching {
                val root = store.read()
                root.optJSONObject("push")?.text("status") ?: root.text("pushAvailability").ifEmpty { "off" }
            }.getOrDefault("storage_error")
    }

    fun enablePush() {
        val current = account ?: return
        launchAction {
            val config =
                try {
                    api().objectValue("/api/push/config")
                } catch (failure: ApiFailure) {
                    if (failure.code == 404 || failure.code == 503) {
                        store.update {
                            it.put("pushAvailability", "server_unavailable")
                            it.optJSONObject("push")?.put("status", "server_unavailable")
                        }
                        readPushStatus()
                        return@launchAction
                    }
                    throw failure
                }
            check(config.text("protocol") == PUSH_PROTOCOL)
            val context = getApplication<Application>()
            val distributor = UnifiedPush.getDistributors(context).firstOrNull { it == "io.heckel.ntfy" }
            if (distributor == null) {
                store.update { it.put("pushAvailability", "ntfy_required") }
                readPushStatus()
                return@launchAction
            }
            UnifiedPush.saveDistributor(context, distributor)
            val root = store.read()
            val active = root.optJSONObject("push")
            val instance = active?.text("instance") ?: Vault.fresh()
            if (active ==
                null
            ) {
                store.update {
                    it.put(
                        "push",
                        JSONObject()
                            .put(
                                "server",
                                current.server,
                            ).put(
                                "epoch",
                                current.epoch,
                            ).put(
                                "instance",
                                instance,
                            ).put(
                                "installation",
                                Vault.fresh(),
                            ).put("generation", Vault.fresh())
                            .put("status", "registering")
                            .put("vapid", config.text("vapid_public_key")),
                    )
                }
            }
            UnifiedPush.register(context, instance = instance, messageForDistributor = "Multica", vapid = config.text("vapid_public_key"))
            PushWork.schedule(context)
            readPushStatus()
        }
    }

    private fun reconnectSavedPush() {
        val push = store.read().optJSONObject("push") ?: return
        val context = getApplication<Application>()
        if (UnifiedPush.getSavedDistributor(context) == "io.heckel.ntfy" && push.text("vapid").isNotEmpty()) {
            UnifiedPush.register(context, instance = push.text("instance"), messageForDistributor = "Multica", vapid = push.text("vapid"))
            PushWork.schedule(context)
        }
    }

    fun disablePush() {
        PushWork.stopPush(getApplication())
        readPushStatus()
    }

    fun personName(id: String): String = people.firstOrNull { it.text("id") == id }?.text("name") ?: id

    fun projectName(id: String): String = projects.firstOrNull { it.text("id") == id }?.text("title") ?: id
}
