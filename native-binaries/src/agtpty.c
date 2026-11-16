/*
 * libagtpty: the AGT app's native terminal bridge.
 *
 * Creates a real pseudo-terminal and forks a child process onto its
 * slave side, the pattern the Termux source study documented
 * (termux-app, terminal-emulator/src/main/jni/termux.c): open
 * /dev/ptmx, set the termios and window size on the master, fork;
 * the child unblocks its signals, starts a new session, opens the
 * slave as its controlling terminal on stdin/stdout/stderr, closes
 * every other descriptor, installs exactly the environment it was
 * given, changes to the working directory and execs the program.
 * The parent keeps the master fd: everything the program prints
 * arrives there, and everything written there is the program's
 * keyboard. No translation layer sits between the app and the
 * process; the child is a native bionic process of the app itself.
 *
 * Written for AGT (com.novainc.agt, linux/NativePty.kt). Failure
 * reporting: createSubprocess returns the failing stage as a
 * negative code in slot 0 (with slot 1 unused) so the Kotlin side
 * can name the stage in its own error message:
 *   -1 open /dev/ptmx    -2 grantpt/unlockpt/ptsname
 *   -3 fork              -4 slave open in child (child exits 127)
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
#include <dirent.h>
#include <errno.h>

/* Create a subprocess on a fresh pty.
 *   cmd:  absolute path of the program
 *   argv: program arguments including argv[0]
 *   envp: "KEY=value" strings, the child's whole environment
 *   cwd:  working directory for the child
 * Returns a two-element int array: [masterFd, pid], or [stage, 0]
 * with stage negative on failure. */
JNIEXPORT jintArray JNICALL
Java_com_novainc_agt_linux_NativePty_createSubprocess(
        JNIEnv *env, jclass clazz,
        jstring cmd, jobjectArray argv, jobjectArray envp,
        jstring cwd, jint rows, jint cols) {
    jintArray result = (*env)->NewIntArray(env, 2);
    jint out[2] = {-1, 0};
    if (result == NULL) return NULL;

    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) { out[0] = -1; goto done; }

    char devname[64];
    if (grantpt(ptm) || unlockpt(ptm) || ptsname_r(ptm, devname, sizeof(devname))) {
        close(ptm);
        out[0] = -2;
        goto done;
    }

    /* UTF-8 input, and no XON/XOFF flow control: Ctrl-S must reach
     * the program, not freeze the terminal. */
    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(ptm, TCSANOW, &tios);
    }
    struct winsize sz;
    memset(&sz, 0, sizeof(sz));
    sz.ws_row = (unsigned short) rows;
    sz.ws_col = (unsigned short) cols;
    ioctl(ptm, TIOCSWINSZ, &sz);

    /* Marshal the strings before fork so the child only touches
     * plain C memory. */
    const char *cmdStr = (*env)->GetStringUTFChars(env, cmd, NULL);
    const char *cwdStr = (*env)->GetStringUTFChars(env, cwd, NULL);
    jsize argc = (*env)->GetArrayLength(env, argv);
    jsize envc = (*env)->GetArrayLength(env, envp);
    char **cargv = calloc((size_t) argc + 1, sizeof(char *));
    char **cenvp = calloc((size_t) envc + 1, sizeof(char *));
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, argv, i);
        cargv[i] = strdup((*env)->GetStringUTFChars(env, s, NULL));
    }
    for (jsize i = 0; i < envc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, envp, i);
        cenvp[i] = strdup((*env)->GetStringUTFChars(env, s, NULL));
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(ptm);
        out[0] = -3;
        goto done;
    }
    if (pid > 0) {
        out[0] = ptm;
        out[1] = (jint) pid;
        goto done;
    }

    /* Child. Only async-signal-safe work from here to exec. */
    {
        sigset_t all;
        sigfillset(&all);
        sigprocmask(SIG_UNBLOCK, &all, NULL);

        close(ptm);
        setsid();

        int pts = open(devname, O_RDWR);
        if (pts < 0) _exit(127);
        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);
        if (pts > 2) close(pts);

        /* Close every other inherited descriptor. */
        DIR *fdDir = opendir("/proc/self/fd");
        if (fdDir != NULL) {
            int dfd = dirfd(fdDir);
            struct dirent *entry;
            while ((entry = readdir(fdDir)) != NULL) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != dfd) close(fd);
            }
            closedir(fdDir);
        }

        clearenv();
        for (char **e = cenvp; *e; ++e) putenv(*e);

        if (cwdStr && chdir(cwdStr) != 0) {
            perror("chdir");
        }
        execv(cmdStr, cargv);
        perror("exec");
        _exit(127);
    }

done:
    (*env)->SetIntArrayRegion(env, result, 0, 2, out);
    return result;
}

/* Wait for a child created by createSubprocess. Returns the exit
 * code, or -signal when the child died by a signal, or -1 when the
 * wait itself failed. */
JNIEXPORT jint JNICALL
Java_com_novainc_agt_linux_NativePty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t) pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}

/* Send a signal to a child created by createSubprocess. */
JNIEXPORT void JNICALL
Java_com_novainc_agt_linux_NativePty_killProcess(JNIEnv *env, jclass clazz, jint pid, jint sig) {
    (void) env; (void) clazz;
    if (pid > 0) kill((pid_t) pid, (int) sig);
}

/* Close a pty master fd returned by createSubprocess. */
JNIEXPORT void JNICALL
Java_com_novainc_agt_linux_NativePty_closeFd(JNIEnv *env, jclass clazz, jint fd) {
    (void) env; (void) clazz;
    if (fd >= 0) close((int) fd);
}

/* Resize the terminal behind a pty master fd. */
JNIEXPORT void JNICALL
Java_com_novainc_agt_linux_NativePty_setWindowSize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    (void) env; (void) clazz;
    struct winsize sz;
    memset(&sz, 0, sizeof(sz));
    sz.ws_row = (unsigned short) rows;
    sz.ws_col = (unsigned short) cols;
    ioctl((int) fd, TIOCSWINSZ, &sz);
}
