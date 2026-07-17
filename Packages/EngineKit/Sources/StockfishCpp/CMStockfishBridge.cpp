// EngineKit — Chessmaster
// GPL-3.0-or-later

#include "include/CMStockfishBridge.h"

#include <atomic>
#include <iostream>
#include <memory>
#include <thread>
#include <unistd.h>

#include "stockfish/bitboard.h"
#include "stockfish/misc.h"
#include "stockfish/position.h"
#include "stockfish/tune.h"
#include "stockfish/uci.h"

namespace {
std::atomic<bool> g_started{false};
int g_command_fd = -1;
int g_output_fd = -1;
}

extern "C" {

int cm_stockfish_start(void) {
    bool expected = false;
    if (!g_started.compare_exchange_strong(expected, true)) {
        return -1;
    }

    // Engine stdin <- inPipe, engine stdout -> outPipe. The engine talks
    // std::cin/std::cout, so the process-wide descriptors are redirected.
    int inPipe[2];
    int outPipe[2];
    if (pipe(inPipe) != 0 || pipe(outPipe) != 0) {
        g_started = false;
        return -1;
    }
    dup2(inPipe[0], STDIN_FILENO);
    dup2(outPipe[1], STDOUT_FILENO);
    g_command_fd = inPipe[1];
    g_output_fd = outPipe[0];

    std::thread([] {
        std::cout << Stockfish::engine_info() << std::endl;
        Stockfish::Bitboards::init();
        Stockfish::Position::init();

        static char arg0[] = "stockfish";
        char* argv[] = {arg0};
        auto uci = std::make_unique<Stockfish::UCIEngine>(1, argv);
        Stockfish::Tune::init(uci->engine_options());
        uci->loop();
    }).detach();

    return 0;
}

int cm_stockfish_command_fd(void) { return g_command_fd; }
int cm_stockfish_output_fd(void) { return g_output_fd; }

}
