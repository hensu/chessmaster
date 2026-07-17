// EngineKit — Chessmaster
// GPL-3.0-or-later
//
// C bridge to the embedded Stockfish engine. Stockfish's UCI loop runs on
// its own thread with the process stdin/stdout redirected onto pipes; the
// Swift side writes commands to the command fd and reads replies from the
// output fd. One engine per process.

#ifndef CM_STOCKFISH_BRIDGE_H
#define CM_STOCKFISH_BRIDGE_H

#ifdef __cplusplus
extern "C" {
#endif

/// Starts the engine thread. Returns 0 on success, -1 if already started.
int cm_stockfish_start(void);

/// File descriptor to write UCI commands to (newline-terminated).
int cm_stockfish_command_fd(void);

/// File descriptor to read UCI output lines from.
int cm_stockfish_output_fd(void);

#ifdef __cplusplus
}
#endif

#endif
