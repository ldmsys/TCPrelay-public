package main

/*
#include <jni.h>
#include <stdlib.h>
#include <android/log.h>

static inline const char* getStringUTFChars(JNIEnv* env, jstring str) {
    return (*env)->GetStringUTFChars(env, str, NULL);
}

static inline void releaseStringUTFChars(JNIEnv* env, jstring str, const char* chars) {
    (*env)->ReleaseStringUTFChars(env, str, chars);
}

static inline void logError(const char* msg) {
    __android_log_print(ANDROID_LOG_ERROR, "TailcatBridge", "%s", msg);
}

static inline void logInfo(const char* msg) {
    __android_log_print(ANDROID_LOG_INFO, "TailcatBridge", "%s", msg);
}
*/
import "C"

import (
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"runtime/debug"
	"sync"
	"time"
	"unsafe"

	"github.com/tailscale/tailcat"
	"tailscale.com/net/netmon"

	_ "tailscale.com/feature/androidbin"
	_ "tailscale.com/feature/androiddns"
)

func logError(msg string) {
	cStr := C.CString(msg)
	defer C.free(unsafe.Pointer(cStr))
	C.logError(cStr)
}

func logInfo(msg string) {
	cStr := C.CString(msg)
	defer C.free(unsafe.Pointer(cStr))
	C.logInfo(cStr)
}

func init() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		var addrs []net.Addr
		for _, target := range []string{"8.8.8.8:53", "1.1.1.1:53"} {
			if c, err := net.Dial("udp", target); err == nil {
				if ua, ok := c.LocalAddr().(*net.UDPAddr); ok && !ua.IP.IsLoopback() && !ua.IP.IsUnspecified() {
					addrs = append(addrs, &net.IPNet{IP: ua.IP, Mask: net.CIDRMask(32, 32)})
					c.Close()
					break
				}
				c.Close()
			}
		}
		if len(addrs) == 0 {
			addrs = append(addrs, &net.IPNet{IP: net.ParseIP("127.0.0.1"), Mask: net.CIDRMask(8, 32)})
		}
		return []netmon.Interface{
			{
				Interface: &net.Interface{
					MTU:   1500,
					Name:  "android0",
					Flags: net.FlagUp | net.FlagRunning,
				},
				AltAddrs: addrs,
			},
		}, nil
	})
}

//export Java_net_ldmsys_tcprelay_tailcat_TailcatBridge_nativeStartRelay
func Java_net_ldmsys_tcprelay_tailcat_TailcatBridge_nativeStartRelay(
	env *C.JNIEnv,
	clazz C.jclass,
	clientFd C.jint,
	jaddress C.jstring,
	port C.jint,
) C.jint {
	var retCode C.jint = 0
	func() {
		defer func() {
			if r := recover(); r != nil {
				stack := debug.Stack()
				errMsg := fmt.Sprintf("PANIC recovered: %v\n%s", r, string(stack))
				logError(errMsg)
				retCode = -99
			}
		}()

		cAddr := C.getStringUTFChars(env, jaddress)
		if cAddr == nil {
			logError("Failed to get UTF chars for tailcat address")
			retCode = -1
			return
		}
		addrStr := C.GoString(cAddr)
		C.releaseStringUTFChars(env, jaddress, cAddr)

		targetPort := uint16(port)
		if targetPort == 0 {
			targetPort = 22
		}

		logInfo(fmt.Sprintf("Tailcat dialing address %s port %d", addrStr, targetPort))

		client := tailcat.NewClient(tailcat.Addr(addrStr))
		defer client.Close()

		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()

		tcConn, err := client.DialTCPPort(ctx, targetPort)
		if err != nil {
			errMsg := fmt.Sprintf("Tailcat Dial failed: %v", err)
			logError(errMsg)
			retCode = -2
			return
		}
		defer tcConn.Close()

		logInfo("Tailcat tunnel established successfully")

		clientFile := os.NewFile(uintptr(clientFd), "client_fd")
		clientConn, err := net.FileConn(clientFile)
		_ = clientFile.Close()
		if err != nil {
			errMsg := fmt.Sprintf("Failed to wrap client socket FD: %v", err)
			logError(errMsg)
			retCode = -3
			return
		}
		defer clientConn.Close()

		if tc, ok := clientConn.(*net.TCPConn); ok {
			_ = tc.SetNoDelay(true)
		}

		var wg sync.WaitGroup
		wg.Add(2)

		go func() {
			defer wg.Done()
			_, _ = io.Copy(tcConn, clientConn)
			if tc, ok := tcConn.(interface{ CloseWrite() error }); ok {
				_ = tc.CloseWrite()
			} else {
				_ = tcConn.Close()
			}
		}()

		go func() {
			defer wg.Done()
			_, _ = io.Copy(clientConn, tcConn)
			if tc, ok := clientConn.(interface{ CloseWrite() error }); ok {
				_ = tc.CloseWrite()
			} else {
				_ = clientConn.Close()
			}
		}()

		wg.Wait()
		logInfo("Tailcat relay connection closed")
	}()
	return retCode
}

func main() {}
