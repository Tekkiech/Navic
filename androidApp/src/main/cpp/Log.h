// Minimal Android logging macros for the JamesDSP native wrapper.
//
// Adapted from timschneeb/RootlessJamesDSP's libcrashlytics-connector/Log.h,
// with the Crashlytics reporting hook removed — Navic Phase 1 doesn't ship
// crash reporting for the native layer, so these macros just forward to
// __android_log_print.
#ifndef NAVIC_JDSP_LOG_H
#define NAVIC_JDSP_LOG_H

#ifndef TAG
#define TAG "Global_JNI"
#endif

#include <android/log.h>

// Trailing semicolons are intentional: JamesDspWrapper.cpp (ported from RootlessJamesDSP)
// calls these as e.g. "LOGE("msg")" with no semicolon of its own, matching the original
// Log.h's convention where the macro body supplied the statement terminator itself.
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__);
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__);
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__);
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__);
#define LOGF(...) __android_log_print(ANDROID_LOG_FATAL, TAG, __VA_ARGS__);
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, TAG, __VA_ARGS__);

#endif // NAVIC_JDSP_LOG_H
