/*
 * #185 - ManyMouse backend for Android touch (and any real USB
 * mouse/lightgun-as-HID-mouse hardware Android also reports as a plain
 * mouse). See LICENSE.txt in this directory for ManyMouse's own license;
 * this file is Hypdroid-authored, not upstream ManyMouse/hypseus-singe.
 *
 * Why this exists instead of reusing linux_evdev.c: Android's kernel does
 * define __linux__, so linux_evdev.c compiles here, but its whole approach
 * (opendir("/dev/input"), open("/dev/input/eventN", O_RDONLY)) needs raw
 * device-node access that a normal, non-rooted Android app's UID is never
 * granted - those nodes are owned by system/input-group with 0660
 * permissions. It would build fine and then silently find zero mice at
 * runtime. There is no viable Android backend among ManyMouse's existing
 * five drivers (evdev/xinput2/windows/hidmanager/hidutilities), so this is
 * a genuine sixth driver, not a reuse of an existing one.
 *
 * Why this is simpler than it sounds: SDL3's Android backend already turns
 * taps into ordinary SDL_EVENT_MOUSE_MOTION/BUTTON_DOWN/BUTTON_UP events on
 * its own (SDL_HINT_TOUCH_MOUSE_EVENTS defaults on - confirmed in the
 * vendored SDL_hints.h), in the same window-pixel coordinate space a real
 * mouse would report. hypseus's own main loop already owns SDL_PollEvent()
 * for every other event type (see process_event()'s caller in input.cpp),
 * so rather than adding a second, competing consumer of that loop, poll()
 * below uses SDL_PeepEvents(..., SDL_GETEVENT, ...) to dequeue *only*
 * mouse-type events directly from SDL's shared queue - it can never see or
 * remove a keyboard/gamepad/quit event, so it can't race the main loop for
 * anything that loop actually needs. That also means zero new Kotlin or
 * JNI plumbing: no touch listener, no native setter to enable/disable this
 * driver - it's selected the same way every other ManyMouse driver is, by
 * passing the existing "-manymouse" CLI flag (see cmdline.cpp).
 *
 * Coordinate space: manymouse_update_mice() (input.cpp) rescales every
 * ABSMOTION event via (value - minval) / (maxval - minval) * video's
 * logical width/height. Reporting the *full window* as minval/maxval would
 * include hypseus's own pillarbox/letterbox bars in that scale. Reporting
 * hypseus's own already-computed on-screen video rect (g_scaling_rect,
 * exposed via video::get_scaling_rect_*() - added for #185 Stage 1) as
 * minval/maxval instead makes that existing rescale land in the game's own
 * coordinate space with no extra math needed here.
 *
 * Real on-device finding (Retroid Pocket 5, 2026-09-06): a stationary tap
 * fires the trigger correctly (sound, ammo, service-menu highlight all
 * responded) but never moved the on-screen cursor - only the thumbstick
 * did. Root cause: SDL only emits SDL_EVENT_MOUSE_MOTION when the pointer
 * actually moves before going down; a tap-in-place produces BUTTON_DOWN/UP
 * with *no* accompanying motion event at all, so mouse.x/mouse.y in
 * manymouse_update_mice() never got updated for that tap - only the
 * button/trigger state did. SDL_MouseButtonEvent carries its own x/y
 * (see SDL_events.h), so the fix is to synthesize the same two ABSMOTION
 * events from a button event's own coordinates as from a real motion
 * event, not to assume a separate prior motion event already ran.
 *
 * #197 - a second real on-device finding: hypseus's own SDL_check_input()
 * does `while (SDL_PollEvent(&e)) process_event(&e);`, and this driver runs
 * from inside process_event() - so SDL_PollEvent dequeues (and discards, in
 * MANY_MOUSE mode) one event per iteration before our SDL_PeepEvents runs.
 * When the dropped event is the tap's MOUSE_BUTTON_UP, the ManyMouse button
 * stays latched "down" and a level-triggered automatic weapon keeps firing
 * until the mag empties. A minimal, additive safety net (see g_trigger_held
 * below) synthesises the missing release, driven by SDL_GetMouseState()
 * (authoritative even when the event was eaten) plus a raw
 * FINGER_UP/_CANCELED peek. Existing mouse-event handling is unchanged;
 * single-shot (down-edge) games are unaffected since the net only ever
 * releases, never presses.
 */

#include "manymouse.h"

#if defined(__ANDROID__)

#include <SDL3/SDL.h>
#include "../video/video.h"

static bool g_initialized = false;

// #197 - lost-release safety net. In MANY_MOUSE mode hypseus's own
// SDL_check_input() does `while (SDL_PollEvent(&e)) process_event(&e);`,
// and manymouse_update_mice() runs *inside* process_event() - so
// SDL_PollEvent dequeues (and, for a mouse event in MANY_MOUSE mode,
// discards via `default:`) one event per iteration before this driver's
// SDL_PeepEvents ever runs. If the MOUSE_BUTTON_UP for a tap is the event
// SDL_PollEvent grabbed, this driver never sees it, the ManyMouse button
// stays latched "down", and a level-triggered automatic weapon keeps
// firing until the mag empties.
//
// SDL updates its own internal button state on every pump regardless of
// who consumes the event, so SDL_GetMouseState() is authoritative even
// when the UP was eaten. Each poll, if we think a trigger is held but
// SDL_GetMouseState() shows that button released, synthesise the release.
// Second signal: raw SDL_EVENT_FINGER_UP / _CANCELED - Android can send
// CANCELED instead of UP when a tap slides slightly, and SDL may then not
// synthesise a mouse-up at all, so GetMouseState alone would miss it.
// Both paths only ever *release*, never press, so single-shot (down-edge)
// games are unaffected.
static bool g_trigger_held = false;
static unsigned int g_held_item = 0;

static SDL_MouseButtonFlags item_mask(unsigned int item)
{
    if (item == 1) return SDL_BUTTON_MMASK;
    if (item == 2) return SDL_BUTTON_RMASK;
    return SDL_BUTTON_LMASK;
}

// Up to 3 ManyMouseEvents can come out of a single dequeued SDL event (an
// X move, a Y move, and - for a button event - the button itself), but
// ManyMouse's poll() contract returns one event per call. This tiny FIFO
// holds whichever ones haven't been returned yet.
static ManyMouseEvent g_pending[3];
static int g_pending_count = 0;
static int g_pending_head = 0;

static void push_pending(ManyMouseEventType type, unsigned int item, int value, int minval, int maxval)
{
    if (g_pending_count >= 3) return; // can't happen with current callers, but stay safe.
    ManyMouseEvent &ev = g_pending[(g_pending_head + g_pending_count) % 3];
    ev.type = type;
    ev.device = 0;
    ev.item = item;
    ev.value = value;
    ev.minval = minval;
    ev.maxval = maxval;
    g_pending_count++;
}

static bool pop_pending(ManyMouseEvent *out)
{
    if (g_pending_count == 0) return false;
    *out = g_pending[g_pending_head];
    g_pending_head = (g_pending_head + 1) % 3;
    g_pending_count--;
    return true;
}

// Queues an X + Y ABSMOTION pair from a raw window-pixel position.
// Deliberately NOT clamped to hypseus's on-screen video rect: tapping the
// black pillarbox/letterbox bars on either side is this touchscreen's
// equivalent of a real lightgun pointed off the CRT, which is exactly the
// gesture these games use for "reload" - clamping it into the visible rect
// silently turned an off-screen shot into an edge-of-screen shot and ate
// that gesture entirely (found on real hardware, 2026-09-06). Reporting
// the rect as minval/maxval but letting value fall outside it lets
// manymouse_update_mice()'s existing rescale (input.cpp) extrapolate to a
// genuinely negative/out-of-range game coordinate, same as a real
// absolute-position lightgun would report when aimed off-screen.
static void queue_position(float raw_x, float raw_y)
{
    const int rect_x = video::get_scaling_rect_x();
    const int rect_y = video::get_scaling_rect_y();
    const int rect_w = video::get_scaling_rect_w();
    const int rect_h = video::get_scaling_rect_h();

    if (rect_w <= 0 || rect_h <= 0)
        return; // not rendered yet - drop rather than feed a zero range.

    push_pending(MANYMOUSE_EVENT_ABSMOTION, 0, (int)raw_x, rect_x, rect_x + rect_w);
    push_pending(MANYMOUSE_EVENT_ABSMOTION, 1, (int)raw_y, rect_y, rect_y + rect_h);
}

static int android_touch_init(const unsigned char filter)
{
    (void)filter; // touch has no "relative-only" hardware to filter out.

    if (g_initialized)
        return -1;

    g_initialized = true;
    g_pending_count = 0;
    g_pending_head = 0;
    g_trigger_held = false;
    g_held_item = 0;
    return 1; // exactly one virtual device: the touchscreen/pointer itself.
}

static void android_touch_quit(void)
{
    g_initialized = false;
    g_pending_count = 0;
    g_trigger_held = false;
}

static const char *android_touch_name(unsigned int index)
{
    return (index == 0) ? "Hypdroid Touch" : nullptr;
}

static int android_touch_poll(ManyMouseEvent *ev)
{
    if (!g_initialized)
        return 0;

    // #197 - lost-release safety net (see notes above). Runs only while the
    // FIFO is idle so it can't interleave with a real event's ABSMOTION
    // pair. Only ever releases - never presses.
    if (g_trigger_held && g_pending_count == 0)
    {
        bool release = false;

        // (a) SDL's own button state disagrees - the UP was eaten by
        // SDL_check_input()'s SDL_PollEvent before we ran.
        if ((SDL_GetMouseState(NULL, NULL) & item_mask(g_held_item)) == 0)
        {
            release = true;
        }
        else
        {
            // (b) a raw FINGER_UP / _CANCELED (Android sent CANCELED, or
            // SDL never synthesised the mouse-up). Nothing else in hypseus
            // consumes SDL finger events, so GETEVENT is safe.
            SDL_Event fev;
            while (SDL_PeepEvents(&fev, 1, SDL_GETEVENT,
                                  SDL_EVENT_FINGER_DOWN, SDL_EVENT_FINGER_CANCELED) > 0)
            {
                if (fev.type == SDL_EVENT_FINGER_UP ||
                    fev.type == SDL_EVENT_FINGER_CANCELED)
                {
                    release = true;
                    break;
                }
            }
        }

        if (release)
        {
            push_pending(MANYMOUSE_EVENT_BUTTON, g_held_item, 0, 0, 0);
            g_trigger_held = false;
        }
    }

    if (pop_pending(ev))
        return 1;

    SDL_Event sdl_ev;
    if (SDL_PeepEvents(&sdl_ev, 1, SDL_GETEVENT,
                        SDL_EVENT_MOUSE_MOTION, SDL_EVENT_MOUSE_BUTTON_UP) <= 0)
        return 0;

    switch (sdl_ev.type)
    {
    case SDL_EVENT_MOUSE_MOTION:
        queue_position(sdl_ev.motion.x, sdl_ev.motion.y);
        return pop_pending(ev) ? 1 : 0;

    case SDL_EVENT_MOUSE_BUTTON_DOWN:
    case SDL_EVENT_MOUSE_BUTTON_UP:
    {
        // A stationary tap never gets a separate SDL_EVENT_MOUSE_MOTION -
        // see this file's header comment for the real on-device finding
        // that made this necessary. SDL_MouseButtonEvent carries its own
        // x/y, so use those directly instead of relying on a prior motion
        // event having already set the position.
        queue_position(sdl_ev.button.x, sdl_ev.button.y);

        // 0/1/2 matches every other driver's left/middle/right convention
        // (see mouse_buttons_map's setup in input.cpp's set_mouse_mode()).
        // A tap synthesizes SDL_BUTTON_LEFT; a real attached mouse/lightgun
        // reports whichever real button was pressed.
        unsigned int item = 1;
        if (sdl_ev.button.button == SDL_BUTTON_LEFT) item = 0;
        else if (sdl_ev.button.button == SDL_BUTTON_RIGHT) item = 2;

        push_pending(MANYMOUSE_EVENT_BUTTON, item, sdl_ev.button.down ? 1 : 0, 0, 0);

        // #197 - track what we last told ManyMouse so the safety net above
        // knows a trigger is (believed) held. Any button source is fine to
        // track: the net compares against SDL_GetMouseState(), which stays
        // correct for a real mouse held for auto-fire, so it self-corrects.
        if (sdl_ev.button.down) { g_trigger_held = true; g_held_item = item; }
        else                    { g_trigger_held = false; }

        return pop_pending(ev) ? 1 : 0;
    }
    default:
        return 0;
    }
}

static const ManyMouseDriver ManyMouseDriver_interface =
{
    "Android touch/pointer interface",
    android_touch_init,
    android_touch_quit,
    android_touch_name,
    android_touch_poll
};

extern "C" const ManyMouseDriver *ManyMouseDriver_android = &ManyMouseDriver_interface;

#else

extern "C" const ManyMouseDriver *ManyMouseDriver_android = nullptr;

#endif // __ANDROID__

/* end of android_touch.cpp ... */
