package com.orsted.chessoverlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * FloatingPanelView
 *
 * A self-contained draggable panel drawn with Canvas that replicates the
 * userscript's HTML panel using native Android views. No HTML, no CSS.
 *
 * Layout (top to bottom):
 *   ┌──────────────────────────────┐
 *   │  ⚡ orsted chess        [−]  │  ← drag handle / collapse toggle
 *   ├──────────────────────────────┤
 *   │  Eval ░░░░░░▓▓▓▓  +0.30     │  ← eval bar (white fill = white advantage)
 *   │  Stealth ░░▓▓▓▓▓▓▓▓         │  ← stealth heat bar
 *   │  Elo:  [2800]  ────●────    │  ← elo slider (800–2800, snaps to 100)
 *   │  🧠 Turbo (10 s)  [OFF]     │  ← turbo toggle
 *   │  ⏱️ Delay          [OFF]     │  ← human-delay toggle
 *   ├──────────────────────────────┤
 *   │  ▶ Nf3  +0.30               │  ← best move (top line)
 *   │  › d4   +0.25               │  ← second move
 *   │  Analyzing… / Engine ready  │  ← status line
 *   └──────────────────────────────┘
 */
class FloatingPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── State exposed to MainActivity ──────────────────────────────────────────
    var targetElo:   Int     = 2800   set(v) { field = v.coerceIn(800, 2800); invalidate() }
    var turboMode:   Boolean = false   set(v) { field = v; invalidate() }
    var humanDelay:  Boolean = false   set(v) { field = v; invalidate() }
    var evalScore:   Float   = 0f      set(v) { field = v; invalidate() }
    var stealthHeat: Int     = 0       set(v) { field = v.coerceIn(0, 100); invalidate() }
    var statusText:  String  = "⚡ Engine starting…" set(v) { field = v; invalidate() }
    var moveLines:   List<Pair<String, String>> = emptyList() set(v) { field = v; invalidate() }
    var collapsed:   Boolean = true    set(v) { field = v; invalidate() }

    var onEloChange:    ((Int)     -> Unit)? = null
    var onTurboToggle:  ((Boolean) -> Unit)? = null
    var onDelayToggle:  ((Boolean) -> Unit)? = null
    var onCollapseToggle: (() -> Unit)? = null

    // ── Layout constants ───────────────────────────────────────────────────────
    private val Float.dp: Float get() = this * resources.displayMetrics.density
    private val Float.sp: Float get() = this * resources.displayMetrics.scaledDensity

    private val PANEL_W   = 290f.dp
    private val PAD       = 12f.dp
    private val RADIUS    = 12f.dp
    private val HEADER_H  = 36f.dp
    private val BAR_H     = 10f.dp
    private val SLIDER_H  = 32f.dp
    private val ROW_H     = 30f.dp
    private val MOVE_H    = 26f.dp

    private val collapsedH get() = PAD + HEADER_H + PAD

    private val expandedH get() = PAD + HEADER_H + PAD +
            BAR_H + 4f.dp +      // eval bar
            BAR_H + 10f.dp +     // stealth bar
            SLIDER_H + 4f.dp +   // elo slider
            ROW_H +              // turbo row
            ROW_H + 10f.dp +     // delay row
            (moveLines.size.coerceAtLeast(1)) * MOVE_H + 4f.dp + // moves
            20f.sp + PAD         // status line

    // ── Paint objects ──────────────────────────────────────────────────────────
    private val bgPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8112b1d") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#442ecc71"); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val titlePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2ecc71"); typeface = Typeface.DEFAULT_BOLD; textSize = 13f.sp
    }
    private val collapseP   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 128; textSize = 16f.sp; typeface = Typeface.DEFAULT_BOLD
    }
    private val labelPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 180; textSize = 11f.sp
    }
    private val valuePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2ecc71"); typeface = Typeface.DEFAULT_BOLD; textSize = 13f.sp
    }
    private val evalBgP     = Paint().apply { color = Color.BLACK }
    private val evalFillP   = Paint().apply { color = Color.WHITE }
    private val trackP      = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#44ffffff") }
    private val thumbP      = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2ecc71") }
    private val greenP      = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2ecc71") }
    private val redP        = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e74c3c") }
    private val yellowP     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#f1c40f") }
    private val dimP        = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33ffffff") }
    private val whiteP      = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val movePrimary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2ecc71"); typeface = Typeface.DEFAULT_BOLD; textSize = 13f.sp
    }
    private val moveSecP    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 180; textSize = 12f.sp
    }
    private val statusP     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 160; textSize = 11f.sp
    }

    // ── Drag / interaction state ───────────────────────────────────────────────
    private var panelX = 20f
    private var panelY = 60f
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var dragging = false
    private var sliderDragging = false
    private var hasDragged = false

    // Rects for hit-testing (in view coordinates, recalculated each draw)
    private val collapseRect = RectF()
    private val sliderTrackRect = RectF()
    private val turboToggleRect = RectF()
    private val delayToggleRect = RectF()

    // ── onMeasure ─────────────────────────────────────────────────────────────
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    // ── onDraw ────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val pH = if (collapsed) collapsedH else expandedH
        val rect = RectF(panelX, panelY, panelX + PANEL_W, panelY + pH)
        canvas.drawRoundRect(rect, RADIUS, RADIUS, bgPaint)
        canvas.drawRoundRect(rect, RADIUS, RADIUS, borderPaint)

        // ── Header ────────────────────────────────────────────────────────────
        val headerMid = panelY + PAD + HEADER_H / 2f
        canvas.drawText("⚡ orsted chess", panelX + PAD, headerMid + titlePaint.textSize * 0.35f, titlePaint)
        val colBtnLabel = if (collapsed) "+" else "−"
        val colBtnX = panelX + PANEL_W - PAD - 20f.dp
        canvas.drawText(colBtnLabel, colBtnX, headerMid + collapseP.textSize * 0.35f, collapseP)
        collapseRect.set(colBtnX - 8f.dp, panelY + PAD, colBtnX + 20f.dp, panelY + PAD + HEADER_H)

        if (collapsed) return

        var curY = panelY + PAD + HEADER_H + PAD

        // ── Eval bar ──────────────────────────────────────────────────────────
        val eL = panelX + PAD; val eR = panelX + PANEL_W - PAD
        canvas.drawRect(eL, curY, eR, curY + BAR_H, evalBgP)
        val frac = when {
            evalScore >= 100f  -> 1f
            evalScore <= -100f -> 0f
            else -> 0.5f + evalScore.coerceIn(-8f, 8f) / 16f
        }
        canvas.drawRect(eL, curY, eL + (eR - eL) * frac, curY + BAR_H, evalFillP)
        val evalStr = when {
            evalScore >= 100f  -> "+M"
            evalScore <= -100f -> "-M"
            evalScore > 0f  -> "+%.2f".format(evalScore)
            else -> "%.2f".format(evalScore)
        }
        canvas.drawText("Eval: $evalStr", panelX + PAD, curY - 2f.dp, labelPaint)
        curY += BAR_H + 4f.dp

        // ── Stealth bar ───────────────────────────────────────────────────────
        canvas.drawRect(eL, curY, eR, curY + BAR_H, evalBgP)
        val heatFrac = stealthHeat / 100f
        val heatColor = when {
            stealthHeat >= 70 -> Color.parseColor("#81b64c")
            stealthHeat >= 40 -> Color.parseColor("#f1c40f")
            else              -> Color.parseColor("#e74c3c")
        }
        val heatFillP = Paint().apply { color = heatColor }
        canvas.drawRect(eL, curY, eL + (eR - eL) * heatFrac, curY + BAR_H, heatFillP)
        canvas.drawText("Stealth: $stealthHeat%", panelX + PAD, curY - 2f.dp, labelPaint)
        curY += BAR_H + 10f.dp

        // ── Elo slider ────────────────────────────────────────────────────────
        val sL = panelX + PAD; val sR = panelX + PANEL_W - PAD
        val sMid = curY + SLIDER_H / 2f
        sliderTrackRect.set(sL, sMid - 4f.dp, sR, sMid + 4f.dp)
        canvas.drawRoundRect(sliderTrackRect, 4f.dp, 4f.dp, trackP)
        val eloFrac = (targetElo - 800f) / 2000f
        val thumbX  = sL + (sR - sL) * eloFrac
        canvas.drawCircle(thumbX, sMid, 11f.dp, thumbP)
        canvas.drawText("Elo", panelX + PAD, curY - 2f.dp, labelPaint)
        canvas.drawText("$targetElo", thumbX - 20f.dp, curY - 2f.dp, valuePaint)
        curY += SLIDER_H + 4f.dp

        // ── Turbo toggle ──────────────────────────────────────────────────────
        val tTogW = 44f.dp; val tTogH = 24f.dp; val tTogR = 12f.dp
        val tTogX = panelX + PANEL_W - PAD - tTogW
        val tTogY = curY + (ROW_H - tTogH) / 2f
        turboToggleRect.set(tTogX, curY, tTogX + tTogW, curY + ROW_H)
        canvas.drawRoundRect(RectF(tTogX, tTogY, tTogX + tTogW, tTogY + tTogH), tTogR, tTogR,
            if (turboMode) greenP else dimP)
        val tKnobX = if (turboMode) tTogX + tTogW - tTogH / 2f else tTogX + tTogH / 2f
        canvas.drawCircle(tKnobX, tTogY + tTogH / 2f, tTogH / 2f - 2f.dp, whiteP)
        canvas.drawText("🧠 Turbo (10 s)", panelX + PAD, curY + ROW_H * 0.65f, labelPaint)
        curY += ROW_H

        // ── Delay toggle ──────────────────────────────────────────────────────
        val dTogX = panelX + PANEL_W - PAD - tTogW
        val dTogY = curY + (ROW_H - tTogH) / 2f
        delayToggleRect.set(dTogX, curY, dTogX + tTogW, curY + ROW_H)
        canvas.drawRoundRect(RectF(dTogX, dTogY, dTogX + tTogW, dTogY + tTogH), tTogR, tTogR,
            if (humanDelay) greenP else dimP)
        val dKnobX = if (humanDelay) dTogX + tTogW - tTogH / 2f else dTogX + tTogH / 2f
        canvas.drawCircle(dKnobX, dTogY + tTogH / 2f, tTogH / 2f - 2f.dp, whiteP)
        canvas.drawText("⏱️ Human Delay", panelX + PAD, curY + ROW_H * 0.65f, labelPaint)
        curY += ROW_H + 10f.dp

        // ── Move lines ────────────────────────────────────────────────────────
        moveLines.forEachIndexed { idx, (move, eval) ->
            val prefix = if (idx == 0) "⭐" else "›"
            val paint  = if (idx == 0) movePrimary else moveSecP
            canvas.drawText("$prefix $move", panelX + PAD, curY + MOVE_H * 0.75f, paint)
            canvas.drawText(eval, panelX + PANEL_W - PAD - 50f.dp, curY + MOVE_H * 0.75f, labelPaint)
            curY += MOVE_H
        }
        if (moveLines.isEmpty()) curY += MOVE_H

        // ── Status line ───────────────────────────────────────────────────────
        canvas.drawText(statusText, panelX + PAD, curY + 14f.sp, statusP)
    }

    // ── onTouchEvent ──────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val ex = event.x; val ey = event.y
        val pH = if (collapsed) collapsedH else expandedH
        fun inPanel() = ex in panelX..(panelX + PANEL_W) && ey in panelY..(panelY + pH)
        fun inSlider() = !collapsed &&
                ey in (panelY + PAD + HEADER_H + PAD + BAR_H*2 + 14f.dp)..
                        (panelY + PAD + HEADER_H + PAD + BAR_H*2 + 14f.dp + SLIDER_H) &&
                ex in (panelX + PAD)..(panelX + PANEL_W - PAD)

        fun sliderElo(): Int {
            val sL = panelX + PAD; val sR = panelX + PANEL_W - PAD
            val frac = ((ex - sL) / (sR - sL)).coerceIn(0f, 1f)
            return ((800 + frac * 2000f).toInt() / 100) * 100
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!inPanel()) return false
                when {
                    collapseRect.contains(ex, ey) -> {
                        collapsed = !collapsed
                        onCollapseToggle?.invoke()
                        return true
                    }
                    turboToggleRect.contains(ex, ey) && !collapsed -> {
                        turboMode = !turboMode
                        onTurboToggle?.invoke(turboMode)
                        return true
                    }
                    delayToggleRect.contains(ex, ey) && !collapsed -> {
                        humanDelay = !humanDelay
                        onDelayToggle?.invoke(humanDelay)
                        return true
                    }
                    inSlider() -> {
                        sliderDragging = true
                        targetElo = sliderElo()
                        onEloChange?.invoke(targetElo)
                        return true
                    }
                    else -> {
                        dragging = true; hasDragged = false
                        dragOffX = ex - panelX; dragOffY = ey - panelY
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when {
                    sliderDragging -> {
                        targetElo = sliderElo(); onEloChange?.invoke(targetElo); return true
                    }
                    dragging -> {
                        hasDragged = true
                        panelX = (ex - dragOffX).coerceIn(0f, width  - PANEL_W)
                        panelY = (ey - dragOffY).coerceIn(0f, height - (if (collapsed) collapsedH else expandedH))
                        invalidate(); return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false; sliderDragging = false
            }
        }
        return false
    }
}
