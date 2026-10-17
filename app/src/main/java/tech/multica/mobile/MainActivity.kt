package tech.multica.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        consumeIntent(intent)
        setContent { MulticaScreen(model) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(source: Intent) {
        val payload = source.getStringExtra("payload") ?: return
        val epoch = source.getStringExtra("epoch") ?: return
        model.receiveTap(payload, epoch)
        source.removeExtra("payload")
        source.removeExtra("epoch")
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MulticaScreen(model: AppModel) {
    val context = LocalContext.current
    var theme by rememberSaveable { mutableStateOf(context.getSharedPreferences("appearance", 0).getString("theme", "system") ?: "system") }
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    MaterialExpressiveTheme(colorScheme = if (dark) darkColorScheme() else expressiveLightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner) {
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.readPushStatus() }
                owner.lifecycle.addObserver(observer)
                onDispose { owner.lifecycle.removeObserver(observer) }
            }
            val taskState = rememberLazyListState()
            val inboxState = rememberLazyListState()
            val detailState = rememberLazyListState()
            BackHandler(enabled = model.screen != "home" && model.account != null) { model.back() }
            if (model.account == null) {
                LoginScreen(model)
            } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    if (model.screen ==
                                        "home"
                                    ) {
                                        model.workspaces.firstOrNull { it.text("id") == model.workspace }?.text("name")
                                            ?: stringResource(R.string.workspaces)
                                    } else {
                                        stringResource(screenTitle(model.screen))
                                    },
                                )
                            },
                            navigationIcon = {
                                if (model.screen !=
                                    "home"
                                ) {
                                    TextButton(onClick = model::back) { Text(stringResource(R.string.back)) }
                                }
                            },
                            actions = {
                                if (model.screen ==
                                    "home"
                                ) {
                                    TextButton(onClick = { model.screen = "settings" }) { Text(stringResource(R.string.settings)) }
                                }
                            },
                        )
                    },
                    bottomBar = {
                        if (model.screen == "home" && model.workspace.isNotEmpty()) {
                            NavigationBar {
                                NavigationBarItem(selected = model.tab == "tasks", onClick = {
                                    model.chooseTab("tasks")
                                }, icon = { Text("☷") }, label = { Text(stringResource(R.string.tasks)) })
                                NavigationBarItem(selected = model.tab == "inbox", onClick = {
                                    model.chooseTab("inbox")
                                }, icon = { Text("▣") }, label = { Text(stringResource(R.string.inbox)) })
                            }
                        }
                    },
                    floatingActionButton = {
                        if (model.screen == "home" &&
                            model.workspace.isNotEmpty()
                        ) {
                            ExtendedFloatingActionButton(onClick = {
                                model.screen = "create"
                            }, text = { Text(stringResource(R.string.create_task)) }, icon = { Text("+") })
                        }
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).imePadding().fillMaxSize()) {
                        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        ErrorMessage(model)
                        if (model.workspace.isEmpty() || model.screen == "workspaces") {
                            WorkspaceScreen(model)
                        } else {
                            when (model.screen) {
                                "settings" ->
                                    SettingsScreen(model, theme, {
                                        theme = it
                                        context
                                            .getSharedPreferences("appearance", 0)
                                            .edit()
                                            .putString("theme", it)
                                            .apply()
                                    })
                                "create" -> CreateScreen(model)
                                "detail" -> DetailScreen(model, detailState)
                                "event" -> EventScreen(model)
                                else -> if (model.tab == "tasks") TaskScreen(model, taskState) else InboxScreen(model, inboxState)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun screenTitle(screen: String): Int =
    when (screen) {
        "settings" -> R.string.settings
        "create" -> R.string.create_task
        "detail" -> R.string.task
        "event" -> R.string.event
        else -> R.string.workspaces
    }

@Composable
fun ErrorMessage(model: AppModel) {
    if (model.error == 0) return
    val resource =
        when (model.error) {
            401 -> R.string.error_auth
            403 -> R.string.error_access
            404 -> R.string.error_missing
            429 -> R.string.error_rate
            409 -> R.string.error_conflict
            402 -> R.string.error_limit
            400 -> R.string.error_validation
            -1 -> R.string.error_offline
            -3 -> R.string.error_storage
            -4 -> R.string.error_pat
            -5 -> R.string.error_https
            -6 -> R.string.error_title
            else -> R.string.error_server
        }
    ElevatedCard(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(resource), color = MaterialTheme.colorScheme.error)
            if (model.retryAt >
                System.currentTimeMillis()
            ) {
                Text(
                    stringResource(
                        R.string.retry_after,
                        java.text.DateFormat
                            .getTimeInstance()
                            .format(java.util.Date(model.retryAt)),
                    ),
                )
            }
            if (model.uncertain) Text(stringResource(R.string.uncertain_write))
            TextButton(onClick = model::clearError) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

@Composable
fun LoginScreen(model: AppModel) {
    var server by rememberSaveable { mutableStateOf(DEFAULT_SERVER) }
    // PAT is held only in memory until verified
    // It is never written into a saved-state Bundle.
    val token = model.loginToken
    var visible by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 64.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Multica", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.login_intro), style = MaterialTheme.typography.titleMedium)
        ErrorMessage(model)
        OutlinedTextField(
            server,
            {
                server = it
            },
            label = {
                Text(stringResource(R.string.server))
            },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                ),
            modifier = Modifier.fillMaxWidth(),
            enabled = !model.busy,
        )
        OutlinedTextField(
            token,
            {
                model.loginToken = it
            },
            label = {
                Text(stringResource(R.string.pat))
            },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                ),
            trailingIcon = {
                TextButton(onClick = {
                    visible =
                        !visible
                }) { Text(stringResource(if (visible) R.string.hide else R.string.show)) }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !model.busy,
        )
        Button(onClick = {
            model.login(server, token)
        }, enabled = !model.busy && token.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.sign_in))
        }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = model::retrySession, enabled = !model.busy) { Text(stringResource(R.string.retry_saved_session)) }
    }
}

@Composable
fun WorkspaceScreen(model: AppModel) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.choose_workspace), style = MaterialTheme.typography.headlineSmall) }
        if (model.workspaces.isEmpty()) {
            item {
                Text(stringResource(R.string.no_workspaces))
                TextButton(onClick = model::retrySession) { Text(stringResource(R.string.refresh)) }
                TextButton(onClick = model::logout) { Text(stringResource(R.string.logout)) }
            }
        }
        items(model.workspaces, key = {
            it.text("id")
        }) { item ->
            Button(onClick = {
                model.selectWorkspace(item.text("id"))
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(item.text("name")) }
        }
    }
}

@Composable
fun Picker(
    label: String,
    selected: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = {
            expanded = true
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("$label: ${choices.firstOrNull { it.first == selected }?.second ?: selected}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    expanded = false
                    onSelect(id)
                })
            }
        }
    }
}

@Composable
fun TaskScreen(
    model: AppModel,
    state: LazyListState,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = state, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Picker(
                stringResource(R.string.status),
                model.statusFilter,
                listOf("" to stringResource(R.string.all)) +
                    model.statuses.map { it.text("key").ifEmpty { it.text("status") }.ifEmpty { it.text("id") } to it.text("name") },
                !model.busy,
            ) { model.filterTasks(status = it) }
            Picker(
                stringResource(R.string.assignee),
                model.assigneeFilter,
                listOf("" to stringResource(R.string.all)) +
                    model.people.map { it.text("id") to "${it.text("name")} · ${personType(it.text("type"))}" },
                !model.busy,
            ) { model.filterTasks(person = it) }
            TextButton(onClick = model::refresh, enabled = !model.busy) { Text(stringResource(R.string.refresh)) }
        }
        if (model.tasks.isEmpty() && !model.busy) item { EmptyMessage() }
        items(model.tasks, key = { it.text("id") }) { item ->
            ElevatedCard(onClick = { model.openIssue(item.text("id")) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.text("identifier"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(item.text("title"), style = MaterialTheme.typography.titleLarge)
                    Text("${item.text("status_name").ifEmpty { item.text("status") }} · ${priorityLabel(item.text("priority"))}")
                    if (item
                            .text(
                                "assignee_id",
                            ).isNotEmpty()
                    ) {
                        Text(model.personName(item.text("assignee_id")), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (model.hasMoreTasks()) {
            item {
                TextButton(onClick = model::moreTasks, enabled = !model.busy) { Text(stringResource(R.string.load_more)) }
            }
        }
        item { Text("", Modifier.padding(bottom = 88.dp)) }
    }
}

@Composable
fun InboxScreen(
    model: AppModel,
    state: LazyListState,
) {
    val visible = model.inbox.filter { !model.unread || !it.optBoolean("read") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = state, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Picker(
                stringResource(R.string.inbox),
                model.archive.toString(),
                listOf(
                    "false" to stringResource(R.string.current),
                    "true" to stringResource(R.string.archive),
                ),
                !model.busy,
            ) { model.inboxMode(it == "true", model.unread) }
            Picker(
                stringResource(R.string.show),
                model.unread.toString(),
                listOf(
                    "false" to stringResource(R.string.all),
                    "true" to stringResource(R.string.unread),
                ),
                !model.busy,
            ) { model.inboxMode(model.archive, it == "true") }
            if (model.archive) Text(stringResource(R.string.grouped_archive), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = model::refresh, enabled = !model.busy) { Text(stringResource(R.string.refresh)) }
        }
        if (visible.isEmpty() && !model.busy) item { EmptyMessage() }
        items(visible.take(model.inboxLimit), key = { it.text("id") }) { item ->
            ElevatedCard(onClick = { model.openEvent(item) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        eventType(item.text("type")),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        item.text("title"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (item.optBoolean("read")) FontWeight.Normal else FontWeight.Bold,
                    )
                    if (item.text("body").isNotEmpty()) Text(item.text("body"))
                    Text(item.text("created_at"), style = MaterialTheme.typography.labelSmall)
                    if (!item.optBoolean("read")) {
                        TextButton(onClick = {
                            model.markRead(item.text("id"))
                        }, enabled = !model.busy) { Text(stringResource(R.string.mark_read)) }
                    }
                }
            }
        }
        if (visible.size > model.inboxLimit ||
            model.archiveMore
        ) {
            item { TextButton(onClick = model::moreInbox, enabled = !model.busy) { Text(stringResource(R.string.load_more)) } }
        }
        item { Text("", Modifier.padding(bottom = 88.dp)) }
    }
}

@Composable
fun CreateScreen(model: AppModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(model.title, {
            model.title = it
        }, label = { Text(stringResource(R.string.title)) }, modifier = Modifier.fillMaxWidth(), enabled = !model.busy)
        OutlinedTextField(model.description, {
            model.description = it
        }, label = { Text(stringResource(R.string.description)) }, minLines = 4, modifier = Modifier.fillMaxWidth(), enabled = !model.busy)
        Picker(
            stringResource(R.string.project),
            model.project,
            listOf("" to stringResource(R.string.none)) + model.projects.map { it.text("id") to it.text("title") },
            !model.busy,
        ) {
            model.project =
                it
        }
        Picker(
            stringResource(R.string.priority),
            model.priority,
            listOf("none", "low", "medium", "high", "urgent").map {
                it to
                    priorityLabel(it)
            },
            !model.busy,
        ) { model.priority = it }
        Picker(
            stringResource(R.string.assignee),
            model.assignee,
            listOf("" to stringResource(R.string.unassigned)) +
                model.people.map { it.text("id") to "${it.text("name")} · ${personType(it.text("type"))}" },
            !model.busy,
        ) {
            model.assignee =
                it
        }
        if (model.people.any { it.text("id") == model.assignee && it.text("type") != "member" }) Text(stringResource(R.string.agent_effect))
        Button(
            onClick = model::create,
            enabled = !model.busy && model.title.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(stringResource(R.string.create_task))
        }
    }
}

@Composable
fun DetailScreen(
    model: AppModel,
    state: LazyListState,
) {
    val item = model.issue ?: return
    val roots = model.comments.filter { it.text("parent_id").isEmpty() }
    LaunchedEffect(model.focusComment, model.comments.size) {
        if (model.focusComment.isNotEmpty()) {
            val index = model.comments.indexOfFirst { it.text("id") == model.focusComment }
            if (index >= 0) state.animateScrollToItem((index + 2).coerceAtMost(state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), state = state, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.text("identifier"), color = MaterialTheme.colorScheme.primary)
                Text(item.text("title"), style = MaterialTheme.typography.headlineMedium)
                Text(item.text("description"))
                Text("${stringResource(R.string.status)}: ${item.text("status_name").ifEmpty { item.text("status") }}")
                Text("${stringResource(R.string.priority)}: ${priorityLabel(item.text("priority"))}")
                Text(
                    "${stringResource(
                        R.string.project,
                    )}: ${model.projectName(item.text("project_id")).ifEmpty { stringResource(R.string.none) }}",
                )
                Text(
                    "${stringResource(
                        R.string.assignee,
                    )}: ${model.personName(item.text("assignee_id")).ifEmpty { stringResource(R.string.unassigned) }}",
                )
                TextButton(onClick = model::refreshDetail, enabled = !model.busy) { Text(stringResource(R.string.refresh)) }
                Text(stringResource(R.string.discussion), style = MaterialTheme.typography.headlineSmall)
            }
        }
        item {
            if (model.nextBefore.isNotEmpty()) {
                TextButton(onClick = model::moreComments, enabled = !model.busy) {
                    Text(stringResource(R.string.older_threads))
                }
            }
            if (model.commentsTruncated) Text(stringResource(R.string.truncated))
            if (model.focusComment.isNotEmpty()) Text(stringResource(R.string.comment_anchor, model.focusComment))
        }
        if (model.comments.isEmpty() && !model.busy) item { EmptyMessage() }
        items(model.comments, key = { it.text("id") }) { comment ->
            OutlinedCard(
                Modifier.fillMaxWidth().padding(start = if (comment.text("parent_id").isEmpty()) 0.dp else 16.dp),
                colors =
                    CardDefaults.outlinedCardColors(
                        containerColor =
                            if (comment.text("id") ==
                                model.focusComment
                            ) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                    ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${model.personName(comment.text("author_id"))} · ${comment.text("created_at")}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (comment
                            .text(
                                "parent_id",
                            ).isNotEmpty()
                    ) {
                        Text(
                            stringResource(R.string.reply_to, comment.text("parent_id")),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(if (comment.text("deleted_at").isNotEmpty()) stringResource(R.string.deleted_comment) else comment.text("content"))
                    TextButton(
                        onClick = { model.replyParent = comment.text("id") },
                        enabled = !model.busy,
                    ) { Text(stringResource(R.string.reply)) }
                    if (roots.any { it.text("id") == comment.text("id") } &&
                        model.threadHasMore(comment.text("id"))
                    ) {
                        TextButton(
                            onClick = { model.moreThread(comment.text("id")) },
                            enabled = !model.busy,
                        ) { Text(stringResource(R.string.older_replies)) }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (model.replyParent.isNotEmpty()) {
                    Text(stringResource(R.string.reply_to, model.replyParent))
                    TextButton(onClick = { model.replyParent = "" }) { Text(stringResource(R.string.cancel_reply)) }
                }
                OutlinedTextField(model.replyText, {
                    model.replyText = it
                }, label = {
                    Text(
                        stringResource(R.string.reply),
                    )
                }, minLines = 3, modifier = Modifier.fillMaxWidth(), enabled = !model.busy)
                Button(
                    onClick = model::sendReply,
                    enabled = !model.busy && model.replyText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(R.string.send))
                }
            }
        }
    }
}

@Composable
fun EventScreen(model: AppModel) {
    val item = model.event ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(eventType(item.text("type")), color = MaterialTheme.colorScheme.primary)
        Text(item.text("title"), style = MaterialTheme.typography.headlineSmall)
        Text(item.text("body"))
        Text(item.text("created_at"))
        Text(item.text("severity"))
        if (item.text("issue_id").isNotEmpty()) {
            Button(onClick = model::openEventTarget, enabled = !model.busy) {
                Text(stringResource(R.string.open_task))
            }
        }
        if (!item.optBoolean("read")) {
            TextButton(onClick = {
                model.openEvent(item)
            }, enabled = !model.busy) { Text(stringResource(R.string.mark_read)) }
        }
        item.optJSONObject("details")?.let { Text(it.toString(2), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun SettingsScreen(
    model: AppModel,
    theme: String,
    onTheme: (String) -> Unit,
) {
    val context = LocalContext.current
    val permission =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> if (granted) model.enablePush() else model.readPushStatus() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(model.account?.server.orEmpty())
        Button(onClick = { model.screen = "workspaces" }) { Text(stringResource(R.string.choose_workspace)) }
        Picker(
            stringResource(R.string.theme),
            theme,
            listOf(
                "system" to stringResource(R.string.system),
                "light" to stringResource(R.string.light),
                "dark" to stringResource(R.string.dark),
            ),
            onSelect = onTheme,
        )
        Text(stringResource(R.string.push), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.ntfy_explanation))
        Text(pushStatusLabel(model.pushStatus))
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) Text(stringResource(R.string.notifications_disabled))
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= 33 &&
                !NotificationManagerCompat.from(context).areNotificationsEnabled()
            ) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                model.enablePush()
            }
        }, enabled = !model.busy) { Text(stringResource(R.string.enable_push)) }
        OutlinedButton(onClick = model::disablePush) { Text(stringResource(R.string.disable_push)) }
        TextButton(onClick = { model.readPushStatus() }) { Text(stringResource(R.string.refresh)) }
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://ntfy.sh/docs/install/")))
        }) { Text(stringResource(R.string.install_ntfy)) }
        TextButton(onClick = {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                ).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }) { Text(stringResource(R.string.notification_settings)) }
        HorizontalDivider()
        Button(onClick = model::logout) { Text(stringResource(R.string.logout)) }
        Text(stringResource(R.string.change_server))
    }
}

@Composable fun EmptyMessage() {
    Text(stringResource(R.string.empty), Modifier.padding(vertical = 32.dp), style = MaterialTheme.typography.bodyLarge)
}

@Composable fun priorityLabel(value: String): String =
    stringResource(
        when (value) {
            "urgent" -> R.string.urgent
            "high" -> R.string.high
            "medium" -> R.string.medium
            "low" -> R.string.low
            "none" -> R.string.none
            else -> R.string.unknown
        },
    ) + if (value !in listOf("urgent", "high", "medium", "low", "none")) " ($value)" else ""

@Composable fun personType(value: String): String =
    stringResource(
        when (value) {
            "member" -> R.string.person
            "agent" -> R.string.agent
            "squad" -> R.string.squad
            else -> R.string.unknown
        },
    )

@Composable fun eventType(value: String): String =
    when (value) {
        "new_comment" -> stringResource(R.string.new_comment)
        "issue_assigned" -> stringResource(R.string.issue_assigned)
        "mention" -> stringResource(R.string.mention)
        else -> stringResource(R.string.event) + " · " + value
    }

@Composable fun pushStatusLabel(value: String): String =
    stringResource(
        when (value) {
            "connected" -> R.string.push_connected
            "registering" -> R.string.push_registering
            "ntfy_required" -> R.string.push_ntfy
            "server_unavailable" -> R.string.push_server
            "off" -> R.string.push_off
            else -> R.string.push_error
        },
    )
