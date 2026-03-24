/* Fake android/log.h for Linux — redirects to stderr */
#ifndef FAKE_ANDROID_LOG_H
#define FAKE_ANDROID_LOG_H

#include <stdio.h>

#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_ERROR 6

#define __android_log_print(prio, tag, fmt, ...) \
    fprintf(stderr, "[%s] " fmt "\n", tag, ##__VA_ARGS__)

#endif
