/*
 * Compatibility shim — replaces <android/log.h> on Linux.
 * Maps Android log macros to stderr fprintf.
 */
#ifndef ANDROID_LOG_COMPAT_H
#define ANDROID_LOG_COMPAT_H

#include <stdio.h>

#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_ERROR 6

#define __android_log_print(prio, tag, fmt, ...) \
    fprintf(stderr, "[%s] " fmt "\n", tag, ##__VA_ARGS__)

#endif
