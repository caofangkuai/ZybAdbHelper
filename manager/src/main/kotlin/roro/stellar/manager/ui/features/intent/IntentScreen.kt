package roro.stellar.manager.ui.features.intent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cfks.startanywhere.StartAnyWhere
import kotlinx.coroutines.launch
import roro.stellar.manager.R
import roro.stellar.manager.db.AppDatabase
import roro.stellar.manager.db.IntentEntity
import roro.stellar.manager.ui.components.LocalScreenConfig
import roro.stellar.manager.ui.components.StellarDialog
import roro.stellar.manager.ui.navigation.components.StandardLargeTopAppBar
import roro.stellar.manager.ui.navigation.components.createTopAppBarScrollBehavior
import roro.stellar.manager.ui.theme.AppShape
import roro.stellar.manager.ui.theme.AppSpacing
import roro.stellar.manager.util.Logger.Companion.LOGGER
import java.util.UUID

enum class LaunchMode(val titleRes: Int, val icon: ImageVector, val descriptionRes: Int) {
    NORMAL(R.string.launch_mode_normal, Icons.Outlined.Launch, R.string.launch_mode_normal_desc),
    START_ANYWHERE(R.string.launch_mode_start_anywhere, Icons.Outlined.Shield, R.string.launch_mode_start_anywhere_desc)
}

data class IntentItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val intentUri: String,
    val launchMode: LaunchMode
)

private suspend fun loadIntents(context: Context): List<IntentItem> =
    AppDatabase.get(context).intentDao().getAll().map {
        IntentItem(it.id, it.title, it.intentUri, LaunchMode.valueOf(it.launchMode))
    }

private suspend fun saveIntents(context: Context, intents: List<IntentItem>) {
    val dao = AppDatabase.get(context).intentDao()
    dao.deleteAll()
    dao.insertAll(intents.map { IntentEntity(it.id, it.title, it.intentUri, it.launchMode.name) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntentScreen(
    topAppBarState: TopAppBarState
) {
    val scrollBehavior = createTopAppBarScrollBehavior(topAppBarState)
    val screenConfig = LocalScreenConfig.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingIntent by remember { mutableStateOf<IntentItem?>(null) }
    var intents by remember { mutableStateOf(emptyList<IntentItem>()) }

    LaunchedEffect(Unit) {
        var loadedIntents = loadIntents(context)
        if (loadedIntents.isEmpty()) {
            val defaultIntents = listOf(
                IntentItem(
                    title = "安装证书",
                    intentUri = "intent:#Intent;component=com.android.settings/.ZybSettings;S.:settings:show_fragment=com.android.settings.security.InstallCertificateFromStorage;end",
                    launchMode = LaunchMode.NORMAL
                ),
                IntentItem(
                    title = "无障碍",
                    intentUri = "intent:#Intent;component=com.android.settings/.ZybSettings;S.:settings:show_fragment=com.android.settings.accessibility.AccessibilitySettings;end",
                    launchMode = LaunchMode.NORMAL
                ),
                IntentItem(
                    title = "开发者磁贴配置",
                    intentUri = "intent:#Intent;component=com.android.settings/.ZybSettings;S.:settings:show_fragment=com.android.settings.development.qstile.DevelopmentTileConfigFragment;end",
                    launchMode = LaunchMode.NORMAL
                ),
                IntentItem(
                    title = "ADB无线调试页面",
                    intentUri = "intent:#Intent;component=com.android.settings/.SettingsActivity;S.:settings:show_fragment=com.android.settings.development.WirelessDebuggingFragment;end",
                    launchMode = LaunchMode.START_ANYWHERE
                )
            )
            loadedIntents = defaultIntents
            saveIntents(context, defaultIntents)
        }
        intents = loadedIntents
    }

    val gridColumns = if (screenConfig.isLandscape) 4 else 2

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardLargeTopAppBar(
                title = stringResource(R.string.intent),
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                shape = AppShape.shapes.cardMedium
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_intent))
            }
        }
    ) { paddingValues ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + AppSpacing.topBarContentSpacing,
                bottom = 96.dp,
                start = AppSpacing.screenHorizontalPadding,
                end = AppSpacing.screenHorizontalPadding
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(intents, key = { it.id }) { intent ->
                IntentCard(
                    item = intent,
                    onLaunch = { launchIntent(context, intent) },
                    onEdit = { editingIntent = intent },
                    onDelete = {
                        intents = intents.filter { it.id != intent.id }
                        scope.launch { saveIntents(context, intents) }
                    }
                )
            }
            item(key = "add_card") {
                AddIntentCard(onClick = { showCreateDialog = true })
            }
        }
    }

    if (showCreateDialog) {
        CreateIntentDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { title, intentUri, launchMode ->
                val newIntent = IntentItem(
                    title = title,
                    intentUri = intentUri,
                    launchMode = launchMode
                )
                intents = intents + newIntent
                scope.launch { saveIntents(context, intents) }
                showCreateDialog = false
            }
        )
    }

    editingIntent?.let { item ->
        EditIntentDialog(
            item = item,
            onDismiss = { editingIntent = null },
            onConfirm = { title, intentUri ->
                intents = intents.map {
                    if (it.id == item.id) it.copy(title = title, intentUri = intentUri) else it
                }
                scope.launch { saveIntents(context, intents) }
                editingIntent = null
            }
        )
    }
}

private fun launchIntent(context: Context, item: IntentItem) {
    try {
        val intent = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME)
        when (item.launchMode) {
            LaunchMode.NORMAL -> context.startActivity(intent)
            LaunchMode.START_ANYWHERE -> StartAnyWhere.pullSpecialActivity(context, intent)
        }
    } catch (e: Exception) {
        LOGGER.e("启动Intent失败: ${item.title}", e)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IntentCard(
    item: IntentItem,
    onLaunch: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.shapes.cardLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(AppShape.shapes.iconSmall)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.launchMode.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .basicMarquee()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp),
                shape = AppShape.shapes.cardMedium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    text = item.intentUri,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalIconButton(
                    onClick = onLaunch,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    shape = AppShape.shapes.buttonMedium,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.launch),
                        modifier = Modifier.size(18.dp)
                    )
                }

                FilledTonalIconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(36.dp),
                    shape = AppShape.shapes.buttonMedium
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.edit),
                        modifier = Modifier.size(18.dp)
                    )
                }

                FilledTonalIconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp),
                    shape = AppShape.shapes.buttonMedium,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AddIntentCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.shapes.cardLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(AppShape.shapes.iconSmall)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = stringResource(R.string.add_intent),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp),
                shape = AppShape.shapes.cardMedium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {}

            Spacer(modifier = Modifier.height(12.dp))

            Box(modifier = Modifier.height(36.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateIntentDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, LaunchMode) -> Unit
) {
    var selectedMode by remember { mutableStateOf(LaunchMode.NORMAL) }
    var title by remember { mutableStateOf("") }
    var intentUri by remember { mutableStateOf("") }

    StellarDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.create_intent),
        confirmText = stringResource(R.string.save),
        confirmEnabled = intentUri.isNotBlank(),
        onConfirm = { onConfirm(title.ifBlank { "Intent" }, intentUri, selectedMode) },
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.dialogContentSpacing)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.title_label)) },
                placeholder = { Text(stringResource(R.string.intent_name)) },
                shape = AppShape.shapes.inputField,
                singleLine = true
            )

            OutlinedTextField(
                value = intentUri,
                onValueChange = { intentUri = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.intent_uri_label)) },
                placeholder = { Text("intent:#Intent;component=...;end") },
                shape = AppShape.shapes.inputField,
                singleLine = false,
                maxLines = 3,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            )

            Text(
                text = stringResource(R.string.select_launch_mode),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LaunchMode.entries.forEach { mode ->
                    ModeSelectionItem(
                        mode = mode,
                        selected = selectedMode == mode,
                        enabled = true,
                        onClick = { selectedMode = mode }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSelectionItem(
    mode: LaunchMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.shapes.cardMedium,
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = mode.icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(mode.titleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = contentColor
                )
                Text(
                    text = stringResource(mode.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }
            if (selected) {
                RadioButton(selected = true, onClick = null)
            }
        }
    }
}

@Composable
private fun EditIntentDialog(
    item: IntentItem,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(item.title) }
    var intentUri by remember { mutableStateOf(item.intentUri) }

    StellarDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.edit_intent),
        confirmText = stringResource(R.string.save),
        confirmEnabled = intentUri.isNotBlank(),
        onConfirm = { onConfirm(title, intentUri) },
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.dialogContentSpacing)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = AppShape.shapes.dialogContent,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = item.launchMode.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(item.launchMode.titleRes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.title_label)) },
                shape = AppShape.shapes.inputField,
                singleLine = true
            )

            OutlinedTextField(
                value = intentUri,
                onValueChange = { intentUri = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.intent_uri_label)) },
                shape = AppShape.shapes.inputField,
                singleLine = false,
                maxLines = 3,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            )
        }
    }
}