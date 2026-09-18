#include <signal.h>
#include <string.h>
#include <android/log.h>

#define TAG "XiluAlarmGuard"

static void alrm_handler(int sig, siginfo_t *si, void *uc) {
    (void) sig;
    (void) uc;
    __android_log_print(ANDROID_LOG_ERROR, TAG,
                        "SIGALRM captured and IGNORED: si_code=%d si_pid=%d si_uid=%d",
                        si ? si->si_code : -1, si ? si->si_pid : -1, si ? si->si_uid : -1);
}

__attribute__((constructor)) static void install_guard(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = alrm_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    int rc = sigaction(SIGALRM, &sa, NULL);
    __android_log_print(ANDROID_LOG_ERROR, TAG, "installed SIGALRM handler, rc=%d", rc);
}
