package org.libsdl.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.alpha
import com.hypdroid.mpvbridge.MpvPlayerView
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import java.io.File
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The real app entry point (see AndroidManifest.xml - this replaced
 * HypseusActivity as the launcher). HypseusActivity is launched explicitly
 * from here, with constructed CLI-equivalent args, once the user picks a
 * game - it's not meant to be started directly.
 *
 * Deliberately does NOT request All Files Access (MANAGE_EXTERNAL_STORAGE) -
 * that grants read/write/delete over the *entire device*, not just the
 * folders the user picks here. Uses Storage Access Framework instead:
 * genuinely scoped to exactly the folder(s) selected, nothing else.
 */
class MainActivity : ComponentActivity() {
    // #41 - set by ControllerConfigScreen only while it's actively listening
    // for a capture. dispatchKeyEvent()/dispatchGenericMotionEvent() forward
    // matching real input events here; both are null/false the rest of the
    // time, so normal Activity input handling (e.g. Compose's own click
    // handling) is completely unaffected outside that screen.
    var gamepadCaptureListener: ((token: String) -> Unit)? = null
    var gamepadCaptureListeningForAxis: Boolean = false

    // #116 - external launch request (e.g. Daijishō, via a .dpt template's
    // `-e gamename {tags.gamename}`). Compose state, not a plain var -
    // onNewIntent() can update this while the Activity is already running
    // and composed, and HypdroidApp's LaunchedEffect needs to react to that.
    var pendingGameName by mutableStateOf<String?>(null)

    // #116 - set right before starting a game that came from an external
    // launch request, so onResume() knows this session should hand control
    // back to whatever launched us (Daijishō) instead of showing the
    // dashboard once the game exits.
    private var launchedExternally = false

    fun markLaunchedExternally() {
        launchedExternally = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // #116 - requires launchMode="singleTop" in the manifest: without
        // it, a second external launch while Hypdroid is already open would
        // go through onCreate on a fresh instance instead, and this
        // wouldn't fire at all.
        setIntent(intent)
        pendingGameName = intent.getStringExtra(EXTRA_GAME_NAME)
    }

    override fun onResume() {
        super.onResume()
        // #116 - fires when returning here after the launched game exits.
        // Only acts on a session that was actually started externally -
        // every other onResume (first launch, coming back from a normal
        // in-app game launch) leaves this false and is a no-op, since that
        // path still wants the dashboard alive and browsable.
        if (launchedExternally) {
            launchedExternally = false
            // Confirmed on-device: plain finish() hands the foreground back
            // to Daijishō and the process does die (verified via `ps`), but
            // it leaves a stale card sitting in Android's recent-apps
            // switcher, since that list doesn't know to clean itself up
            // just because the process behind it exited. finishAndRemoveTask()
            // is the actual API for "close this and forget it ever ran" -
            // removes the task from Recents too, not just the process.
            finishAndRemoveTask()
            // Still kill explicitly rather than relying on the task removal
            // alone - matches HypseusActivity.onDestroy()'s existing pattern
            // for the game's own :hypseus process, so an externally launched
            // session leaves nothing behind either way.
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    companion object {
        // #116 - matches the tag name used in the .dpt templates
        // (`[gamename] <name>`) and the Daijishō Player's amStartArguments
        // (`-e gamename {tags.gamename}`).
        const val EXTRA_GAME_NAME = "gamename"
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val listener = gamepadCaptureListener
        if (listener != null) {
            val token = captureTokenForKeyEvent(event, gamepadCaptureListeningForAxis)
            if (token != null) {
                listener(token)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        val listener = gamepadCaptureListener
        if (listener != null) {
            val token = captureTokenForMotionEvent(event, gamepadCaptureListeningForAxis)
            if (token != null) {
                listener(token)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingGameName = intent?.getStringExtra(EXTRA_GAME_NAME)
        setContent {
            HypdroidTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HypdroidApp(context = this)
                }
            }
        }
    }
}

/**
 * SAF hands back a content:// tree URI, not a real path - hypseus's file
 * I/O is plain fopen() and can't use that. This resolves the real
 * filesystem path from the URI's document ID, which encodes it as
 * "<volume>:<relative path>" - "primary" for internal storage, or the
 * SD card's actual volume ID otherwise. This mapping isn't official/
 * guaranteed-stable API, but is widely relied on in practice and verified
 * working on the actual target hardware (Retroid Pocket 5).
 *
 * Returns null if the path can't be resolved or doesn't exist - caller
 * must treat that as "unsupported", not silently proceed with a broken path.
 */
private fun resolveRealPath(treeUri: Uri): String? {
    val docId = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (e: Exception) {
        return null
    }
    val split = docId.split(":", limit = 2)
    if (split.size < 2) return null
    val volume = split[0]
    val relativePath = split[1]

    val path = if (volume.equals("primary", ignoreCase = true)) {
        "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
    } else {
        "/storage/$volume/$relativePath"
    }

    val file = File(path)
    return if (file.exists() && file.isDirectory) path else null
}

// A sealed class rather than a plain enum since #31's per-game options
// screen needs to carry which game it's scoped to.
private sealed class Screen {
    object Home : Screen()
    object Settings : Screen()
    // #55 - Settings' own destination screens, one per card.
    object ManageGameFolder : Screen()
    object ManageMediaFolder : Screen()
    object AppSettings : Screen()
    object About : Screen()
    object ControllerConfig : Screen()
    object TouchControls : Screen()
    data class GameOptionsFor(val gameName: String) : Screen()
    // #135 - pushed from GameOptionsFor's "Game Hack" card, backs to that
    // same game's options rather than all the way to Home.
    data class GameHackFor(val gameName: String) : Screen()
    // #161 - GameOptionsFor's other 3 destination pages, same
    // back-to-Options (not Home) pattern as GameHackFor above.
    data class CoverArtSettingsFor(val gameName: String) : Screen()
    data class BezelSettingsFor(val gameName: String) : Screen()
    data class ArgumentsSettingsFor(val gameName: String) : Screen()
}

// Not private - #47's global Cover Art prefs (GameOptions.kt) live in this
// same prefs file, alongside the folder paths below.
const val PREFS_NAME = "hypdroid_prefs"
private const val PREF_GAME_FOLDER_URI = "game_folder_uri"
private const val PREF_MEDIA_FOLDER_URI = "media_folder_uri"

// Generalized over a pref key so the same save/load/clear logic covers both
// the game folder (#36) and the media folder (#30) without duplicating it.
private fun savePersistedFolderUri(context: Context, key: String, uri: Uri) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(key, uri.toString())
        .apply()
}

private fun clearPersistedFolderUri(context: Context, key: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .remove(key)
        .apply()
}

private fun loadPersistedFolderUri(context: Context, key: String): Uri? {
    val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(key, null) ?: return null
    val uri = Uri.parse(stored)
    // takePersistableUriPermission() survives restarts on its own, but not a
    // permission revocation (e.g. user cleared it in Android's own Settings)
    // or the SD card the tree lived on being removed - persistedUriPermissions
    // is the actual source of truth for whether we still really hold it.
    val stillGranted = context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission
    }
    return if (stillGranted) uri else null
}

@Composable
private fun HypdroidApp(context: MainActivity) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var gameFolderPath by remember { mutableStateOf<String?>(null) }
    var pathResolutionFailed by remember { mutableStateOf(false) }
    var games by remember { mutableStateOf<List<Game>>(emptyList()) }
    var mediaFolderPath by remember { mutableStateOf<String?>(null) }
    // #31 - per-game Cover Art/Bezel/Arguments, keyed by game name. Loaded
    // from SharedPreferences whenever the game list changes (including the
    // very first scan), kept in Compose state after that so edits made in
    // GameOptionsScreen show up immediately (e.g. the carousel's cover art)
    // without needing a rescan.
    var gameOptionsMap by remember { mutableStateOf<Map<String, GameOptions>>(emptyMap()) }
    // #52 fix - GameCarousel's own pagerState lives inside GameCarousel,
    // which (along with the rest of HomeScreen) is torn down entirely
    // whenever currentScreen leaves Screen.Home - so the focused page was
    // being lost every time the per-game Options screen (#31) was opened
    // and backed out of. Hoisting the current page here, one level up in a
    // composable that survives screen navigation, lets it be restored.
    var carouselPage by remember { mutableStateOf(0) }
    // #47 - Global Cover Art override (Settings). Plain synchronous
    // SharedPreferences reads, unlike the folder pickers above, so no
    // LaunchedEffect needed - the initial remember{} read is enough.
    var globalCoverArtEnabled by remember { mutableStateOf(loadGlobalCoverArtEnabled(context)) }
    var globalCoverArtType by remember { mutableStateOf(loadGlobalCoverArtType(context)) }
    // #66 - Background Art. Same synchronous-read pattern as Global Cover
    // Art above.
    var backgroundArtEnabled by remember { mutableStateOf(loadBackgroundArtEnabled(context)) }
    var defaultArtEnabled by remember { mutableStateOf(loadDefaultArtEnabled(context)) }
    // #109 - same synchronous-read pattern as the toggles above. Read here
    // (MainActivity's own process) and baked directly into the launch argv
    // below, not passed via a separate Intent extra like the touch settings -
    // this is just a plain CLI flag, no cross-process SharedPreferences
    // concern applies since it's resolved once at launch time.
    var preserveAspectRatioEnabled by remember { mutableStateOf(loadPreserveAspectRatioEnabled(context)) }
    // #163 - phase 1 of the Carousel Layout brainstorm. Same
    // synchronous-read pattern as the toggles above; read once here, passed
    // down into GameCarousel below.
    var attractModeEnabled by remember { mutableStateOf(loadAttractModeEnabled(context)) }
    // #83 - Touch Controls. Same synchronous-read pattern as the toggles
    // above; HypseusActivity reads these same SharedPreferences directly at
    // game-launch time rather than via an Intent extra (see TouchControls.kt).
    var touchControlsEnabled by remember { mutableStateOf(loadTouchControlsEnabled(context)) }
    var touchControlsStickMode by remember { mutableStateOf(loadTouchControlsStickMode(context)) }
    var touchControlsOpacity by remember { mutableStateOf(loadTouchControlsOpacity(context)) }
    // #88 - unset on both a genuinely fresh install and an existing install
    // upgrading to this feature (the flag simply doesn't exist yet either
    // way), so both cases correctly see onboarding once.
    var onboardingComplete by remember { mutableStateOf(loadOnboardingComplete(context)) }

    LaunchedEffect(games) {
        gameOptionsMap = games.associate { it.name to loadGameOptions(context, it.name) }
    }

    fun updateGameOptions(gameName: String, updated: GameOptions) {
        gameOptionsMap = gameOptionsMap + (gameName to updated)
    }

    fun applyGameFolder(uri: Uri) {
        val realPath = resolveRealPath(uri)
        if (realPath == null) {
            pathResolutionFailed = true
            gameFolderPath = null
            games = emptyList()
            clearPersistedFolderUri(context, PREF_GAME_FOLDER_URI)
        } else {
            pathResolutionFailed = false
            gameFolderPath = realPath
            // hypseus hard-fails video init without its own pics/ assets in
            // the home dir (confirmed via a real on-device boot test) - make
            // sure they're there before anything tries to launch a game.
            ensureHypseusAssets(context)
            // Scan automatically as soon as a folder is picked - no separate
            // manual "scan" action, per the owner's UX guidance.
            games = scanGames(File(realPath))
        }
    }

    fun applyMediaFolder(uri: Uri) {
        // No scanning here - nothing consumes the media folder yet (that's
        // Phase E). Just resolve + persist, same as the game folder, minus
        // the game-specific side effects.
        val realPath = resolveRealPath(uri)
        mediaFolderPath = realPath
        if (realPath == null) {
            clearPersistedFolderUri(context, PREF_MEDIA_FOLDER_URI)
        }
    }

    // #116 - extracted from the tile-tap onPlay callback so an externally-
    // triggered launch (Daijishō) goes through the exact same path -
    // per-game flags, touch-overlay Intent extras, everything - instead of
    // a second, easy-to-drift-out-of-sync copy.
    fun launchGame(game: Game) {
        val homeDir = gameFolderPath ?: return
        val args = buildLaunchArgs(game, homeDir).toMutableList()
        val options = gameOptionsMap[game.name]
        if (options?.bezelEnabled == true) {
            args += bezelLaunchArgs(homeDir, game.name)
        }
        if (options?.scorebezelAutofit == true) {
            args += "-scorebezel_autofit"
        }
        if (options?.overlayBezel == true) {
            args += "-overlaybezel"
        }
        if (options?.aspectBezelFix == true) {
            args += "-aspectbezelfix"
        }
        if (preserveAspectRatioEnabled) {
            args += "-preserve_aspect_ratio"
        }
        options?.arguments?.forEach { entry ->
            args += entry.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        }
        val intent = Intent(context, HypseusActivity::class.java)
            .putExtra(HypseusActivity.EXTRA_ARGS, args.toTypedArray())
            .putExtra(HypseusActivity.EXTRA_TOUCH_ENABLED, touchControlsEnabled)
            .putExtra(HypseusActivity.EXTRA_TOUCH_STICK_MODE, touchControlsStickMode)
            .putExtra(HypseusActivity.EXTRA_TOUCH_OPACITY, touchControlsOpacity)
        context.startActivity(intent)
    }

    // #116 - an external launch request (Daijishō) arrives before the game
    // list is necessarily scanned yet (scanning depends on the persisted
    // folder LaunchedEffect below), so this re-runs on every change to
    // either side until both are ready, then fires exactly once.
    LaunchedEffect(context.pendingGameName, games) {
        val name = context.pendingGameName ?: return@LaunchedEffect
        val match = games.find { it.name == name } ?: return@LaunchedEffect
        context.pendingGameName = null
        context.markLaunchedExternally()
        launchGame(match)
    }

    // #88 - All Files Access used to be requested automatically on every
    // launch, a full Settings-screen redirect with zero in-app explanation
    // first - confirmed confusing. Now an explicit action from
    // OnboardingScreen (first run) or the recovery row on Manage Game
    // Folder (afterward) instead of firing unprompted. State here just
    // tracks current status so those screens can render it and know when to
    // swap the action button for a "granted" label.
    var allFilesAccessGranted by remember { mutableStateOf(isAllFilesAccessGranted(context)) }

    // All Files Access has no runtime popup on API 30+ - the only path is
    // this Settings-screen redirect (see StorageAccessFlavor.kt), so there's
    // no direct result callback - re-checked below instead, on resume.
    val onRequestAllFilesAccess = { requestAllFilesAccessIfNeeded(context) }

    // The redirect above returns to onResume with no result callback at
    // all, and even a runtime-popup-style grant can happen from Android's
    // own Settings while backgrounded - re-checking on every resume covers
    // every path a user could take back to the app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesAccessGranted = isAllFilesAccessGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Re-resolve previously-picked folders on every fresh launch, so the
    // dashboard doesn't reset to empty on every restart/crash (#36) - the
    // SAF grant itself already survives restarts via
    // takePersistableUriPermission(), this just re-runs the same
    // resolve(+scan) pipeline the pickers use, automatically.
    LaunchedEffect(Unit) {
        val persistedGameUri = loadPersistedFolderUri(context, PREF_GAME_FOLDER_URI)
        if (persistedGameUri == null) {
            // Either nothing was ever picked, or the grant is no longer
            // valid (revoked, SD card removed) - fall back to the empty
            // "+" state rather than showing a stale/broken path.
            clearPersistedFolderUri(context, PREF_GAME_FOLDER_URI)
        } else {
            applyGameFolder(persistedGameUri)
        }

        val persistedMediaUri = loadPersistedFolderUri(context, PREF_MEDIA_FOLDER_URI)
        if (persistedMediaUri == null) {
            clearPersistedFolderUri(context, PREF_MEDIA_FOLDER_URI)
        } else {
            applyMediaFolder(persistedMediaUri)
        }
    }

    val pickGameFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Persist access to just this folder tree across app restarts -
        // this is the SAF equivalent of "remembering" the folder, scoped
        // to only what was picked, unlike a blanket storage permission.
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        savePersistedFolderUri(context, PREF_GAME_FOLDER_URI, uri)
        applyGameFolder(uri)
    }

    // Used from both the dashboard's "+" and Settings' "Game folder" row -
    // same launcher instance, so both really do share one underlying value
    // rather than each keeping their own copy (#30 acceptance criteria).
    val onChooseGameFolder = { pickGameFolder.launch(null) }

    val pickMediaFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        savePersistedFolderUri(context, PREF_MEDIA_FOLDER_URI, uri)
        applyMediaFolder(uri)
    }

    if (!onboardingComplete) {
        OnboardingScreen(
            allFilesAccessRequired = isAllFilesAccessSupported(),
            allFilesAccessGranted = allFilesAccessGranted,
            onRequestAllFilesAccess = onRequestAllFilesAccess,
            gameFolderPath = gameFolderPath,
            onChooseGameFolder = onChooseGameFolder,
            mediaFolderPath = mediaFolderPath,
            onChooseMediaFolder = { pickMediaFolder.launch(null) },
            onContinue = {
                saveOnboardingComplete(context)
                onboardingComplete = true
            },
        )
        return
    }

    when (val screen = currentScreen) {
        Screen.Home -> HomeScreen(
            gameFolderPath = gameFolderPath,
            mediaFolderPath = mediaFolderPath,
            pathResolutionFailed = pathResolutionFailed,
            games = games,
            gameOptionsMap = gameOptionsMap,
            globalCoverArtEnabled = globalCoverArtEnabled,
            globalCoverArtType = globalCoverArtType,
            backgroundArtEnabled = backgroundArtEnabled,
            defaultArtEnabled = defaultArtEnabled,
            attractModeEnabled = attractModeEnabled,
            carouselPage = carouselPage,
            onCarouselPageChanged = { carouselPage = it },
            onChooseFolder = onChooseGameFolder,
            onOpenSettings = { currentScreen = Screen.Settings },
            onOpenOptions = { game -> currentScreen = Screen.GameOptionsFor(game.name) },
            onPlay = { game -> launchGame(game) },
        )
        Screen.Settings -> SettingsScreen(
            onOpenManageGameFolder = { currentScreen = Screen.ManageGameFolder },
            onOpenManageMediaFolder = { currentScreen = Screen.ManageMediaFolder },
            onOpenAppSettings = { currentScreen = Screen.AppSettings },
            onOpenControllerConfig = { currentScreen = Screen.ControllerConfig },
            onOpenAbout = { currentScreen = Screen.About },
            onOpenTouchControls = { currentScreen = Screen.TouchControls },
            onBack = { currentScreen = Screen.Home },
        )
        Screen.ManageGameFolder -> FolderManageScreen(
            title = "Game folder",
            path = gameFolderPath,
            // #60 - the singe/roms/vldp layout is a real hypseus/Singe
            // requirement (every fan-made game's own script hardcodes
            // BASEDIR = "singe", expecting it as a sibling of roms/vldp at
            // the picked folder's own top level), not obvious from the
            // folder picker alone.
            instructions = "Recommended folder name: hypseus\n\n" +
                "Create subfolders inside your game folder:\n" +
                "- roms - Daphne-native ROM(s)\n" +
                "- vldp - Daphne-native framefile folder(s)\n" +
                "- singe - fan-made games\n\n" +
                "Unzipped Game Requirements:\n" +
                "- Framework - required in singe folder",
            // #88 - recovery path for onboarding's All Files Access row
            // (Touch only) - null on Handheld, which has no such concept.
            permissionRow = if (isAllFilesAccessSupported()) {
                {
                    OnboardingPermissionRow(
                        description = "Hypdroid Touch needs access to launch games stored outside the app.",
                        granted = allFilesAccessGranted,
                        grantedLabel = "Access granted",
                        actionLabel = "Open Android settings",
                        helperText = "Turn on access for Hypdroid, then return here.",
                        onAction = onRequestAllFilesAccess,
                    )
                }
            } else {
                null
            },
            onChange = onChooseGameFolder,
            onBack = { currentScreen = Screen.Settings },
        )
        Screen.ManageMediaFolder -> FolderManageScreen(
            title = "Media folder",
            path = mediaFolderPath,
            // #63 - box/cd/logo is a real Hypdroid convention (coverArtFile()
            // in GameOptions.kt), not obvious from the folder picker alone -
            // confirmed the hard way (a .jpg silently didn't work, since
            // coverArtFile() hardcodes the .png extension). Bezel art is
            // deliberately not mentioned here - it comes from hypseus's own
            // auto-created bezels/ folder inside the Game folder, not this one.
            // bg (#66) added the same way - backgroundArtFile() reads it
            // straight from this folder, full-screen with no cropping
            // adjustment, so it needs to already match the device's own
            // resolution rather than relying on scale-to-fit.
            instructions = "Recommended folder name: media\n\n" +
                "Create subfolders inside your media folder:\n" +
                "- box - 2d or 3d box art\n" +
                "- cd - CD/laserdisc art\n" +
                "- logo - logo art\n" +
                "- bg - Background art, same resolution as your device\n\n" +
                "Required format: PNG",
            onChange = { pickMediaFolder.launch(null) },
            onBack = { currentScreen = Screen.Settings },
        )
        Screen.AppSettings -> AppSettingsScreen(
            globalCoverArtEnabled = globalCoverArtEnabled,
            globalCoverArtType = globalCoverArtType,
            onGlobalCoverArtToggle = { enabled ->
                saveGlobalCoverArtEnabled(context, enabled)
                globalCoverArtEnabled = enabled
            },
            onGlobalCoverArtTypeChange = { type ->
                saveGlobalCoverArtType(context, type)
                globalCoverArtType = type
            },
            backgroundArtEnabled = backgroundArtEnabled,
            defaultArtEnabled = defaultArtEnabled,
            onBackgroundArtToggle = { enabled ->
                saveBackgroundArtEnabled(context, enabled)
                backgroundArtEnabled = enabled
            },
            onDefaultArtToggle = { enabled ->
                saveDefaultArtEnabled(context, enabled)
                defaultArtEnabled = enabled
            },
            preserveAspectRatioEnabled = preserveAspectRatioEnabled,
            onPreserveAspectRatioToggle = { enabled ->
                savePreserveAspectRatioEnabled(context, enabled)
                preserveAspectRatioEnabled = enabled
            },
            attractModeEnabled = attractModeEnabled,
            onAttractModeToggle = { enabled ->
                saveAttractModeEnabled(context, enabled)
                attractModeEnabled = enabled
            },
            onBack = { currentScreen = Screen.Settings },
        )
        Screen.About -> AboutScreen(
            context = context,
            onBack = { currentScreen = Screen.Settings },
        )
        Screen.TouchControls -> TouchControlsScreen(
            touchControlsEnabled = touchControlsEnabled,
            stickModeEnabled = touchControlsStickMode,
            opacity = touchControlsOpacity,
            onTouchControlsToggle = { enabled ->
                saveTouchControlsEnabled(context, enabled)
                touchControlsEnabled = enabled
            },
            onStickModeToggle = { stickMode ->
                saveTouchControlsStickMode(context, stickMode)
                touchControlsStickMode = stickMode
            },
            onOpacityChange = { value ->
                saveTouchControlsOpacity(context, value)
                touchControlsOpacity = value
            },
            onBack = { currentScreen = Screen.Settings },
        )
        Screen.ControllerConfig -> {
            val homeDir = gameFolderPath
            if (homeDir == null) {
                // Shouldn't normally be reachable (the row that opens this
                // screen only exists once a game folder is set), but fall
                // back to Settings rather than crash if it somehow is - as
                // a state change, this has to happen in an effect, not
                // directly in the composable body.
                LaunchedEffect(Unit) { currentScreen = Screen.Settings }
            } else {
                ControllerConfigScreen(
                    activity = context,
                    gameFolderPath = homeDir,
                    onBack = { currentScreen = Screen.Settings },
                )
            }
        }
        is Screen.GameOptionsFor -> {
            val game = games.find { it.name == screen.gameName }
            if (game == null) {
                // Game list changed out from under this screen (folder
                // re-picked, etc.) - fall back rather than crash.
                LaunchedEffect(Unit) { currentScreen = Screen.Home }
            } else {
                val options = gameOptionsMap[game.name] ?: GameOptions(null, false, emptyList())
                GameOptionsScreen(
                    game = game,
                    options = options,
                    onOpenCoverArtSettings = { currentScreen = Screen.CoverArtSettingsFor(game.name) },
                    onOpenBezelSettings = { currentScreen = Screen.BezelSettingsFor(game.name) },
                    onOpenGameHack = { currentScreen = Screen.GameHackFor(game.name) },
                    onOpenArgumentsSettings = { currentScreen = Screen.ArgumentsSettingsFor(game.name) },
                    onBack = { currentScreen = Screen.Home },
                )
            }
        }
        is Screen.GameHackFor -> {
            val game = games.find { it.name == screen.gameName }
            if (game == null) {
                LaunchedEffect(Unit) { currentScreen = Screen.Home }
            } else {
                val options = gameOptionsMap[game.name] ?: GameOptions(null, false, emptyList())
                GameHackScreen(
                    game = game,
                    options = options,
                    onAspectBezelFixToggle = { enabled ->
                        saveAspectBezelFix(context, game.name, enabled)
                        updateGameOptions(game.name, options.copy(aspectBezelFix = enabled))
                    },
                    onReduceAttractVideoResolutionToggle = { enabled ->
                        saveReduceAttractVideoResolution(context, game.name, enabled)
                        updateGameOptions(game.name, options.copy(reduceAttractVideoResolution = enabled))
                    },
                    onBack = { currentScreen = Screen.GameOptionsFor(game.name) },
                )
            }
        }
        is Screen.CoverArtSettingsFor -> {
            val game = games.find { it.name == screen.gameName }
            if (game == null) {
                LaunchedEffect(Unit) { currentScreen = Screen.Home }
            } else {
                val options = gameOptionsMap[game.name] ?: GameOptions(null, false, emptyList())
                CoverArtSettingsScreen(
                    game = game,
                    options = options,
                    globalCoverArtEnabled = globalCoverArtEnabled,
                    onCoverArtChange = { type ->
                        saveCoverArt(context, game.name, type)
                        updateGameOptions(game.name, options.copy(coverArt = type))
                    },
                    onBack = { currentScreen = Screen.GameOptionsFor(game.name) },
                )
            }
        }
        is Screen.BezelSettingsFor -> {
            val game = games.find { it.name == screen.gameName }
            if (game == null) {
                LaunchedEffect(Unit) { currentScreen = Screen.Home }
            } else {
                val options = gameOptionsMap[game.name] ?: GameOptions(null, false, emptyList())
                BezelSettingsScreen(
                    game = game,
                    options = options,
                    onBezelToggle = { enabled ->
                        saveBezelEnabled(context, game.name, enabled)
                        updateGameOptions(game.name, options.copy(bezelEnabled = enabled))
                    },
                    onScorebezelAutofitToggle = { enabled ->
                        saveScorebezelAutofit(context, game.name, enabled)
                        updateGameOptions(game.name, options.copy(scorebezelAutofit = enabled))
                    },
                    onOverlayBezelToggle = { enabled ->
                        saveOverlayBezel(context, game.name, enabled)
                        updateGameOptions(game.name, options.copy(overlayBezel = enabled))
                    },
                    onBack = { currentScreen = Screen.GameOptionsFor(game.name) },
                )
            }
        }
        is Screen.ArgumentsSettingsFor -> {
            val game = games.find { it.name == screen.gameName }
            if (game == null) {
                LaunchedEffect(Unit) { currentScreen = Screen.Home }
            } else {
                val options = gameOptionsMap[game.name] ?: GameOptions(null, false, emptyList())
                ArgumentsSettingsScreen(
                    game = game,
                    options = options,
                    onAddArgument = { arg ->
                        val updated = options.arguments + arg
                        saveArguments(context, game.name, updated)
                        updateGameOptions(game.name, options.copy(arguments = updated))
                    },
                    onRemoveArgument = { arg ->
                        val updated = options.arguments - arg
                        saveArguments(context, game.name, updated)
                        updateGameOptions(game.name, options.copy(arguments = updated))
                    },
                    onBack = { currentScreen = Screen.GameOptionsFor(game.name) },
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    gameFolderPath: String?,
    mediaFolderPath: String?,
    pathResolutionFailed: Boolean,
    games: List<Game>,
    gameOptionsMap: Map<String, GameOptions>,
    globalCoverArtEnabled: Boolean,
    globalCoverArtType: CoverArtType,
    backgroundArtEnabled: Boolean,
    defaultArtEnabled: Boolean,
    attractModeEnabled: Boolean,
    carouselPage: Int,
    onCarouselPageChanged: (Int) -> Unit,
    onChooseFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOptions: (Game) -> Unit,
    onPlay: (Game) -> Unit,
) {
    // #66 - resolved from whichever game is currently focused in the
    // carousel (carouselPage, kept in sync by GameCarousel's own
    // snapshotFlow) - re-resolves on every recomposition without any extra
    // plumbing. #95 gives the actual AsyncImage below a crossfade so this
    // no longer reads as a hard cut when it changes.
    val focusedGame = games.getOrNull(carouselPage)
    val backgroundFile = focusedGame?.let {
        backgroundArtFile(mediaFolderPath, it.name, backgroundArtEnabled, defaultArtEnabled)
    }

    // #95 - the carousel's own swipe animation and this background swap
    // used to fight each other visually: HorizontalPager already animates
    // the page transition, but the full-screen background behind it (and
    // each neighboring page's own cover art, still off-screen at this
    // point) hadn't even started loading until the swipe actually landed -
    // a real Coil disk read, not free. Prefetching the immediate neighbors'
    // art into Coil's cache as soon as carouselPage changes means it's
    // usually already there by the time a swipe lands on it, rather than
    // starting the load only then.
    val context = LocalContext.current
    LaunchedEffect(carouselPage, games, mediaFolderPath) {
        for (index in listOf(carouselPage - 1, carouselPage + 1)) {
            val neighbor = games.getOrNull(index) ?: continue
            val coverArtOverride = if (globalCoverArtEnabled) {
                globalCoverArtType
            } else {
                gameOptionsMap[neighbor.name]?.coverArt
            }
            resolveCoverArtFile(mediaFolderPath, neighbor.name, coverArtOverride)?.let { file ->
                context.imageLoader.enqueue(ImageRequest.Builder(context).data(file).build())
            }
            backgroundArtFile(mediaFolderPath, neighbor.name, backgroundArtEnabled, defaultArtEnabled)?.let { file ->
                context.imageLoader.enqueue(ImageRequest.Builder(context).data(file).build())
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (backgroundFile != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(backgroundFile).crossfade(300).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Per the owner's #36 redesign: the dashboard never shows the raw
        // folder path - just the game carousel, plus a "+" (add/change
        // game folder) and gear (Settings, #30) icon pair in the upper
        // right.
        //
        // #65 - the top bar (logo + icons) is a real layout region here,
        // not an overlay floating on top of the carousel's own full-screen
        // Box. The carousel is confined to the space below it, so the two
        // never occupy overlapping screen bounds - #49's declaration-order
        // workaround (the icon Row had to be declared *after* the carousel
        // purely to win touch priority in the region where they used to
        // visually overlap) is no longer needed at all.
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    // #66 - semi-transparent dark scrim, only while a real
                    // background image is actually showing underneath -
                    // keeps the logo/icons legible regardless of the
                    // image's own tone/brightness. No scrim needed against
                    // the plain background (Background Art off, or this
                    // game just has no art) - already legible on its own.
                    .then(
                        if (backgroundFile != null) {
                            Modifier.background(Color.Black.copy(alpha = 0.5f))
                        } else {
                            Modifier
                        },
                    )
                    .padding(8.dp),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.hypdroid_logo),
                    contentDescription = "Hypdroid",
                    modifier = Modifier.height(40.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                // White against the scrim always contrasts, since the scrim
                // itself is always dark - that's the "universal color" the
                // scrim exists to make possible. Left at the default theme
                // color the rest of the time, matching the plain background.
                val iconTint = if (backgroundFile != null) Color.White else LocalContentColor.current
                HypdroidIconButton(onClick = onChooseFolder) {
                    Icon(Icons.Filled.Add, contentDescription = "Choose game folder", tint = iconTint)
                }
                HypdroidIconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = iconTint)
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (pathResolutionFailed) {
                    Text(
                        "Couldn't resolve a real filesystem path for that folder " +
                            "on this device. Try a different folder.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                } else if (gameFolderPath == null) {
                    Text("No games yet. Tap + to choose a game folder.")
                } else if (games.isEmpty()) {
                    Text("No games found in this folder.")
                } else {
                    GameCarousel(
                        games = games,
                        mediaFolderPath = mediaFolderPath,
                        gameOptionsMap = gameOptionsMap,
                        globalCoverArtEnabled = globalCoverArtEnabled,
                        globalCoverArtType = globalCoverArtType,
                        attractModeEnabled = attractModeEnabled,
                        initialPage = carouselPage,
                        onPageChanged = onCarouselPageChanged,
                        onPlay = onPlay,
                        onOpenOptions = onOpenOptions,
                    )
                }
            }
        }
    }
}

/**
 * #44 - Eden-style carousel: one game centered/highlighted, neighbors
 * peeking in at reduced scale, swipe to page between them, tap the
 * centered card to launch. First pass shows box art only (the owner's
 * available content right now) - per-game choice of which media type to
 * show (#31's future "Cover Art" field: CD/Logo/Box/Text) isn't built yet,
 * so every card just tries box art and falls back to a plain text card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCarousel(
    games: List<Game>,
    mediaFolderPath: String?,
    gameOptionsMap: Map<String, GameOptions>,
    globalCoverArtEnabled: Boolean,
    globalCoverArtType: CoverArtType,
    // #163 - phase 1 of the Carousel Layout brainstorm: one focused card
    // per page, left-aligned, instead of three-up centered. No video frame
    // yet - just proving out the resize/reposition and that d-pad
    // navigation still works, before building anything on top of it.
    attractModeEnabled: Boolean,
    initialPage: Int,
    onPageChanged: (Int) -> Unit,
    onPlay: (Game) -> Unit,
    onOpenOptions: (Game) -> Unit,
) {
    // #52 fix - restores whatever page was focused before navigating away
    // (e.g. to #31's Options screen and back), instead of always starting
    // over at page 0 - this composable itself gets torn down and recreated
    // on every trip through Screen.Home, so the initial value has to come
    // from outside (HypdroidApp), not this function's own remembered state.
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, (games.size - 1).coerceAtLeast(0)),
        pageCount = { games.size },
    )
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onPageChanged(it) }
    }
    val coroutineScope = rememberCoroutineScope()
    // HorizontalPager doesn't respond to d-pad left/right on its own the
    // way LazyColumn responds to up/down "for free" (Compose's built-in
    // focus-traversal handles simple linear lists, but not page-changing
    // gestures like this) - this is gamepad-first hardware, so real d-pad
    // paging needs to be wired up explicitly, not left as a touch-only gap.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // #105 - card width derived from screen width instead of a fixed
    // 420.dp, same basis as #97's touch-button fix. The fraction
    // (0.319) is reverse-engineered from 420dp against the Samsung Tab
    // S7+'s actual screenWidthDp (~1317dp, the device this layout was
    // originally tuned against) - a like-for-like swap in sizing basis,
    // not a redesign, so Samsung's already-approved look stays the same.
    // Confirmed on the Retroid (~853dp wide) that this leaves real room
    // for three fully visible cards with real gaps, instead of one
    // dominant card clipping its neighbors through the middle of their art.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // #163 - Attract Mode: one full-width page per game instead of three
    // fractional pages side by side, so neighbors don't peek in at all.
    // The focused card's own on-screen size is still governed entirely by
    // GameCard's height/aspect-ratio rule below (unchanged either way) -
    // this only changes how much horizontal room the *page* claims.
    val cardWidth = if (attractModeEnabled) screenWidthDp.dp else (screenWidthDp * 0.319f).dp

    // #179 - hoisted above the pager (was previously declared inside the
    // video-overlay block further down) so the pager's own onKeyEvent can
    // read/toggle isPlaying too - a gamepad Handheld device has no touch
    // to tap the on-screen Play button with. Harmless no-ops when Attract
    // Mode is off: focusedGame is null, attractClip resolves to null,
    // isPlaying just never becomes true.
    val focusedGame = if (attractModeEnabled) games.getOrNull(pagerState.settledPage) else null
    // Real attract clip, looked up once per focused game (not on every
    // recomposition). Null is the default/common case (no offset data,
    // multi-file, zipped, or Daphne without a framefile - see
    // AttractClip.kt), not an error - the frame stays empty, background
    // visible through its center, in that case.
    //
    // Real bug found and fixed (2026-09-03): findAttractClip does real
    // file I/O (reading the .singe script + framefile) - doing that
    // synchronously inside `remember` blocked the whole Row's
    // recomposition on the main thread, including the plain static frame
    // image, which is why even just the border took a noticeable beat to
    // show up when swiping. produceState moves the read to a background
    // dispatcher so the frame renders immediately regardless of how long
    // the file read takes; the Play button/video path just update
    // reactively once it resolves.
    val attractClip by produceState<AttractClipInfo?>(initialValue = null, focusedGame?.name) {
        value = focusedGame?.let { game ->
            withContext(Dispatchers.IO) { findAttractClip(game) }
        }
    }
    // #168 - explicit Play button instead of auto-play-on-focus, simplified
    // after real testing surfaced repeated auto-restart bugs (loop-file +
    // repeated same-instance reloads producing an instant EOF and mpv core
    // shutdown - see MpvPlayerView.kt). Reset whenever the focused game
    // changes, so leaving and coming back always needs a fresh
    // tap/press - no ambiguity about "should this auto-resume."
    var isPlaying by remember(focusedGame?.name) { mutableStateOf(false) }
    // Real bug found and fixed (2026-09-03): returning from an actual game
    // (HypseusActivity closing, MainActivity resuming) left the UI showing
    // a black box instead of the Play button, even though MpvPlayerView's
    // own ON_RESUME handler now stops playback - this Compose-side
    // isPlaying state didn't know that happened. Resets in lockstep with
    // the player's own stop, so the Play button reliably reappears every
    // time we come back.
    val homeLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(homeLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isPlaying = false
            }
        }
        homeLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { homeLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Real bug found and fixed (2026-09-03): once the clip reached its own
    // natural end, the screen went black with no way to replay it -
    // keep-open=yes was meant to freeze on the last frame, but didn't
    // visually hold it here. Simpler fix matching the button-driven model
    // already chosen: auto-return to the Play button once the known clip
    // duration elapses, same alpha-hide path already used for swiping
    // away - no black gap, and the button is always available to replay.
    LaunchedEffect(isPlaying, attractClip) {
        val clip = attractClip
        if (isPlaying && clip != null) {
            val durationMs = ((clip.endSeconds - clip.startSeconds) * 1000).toLong().coerceAtLeast(0)
            delay(durationMs)
            isPlaying = false
        }
    }

    // #168 - the video+frame overlay lives here, as a sibling of the pager
    // rather than inside its per-page content (see MpvPlayerView.kt's own
    // doc comment for the real crash this fixes: MPVLib is a native global
    // singleton, and mounting/unmounting one per page meant a fresh
    // create()/destroy() on every swipe - real crashes on real hardware).
    // One persistent instance for the whole carousel; MpvPlayerView itself
    // handles a changing focused game by issuing a fresh `loadfile`, not
    // by tearing anything down.
    Box(modifier = Modifier.fillMaxSize()) {
    HorizontalPager(
        state = pagerState,
        // A fixed, modest page width (rather than the default full-width
        // page) is what actually produces the Eden-style look on this wide
        // landscape screen - a full-width page left the narrow portrait
        // card pinned to the page's start edge with a huge empty gap
        // before the next page, instead of a tight, centered carousel.
        // Attract Mode intentionally goes back to a full-width page - that
        // gap is exactly the point there, it's what leaves room for the
        // focused card to sit left-aligned instead of centered.
        pageSize = PageSize.Fixed(cardWidth),
        contentPadding = if (attractModeEnabled) {
            PaddingValues(0.dp)
        } else {
            PaddingValues(horizontal = (screenWidthDp.dp - cardWidth) / 2)
        },
        pageSpacing = 16.dp,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // #31 - pressing down opens the focused game's options
                // screen, the gamepad equivalent of a touch long-press
                // (see GameCard's combinedClickable below).
                if (event.key == Key.DirectionDown) {
                    onOpenOptions(games[pagerState.currentPage])
                    return@onKeyEvent true
                }
                // #179 - Handheld (non-touch) had no way to trigger the
                // on-screen Play button at all - all 4 d-pad directions
                // were already spoken for (paging/Options/implicit focus-
                // escape), so this uses X instead, toggling the same
                // isPlaying state the Play button itself sets. No-op
                // (doesn't consume the key) when the focused game has no
                // attract clip - nothing to toggle.
                if (event.key == Key.ButtonX) {
                    if (attractClip != null) {
                        isPlaying = !isPlaying
                        return@onKeyEvent true
                    }
                    return@onKeyEvent false
                }
                // Launching used to rely on the focused card's own
                // combinedClickable, which is exactly what made A launch
                // the wrong game (see GameCard's focusProperties comment).
                // Now that cards are out of focus traversal, the pager
                // has to handle confirm itself - and it launches the
                // centered page, which is the game the user can actually
                // see is selected.
                if (event.key == Key.Enter ||
                    event.key == Key.NumPadEnter ||
                    event.key == Key.DirectionCenter ||
                    event.key == Key.ButtonA
                ) {
                    onPlay(games[pagerState.currentPage])
                    return@onKeyEvent true
                }
                val targetPage = when (event.key) {
                    Key.DirectionLeft -> pagerState.currentPage - 1
                    Key.DirectionRight -> pagerState.currentPage + 1
                    else -> return@onKeyEvent false
                }
                if (targetPage in games.indices) {
                    coroutineScope.launch { pagerState.animateScrollToPage(targetPage) }
                }
                true
            },
    ) { page ->
        val game = games[page]
        val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
        val scale = lerp(0.82f, 1f, 1f - pageOffset.coerceIn(0f, 1f))
        // #47 - Global Cover Art (Settings) wins over each game's own #31
        // choice while it's on; resolveCoverArtFile's fallback-to-BOX
        // behavior only kicks in for the null (no override at all) case, so
        // this always passes a concrete type when global is enabled.
        val coverArtOverride = if (globalCoverArtEnabled) {
            globalCoverArtType
        } else {
            gameOptionsMap[game.name]?.coverArt
        }
        if (attractModeEnabled) {
            // #163 - Attract Mode positions the focused card toward the
            // left edge (with a margin, matching the app's standard 16dp)
            // instead of centering it across the now-full-width page. The
            // frame+video overlay is no longer built per-page here (see
            // #168 - it's a single instance hoisted above, outside the
            // pager entirely).
            Box(
                modifier = Modifier.fillMaxSize().padding(start = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                GameCard(
                    game = game,
                    coverArtFile = resolveCoverArtFile(mediaFolderPath, game.name, coverArtOverride),
                    scale = scale,
                    onClick = { onPlay(game) },
                    onLongClick = { onOpenOptions(game) },
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GameCard(
                    game = game,
                    coverArtFile = resolveCoverArtFile(mediaFolderPath, game.name, coverArtOverride),
                    scale = scale,
                    onClick = { onPlay(game) },
                    onLongClick = { onOpenOptions(game) },
                )
            }
        }
    }
    // #168 - single persistent video+frame overlay for the whole carousel,
    // not one per page (see MpvPlayerView.kt's doc comment for why that
    // crashed). Positioned via the same relative Row math the per-page
    // card used to use (invisible same-shaped spacer + gap), so it lines
    // up with wherever the settled page's card sits without measuring
    // anything - both are laid out against the same container bounds
    // using identical relative rules.
    //
    // Mounted for as long as Attract Mode is on, full stop - MpvPlayerView
    // must NOT be conditionally composed on scroll state (e.g. hidden via
    // an `if`), or it unmounts/remounts (destroy/recreate) on *every
    // swipe*, the exact churn that caused real crashes in the first place.
    // Instead, only visibility (alpha) reacts to an active swipe - a
    // settled page is the only one with a stable position to align with,
    // but the underlying player instance itself never tears down for it.
    if (attractModeEnabled) {
        // #179 - focusedGame/attractClip/isPlaying and their effects are
        // now hoisted above the pager (shared with onKeyEvent's X-button
        // toggle) - this block just renders them.
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 16.dp)
                .alpha(if (pagerState.isScrollInProgress) 0f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(54.dp),
        ) {
            // Invisible - exists only so this Row's second item (the
            // frame/video) lands in the same spot the real GameCard's
            // width would have pushed it to. Same fillMaxHeight/aspectRatio
            // rule GameCard itself uses.
            Spacer(modifier = Modifier.fillMaxHeight(0.8f).aspectRatio(0.7f))
            Box(modifier = Modifier.fillMaxHeight(0.64f).aspectRatio(1920f / 1080f)) {
                // Video sized to the frame's full outer box, not inset to
                // its transparent window - the frame's opaque border
                // overlaps and crops the video's edges on purpose (settled
                // design, see #168) - and its semi-transparent rivets
                // blend with whatever's playing behind them, purely from
                // alpha compositing.
                //
                // Real bug found and fixed (2026-09-03): MPVLib's own
                // "stop" command halts playback but does not blank the
                // last rendered frame on the GL surface - since this is
                // one persistent instance (not recreated per game), the
                // previous game's paused/last frame kept showing through
                // for every subsequent game with no attract data of its
                // own, confirmed on a real screenshot. Fixed at the
                // Compose layer instead of fighting mpv's own state:
                // alpha=0 hides the video layer outright whenever the
                // focused game has no clip, regardless of whatever mpv
                // still has rendered underneath.
                MpvPlayerView(
                    videoPath = if (isPlaying) attractClip?.videoPath else null,
                    audioPath = if (isPlaying) attractClip?.audioPath else null,
                    startSeconds = attractClip?.startSeconds,
                    endSeconds = attractClip?.endSeconds,
                    // #183 - per-game Game Hack, attract preview only.
                    reduceResolution = focusedGame?.let { gameOptionsMap[it.name]?.reduceAttractVideoResolution }
                        ?: false,
                    modifier = Modifier.fillMaxSize().alpha(if (isPlaying && attractClip != null) 1f else 0f),
                )
                Image(
                    painter = painterResource(id = R.drawable.attract_frame),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
                // #168 - only offered when there's actually a clip to play;
                // no button (and no empty-tap dead zone) for a game with no
                // attract data.
                if (attractClip != null && !isPlaying) {
                    // Real bug found and fixed (2026-09-03): a plain white
                    // icon has no guaranteed contrast against whatever's
                    // behind it - invisible against the app's own default
                    // white background (Background Art off), and real
                    // background art could be any color. A dark backing
                    // circle keeps it visible regardless.
                    IconButton(
                        onClick = { isPlaying = true },
                        modifier = Modifier.align(Alignment.Center).size(64.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play attract clip",
                            tint = Color.White,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCard(
    game: Game,
    coverArtFile: File?,
    scale: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .aspectRatio(0.7f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            // Quick on-device test: transparent instead of surfaceVariant,
            // so any remaining letterbox gap blends into the background
            // art behind the carousel instead of showing a gray box.
            // #31 - long-press is touch's equivalent of pressing down on
            // the d-pad (see GameCarousel's onKeyEvent above) - opens this
            // game's options screen (Cover Art/Bezel/Arguments).
            //
            // combinedClickable makes a component focusable by default,
            // which put every card into d-pad focus traversal alongside
            // the pager itself. Focus then landed on an individual card
            // rather than the pager, and that card did not track the
            // pager's centered page - so the highlight sat on the wrong
            // card and pressing A launched whatever card held focus
            // instead of the centered one. Left/right still worked only
            // because the card ignores them and they bubbled up to the
            // pager. Cards stay clickable for touch; the pager owns all
            // d-pad handling (see its onKeyEvent). Same treatment as
            // ControllerConfigScreen's picker buttons (#84).
            .focusProperties { canFocus = false }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        if (coverArtFile != null) {
            AsyncImage(
                // #95 - crossfade, and usually already warm from the
                // neighbor-prefetch effect in HomeScreen by the time this
                // page is actually reached.
                model = ImageRequest.Builder(LocalContext.current).data(coverArtFile).crossfade(300).build(),
                contentDescription = game.name,
                // Fit, not Crop - the real box art PNGs include a
                // transparent shadow margin around the rendered box, and
                // Crop was cutting into the top/bottom of that to fill the
                // card's fixed aspect ratio (confirmed by comparing the
                // in-app render against the source file directly).
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // No art for this game's resolved Cover Art type (or "Text"
            // was explicitly chosen, or no media folder set) - falls back
            // to a plain text card rather than an error/blank space,
            // matching the app's existing missing-content pattern.
            //
            // #101 - the card's background fill was removed, so this label
            // sits directly over whatever background art is behind it
            // instead of a neutral gray box. The default text color is
            // black (MaterialTheme.colorScheme.onSurface, unthemed), which
            // reads fine on light/no-background but disappears into dark or
            // busy background art. First attempt used a BLACK shadow, which
            // did nothing against a dark backdrop (black shadow behind
            // black text has no contrast against black background - visually
            // confirmed on-device, not just a theoretical miss). A WHITE
            // glow (zero offset, wide blur - a halo, not a directional drop
            // shadow) fixes both ends: it's a strong light ring around the
            // dark text against dark/busy art, and a near-invisible no-op
            // against light/white backgrounds where the black text already
            // has full contrast on its own.
            Text(
                game.name,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge.copy(
                    shadow = Shadow(
                        color = Color.White,
                        offset = Offset.Zero,
                        blurRadius = 24f,
                    ),
                ),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

// #55 - SettingsScreen and its destination screens (FolderManageScreen,
// AppSettingsScreen, AboutScreen) moved to SettingsScreens.kt.
