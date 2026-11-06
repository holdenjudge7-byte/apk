/**
 * native-lib.cpp
 *
 * JNI bridge for Stockfish 11 on Android (armeabi-v7a / arm64-v8a).
 *
 * Design — direct C++ API, no pipe/dup2:
 *  - Calls Stockfish's internal C++ functions directly instead of
 *    routing through its UCI stdin/stdout interface.
 *  - This avoids the dup2() crash on 32-bit ARM Android 8.x that
 *    happens when STDIN/STDOUT are shared with the WebView process.
 *  - SF11 exposes its internals cleanly through position.h / search.h /
 *    uci.h so we can drive it without the UCI text loop.
 *
 * JNI functions exposed:
 *   engineInit()                       — call once; initialises all SF modules
 *   engineSetElo(elo: Int)             — UCI_LimitStrength / UCI_Elo
 *   engineGetMoves(fen, movetime): String
 *       returns "move1,cp1|move2,cp2|..." (up to 7 entries, pipe-delimited)
 *   engineStop()                       — graceful shutdown
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <sstream>
#include <vector>
#include <algorithm>
#include <mutex>
#include <atomic>
#include <cstring>

// ── Stockfish 11 headers ──────────────────────────────────────────────────────
// These are present after the CI step copies SF11/src/* here.
#include "bitboard.h"
#include "evaluate.h"
#include "position.h"
#include "search.h"
#include "thread.h"
#include "tt.h"
#include "uci.h"
#include "syzygy/tbprobe.h"

#define TAG "OrstedEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace Stockfish;

static std::atomic<bool> g_initialised{false};
static std::mutex         g_searchMutex;

// UCI option helpers — SF11 uses Options[] map
static void setOption(const char* name, const char* value) {
    // SF11 UCI options are set via Options[name] = value string
    if (Options.count(name)) {
        Options[name] = std::string(value);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI: engineInit
// Called once from Kotlin on a background thread.
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT void JNICALL
Java_com_orsted_chessoverlay_MainActivity_engineInit(JNIEnv*, jobject) {
    if (g_initialised.exchange(true)) {
        LOGI("engineInit: already initialised");
        return;
    }

    // Initialise all SF11 subsystems (no NNUE — SF11 classical eval)
    UCI::init(Options);
    Tune::init();
    PSQT::init();
    Bitboards::init();
    Position::init();
    Bitbases::init();
    Search::init();
    Eval::init();
    Pawns::init();
    Threads.set(1);         // single thread — safe on 32-bit low-RAM devices
    TT.resize(16);          // 16 MB hash — fits in Oppo A5s RAM budget

    LOGI("engineInit: Stockfish 11 ready");
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI: engineSetElo
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT void JNICALL
Java_com_orsted_chessoverlay_MainActivity_engineSetElo(JNIEnv*, jobject, jint elo) {
    if (!g_initialised.load()) return;
    if (elo >= 2800) {
        setOption("UCI_LimitStrength", "false");
    } else {
        setOption("UCI_LimitStrength", "true");
        setOption("UCI_Elo", std::to_string(static_cast<int>(elo)).c_str());
    }
    LOGI("engineSetElo: %d", (int)elo);
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI: engineGetMoves
// Runs a MultiPV=7 search and returns up to 7 moves in the format:
//   "e2e4,+0.30|d2d4,+0.25|g1f3,+0.20|..."
// Each entry is "uciMove,evalString". Returns "" on failure.
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jstring JNICALL
Java_com_orsted_chessoverlay_MainActivity_engineGetMoves(
        JNIEnv* env, jobject, jstring fenJ, jint movetime) {

    if (!g_initialised.load()) return env->NewStringUTF("");

    const char* fenC = env->GetStringUTFChars(fenJ, nullptr);
    std::string fen(fenC ? fenC : "");
    if (fenC) env->ReleaseStringUTFChars(fenJ, fenC);

    if (fen.empty()) return env->NewStringUTF("");

    std::lock_guard<std::mutex> lock(g_searchMutex);

    try {
        // --- Set up position ---
        StateListPtr states(new std::deque<StateInfo>(1));
        Position pos;
        pos.set(fen, false, &states->back(), Threads.main());

        // --- Configure search limits ---
        Search::LimitsType limits;
        limits.startTime  = now();
        limits.movetime   = static_cast<int>(movetime);

        // MultiPV 7 — mirrors ARROW_MULTIPV from the userscript
        setOption("MultiPV", "7");

        // --- Run the search (blocking on main thread) ---
        Threads.start_thinking(pos, states, limits, false);
        Threads.main()->wait_for_search_finished();

        // --- Collect results from rootMoves ---
        const Search::RootMoves& rootMoves = Threads.main()->rootMoves;

        if (rootMoves.empty()) return env->NewStringUTF("");

        std::ostringstream result;
        int count = 0;
        const int MAX_PV = 7;

        for (int i = 0; i < (int)rootMoves.size() && count < MAX_PV; ++i) {
            const Search::RootMove& rm = rootMoves[i];
            if (rm.pv.empty()) continue;

            // UCI move string from the first ply of the PV
            std::string uciMove = UCI::move(rm.pv[0], pos.is_chess960());

            // Convert centipawns to pawn units; cap mate scores at ±100
            Value score = rm.score;
            std::string evalStr;
            if (abs(score) >= VALUE_MATE_IN_MAX_PLY) {
                evalStr = score > 0 ? "+100.00" : "-100.00";
            } else {
                float pawns = static_cast<float>(score) / 100.0f;
                char buf[32];
                snprintf(buf, sizeof(buf), "%+.2f", pawns);
                evalStr = buf;
            }

            if (count > 0) result << "|";
            result << uciMove << "," << evalStr;
            ++count;
        }

        return env->NewStringUTF(result.str().c_str());

    } catch (const std::exception& e) {
        LOGE("engineGetMoves exception: %s", e.what());
        return env->NewStringUTF("");
    } catch (...) {
        LOGE("engineGetMoves unknown exception");
        return env->NewStringUTF("");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI: engineStop
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT void JNICALL
Java_com_orsted_chessoverlay_MainActivity_engineStop(JNIEnv*, jobject) {
    if (!g_initialised.load()) return;
    Threads.set(0);
    LOGI("engineStop: done");
}
