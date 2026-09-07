set( LIB_SOURCES
    manymouse.c linux_evdev.c macosx_hidmanager.c
    macosx_hidutilities.c windows_wminput.c
    x11_xinput2.c android_touch.cpp
)

set( LIB_HEADERS
    manymouse.h
)

add_library( manymouse ${LIB_SOURCES} ${LIB_HEADERS} )
# #185 - android_touch.cpp (the Android ManyMouse backend) needs SDL3's
# headers (<SDL3/SDL.h>) and, transitively through video.h, SDL3's basic
# typedefs (Uint16 etc.) - every other backend here is plain C with no SDL
# dependency at all, so this wasn't needed before.
target_link_libraries( manymouse PRIVATE ${CMAKE_DL_LIBS} sdl3_deps )

