#define _GNU_SOURCE
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <netdb.h>
#include <signal.h>
#include <android/log.h>

#define LOG_TAG "NativeTcpRelay"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef SPLICE_F_MOVE
#define SPLICE_F_MOVE 1
#endif
#ifndef SPLICE_F_MORE
#define SPLICE_F_MORE 4
#endif
#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

typedef struct {
    int src_fd;
    int dst_fd;
} direction_args_t;

static void relay_fallback(int src_fd, int dst_fd, const char* reason) {
    LOGW("Falling back to userland recv/send relay (src_fd=%d, dst_fd=%d): %s",
         src_fd, dst_fd, reason ? reason : "unspecified");
    char buffer[16384];
    ssize_t nread;
    while ((nread = recv(src_fd, buffer, sizeof(buffer), 0)) > 0) {
        ssize_t nwritten = 0;
        while (nwritten < nread) {
            ssize_t ret = send(dst_fd, buffer + nwritten, nread - nwritten, MSG_NOSIGNAL);
            if (ret <= 0) break;
            nwritten += ret;
        }
        if (nwritten < nread) break;
    }
}

static void relay_direction(int src_fd, int dst_fd) {
    int pipefd[2];
    if (pipe(pipefd) < 0) {
        char reason[128];
        snprintf(reason, sizeof(reason), "pipe() failed: errno %d (%s)", errno, strerror(errno));
        relay_fallback(src_fd, dst_fd, reason);
        return;
    }

    while (1) {
        ssize_t nread = splice(src_fd, NULL, pipefd[1], NULL, 65536, SPLICE_F_MOVE | SPLICE_F_MORE);
        if (nread < 0) {
            if (errno == EINVAL || errno == ENOSYS) {
                int err = errno;
                close(pipefd[0]);
                close(pipefd[1]);
                char reason[128];
                snprintf(reason, sizeof(reason), "splice() syscall unsupported on this device/kernel: errno %d (%s)", err, strerror(err));
                relay_fallback(src_fd, dst_fd, reason);
                return;
            }
            break;
        } else if (nread == 0) {
            break; // EOF
        }

        ssize_t nwritten = 0;
        while (nwritten < nread) {
            ssize_t ret = splice(pipefd[0], NULL, dst_fd, NULL, nread - nwritten, SPLICE_F_MOVE | SPLICE_F_MORE);
            if (ret <= 0) break;
            nwritten += ret;
        }
        if (nwritten < nread) break;
    }

    close(pipefd[0]);
    close(pipefd[1]);
}

static void* relay_thread(void* arg) {
    direction_args_t* args = (direction_args_t*)arg;
    relay_direction(args->src_fd, args->dst_fd);
    shutdown(args->dst_fd, SHUT_WR);
    shutdown(args->src_fd, SHUT_RD);
    free(args);
    return NULL;
}

JNIEXPORT jint JNICALL
Java_net_ldmsys_tcprelay_connector_NativeTcpConnector_nativeStartRelay(
        JNIEnv *env, jclass clazz, jint clientFd, jstring jhost, jint port) {

    const char *host = (*env)->GetStringUTFChars(env, jhost, NULL);
    if (!host) return -1;

    char port_str[16];
    snprintf(port_str, sizeof(port_str), "%d", port);

    struct addrinfo hints, *res = NULL, *rp;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    int err = getaddrinfo(host, port_str, &hints, &res);
    if (err != 0) {
        LOGE("Failed to resolve host %s: %s", host, gai_strerror(err));
        (*env)->ReleaseStringUTFChars(env, jhost, host);
        return -2;
    }

    int dest_fd = -1;
    for (rp = res; rp != NULL; rp = rp->ai_next) {
        dest_fd = socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
        if (dest_fd < 0) continue;
        if (connect(dest_fd, rp->ai_addr, rp->ai_addrlen) == 0) break;
        close(dest_fd);
        dest_fd = -1;
    }
    freeaddrinfo(res);

    if (dest_fd < 0) {
        LOGE("Failed to connect to %s:%d", host, port);
        (*env)->ReleaseStringUTFChars(env, jhost, host);
        return -3;
    }

    signal(SIGPIPE, SIG_IGN);

    int flag = 1;
    setsockopt(clientFd, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));
    setsockopt(dest_fd, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));
    setsockopt(clientFd, SOL_SOCKET, SO_KEEPALIVE, &flag, sizeof(flag));
    setsockopt(dest_fd, SOL_SOCKET, SO_KEEPALIVE, &flag, sizeof(flag));

    LOGI("Relaying natively between client fd %d and dest fd %d (%s:%d)", clientFd, dest_fd, host, port);
    (*env)->ReleaseStringUTFChars(env, jhost, host);

    direction_args_t* args_c2d = (direction_args_t*)malloc(sizeof(direction_args_t));
    if (!args_c2d) {
        LOGE("Failed to allocate memory for direction_args_t");
        close(dest_fd);
        close(clientFd);
        return -4;
    }
    args_c2d->src_fd = clientFd;
    args_c2d->dst_fd = dest_fd;

    pthread_t thread;
    pthread_create(&thread, NULL, relay_thread, args_c2d);

    relay_direction(dest_fd, clientFd);

    // Unblock the worker thread immediately in case the client is idle
    shutdown(clientFd, SHUT_RDWR);
    shutdown(dest_fd, SHUT_RDWR);

    pthread_join(thread, NULL);

    close(dest_fd);
    close(clientFd);

    LOGI("Relay finished for client fd %d", clientFd);
    return 0;
}
