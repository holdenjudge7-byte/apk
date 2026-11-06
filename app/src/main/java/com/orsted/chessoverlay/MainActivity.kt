package com.orsted.chessoverlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.math.*

private const val TAG = "OrstedMain"

// ─────────────────────────────────────────────────────────────────────────────
// Opening book — ported directly from v1.0.16
// ─────────────────────────────────────────────────────────────────────────────
private val OPENING_BOOK = mapOf(
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"               to Triple("e2e4", "e4",  0.30f),
    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"            to Triple("e7e5", "e5",  0.30f),
    "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2"         to Triple("g1f3", "Nf3", 0.30f),
    "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"       to Triple("b8c6", "Nc6", 0.30f)
)

// ─────────────────────────────────────────────────────────────────────────────
// ArrowOverlayView — transparent Canvas view drawn over the WebView
// ─────────────────────────────────────────────────────────────────────────────
class ArrowOverlayView @JvmOverloads constructor(
    context: Context, attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    data class Arrow(
        val fromFile: Int, val fromRank: Int,
        val toFile: Int,   val toRank: Int,
        val isFlipped: Boolean,
        val boardX: Float, val boardY: Float, val boardSize: Float
    )

    private var arrow: Arrow? = null
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2ecc71") }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2ecc71"); style = Paint.Style.STROKE; alpha = 100
    }

    fun setArrow(uci: String, isFlipped: Boolean, bx: Float, by: Float, bs: Float) {
        if (uci.length < 4) { arrow = null; invalidate(); return }
        arrow = Arrow(
            uci[0] - 'a', uci[1] - '1',
            uci[2] - 'a', uci[3] - '1',
            isFlipped, bx, by, bs
        )
        invalidate()
    }

    fun clearArrow() { arrow = null; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val a = arrow ?: return
        val sq = a.boardSize / 8f

        fun cx(file: Int) = a.boardX + (if (a.isFlipped) (7 - file) else file) * sq + sq / 2f
        fun cy(rank: Int) = a.boardY + (if (a.isFlipped) rank else 7 - rank) * sq + sq / 2f

        val x1 = cx(a.fromFile); val y1 = cy(a.fromRank)
        val x2 = cx(a.toFile);   val y2 = cy(a.toRank)

        val lw   = sq * 0.18f
        val dx   = x2 - x1; val dy = y2 - y1
        val len  = sqrt(dx * dx + dy * dy).takeIf { it > 0f } ?: return
        val hd   = sq * 0.38f
        val ux   = dx / len; val uy = dy / len
        val ex   = x2 - ux * hd; val ey = y2 - uy * hd
        val px   = -uy * lw / 2f; val py = ux * lw / 2f

        // Shaft
        val shaft = Path()
        shaft.moveTo(x1 + px, y1 + py); shaft.lineTo(ex + px, ey + py)
        shaft.lineTo(ex - px, ey - py); shaft.lineTo(x1 - px, y1 - py); shaft.close()
        fillPaint.alpha = 210; canvas.drawPath(shaft, fillPaint)

        // Head
        val head = Path()
        head.moveTo(x2, y2)
        head.lineTo(ex + uy * hd * 0.5f, ey - ux * hd * 0.5f)
        head.lineTo(ex - uy * hd * 0.5f, ey + ux * hd * 0.5f); head.close()
        fillPaint.alpha = 240; canvas.drawPath(head, fillPaint)

        // Glow ring on destination
        glowPaint.strokeWidth = sq * 0.06f
        canvas.drawRect(x2 - sq * 0.48f, y2 - sq * 0.48f, x2 + sq * 0.48f, y2 + sq * 0.48f, glowPaint)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ChessBridge — @JavascriptInterface received by WebView JS
// ─────────────────────────────────────────────────────────────────────────────
class ChessBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun onMyTurn(fen: String, isFlipped: Boolean,
                 boardX: Float, boardY: Float, boardW: Float, boardH: Float) {
        activity.onMyTurnFromJs(fen, isFlipped, boardX, boardY, boardW, boardH)
    }

    @JavascriptInterface
    fun onOpponentTurn() {
        activity.runOnUiThread { activity.clearOverlay() }
    }

    @JavascriptInterface
    fun onGameEnd() {
        activity.runOnUiThread { activity.clearOverlay() }
    }

    @JavascriptInterface
    fun log(msg: String) { Log.d("OrstedJS", msg) }
}

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity
// ─────────────────────────────────────────────────────────────────────────────
class MainActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var webView:        WebView
    private lateinit var arrowOverlay:   ArrowOverlayView
    private lateinit var floatingPanel:  FloatingPanelView
    private lateinit var crashBanner:    TextView

    // ── State ─────────────────────────────────────────────────────────────────
    private var engineReady  = false
    private var analysisJob: Job? = null
    private val engineScope  = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Blunder engine state — ported from v1.0.16
    private var lastUserEval:  Float? = null
    private var currentHeat:   Int   = 0
    private var lastHeatFen:   String = ""
    private val dropHistory    = ArrayDeque<Float>()
    private val evalHistory    = ArrayDeque<Float>()

    companion object {
        init { System.loadLibrary("orsted_engine") }
        private const val PREFS = "orsted_prefs"
    }

    // ── JNI declarations ──────────────────────────────────────────────────────
    external fun engineInit()
    external fun engineSetElo(elo: Int)
    external fun engineGetMoves(fen: String, movetime: Int): String
    external fun engineStop()

    // ─────────────────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── CRASH CATCHER ─────────────────────────────────────────────────────
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val msg = "CRASH on ${thread.name}:\n$sw"
            Log.e(TAG, msg)
            try {
                runOnUiThread {
                    crashBanner.text = msg
                    crashBanner.visibility = android.view.View.VISIBLE
                }
            } catch (ignored: Exception) {}
        }

        // ── Root layout ───────────────────────────────────────────────────────
        val root = FrameLayout(this)
        setContentView(root)

        // 1. WebView
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        setupWebView()
        root.addView(webView)

        // 2. Arrow overlay — transparent, touch passes through
        arrowOverlay = ArrowOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = false; isFocusable = false
        }
        root.addView(arrowOverlay)

        // 3. Floating panel
        floatingPanel = FloatingPanelView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            targetElo  = prefs.getInt("elo", 2800)
            turboMode  = prefs.getBoolean("turbo", false)
            humanDelay = prefs.getBoolean("delay", false)

            onEloChange = { elo ->
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("elo", elo).apply()
                engineScope.launch { engineSetElo(elo) }
            }
            onTurboToggle = { turbo ->
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("turbo", turbo).apply()
            }
            onDelayToggle = { delay ->
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("delay", delay).apply()
            }
        }
        root.addView(floatingPanel)

        // 4. Crash banner (hidden by default; shown red on crash)
        crashBanner = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = android.view.Gravity.BOTTOM }
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.RED)
            textSize   = 10f
            setPadding(12, 8, 12, 8)
            visibility = android.view.View.GONE
        }
        root.addView(crashBanner)

        // ── Engine init on background thread ──────────────────────────────────
        engineScope.launch {
            try {
                engineInit()
                engineSetElo(floatingPanel.targetElo)
                engineReady = true
                withContext(Dispatchers.Main) {
                    floatingPanel.statusText = "Engine ready"
                    floatingPanel.invalidate()
                }
            } catch (e: Exception) {
                Log.e(TAG, "engineInit failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    floatingPanel.statusText = "Engine failed: ${e.message}"
                    floatingPanel.invalidate()
                }
            }
        }

        webView.loadUrl("https://lichess.org")
    }

    // ─────────────────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            databaseEnabled      = true
            useWideViewPort      = true
            loadWithOverviewMode = true
            userAgentString      = "Mozilla/5.0 (Linux; Android 8.1; Oppo A5s) AppleWebKit/537.36 " +
                                   "(KHTML, like Gecko) Chrome/89.0.4389.90 Mobile Safari/537.36"
            setSupportZoom(true)
            builtInZoomControls  = true
            displayZoomControls  = false
            mixedContentMode     = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.addJavascriptInterface(ChessBridge(this), "OrstedBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectBridgeScript(url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d("WebConsole", "${msg.messageLevel()} ${msg.message()}")
                return true
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Board-scraping JS — exact logic ported from readBoardLichess /
    // readBoardChessCom in v1.0.16, with getBoundingClientRect() coord pass-
    // through so the native arrow lands on the correct squares.
    // ─────────────────────────────────────────────────────────────────────────
    private fun injectBridgeScript(url: String) {
        val site = when {
            url.contains("lichess.org") -> "lichess"
            url.contains("chess.com")   -> "chesscom"
            else -> return
        }

        // language=JavaScript
        val js = """
(function() {
    if (window.__orstedInjected) return;
    window.__orstedInjected = true;
    const SITE = '$site';
    let lastFen = '';
    let lastTurn = '';

    function gridToFen(grid, turn) {
        let wK = 0, bK = 0;
        const ranks = [];
        for (let r = 0; r < 8; r++) {
            let s = '', empty = 0;
            for (let f = 0; f < 8; f++) {
                const p = grid[r][f];
                if (!p) { empty++; } else {
                    if (p === 'K') wK++; if (p === 'k') bK++;
                    if (empty) { s += empty; empty = 0; } s += p;
                }
            }
            if (empty) s += empty;
            ranks.push(s);
        }
        if (wK !== 1 || bK !== 1) return null;
        return ranks.join('/') + ' ' + turn + ' - - 0 1';
    }

    // ── Lichess board reader — ported from readBoardLichess() in v1.0.16 ───
    function readLichess() {
        const board = document.querySelector('cg-board');
        if (!board) return null;
        const wrap = board.closest('.cg-wrap') || board.parentElement || board;
        const isFlipped = wrap.classList.contains('orientation-black');
        const rect = board.getBoundingClientRect();
        const sq = rect.width / 8;
        if (sq < 1) return null;
        const grid = Array.from({length:8}, () => Array(8).fill(''));
        const pieces = board.querySelectorAll('piece');
        if (pieces.length < 2) return null;
        for (let i = 0; i < pieces.length; i++) {
            const p = pieces[i]; const cls = p.classList;
            const isW = cls.contains('white'), isB = cls.contains('black');
            if (!isW && !isB) continue;
            let sym = cls.contains('pawn') ? 'p' : cls.contains('knight') ? 'n' :
                      cls.contains('bishop') ? 'b' : cls.contains('rook') ? 'r' :
                      cls.contains('queen') ? 'q' : cls.contains('king') ? 'k' : null;
            if (!sym) continue;
            if (isW) sym = sym.toUpperCase();
            const m = (p.getAttribute('style')||'').match(/translate(?:3d)?\(\s*(-?[\d.]+)px\s*,\s*(-?[\d.]+)px/);
            if (!m) continue;
            let file = Math.round(parseFloat(m[1]) / sq);
            let rankTop = Math.round(parseFloat(m[2]) / sq);
            file = Math.max(0, Math.min(7, file));
            rankTop = Math.max(0, Math.min(7, rankTop));
            const gf = isFlipped ? 7 - file : file;
            const gr = isFlipped ? 7 - rankTop : rankTop;
            grid[gr][gf] = sym;
        }
        const moves = document.querySelectorAll('move, rm6 move, l4x move');
        const turn = moves.length % 2 === 0 ? 'w' : 'b';
        const userTurn = isFlipped ? 'b' : 'w';
        return { grid, turn, isFlipped, userTurn, boardEl: board, rect };
    }

    // ── Chess.com board reader — ported from readBoardChessCom() in v1.0.16 ─
    function readChesscom() {
        const board = document.querySelector('chess-board') || document.querySelector('wc-chess-board');
        if (!board) return null;
        const isFlipped = board.classList.contains('flipped');
        const rect = board.getBoundingClientRect();
        const grid = Array.from({length:8}, () => Array(8).fill(''));
        board.querySelectorAll('.piece').forEach(p => {
            const cls = [...p.classList];
            const pc = cls.find(c => /^[wb][pnbrqk]$/.test(c)); if (!pc) return;
            const sq = cls.find(c => c.startsWith('square-')); if (!sq) return;
            const n  = sq.split('-')[1];
            const file = parseInt(n[0], 10) - 1;
            const rank = parseInt(n[1], 10) - 1;
            const gr   = 7 - rank;
            let sym = pc[1]; if (pc[0] === 'w') sym = sym.toUpperCase();
            if (file >= 0 && file < 8 && gr >= 0 && gr < 8) grid[gr][file] = sym;
        });
        const nodes = document.querySelectorAll('wc-simple-move-list .node, .move-list .node');
        const turn  = nodes.length % 2 === 0 ? 'w' : 'b';
        const userTurn = isFlipped ? 'b' : 'w';
        return { grid, turn, isFlipped, userTurn, boardEl: board, rect };
    }

    function tick() {
        try {
            const data = SITE === 'lichess' ? readLichess() : readChesscom();
            if (!data) return;
            const fen = gridToFen(data.grid, data.turn);
            if (!fen || (fen === lastFen && data.turn === lastTurn)) return;
            lastFen  = fen;
            lastTurn = data.turn;

            if (data.turn === data.userTurn) {
                const r = data.rect;
                window.OrstedBridge.onMyTurn(
                    fen, data.isFlipped,
                    r.left, r.top, r.width, r.height
                );
            } else {
                window.OrstedBridge.onOpponentTurn();
            }
        } catch (e) {
            window.OrstedBridge.log('tick error: ' + e.message);
        }
    }

    setInterval(tick, 300);
    window.OrstedBridge.log('OrstedBridge injected on ' + SITE);
})();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Called from ChessBridge — dispatched on background (JS) thread
    // ─────────────────────────────────────────────────────────────────────────
    fun onMyTurnFromJs(fen: String, isFlipped: Boolean,
                       boardX: Float, boardY: Float, boardW: Float, boardH: Float) {
        if (!engineReady) {
            runOnUiThread { floatingPanel.statusText = "Engine starting…" }
            return
        }

        analysisJob?.cancel()
        analysisJob = engineScope.launch {
            withContext(Dispatchers.Main) {
                floatingPanel.statusText = "Analyzing…"
                floatingPanel.invalidate()
            }

            // Opening book check — ported from v1.0.16
            val bookEntry = OPENING_BOOK[fen]
            if (bookEntry != null) {
                val (uci, san, eval) = bookEntry
                withContext(Dispatchers.Main) {
                    updateResultUI(fen, listOf(uci to san), eval, isFlipped, boardX, boardY, boardW, boardH)
                }
                return@launch
            }

            val movetime = if (floatingPanel.turboMode) 10_000 else 200
            val payload  = engineGetMoves(fen, movetime)

            if (payload.isEmpty()) {
                withContext(Dispatchers.Main) {
                    floatingPanel.statusText = "No move found"
                    floatingPanel.invalidate()
                }
                return@launch
            }

            // Parse "uci1,evalStr1|uci2,evalStr2|..." — up to 7 entries (MultiPV 7)
            data class Move(val uci: String, val evalF: Float, val evalStr: String)
            val variants = payload.split("|").mapNotNull {
                val parts = it.split(",")
                if (parts.size == 2) Move(parts[0], parts[1].toFloatOrNull() ?: 0f, parts[1])
                else null
            }

            if (variants.isEmpty()) {
                withContext(Dispatchers.Main) { floatingPanel.statusText = "Game over" }
                return@launch
            }

            // ── BLUNDER ENGINE MATH — ported exactly from v1.0.16 ────────────
            // trueBest = variants[0] (highest-ranked move from MultiPV)
            val trueBest = variants[0]
            var positionEval = trueBest.evalF
            // If Black to move, invert eval (SF always reports from side to move)
            val turn = fen.split(" ").getOrNull(1) ?: "w"
            if (turn == "b") positionEval = -positionEval

            var selectedVariant = trueBest

            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val targetElo = prefs.getInt("elo", 2800)

            if (targetElo < 2800 && variants.size > 1) {
                val eloFactor = (2800 - targetElo) / 2000.0f
                val missProb  = eloFactor * 0.35f
                val maxDrop   = eloFactor * 3.0f

                if (Math.random() < missProb) {
                    val bestEval = trueBest.evalF
                    val candidates = variants.drop(1).filter { v ->
                        val drop = bestEval - v.evalF
                        drop > 0f && drop <= maxDrop
                    }.sortedByDescending { it.evalF }

                    if (candidates.isNotEmpty()) {
                        val pickIdx = (Math.pow(Math.random(), 1.5) * candidates.size).toInt()
                            .coerceIn(0, candidates.size - 1)
                        selectedVariant = candidates[pickIdx]
                    }
                }
            }

            // ── STEALTH METER update — ported from v1.0.16 ───────────────────
            val userColorIsBlack = isFlipped
            val lastEval = lastUserEval
            if (lastEval != null && fen != lastHeatFen) {
                val drop = if (!userColorIsBlack) lastEval - positionEval
                           else positionEval - lastEval
                val clampedDrop = drop.coerceAtLeast(0f)
                dropHistory.addLast(clampedDrop)
                if (dropHistory.size > 3) dropHistory.removeFirst()
                val avgDrop = dropHistory.average().toFloat()

                val heatChange = when {
                    avgDrop <= 0.3f -> 8
                    avgDrop <= 0.7f -> 4
                    avgDrop <= 1.2f -> -2
                    avgDrop <= 2.0f -> -8
                    avgDrop <= 3.5f -> -18
                    else            -> -999
                }
                currentHeat = if (heatChange == -999) 0
                              else (currentHeat + heatChange).coerceIn(0, 100)
                lastHeatFen = fen
            }
            lastUserEval = positionEval

            // ── Build display move list ───────────────────────────────────────
            val displayMoves = listOf(
                selectedVariant.uci to selectedVariant.evalStr,
                trueBest.uci to trueBest.evalStr
            ).distinctBy { it.first }

            withContext(Dispatchers.Main) {
                floatingPanel.stealthHeat = currentHeat
                updateResultUI(fen, displayMoves, positionEval, isFlipped, boardX, boardY, boardW, boardH)
            }
        }
    }

    private fun updateResultUI(
        fen: String,
        moves: List<Pair<String, String>>,
        eval: Float,
        isFlipped: Boolean,
        boardX: Float, boardY: Float, boardW: Float, boardH: Float
    ) {
        // Scale CSS px → screen px
        val density   = resources.displayMetrics.density
        val boardSize = minOf(boardW, boardH) * density

        floatingPanel.evalScore = eval
        floatingPanel.moveLines = moves.map { (uci, evalStr) ->
            // Format as "Nf3" (or raw UCI if SAN not available) + eval string
            uci to evalStr
        }
        floatingPanel.statusText = "▶ ${moves.firstOrNull()?.first ?: ""}"
        floatingPanel.invalidate()

        moves.firstOrNull()?.first?.let { bestUci ->
            arrowOverlay.setArrow(
                bestUci, isFlipped,
                boardX * density,
                boardY * density,
                boardSize
            )
        }
    }

    fun clearOverlay() {
        arrowOverlay.clearArrow()
        lastUserEval = null
        floatingPanel.moveLines  = emptyList()
        floatingPanel.statusText = "Waiting for your turn…"
        floatingPanel.invalidate()
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        analysisJob?.cancel()
        runBlocking { withContext(Dispatchers.Default) { engineStop() } }
        engineScope.cancel()
        super.onDestroy()
    }

    override fun onPause()  { super.onPause();  webView.onPause()  }
    override fun onResume() { super.onResume(); webView.onResume() }
}
