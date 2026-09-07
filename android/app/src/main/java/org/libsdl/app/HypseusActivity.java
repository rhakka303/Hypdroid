package org.libsdl.app;

import android.os.Bundle;

/**
 * hypseus links SDL3 (and everything else - Vorbis, libzip, libmpeg2) as a
 * static library into a single libmain.so, rather than the stock SDL
 * template's assumption of a separate libSDL3.so alongside libmain.so.
 * SDLActivity's default getLibraries() tries to load "SDL3" first, which
 * doesn't exist here and throws UnsatisfiedLinkError before "main" is ever
 * reached - overriding it to just "main" is SDLActivity's documented
 * customization point for exactly this case.
 */
public class HypseusActivity extends SDLActivity {
    public static final String EXTRA_ARGS = "org.libsdl.app.HypseusActivity.EXTRA_ARGS";

    // #85 - the touch overlay's three settings, handed over explicitly by
    // the launching Intent instead of read from SharedPreferences on this
    // side. This activity runs in its own `:hypseus` process now (see
    // AndroidManifest.xml), and SharedPreferences aren't dependable across
    // processes - see TouchOverlay.attach()'s comment for the full reasoning.
    public static final String EXTRA_TOUCH_ENABLED =
        "org.libsdl.app.HypseusActivity.EXTRA_TOUCH_ENABLED";
    public static final String EXTRA_TOUCH_STICK_MODE =
        "org.libsdl.app.HypseusActivity.EXTRA_TOUCH_STICK_MODE";
    public static final String EXTRA_TOUCH_OPACITY =
        "org.libsdl.app.HypseusActivity.EXTRA_TOUCH_OPACITY";
    // #185 - independent of EXTRA_TOUCH_ENABLED. When true, only the bottom
    // SELECT/START/L3/R3 row is shown (see TouchOverlay.attach()'s
    // minimalOnly) - Touch Lightgun hides the rest of the overlay so it
    // doesn't sit on top of tap-to-aim, but a touch-only device still needs
    // some way to reach Start/Select/L3/R3.
    public static final String EXTRA_TOUCH_MINIMAL =
        "org.libsdl.app.HypseusActivity.EXTRA_TOUCH_MINIMAL";

    // #83 - a no-op unless the Settings "Touch Controls" toggle is on (see
    // TouchOverlay.attach()). Registered/torn down for the lifetime of a
    // single game session rather than per onResume/onPause, since it's a
    // virtual SDL joystick, not something that needs to react to Android
    // focus changes the way real input devices do.
    private TouchOverlay touchOverlay;

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "main"
        };
    }

    /**
     * MainActivity constructs the real argv (see LaunchArgs.kt) and passes
     * it via this Intent extra before starting this activity - the base
     * SDLActivity default (empty array) only gets hypseus as far as its own
     * "no game specified" non-crashing exit path (verified in Phase C).
     */
    @Override
    protected String[] getArguments() {
        String[] args = getIntent().getStringArrayExtra(EXTRA_ARGS);
        return args != null ? args : new String[0];
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        touchOverlay = new TouchOverlay(this);
        touchOverlay.attach(
            getIntent().getBooleanExtra(EXTRA_TOUCH_ENABLED, false),
            getIntent().getBooleanExtra(EXTRA_TOUCH_STICK_MODE, false),
            getIntent().getFloatExtra(EXTRA_TOUCH_OPACITY, 0.5f),
            getIntent().getBooleanExtra(EXTRA_TOUCH_MINIMAL, false));
    }

    @Override
    protected void onDestroy() {
        // #85 - SDL deliberately allows native main() to run only once per
        // process (SDLActivity's own mSDLMainFinished/run_count guard, and
        // SDL_HINT_ANDROID_ALLOW_RECREATE_ACTIVITY's docs: "SDL will call
        // exit() when you return from your main function and the
        // application will be terminated and then started fresh each
        // time"). A fresh process per game session is therefore SDL's
        // intended architecture, not a workaround - and hypseus's own C++
        // globals (g_game, g_ldp, video/sound state) equally depend on
        // starting clean.
        //
        // Before this, that guard's System.exit(0) killed the *whole* app
        // (dashboard included) on a second launch. With this activity in
        // its own `:hypseus` process, killing it here leaves the dashboard
        // process untouched - so its Compose state, including the carousel
        // position, simply survives. That's what the earlier
        // restart-trampoline attempt got wrong: it restarted the dashboard
        // process too, wiping exactly that state.
        //
        // isFinishing() && !isChangingConfigurations() so this only fires
        // on a real session end, never on a configuration-change teardown.
        // The kill goes after super.onDestroy() so SDL's own thread join
        // and native quit complete first.
        boolean terminateGameProcess = isFinishing() && !isChangingConfigurations();

        if (touchOverlay != null) {
            touchOverlay.detach();
            touchOverlay = null;
        }
        super.onDestroy();

        if (terminateGameProcess) {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
}
