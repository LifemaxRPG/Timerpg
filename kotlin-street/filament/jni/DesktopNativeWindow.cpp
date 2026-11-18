// Stands in for filament-android's nativewindow/Android.cpp when the JNI bindings are built for a Linux desktop.
// The trial renders only to headless swap chains (Engine.createSwapChain(width, height, flags)), so there is no
// window to take from a Java object: these return nothing and release nothing.
#include <jni.h>

extern "C" {

void* getNativeWindow(JNIEnv*, jclass, jobject) {
    return nullptr;
}

void releaseNativeWindow(void*) {
}

jlong createNativeSurface(jint, jint) {
    return 0;
}

void destroyNativeSurface(jlong) {
}

}
