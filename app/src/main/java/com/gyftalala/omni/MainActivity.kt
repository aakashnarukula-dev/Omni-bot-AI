package com.gyftalala.omni

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.app.ActivityOptionsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gyftalala.omni.sharing.WalletShares
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.gyftalala.omni.data.Attachment
import com.gyftalala.omni.data.Category
import com.gyftalala.omni.security.UnlockSession
import com.gyftalala.omni.ui.*
import com.gyftalala.omni.cloud.CloudServices
import java.io.File

class MainActivity : FragmentActivity() {
    companion object { private var captureCacheChecked = false }
    private val model: OmniViewModel by viewModels()
    private val session = UnlockSession()
    private var unlocked by mutableStateOf(false)
    private var authError by mutableStateOf<String?>(null)
    private var promptActive = false
    private var biometricPrompt: BiometricPrompt? = null
    private var googleSignedIn by mutableStateOf(false)
    private var signingIn by mutableStateOf(false)
    private var signInError by mutableStateOf<String?>(null)
    private var discoverAfterSignIn = false
    private var permissionsReady by mutableStateOf(false)
    private var requestingInitialPermissions by mutableStateOf(false)
    private val onboardingPreferences get() = getSharedPreferences("omni-onboarding", MODE_PRIVATE)
    private val initialPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requestInitialAlarms()
    }
    private val initialAlarmSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finishPermissions() }
    private fun finishPermissions() {
        onboardingPreferences.edit().putBoolean("permissions_seen", true).apply()
        requestingInitialPermissions = false; permissionsReady = true
        if (googleSignedIn) authenticate()
    }
    private fun requestInitialAlarms() {
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()) {
            try { initialAlarmSettings.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { finishPermissions() }
        } else finishPermissions()
    }
    private fun requestInitialPermissions() {
        if (requestingInitialPermissions) return
        requestingInitialPermissions = true
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        try { if (permissions.isEmpty()) requestInitialAlarms() else initialPermissions.launch(permissions.toTypedArray()) }
        catch (_: Exception) { finishPermissions() }
    }
    private val accountListener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { syncGoogleAccount() }
    private var selectedId by mutableStateOf<String?>(null)
    private var pendingFiles: List<Uri> = emptyList()
    private var pendingText = ""
    private var importCategory: Category? = null
    private var appendTo: String? = null
    private var capture: Uri? = null
    private var export: Attachment? = null
    private var cardExport: com.gyftalala.omni.data.Memory? = null
    private var scanningCard by mutableStateOf(false)
    private var cameraAllowed by mutableStateOf(false)
    private var pendingNormalCamera = false
    private var backupOpen by mutableStateOf(false)
    private var cloudBackupOpen by mutableStateOf(false)
    private val googleSignIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        session.externalResult()
        signingIn = false
        signInError = result.data?.getStringExtra(com.gyftalala.omni.cloud.GoogleSignInActivity.ERROR)
        syncGoogleAccount()
        if (result.resultCode == RESULT_OK && googleSignedIn) {
            signInError = null
            discoverAfterSignIn = true
            if (session.unlocked) { discoverAfterSignIn = false; model.cloudBackups.signedIn() }
            else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) authenticate()
        }
    }
    private var pendingAccountBackup: Uri? = null
    private var pendingPhoneBackups: List<Uri> = emptyList()
    private fun keepBackupAccess(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    private val choosePhoneBackups = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        session.externalResult()
        uris.forEach(::keepBackupAccess)
        pendingPhoneBackups = uris
        drainAccountBackup()
    }
    private val saveAccountBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        session.externalResult()
        uri?.let { keepBackupAccess(it); pendingAccountBackup = it; drainAccountBackup() }
    }
    private fun drainAccountBackup() {
        if (!session.unlocked) return
        pendingAccountBackup?.let { pendingAccountBackup = null; cloudBackupOpen = true; model.cloudBackups.exportFile(it) }
        if (pendingPhoneBackups.isNotEmpty()) {
            val files = pendingPhoneBackups; pendingPhoneBackups = emptyList(); cloudBackupOpen = true
            model.cloudBackups.chooseMobile(files)
        }
    }
    private var pendingBackup: Pair<Boolean, Uri>? = null
    private val clockActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Clock apps do not return a reliable alarm-created result.
        session.externalResult()
    }
    private fun openClock(launch: com.gyftalala.omni.alarms.ClockLaunch) {
        if (!session.unlocked) return
        try {
            val intent = launch.alarm.intent()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(window.decorView.windowToken, 0)
            currentFocus?.clearFocus()
            session.beginExternalFlow()
            clockActivity.launch(intent)
            model.clockResult(launch)
        } catch (_: android.content.ActivityNotFoundException) {
            session.externalResult(); model.clockResult(launch, "No compatible Clock app is available. Install or enable one, then try again.")
        } catch (error: IllegalArgumentException) {
            session.externalResult(); model.clockResult(launch, error.message ?: "Send the alarm time again.")
        } catch (_: Exception) {
            session.externalResult(); model.clockResult(launch, "No alarm was confirmed. Open your Clock app to set it.")
        }
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        session.externalResult()
        if (uri != null) { pendingBackup = true to uri; drainBackup() }
    }
    private val restoreBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        session.externalResult()
        if (uri != null) { pendingBackup = false to uri; drainBackup() }
    }
    private fun drainBackup() {
        if (!session.unlocked) return
        val selection = pendingBackup ?: return
        pendingBackup = null; backupOpen = true
        if (selection.first) model.backups.chooseCreate(selection.second) else model.backups.chooseRestore(selection.second)
    }
    private var micAllowed by mutableStateOf(false)
    private var pendingMicrophone: ((Boolean) -> Unit)? = null
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        session.externalResult(); micAllowed = granted
        val callback = pendingMicrophone; pendingMicrophone = null
        window.decorView.post { if (session.unlocked) callback?.invoke(granted) }
    }
    private fun requestMicrophone(callback: (Boolean) -> Unit) {
        if (!session.unlocked) return
        pendingMicrophone = callback
        launchExternal("Could not request microphone access.") { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    private val changed = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (unlocked) model.refresh() }
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { session.lock(); unlocked = false; pendingMicrophone = null; model.backups.reset(); model.cloudBackups.cancel(); model.clearClockLaunch() }
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        session.externalResult()
        pendingFiles = uris
        if (uris.isEmpty()) { importCategory = null; appendTo = null }
        uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        drain()
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        session.externalResult()
        pendingFiles = if (success) listOfNotNull(capture) else emptyList()
        if (!success) {
            capture?.lastPathSegment?.let { File(cacheDir, "captures/$it").delete() }
            importCategory = null; appendTo = null; capture = null
        }
        drain()
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        session.externalResult()
        val attachment = export
        export = null
        if (uri != null && attachment != null) exportTo(uri) { model.vault.export(attachment, it) }
    }
    private val exportCard = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        session.externalResult()
        val memory = cardExport
        cardExport = null
        if (uri != null && memory != null) exportTo(uri) { CardRenderer.render(memory, it) }
    }
    private var preparingShare = false
    private val cardShare = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        session.externalResult()
    }
    private fun shareCard(memory: com.gyftalala.omni.data.Memory) {
        if (!session.unlocked || preparingShare) return
        preparingShare = true
        lifecycleScope.launch {
            var uri: Uri? = null
            try {
                uri = withContext(Dispatchers.IO) { WalletShares.prepare(this@MainActivity, memory, model.vault) }
                if (!session.unlocked || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    WalletShares.discard(uri); return@launch
                }
                launchExternal("Could not open sharing.") {
                    cardShare.launch(Intent.createChooser(WalletShares.intent(this@MainActivity, uri), "Share card"))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                uri?.let(WalletShares::discard); throw cancelled
            } catch (_: Exception) {
                uri?.let(WalletShares::discard); model.error("Could not prepare this card for sharing. Try again.")
            } finally { preparingShare = false }
        }
    }
    private val permissionSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionReturned()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionReturned()
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        session.externalResult()
        cameraAllowed = granted
        if (!granted) {
            pendingNormalCamera = false
            if (!scanningCard) model.error("Camera access is needed. Enable Camera in Android app permissions.")
        } else window.decorView.post {
            // Run after onResume has validated the permission-return session.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) drainCamera()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        signingIn = savedInstanceState?.getBoolean("signingIn") ?: false
        syncGoogleAccount()
        if (CloudServices.configured) CloudServices.get(this).auth.addAuthStateListener(accountListener)
        permissionsReady = onboardingPreferences.getBoolean("permissions_seen", false) || googleSignedIn
        requestingInitialPermissions = savedInstanceState?.getBoolean("requestingInitialPermissions") ?: false
        if (permissionsReady) onboardingPreferences.edit().putBoolean("permissions_seen", true).apply()
        if (!captureCacheChecked) {
            captureCacheChecked = true
            // Abandoned captures from a terminated process have no surviving review session.
            File(cacheDir, "captures").listFiles()?.filter { it.isFile }?.forEach(File::delete)
        }
        if (!BuildConfig.ALLOW_TEST_SCREENSHOTS) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        ContextCompat.registerReceiver(this, changed, IntentFilter("com.gyftalala.omni.CHANGED"), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        readIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collect { state ->
                    state.clockLaunch?.let { request ->
                        if (session.unlocked) model.takeClockLaunch(request.messageId)?.let(::openClock)
                    }
                }
            }
        }
        setContent {
            OmniTheme {
                val unlockedUiState = rememberSaveableStateHolder()
                val state by model.state.collectAsStateWithLifecycle()
                if (!permissionsReady) FirstPermissionsScreen(::requestInitialPermissions, ::finishPermissions, !requestingInitialPermissions)
                else if (!googleSignedIn) GoogleWelcomeScreen(
                    signIn = ::startGoogleSignIn, enabled = !signingIn, error = signInError)
                else if (!unlocked) UnlockScreen(authError, ::unlockOrSetup)
                else {
                    val cloudState by model.cloudBackups.state.collectAsStateWithLifecycle()
                    // A discovery prompt opens a flow. Later password/preview state changes must
                    // not dismiss that flow; only the user's Back/Done navigation closes it.
                    LaunchedEffect(cloudState.prompt) { if (cloudState.prompt) cloudBackupOpen = true }
                    LaunchedEffect(cloudState.enterApp) {
                        if (cloudState.enterApp) { cloudBackupOpen = false; model.cloudBackups.enteredApp() }
                    }
                    if (backupOpen) {
                        val backupState by model.backups.state.collectAsStateWithLifecycle()
                        BackupScreen(backupState,
                            { launchExternal { createBackup.launch("Omni-${java.time.LocalDate.now()}.omnibak") } },
                            { launchExternal { restoreBackup.launch(arrayOf("*/*")) } },
                            model.backups::create, model.backups::inspect, model.backups::restore, model.backups::reset,
                            { model.backups.reset(); backupOpen = false }, allowCreate = false)
                    }
                    else if (cloudBackupOpen || cloudState.prompt || (cloudState.configured && (!cloudState.ready || cloudState.onboarding))) {
                        CloudBackupScreen(cloudState, model.cloudBackups,
                            ::startGoogleSignIn,
                            { launchExternal { choosePhoneBackups.launch(arrayOf("*/*")) } },
                            { launchExternal { saveAccountBackup.launch("Omni-${java.time.LocalDate.now()}.omnibak") } },
                            { model.cloudBackups.dismissPrompt(); cloudBackupOpen = true; backupOpen = true },
                            { model.cloudBackups.dismissPrompt(); model.cloudBackups.cancel(); cloudBackupOpen = false })
                    }
                    else unlockedUiState.SaveableStateProvider("omni") { OmniApp(state, model, selectedId, { selectedId = it }, ::pick,
                        { attachment -> export = attachment; launchExternal { exportFile.launch(attachment.name) } },
                        { memory -> cardExport = memory; launchExternal { exportCard.launch("${memory.title}.png") } },
                        { session.lock(); unlocked = false; authenticate() }, ::openPermissionScreen, ::shareCard,
                        scannerOpen = scanningCard, dismissScanner = ::closeScanner,
                        scanner = { close -> CardScannerScreen(cameraAllowed,
                            appendTo?.takeIf { scanningCard }?.let { id -> state.memories.firstOrNull { it.id == id }?.card },
                            ::requestCamera, ::openCameraSettings, close, ::saveScan,
                            initialIdentity = appendTo?.takeIf { scanningCard }?.let { id -> state.memories.firstOrNull { it.id == id }?.identity },
                            embedded = true) }, openBackup = { cloudBackupOpen = true; model.cloudBackups.refresh() }, requestMicrophone = ::requestMicrophone, logOut = { model.cloudBackups.signOut() }) }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        syncGoogleAccount()
        session.resumed(getSystemService(KeyguardManager::class.java).isDeviceLocked)
        unlocked = session.unlocked
        cameraAllowed = hasCameraPermission()
        micAllowed = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!permissionsReady || !googleSignedIn || signingIn) return
        if (!unlocked) authenticate() else { model.refresh(); model.cloudBackups.refresh(); drain(); drainCamera(); drainBackup(); drainAccountBackup() }
    }
    override fun onStop() {
        super.onStop()
        session.stopped()
        unlocked = session.unlocked
        if (!unlocked) { pendingMicrophone = null; model.backups.reset(); model.cloudBackups.cancel(); model.clearClockLaunch() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("signingIn", signingIn)
        outState.putBoolean("requestingInitialPermissions", requestingInitialPermissions)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (CloudServices.configured) CloudServices.get(this).auth.removeAuthStateListener(accountListener)
        if (!isChangingConfigurations) biometricPrompt?.cancelAuthentication()
        unregisterReceiver(changed); unregisterReceiver(screenOff); super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent); readIntent(intent)
        syncGoogleAccount()
        if (unlocked) drain()
    }
    private fun hasGoogleAccount(): Boolean = !CloudServices.configured || CloudServices.get(this).owner != null

    private fun syncGoogleAccount() {
        googleSignedIn = hasGoogleAccount()
        if (!googleSignedIn) {
            session.lock(); unlocked = false; authError = null
            biometricPrompt?.cancelAuthentication(); biometricPrompt = null; promptActive = false
        }
    }

    // Google sign-in is public onboarding, not an operation on the locked vault.
    private fun startGoogleSignIn() {
        if (signingIn || isFinishing) return
        signingIn = true; signInError = null
        if (session.unlocked) session.beginExternalFlow()
        try {
            googleSignIn.launch(
                Intent(this, com.gyftalala.omni.cloud.GoogleSignInActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
                ActivityOptionsCompat.makeCustomAnimation(this, 0, 0))
        }
        catch (_: Exception) {
            signingIn = false; session.externalResult()
            signInError = "Could not open Google sign-in. Please try again."
        }
    }

    private fun authenticate() {
        // Guard the actual system prompt as well as the Compose screen. Every caller comes here.
        if (!permissionsReady || !hasGoogleAccount() || signingIn || promptActive || isFinishing) return
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            authError = "Set a fingerprint, PIN, pattern or password in Android Settings to protect your vault. Tap below to open security settings."
            return
        }
        promptActive = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (!hasGoogleAccount()) { syncGoogleAccount(); return }
                promptActive = false; authError = null; session.authenticated(); unlocked = true
                model.refresh()
                if (discoverAfterSignIn) { discoverAfterSignIn = false; model.cloudBackups.signedIn() }
                else model.cloudBackups.refresh()
                drain(); drainCamera(); drainBackup(); drainAccountBackup()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                promptActive = false
                if (hasGoogleAccount()) authError = errString.toString()
            }
        })
        biometricPrompt = prompt
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Omni bot AI")
            .setSubtitle("Your memories, for your eyes")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build())
    }
    fun unlockOrSetup() {
        if (!hasGoogleAccount() || signingIn) return
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        else authenticate()
    }
    private fun pick(kind: String, category: Category?, target: String?) {
        importCategory = category; appendTo = target
        if (kind == "card_scan") {
            importCategory = Category.CARD
            cameraAllowed = hasCameraPermission()
            scanningCard = true
        } else if (kind == "camera") {
            pendingNormalCamera = true
            if (hasCameraPermission()) drainCamera() else requestCamera()
        } else launchExternal { filePicker.launch(if (kind == "image") arrayOf("image/*") else arrayOf("*/*")) }
    }
    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun requestCamera() {
        launchExternal("Could not request camera permission.") { cameraPermission.launch(Manifest.permission.CAMERA) }
    }
    private fun openCameraSettings() {
        launchExternal("Could not open camera settings.") {
            permissionSettings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
    private fun drainCamera() {
        if (pendingNormalCamera && session.unlocked && hasCameraPermission()) {
            pendingNormalCamera = false
            val file = File(cacheDir, "captures/${java.util.UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
            capture = FileProvider.getUriForFile(this, "$packageName.files", file)
            launchExternal { camera.launch(capture!!) }
        }
    }
    private fun closeScanner() {
        scanningCard = false; importCategory = null; appendTo = null
    }
    private fun saveScan(scan: ScannedCard) {
        val target = appendTo
        val uris = scan.photos.map { FileProvider.getUriForFile(this, "$packageName.files", it) }
        model.send("", uris, if (scan.identity == null) Category.CARD else Category.DOCUMENT, target,
            reviewedCard = if (scan.identity == null) scan.details else null, reviewedIdentity = scan.identity).invokeOnCompletion {
            scan.photos.forEach(File::delete)
        }
        closeScanner()
    }
    private fun openPermissionScreen(screen: PermissionScreen) {
        val error = "Could not open Android permissions. Try from your phone's Settings app."
        when (screen) {
            PermissionScreen.MICROPHONE -> launchExternal(error) {
                permissionSettings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            PermissionScreen.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    launchExternal(error) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                } else launchExternal(error) {
                    permissionSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
            }
            PermissionScreen.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= 31) launchExternal(error) {
                permissionSettings.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
            PermissionScreen.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) launchExternal(error) {
                permissionSettings.launch(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
    }
    private fun permissionReturned() {
        session.externalResult()
        cameraAllowed = hasCameraPermission()
        micAllowed = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (session.unlocked) model.refresh()
    }
    private fun launchExternal(errorMessage: String = "Could not open the picker or camera. Try another attachment option.", launch: () -> Unit) {
        if (!session.unlocked) { authenticate(); return }
        session.beginExternalFlow()
        runCatching(launch).onFailure {
            session.externalResult()
            model.error(errorMessage)
        }
    }
    private fun readIntent(intent: Intent) {
        selectedId = intent.getStringExtra("memoryId") ?: selectedId
        val shared = IncomingShare.parse(intent) ?: return
        pendingText = shared.text
        pendingFiles = shared.files
        intent.action = null
    }
    private fun drain() {
        if (!unlocked || (pendingFiles.isEmpty() && pendingText.isBlank())) return
        val capturedFiles = pendingFiles.filter { it.authority == "$packageName.files" && it.path?.startsWith("/captures/") == true }
        model.send(pendingText, pendingFiles, importCategory, appendTo).invokeOnCompletion {
            capturedFiles.forEach { uri -> File(cacheDir, "captures/${uri.lastPathSegment}").delete() }
        }
        pendingText = ""; pendingFiles = emptyList(); importCategory = null; appendTo = null
    }
    private fun exportTo(uri: Uri, write: (java.io.OutputStream) -> Unit) {
        // Exports are explicitly initiated by the owner through Android's destination picker.
        Thread {
            runCatching { contentResolver.openOutputStream(uri)?.use(write) ?: error("Cannot write to that location.") }
                .onFailure { runOnUiThread { model.error("Export failed. Check the destination and try again.") } }
                .onSuccess { runOnUiThread { android.widget.Toast.makeText(this, "Export saved", android.widget.Toast.LENGTH_SHORT).show() } }
        }.start()
    }
}
