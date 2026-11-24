// EngineKit — Chessmaster (Android)
// GPL-3.0-or-later
//
// JNI face of the stdio bridge (CMStockfishBridge): start the engine thread, write UCI
// commands to its stdin pipe, read its stdout pipe one line at a time.

#include <jni.h>
#include <string>
#include <unistd.h>

#include "include/CMStockfishBridge.h"

extern "C" {

JNIEXPORT jint JNICALL
Java_com_hensu_chessmaster_StockfishBridge_start(JNIEnv*, jobject) {
    return cm_stockfish_start();
}

JNIEXPORT void JNICALL
Java_com_hensu_chessmaster_StockfishBridge_send(JNIEnv* env, jobject, jstring command) {
    const char* chars = env->GetStringUTFChars(command, nullptr);
    std::string line(chars);
    env->ReleaseStringUTFChars(command, chars);
    line += "\n";
    const char* p = line.data();
    size_t remaining = line.size();
    int fd = cm_stockfish_command_fd();
    while (remaining > 0) {
        ssize_t written = write(fd, p, remaining);
        if (written <= 0) return;
        remaining -= static_cast<size_t>(written);
        p += written;
    }
}

// Blocks until the engine prints a full line; returns null at end of stream.
JNIEXPORT jstring JNICALL
Java_com_hensu_chessmaster_StockfishBridge_readLine(JNIEnv* env, jobject) {
    static std::string buffer;   // one reader thread only
    int fd = cm_stockfish_output_fd();
    for (;;) {
        size_t newline = buffer.find('\n');
        if (newline != std::string::npos) {
            std::string line = buffer.substr(0, newline);
            buffer.erase(0, newline + 1);
            if (!line.empty() && line.back() == '\r') line.pop_back();
            return env->NewStringUTF(line.c_str());
        }
        char chunk[4096];
        ssize_t n = read(fd, chunk, sizeof(chunk));
        if (n <= 0) return nullptr;
        buffer.append(chunk, static_cast<size_t>(n));
    }
}

}
