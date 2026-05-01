package roro.stellar.manager

import android.content.res.Configuration
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import roro.stellar.Stellar
import roro.stellar.manager.domain.apps.AppsViewModel
import roro.stellar.manager.domain.apps.appsViewModel
import roro.stellar.manager.ui.components.AdaptiveLayoutProvider
import roro.stellar.manager.ui.features.apps.AppsScreen
import roro.stellar.manager.ui.features.home.HomeScreen
import roro.stellar.manager.ui.features.home.HomeViewModel
import roro.stellar.manager.ui.features.manager.ManagerActivity
import roro.stellar.manager.ui.features.settings.SettingsScreen
import roro.stellar.manager.ui.features.terminal.TerminalScreen
import roro.stellar.manager.ui.navigation.components.LocalNavigationState
import roro.stellar.manager.ui.navigation.components.LocalTopAppBarState
import roro.stellar.manager.ui.navigation.components.NavigationState
import roro.stellar.manager.ui.navigation.components.StandardBottomNavigation
import roro.stellar.manager.ui.navigation.components.StandardNavigationRail
import roro.stellar.manager.ui.navigation.components.TopAppBarProvider
import roro.stellar.manager.ui.navigation.routes.MainScreen
import roro.stellar.manager.ui.navigation.safePopBackStack
import roro.stellar.manager.ui.theme.StellarTheme
import roro.stellar.manager.ui.theme.ThemePreferences
import roro.stellar.manager.ui.theme.StartPage

class MainActivity : ComponentActivity() {

    private val binderReceivedListener = Stellar.OnBinderReceivedListener {
        checkServerStatus()
        try {
            appsModel.load()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val binderDeadListener = Stellar.OnBinderDeadListener {
        checkServerStatus()
    }

    private val homeModel by viewModels<HomeViewModel>()
    private val appsModel by appsViewModel()
    
    // 权限是否已授予
    private var permissionsGranted = false
    
    // 是否正在等待权限请求结果
    private var isWaitingForPermission = false

    // 权限请求启动器
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        
        if (allGranted) {
            // 权限全部授予，继续加载主界面
            permissionsGranted = true
            isWaitingForPermission = false
            loadMainContent()
        } else {
            // 有权限被拒绝，显示提示并退出
            isWaitingForPermission = false
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        
        // 显示声明对话框
        showZybAdbHelperDialog()
        
        // 检查并请求权限
        checkAndRequestPermissions()
        
        // 先设置一个等待权限的界面
        setContent {
            val themeMode = ThemePreferences.themeMode.value
            StellarTheme(themeMode = themeMode) {
                if (permissionsGranted) {
                    // 权限已授予，显示主界面
                    TopAppBarProvider {
                        MainScreenContent(
                            homeViewModel = homeModel,
                            appsViewModel = appsModel
                        )
                    }
                } else {
                    // 等待权限授予或显示权限请求界面
                    PermissionRequestScreen(
                        onRetry = { checkAndRequestPermissions() },
                        isRequesting = isWaitingForPermission
                    )
                }
            }
        }

        Stellar.addBinderReceivedListenerSticky(binderReceivedListener)
        Stellar.addBinderDeadListener(binderDeadListener)
        
        checkServerStatus()
        
        if (Stellar.pingBinder() && appsModel.stellarApps.value == null) {
            appsModel.load()
        }
    }
    
    /**
     * 检查并请求权限
     */
    private fun checkAndRequestPermissions() {
        if (checkAllPermissionsGranted()) {
            permissionsGranted = true
            // 重新设置界面
            recreate()
        } else {
            isWaitingForPermission = true
            requestStoragePermissions()
        }
    }
    
    /**
     * 检查所有需要的权限是否已授予
     */
    private fun checkAllPermissionsGranted(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> { // Android 14+
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> { // Android 13
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> { // Android 6-12
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            else -> true // Android 6 以下不需要运行时权限
        }
    }
    
    /**
     * 请求存储权限（兼容不同 Android 版本）
     */
    private fun requestStoragePermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> { // Android 14+
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> { // Android 13
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> { // Android 6-12
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        
        // 过滤出未授权的权限
        val notGrantedPermissions = permissionsToRequest.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (notGrantedPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(notGrantedPermissions.toTypedArray())
        } else {
            // 理论上不应该走到这里，因为 checkAllPermissionsGranted 已经检查过了
            permissionsGranted = true
            loadMainContent()
        }
    }
    
    /**
     * 权限被拒绝时显示对话框
     */
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("权限必要")
            .setMessage("应用需要存储权限才能正常运行，请授予权限后重新打开应用。")
            .setPositiveButton("退出应用") { _, _ ->
                finish()
            }
            .setNegativeButton("重新授权") { _, _ ->
                // 重新请求权限
                isWaitingForPermission = true
                requestStoragePermissions()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 加载主界面内容
     */
    private fun loadMainContent() {
        runOnUiThread {
            setContent {
                val themeMode = ThemePreferences.themeMode.value
                StellarTheme(themeMode = themeMode) {
                    TopAppBarProvider {
                        MainScreenContent(
                            homeViewModel = homeModel,
                            appsViewModel = appsModel
                        )
                    }
                }
            }
        }
    }
    
    private fun showZybAdbHelperDialog() {
        AlertDialog.Builder(this)
            .setTitle("声明")
            .setMessage("ZybAdbHelper 由 caofangkuai 开发，基于 Stellar，为 ZybOS 进行了深度定制")
            .setPositiveButton("确定") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("开源地址") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/caofangkuai/ZybAdbHelper/"))
                startActivity(intent)
            }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (permissionsGranted) {
            checkServerStatus()
            if (Stellar.pingBinder()) {
                appsModel.load(true)
            }
        }
    }

    private fun checkServerStatus() {
        homeModel.reload(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Stellar.removeBinderReceivedListener(binderReceivedListener)
        Stellar.removeBinderDeadListener(binderDeadListener)
    }
}

/**
 * 权限请求等待界面
 */
@Composable
fun PermissionRequestScreen(
    onRetry: () -> Unit,
    isRequesting: Boolean
) {
    val context = LocalContext.current
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "需要存储权限",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.size(16.dp))
            
            Text(
                text = "应用需要读取存储设备的权限才能正常运行，请授予权限后继续使用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.size(32.dp))
            
            Button(
                onClick = onRetry,
                enabled = !isRequesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (isRequesting) "正在请求权限..." else "授予权限")
            }
            
            if (isRequesting) {
                Spacer(modifier = Modifier.size(16.dp))
                Text(
                    text = "请在弹出的对话框中授予权限",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenContent(
    homeViewModel: HomeViewModel,
    appsViewModel: AppsViewModel
) {
    val topAppBarState = LocalTopAppBarState.current!!
    val navController = rememberNavController()

    val startPage = remember { ThemePreferences.startPage.value }
    val initialIndex = when (startPage) {
        StartPage.HOME -> 0
        StartPage.APPS -> 1
        StartPage.TERMINAL -> 2
    }
    val startRoute = when (startPage) {
        StartPage.HOME -> MainScreen.Home.route
        StartPage.APPS -> MainScreen.Apps.route
        StartPage.TERMINAL -> MainScreen.Terminal.route
    }

    var selectedIndex by remember { androidx.compose.runtime.mutableIntStateOf(initialIndex) }

    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    val context = navController.context

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    BackHandler {
        if (navController.previousBackStackEntry == null) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000) {
                (context as? ComponentActivity)?.finish()
            } else {
                lastBackPressTime = currentTime
                Toast.makeText(context, context.getString(R.string.press_again_to_exit), Toast.LENGTH_SHORT).show()
            }
        } else {
            navController.safePopBackStack()
        }
    }

    val onNavigationItemClick: (Int) -> Unit = { index ->
        if (selectedIndex != index) {
            selectedIndex = index
            val route = MainScreen.entries[index].route
            navController.navigate(route) {
                popUpTo(0) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
    }

    val navigationState = NavigationState(
        selectedIndex = selectedIndex,
        onItemClick = onNavigationItemClick
    )

    val navHostContent: @Composable (Modifier) -> Unit = { modifier ->
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = modifier,
            enterTransition = { fadeIn(animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) },
            popEnterTransition = { fadeIn(animationSpec = tween(300)) },
            popExitTransition = { fadeOut(animationSpec = tween(300)) }
        ) {
            navigation(
                startDestination = "home",
                route = MainScreen.Home.route
            ) {
                composable("home") {
                    HomeScreen(
                        topAppBarState = topAppBarState,
                        homeViewModel = homeViewModel,
                        onNavigateToStarter = { isRoot, host, port, hasSecureSettings ->
                            context.startActivity(ManagerActivity.createStarterIntent(context, isRoot, host, port, hasSecureSettings))
                        }
                    )
                }
            }

            navigation(
                startDestination = "apps",
                route = MainScreen.Apps.route
            ) {
                composable("apps") {
                    AppsScreen(
                        topAppBarState = topAppBarState,
                        appsViewModel = appsViewModel
                    )
                }
            }

            navigation(
                startDestination = "terminal",
                route = MainScreen.Terminal.route
            ) {
                composable("terminal") {
                    TerminalScreen(
                        topAppBarState = topAppBarState
                    )
                }
            }

            navigation(
                startDestination = "settings",
                route = MainScreen.Settings.route
            ) {
                composable("settings") {
                    SettingsScreen(
                        topAppBarState = topAppBarState,
                        onNavigateToLogs = {
                            context.startActivity(ManagerActivity.createLogsIntent(context))
                        }
                    )
                }
            }
        }
    }

    CompositionLocalProvider(LocalNavigationState provides navigationState) {
        AdaptiveLayoutProvider {
            if (isLandscape) {
                Row(modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                ) {
                    StandardNavigationRail(
                        selectedIndex = selectedIndex,
                        onItemClick = onNavigationItemClick
                    )
                    navHostContent(Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        bottomBar = {
                            StandardBottomNavigation(
                                selectedIndex = selectedIndex,
                                onItemClick = onNavigationItemClick
                            )
                        },
                        contentWindowInsets = WindowInsets.navigationBars
                    ) {
                        navHostContent(Modifier.fillMaxSize().padding(it))
                    }
                }
            }
        }
    }
}