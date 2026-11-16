package com.shamail.gpscam.watermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import com.shamail.gpscam.R
import com.shamail.gpscam.data.CoordFormat
import com.shamail.gpscam.data.GpsSnapshot
import com.shamail.gpscam.data.WatermarkConfig
import com.shamail.gpscam.data.WatermarkPosition
import com.shamail.gpscam.data.WatermarkSize
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

object WatermarkRenderer {

    private const val CREATED_BY_LABEL = "Created by:"
    private const val URDU_FOOTER_TEXT = "شمائل احمد رندھاوا) پیرا فورس سرائے عالمگیر"

    @Volatile
    private var assetUrduTypeface: Typeface? = null

    @Volatile
    private var assetUrduChecked: Boolean = false

    private val systemUrduTypeface: Typeface by lazy {
        val fontPaths = listOf(
            "/system/fonts/JameelNooriNastaleeq.ttf",
            "/system/fonts/NotoNastaliqUrdu-Regular.ttf",
            "/product/fonts/NotoNastaliqUrdu-Regular.ttf",
            "/system/fonts/NotoNastaliqUrduUI-Regular.ttf",
            "/system/fonts/NotoNaskhArabic-Regular.ttf",
            "/system/fonts/NotoSansArabic-Regular.ttf"
        )
        for (path in fontPaths) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                runCatching { Typeface.createFromFile(file) }.getOrNull()?.let { return@lazy it }
            }
        }
        Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val isoCountryByName: Map<String, String> by lazy {
        val map = HashMap<String, String>(300)
        for (iso in Locale.getISOCountries()) {
            val name = Locale("", iso).getDisplayCountry(Locale.ENGLISH).trim()
            if (name.isNotEmpty()) {
                map[name.lowercase(Locale.US)] = iso.uppercase(Locale.US)
            }
        }
        map["pakistan"] = "PK"
        map["uae"] = "AE"
        map["united arab emirates"] = "AE"
        map["uk"] = "GB"
        map["united kingdom"] = "GB"
        map["usa"] = "US"
        map["united states"] = "US"
        map["ksa"] = "SA"
        map["saudi arabia"] = "SA"
        map
    }

    private fun resolveUrduTypeface(): Typeface {
        return assetUrduTypeface ?: systemUrduTypeface
    }

    fun loadLogoBitmap(context: Context, config: WatermarkConfig): Bitmap? {
        if (!assetUrduChecked) {
            assetUrduChecked = true
            val candidates = listOf(
                "fonts/JameelNooriNastaleeq.ttf",
                "fonts/jameel_noori_nastaleeq.ttf",
                "fonts/NotoNastaliqUrdu-Regular.ttf"
            )
            for (assetPath in candidates) {
                val tf = runCatching { Typeface.createFromAsset(context.assets, assetPath) }.getOrNull()
                if (tf != null) {
                    assetUrduTypeface = tf
                    break
                }
            }
        }

        if (!config.showLogo) return null
        val customPath = config.customLogoPath
        if (!customPath.isNullOrBlank()) {
            val f = File(customPath)
            if (f.exists()) {
                BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
            }
        }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, R.drawable.default_org_logo, bounds)
            var sample = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / sample > 512) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeResource(context.resources, R.drawable.default_org_logo, decodeOpts)
        }.getOrNull()
    }

    private data class WatermarkBounds(
        val cardRect: RectF,
        val logoRect: RectF?,
        val cornerRadius: Float,
        val unit: Float,
        val outerMargin: Float
    )

    private data class MeasuredOverlayLayout(
        val bounds: WatermarkBounds,
        val padX: Float,
        val padTop: Float,
        val padBottom: Float,
        val titleGap: Float,
        val detailGap: Float,
        val dividerRelY: Float,
        val totalVisualHeight: Float,
        val locationLayout: StaticLayout?,
        val addressLayout: StaticLayout?,
        val coordLayout: StaticLayout?,
        val dateTimeLayout: StaticLayout?,
        val enFooterLayout: StaticLayout,
        val urduFooterLayout: StaticLayout,
        val labelUrduGap: Float,
        val enAscent: Float,
        val maxFooterAscent: Float,
        val maxFooterDescent: Float,
        val footerZoneHeight: Float
    )

    fun renderOntoBitmap(
        source: Bitmap,
        config: WatermarkConfig,
        gps: GpsSnapshot,
        logoBitmap: Bitmap?,
        captureMillis: Long = System.currentTimeMillis(),
        @Suppress("UNUSED_PARAMETER") forceOpaqueBackground: Boolean = false,
        replaceExistingWatermark: Boolean = false,
        oldGpsForRemoval: GpsSnapshot? = null
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        if (!config.showWatermark) {
            return output
        }
        if (replaceExistingWatermark) {
            patchExistingWatermarkArea(
                target = output,
                config = config,
                oldGps = oldGpsForRemoval ?: gps,
                nowMillis = captureMillis
            )
        }
        val canvas = Canvas(output)
        drawWatermarkOverlay(
            canvas = canvas,
            containerWidth = output.width.toFloat(),
            containerHeight = output.height.toFloat(),
            config = config,
            gps = gps,
            logoBitmap = logoBitmap,
            nowMillis = captureMillis,
            anchorToBottom = true,
            forceOpaqueBackground = false
        )
        return output
    }

    fun prepareCleanBaseForReWatermark(
        source: Bitmap,
        config: WatermarkConfig,
        oldGps: GpsSnapshot,
        captureMillis: Long = System.currentTimeMillis()
    ): Bitmap {
        val cleanBase = source.copy(Bitmap.Config.ARGB_8888, true)
        if (config.showWatermark) {
            patchExistingWatermarkArea(
                target = cleanBase,
                config = config,
                oldGps = oldGps,
                nowMillis = captureMillis
            )
        }
        return cleanBase
    }

    data class VideoWatermarkStrip(
        val stripBitmap: Bitmap,
        val stripTopFraction: Float,
        val stripHeightFraction: Float,
        val cardNormRect: RectF,
        val logoNormRect: RectF?,
        val cornerRadiusNormX: Float,
        val cornerRadiusNormY: Float
    )

    fun renderVideoOverlayStrip(
        videoWidth: Int,
        videoHeight: Int,
        config: WatermarkConfig,
        gps: GpsSnapshot,
        logoBitmap: Bitmap?,
        captureMillis: Long
    ): VideoWatermarkStrip? {
        if (!config.showWatermark || videoWidth < 64 || videoHeight < 64) return null

        val measured = measureOverlayLayout(
            containerWidth = videoWidth.toFloat(),
            containerHeight = videoHeight.toFloat(),
            config = config,
            gps = gps,
            nowMillis = captureMillis,
            anchorToBottom = true
        )
        val bounds = measured.bounds
        val wF = videoWidth.toFloat()
        val hF = videoHeight.toFloat()

        val cardRect = bounds.cardRect
        val logoRect = bounds.logoRect

        val unionTop = minOf(cardRect.top, logoRect?.top ?: cardRect.top)
        val stripTop = (unionTop - 6f).toInt().coerceIn(0, videoHeight - 2)
        val stripHeight = (videoHeight - stripTop).coerceAtLeast(2)

        val stripBitmap = Bitmap.createBitmap(videoWidth, stripHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(stripBitmap)
        canvas.save()
        canvas.translate(0f, -stripTop.toFloat())
        drawWatermarkOverlay(
            canvas = canvas,
            containerWidth = wF,
            containerHeight = hF,
            config = config,
            gps = gps,
            logoBitmap = logoBitmap,
            nowMillis = captureMillis,
            anchorToBottom = true,
            forceOpaqueBackground = false
        )
        canvas.restore()

        val cardNorm = RectF(
            cardRect.left / wF,
            cardRect.top / hF,
            cardRect.right / wF,
            cardRect.bottom / hF
        )
        val logoNorm = logoRect?.let {
            RectF(it.left / wF, it.top / hF, it.right / wF, it.bottom / hF)
        }

        return VideoWatermarkStrip(
            stripBitmap = stripBitmap,
            stripTopFraction = stripTop.toFloat() / hF,
            stripHeightFraction = stripHeight.toFloat() / hF,
            cardNormRect = cardNorm,
            logoNormRect = logoNorm,
            cornerRadiusNormX = bounds.cornerRadius / wF,
            cornerRadiusNormY = bounds.cornerRadius / hF
        )
    }

    fun drawWatermarkOverlay(
        canvas: Canvas?,
        containerWidth: Float,
        containerHeight: Float,
        config: WatermarkConfig,
        gps: GpsSnapshot,
        logoBitmap: Bitmap?,
        nowMillis: Long = System.currentTimeMillis(),
        anchorToBottom: Boolean = true,
        @Suppress("UNUSED_PARAMETER") forceOpaqueBackground: Boolean = false
    ): Float {
        if (!config.showWatermark || containerWidth <= 10f) return 0f

        val measured = measureOverlayLayout(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            config = config,
            gps = gps,
            nowMillis = nowMillis,
            anchorToBottom = anchorToBottom
        )

        if (canvas == null) {
            return measured.totalVisualHeight + if (anchorToBottom) measured.bounds.outerMargin * 2f else 0f
        }

        val bounds = measured.bounds
        val cardRect = bounds.cardRect
        val unit = bounds.unit
        val padX = measured.padX
        val padTop = measured.padTop
        val cornerRadius = bounds.cornerRadius

        // Always use the user's configured semi-transparent charcoal opacity (never solid/opaque 248)
        val alphaInt = ((config.watermarkOpacity.coerceIn(30, 95) / 100f) * 255f).toInt()
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alphaInt, 20, 22, 24)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, bgPaint)

        // Subtle, thin neutral gray border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(56, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = (1.1f * unit).coerceAtLeast(1f)
        }
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint)

        // Draw dynamic GPS text stack in exact order
        val textStartX = cardRect.left + padX
        var drawY = cardRect.top + padTop

        val locationLayout = measured.locationLayout
        if (locationLayout != null) {
            canvas.save()
            canvas.translate(textStartX, drawY)
            locationLayout.draw(canvas)
            canvas.restore()
            drawY += locationLayout.height + measured.titleGap
        }

        val addressLayout = measured.addressLayout
        if (addressLayout != null) {
            canvas.save()
            canvas.translate(textStartX, drawY)
            addressLayout.draw(canvas)
            canvas.restore()
            drawY += addressLayout.height + measured.detailGap
        }

        val coordLayout = measured.coordLayout
        if (coordLayout != null) {
            canvas.save()
            canvas.translate(textStartX, drawY)
            coordLayout.draw(canvas)
            canvas.restore()
            drawY += coordLayout.height + measured.detailGap
        }

        val dateTimeLayout = measured.dateTimeLayout
        if (dateTimeLayout != null) {
            canvas.save()
            canvas.translate(textStartX, drawY)
            dateTimeLayout.draw(canvas)
            canvas.restore()
        }

        // Thin, subtle horizontal gray divider line above footer
        val dividerY = cardRect.top + measured.dividerRelY
        val dividerStartX = cardRect.left + (10f * unit)
        val dividerEndX = cardRect.right - (10f * unit)
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (1.0f * unit).coerceAtLeast(1f)
            shader = LinearGradient(
                dividerStartX,
                dividerY,
                dividerEndX,
                dividerY,
                intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(115, 235, 238, 242),
                    Color.argb(115, 235, 238, 242),
                    Color.argb(0, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.06f, 0.94f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawLine(dividerStartX, dividerY, dividerEndX, dividerY, dividerPaint)

        // Draw Created by: (LTR) + exact Urdu text (RTL) centered with subtle side gray lines
        val enFooterLayout = measured.enFooterLayout
        val urduFooterLayout = measured.urduFooterLayout
        val enLineLeft = enFooterLayout.getLineLeft(0)
        val enVisualWidth = (enFooterLayout.getLineRight(0) - enLineLeft).coerceAtLeast(1f)
        val urduLineLeft = urduFooterLayout.getLineLeft(0)
        val urduVisualWidth = (urduFooterLayout.getLineRight(0) - urduLineLeft).coerceAtLeast(1f)

        val labelUrduGap = measured.labelUrduGap
        val totalFooterTextWidth = enVisualWidth + labelUrduGap + urduVisualWidth
        val cardWidth = cardRect.width()
        val footerGroupLeft = cardRect.left + (cardWidth - totalFooterTextWidth) / 2f
        val footerBaselineY =
            dividerY + ((measured.footerZoneHeight - (measured.maxFooterAscent + measured.maxFooterDescent)) / 2f) + measured.maxFooterAscent

        val enDrawX = footerGroupLeft - enLineLeft
        val enDrawY = footerBaselineY - enFooterLayout.getLineBaseline(0)
        canvas.save()
        canvas.translate(enDrawX, enDrawY)
        enFooterLayout.draw(canvas)
        canvas.restore()

        val urduVisualStartX = footerGroupLeft + enVisualWidth + labelUrduGap
        val urduDrawX = urduVisualStartX - urduLineLeft
        val urduDrawY = footerBaselineY - urduFooterLayout.getLineBaseline(0)
        canvas.save()
        canvas.translate(urduDrawX, urduDrawY)
        urduFooterLayout.draw(canvas)
        canvas.restore()

        // Short thin gray decorative lines beside the footer when space permits
        val sideGap = 12f * unit
        val availSideSpace = footerGroupLeft - (cardRect.left + padX) - sideGap
        if (availSideSpace >= 14f * unit) {
            val sideLineLen = min(38f * unit, availSideSpace)
            val sideLineY = footerBaselineY - (measured.enAscent * 0.36f)
            val sideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(135, 226, 232, 240)
                style = Paint.Style.STROKE
                strokeWidth = (1.1f * unit).coerceAtLeast(1f)
            }
            val leftLineEnd = footerGroupLeft - sideGap
            val leftLineStart = leftLineEnd - sideLineLen
            canvas.drawLine(leftLineStart, sideLineY, leftLineEnd, sideLineY, sideLinePaint)

            val footerGroupRight = footerGroupLeft + totalFooterTextWidth
            val rightLineStart = footerGroupRight + sideGap
            val rightLineEnd = rightLineStart + sideLineLen
            canvas.drawLine(rightLineStart, sideLineY, rightLineEnd, sideLineY, sideLinePaint)
        }

        // Draw circular SHAMAIL GPS logo at upper-right corner of the watermark panel
        val logoRect = bounds.logoRect
        if (logoRect != null) {
            drawUpperRightCircularLogo(canvas, logoRect, logoBitmap, unit)
        }

        return measured.totalVisualHeight
    }

    private fun measureOverlayLayout(
        containerWidth: Float,
        containerHeight: Float,
        config: WatermarkConfig,
        gps: GpsSnapshot,
        nowMillis: Long,
        anchorToBottom: Boolean
    ): MeasuredOverlayLayout {

        val outerMargin = (containerWidth * 0.028f).coerceAtLeast(8f)
        val availableWidth = (containerWidth - outerMargin * 2f).coerceAtLeast(120f)
        val baseFraction = when (config.watermarkSize) {
            WatermarkSize.SMALL -> 0.76f
            WatermarkSize.MEDIUM -> 0.90f
            WatermarkSize.LARGE -> 0.96f
        }
        val isFullLandscapePhoto =
            anchorToBottom && containerHeight >= containerWidth * 0.45f && containerWidth > containerHeight
        val cardWidth = if (isFullLandscapePhoto) {
            min(availableWidth * baseFraction, containerHeight * 1.50f).coerceAtLeast(160f)
        } else {
            availableWidth * baseFraction
        }

        val unit = (cardWidth / 460f) * config.fontScale
        val padX = (18f * unit).coerceAtLeast(10f)
        val padTop = (15f * unit).coerceAtLeast(9f)
        val padBottom = (10f * unit).coerceAtLeast(7f)
        val cornerRadius = (16f * unit).coerceAtLeast(10f)

        val hasLogo = config.showLogo
        val logoSize = if (hasLogo) (68f * unit).coerceAtLeast(42f) else 0f
        val logoOverhang = if (hasLogo) logoSize * 0.40f else 0f
        val logoRightInset = 14f * unit
        val logoInsideDrop = logoSize - logoOverhang

        val shadowRadius = (1.6f * unit).coerceAtLeast(0.8f)
        val shadowDy = (0.7f * unit).coerceAtLeast(0.4f)
        val shadowColor = Color.argb(120, 0, 0, 0)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22.5f * unit
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)
        }

        val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D8DCE2")
            textSize = 14.5f * unit
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)
        }

        val fullInnerWidth = (cardWidth - padX * 2f).toInt().coerceAtLeast(90)
        val logoClearWidth = if (hasLogo) {
            (cardWidth - padX - logoRightInset - logoSize - (12f * unit)).toInt().coerceAtLeast(80)
        } else {
            fullInnerWidth
        }

        var contentY = padTop
        val titleGap = 5.5f * unit
        val detailGap = 4.2f * unit

        val locationLayout = if (config.showLocationTitle) {
            buildLtrLayout(buildLocationHeadlineWithFlag(gps), titlePaint, logoClearWidth, 2)
        } else null
        if (locationLayout != null) {
            contentY += locationLayout.height + titleGap
        }

        val addressWidth = if (hasLogo && contentY < logoInsideDrop + (2f * unit)) {
            logoClearWidth
        } else {
            fullInnerWidth
        }
        val addressLayout = if (config.showFullAddress) {
            buildLtrLayout(gps.buildFullAddressLine(), detailPaint, addressWidth, 3)
        } else null
        if (addressLayout != null) {
            contentY += addressLayout.height + detailGap
        }

        val coordWidth = if (hasLogo && contentY < logoInsideDrop + (2f * unit)) {
            logoClearWidth
        } else {
            fullInnerWidth
        }
        val coordLayout = if (config.showCoordinates) {
            buildLtrLayout(formatWatermarkCoordinatesLine(config, gps), detailPaint, coordWidth, 2)
        } else null
        if (coordLayout != null) {
            contentY += coordLayout.height + detailGap
        }

        val dateTimeWidth = if (hasLogo && contentY < logoInsideDrop + (2f * unit)) {
            logoClearWidth
        } else {
            fullInnerWidth
        }
        val dateTimeLayout = if (config.showDateTime) {
            buildLtrLayout(formatWatermarkDateTimeLine(config, gps, nowMillis), detailPaint, dateTimeWidth, 2)
        } else null
        if (dateTimeLayout != null) {
            contentY += dateTimeLayout.height
        }

        val minPreDividerBottom = if (hasLogo) {
            maxOf(contentY, logoInsideDrop + (6f * unit))
        } else {
            contentY
        }
        val dividerGapTop = 10f * unit
        val dividerRelY = minPreDividerBottom + dividerGapTop

        // Prepare Created by + RTL Urdu footer measurements so they always fit inside cardWidth
        var enTextSize = 13.5f * unit
        var urduTextSize = 14.5f * unit
        val labelUrduGap = 6.5f * unit

        val enFooterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = enTextSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)
        }
        val urduFooterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = urduTextSize
            typeface = resolveUrduTypeface()
            setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)
        }

        val maxFooterTextWidth = (cardWidth - padX * 2f).coerceAtLeast(90f)
        val rawEnW = Layout.getDesiredWidth(CREATED_BY_LABEL, enFooterPaint)
        val rawUrduW = Layout.getDesiredWidth(URDU_FOOTER_TEXT, urduFooterPaint)
        val rawCombinedW = rawEnW + labelUrduGap + rawUrduW
        if (rawCombinedW > maxFooterTextWidth && rawCombinedW > 1f) {
            val shrink = (maxFooterTextWidth / rawCombinedW).coerceIn(0.55f, 1f)
            enTextSize *= shrink
            urduTextSize *= shrink
            enFooterPaint.textSize = enTextSize
            urduFooterPaint.textSize = urduTextSize
        }

        val enLayoutWidth = ceil(Layout.getDesiredWidth(CREATED_BY_LABEL, enFooterPaint)).toInt().coerceAtLeast(1)
        val enFooterLayout = StaticLayout.Builder.obtain(
            CREATED_BY_LABEL,
            0,
            CREATED_BY_LABEL.length,
            enFooterPaint,
            enLayoutWidth
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.LTR)
            .setIncludePad(true)
            .setMaxLines(1)
            .build()

        val urduLayoutWidth = ceil(Layout.getDesiredWidth(URDU_FOOTER_TEXT, urduFooterPaint)).toInt().coerceAtLeast(1)
        val urduFooterLayout = StaticLayout.Builder.obtain(
            URDU_FOOTER_TEXT,
            0,
            URDU_FOOTER_TEXT.length,
            urduFooterPaint,
            urduLayoutWidth
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(true)
            .setMaxLines(1)
            .build()

        val enAscent = -enFooterPaint.fontMetrics.ascent
        val enDescent = enFooterPaint.fontMetrics.descent
        val urduAscent = -urduFooterPaint.fontMetrics.ascent
        val urduDescent = urduFooterPaint.fontMetrics.descent
        val maxFooterAscent = maxOf(enAscent, urduAscent)
        val maxFooterDescent = maxOf(enDescent, urduDescent)
        val footerZoneHeight = maxOf(26f * unit, maxFooterAscent + maxFooterDescent + (8f * unit))

        val cardHeight = dividerRelY + footerZoneHeight + (padBottom * 0.35f)
        val totalVisualHeight = logoOverhang + cardHeight

        val cardLeft = when (config.watermarkPosition) {
            WatermarkPosition.BOTTOM_LEFT -> outerMargin
            WatermarkPosition.BOTTOM_CENTER -> (containerWidth - cardWidth) / 2f
            WatermarkPosition.BOTTOM_RIGHT -> containerWidth - outerMargin - cardWidth
        }
        val cardTop = if (anchorToBottom) {
            (containerHeight - outerMargin - cardHeight).coerceAtLeast(logoOverhang + 4f)
        } else {
            logoOverhang
        }
        val cardRect = RectF(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)

        val logoRect = if (hasLogo) {
            val logoRight = cardRect.right - logoRightInset
            val logoLeft = logoRight - logoSize
            val logoTop = cardRect.top - logoOverhang
            RectF(logoLeft, logoTop, logoRight, logoTop + logoSize)
        } else {
            null
        }

        val bounds = WatermarkBounds(
            cardRect = cardRect,
            logoRect = logoRect,
            cornerRadius = cornerRadius,
            unit = unit,
            outerMargin = outerMargin
        )

        return MeasuredOverlayLayout(
            bounds = bounds,
            padX = padX,
            padTop = padTop,
            padBottom = padBottom,
            titleGap = titleGap,
            detailGap = detailGap,
            dividerRelY = dividerRelY,
            totalVisualHeight = totalVisualHeight,
            locationLayout = locationLayout,
            addressLayout = addressLayout,
            coordLayout = coordLayout,
            dateTimeLayout = dateTimeLayout,
            enFooterLayout = enFooterLayout,
            urduFooterLayout = urduFooterLayout,
            labelUrduGap = labelUrduGap,
            enAscent = enAscent,
            maxFooterAscent = maxFooterAscent,
            maxFooterDescent = maxFooterDescent,
            footerZoneHeight = footerZoneHeight
        )
    }

    /**
     * Reconstructs the area previously occupied by a baked-in watermark card & upper-right circular logo
     * from clean surrounding pixels so a newly rendered semi-transparent watermark keeps its natural
     * transparency without double-darkening or ghosting old text underneath.
     */
    private fun patchExistingWatermarkArea(
        target: Bitmap,
        config: WatermarkConfig,
        oldGps: GpsSnapshot,
        nowMillis: Long
    ) {
        val w = target.width
        val h = target.height
        if (w < 64 || h < 64) return

        val oldMeasured = measureOverlayLayout(
            containerWidth = w.toFloat(),
            containerHeight = h.toFloat(),
            config = config,
            gps = oldGps,
            nowMillis = nowMillis,
            anchorToBottom = true
        )

        val oldBounds = oldMeasured.bounds
        val unit = oldBounds.unit
        val cardLeft = oldBounds.cardRect.left
        val cardRight = oldBounds.cardRect.right
        val cardBottom = oldBounds.cardRect.bottom

        // Use pixel luminance detection when the baked card's top border sits higher than oldGps estimate
        val detectedCardTop = detectBakedCardTop(
            bitmap = target,
            cardRect = oldBounds.cardRect,
            logoRect = oldBounds.logoRect,
            unit = unit,
            outerMargin = oldBounds.outerMargin
        )

        val effectiveCardTop = minOf(oldBounds.cardRect.top, detectedCardTop)
        val pad = (3f * unit).coerceAtLeast(3f)

        val patchCard = RectF(
            (cardLeft - pad).coerceAtLeast(0f),
            (effectiveCardTop - pad).coerceAtLeast(0f),
            (cardRight + pad).coerceAtMost(w.toFloat()),
            (cardBottom + pad).coerceAtMost(h.toFloat())
        )
        if (patchCard.width() < 8f || patchCard.height() < 8f) return

        val effectiveLogoRect = oldBounds.logoRect?.let { lr ->
            val shiftY = effectiveCardTop - oldBounds.cardRect.top
            val shiftedTop = lr.top + shiftY
            val unionTop = minOf(lr.top, shiftedTop)
            val unionBottom = maxOf(lr.bottom, shiftedTop + lr.height())
            RectF(
                (lr.left - pad).coerceAtLeast(0f),
                (unionTop - pad).coerceAtLeast(0f),
                (lr.right + pad).coerceAtMost(w.toFloat()),
                (unionBottom + pad).coerceAtMost(h.toFloat())
            )
        }

        val patchUnion = RectF(patchCard).apply {
            if (effectiveLogoRect != null) union(effectiveLogoRect)
        }

        val sampleOffset = (6f * unit).coerceIn(4f, 18f)
        val topSamples = IntArray(32) { i ->
            val u = i / 31f
            val sx = (patchCard.left + u * patchCard.width()).toInt().coerceIn(0, w - 1)
            val hitsLogo = effectiveLogoRect != null &&
                sx >= (effectiveLogoRect.left - 3f).toInt() &&
                sx <= (effectiveLogoRect.right + 3f).toInt()
            val refTop = if (hitsLogo && effectiveLogoRect != null) effectiveLogoRect.top else patchCard.top
            val sy = (refTop - sampleOffset).toInt().coerceIn(0, h - 1)
            sampleLocalAverageColor(target, sx, sy, w, h)
        }

        val botSamples = IntArray(32) { i ->
            val u = i / 31f
            val sx = (patchCard.left + u * patchCard.width()).toInt().coerceIn(0, w - 1)
            val sy = (patchCard.bottom + sampleOffset).toInt().coerceIn(0, h - 1)
            sampleLocalAverageColor(target, sx, sy, w, h)
        }

        val leftSamples = IntArray(12) { i ->
            val v = i / 11f
            val sy = (patchCard.top + v * patchCard.height()).toInt().coerceIn(0, h - 1)
            val sx = (patchCard.left - sampleOffset).toInt().coerceIn(0, w - 1)
            sampleLocalAverageColor(target, sx, sy, w, h)
        }

        val rightSamples = IntArray(12) { i ->
            val v = i / 11f
            val sy = (patchCard.top + v * patchCard.height()).toInt().coerceIn(0, h - 1)
            val sx = (patchCard.right + sampleOffset).toInt().coerceIn(0, w - 1)
            sampleLocalAverageColor(target, sx, sy, w, h)
        }

        val gridW = 160
        val gridH = 64
        val gridPixels = IntArray(gridW * gridH)
        val topMax = topSamples.size - 1
        val botMax = botSamples.size - 1
        val sideMax = leftSamples.size - 1

        for (gy in 0 until gridH) {
            val v = gy.toFloat() / (gridH - 1)
            val wyTop = 1f / ((v + 0.03f) * (v + 0.03f))
            val wyBot = 1f / ((1f - v + 0.03f) * (1f - v + 0.03f))
            val leftCol = interpolateSampleArray(leftSamples, v * sideMax)
            val rightCol = interpolateSampleArray(rightSamples, v * sideMax)

            for (gx in 0 until gridW) {
                val u = gx.toFloat() / (gridW - 1)
                val wxLeft = 1f / ((u + 0.03f) * (u + 0.03f))
                val wxRight = 1f / ((1f - u + 0.03f) * (1f - u + 0.03f))

                val topCol = interpolateSampleArray(topSamples, u * topMax)
                val botCol = interpolateSampleArray(botSamples, u * botMax)

                val wSum = wyTop + wyBot + wxLeft + wxRight
                val r = (Color.red(topCol) * wyTop +
                    Color.red(botCol) * wyBot +
                    Color.red(leftCol) * wxLeft +
                    Color.red(rightCol) * wxRight) / wSum
                val g = (Color.green(topCol) * wyTop +
                    Color.green(botCol) * wyBot +
                    Color.green(leftCol) * wxLeft +
                    Color.green(rightCol) * wxRight) / wSum
                val b = (Color.blue(topCol) * wyTop +
                    Color.blue(botCol) * wyBot +
                    Color.blue(leftCol) * wxLeft +
                    Color.blue(rightCol) * wxRight) / wSum

                gridPixels[gy * gridW + gx] = Color.argb(
                    255,
                    r.toInt().coerceIn(0, 255),
                    g.toInt().coerceIn(0, 255),
                    b.toInt().coerceIn(0, 255)
                )
            }
        }

        val patchBitmap = Bitmap.createBitmap(gridW, gridH, Bitmap.Config.ARGB_8888)
        patchBitmap.setPixels(gridPixels, 0, gridW, 0, 0, gridW, gridH)

        val clipPath = Path().apply {
            val radius = oldBounds.cornerRadius + pad
            addRoundRect(patchCard, radius, radius, Path.Direction.CW)
            if (effectiveLogoRect != null) {
                addCircle(
                    effectiveLogoRect.centerX(),
                    effectiveLogoRect.centerY(),
                    effectiveLogoRect.width() / 2f,
                    Path.Direction.CW
                )
            }
        }

        val canvas = Canvas(target)
        canvas.save()
        canvas.clipPath(clipPath)
        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(patchBitmap, null, patchUnion, drawPaint)
        canvas.restore()
        patchBitmap.recycle()
    }

    private fun detectBakedCardTop(
        bitmap: Bitmap,
        cardRect: RectF,
        logoRect: RectF?,
        unit: Float,
        outerMargin: Float
    ): Float {
        val w = bitmap.width
        val h = bitmap.height
        val xStart = (cardRect.left + 20f * unit).toInt().coerceIn(0, w - 1)
        val xRightLimit = logoRect?.left ?: (cardRect.right - 20f * unit)
        val xEnd = (xRightLimit - 12f * unit).toInt().coerceIn(xStart + 10, w - 1)
        if (xEnd <= xStart + 10) return cardRect.top

        val sampleYInside = (cardRect.bottom - 20f * unit).toInt().coerceIn(0, h - 1)
        val insideLum = rowLowPercentileLum(bitmap, xStart, xEnd, sampleYInside)
        val aboveExpectedY = (cardRect.top - 8f * unit).toInt().coerceIn(0, h - 1)
        val lumAboveExpected = rowLowPercentileLum(bitmap, xStart, xEnd, aboveExpectedY)

        // Only search higher if pixels just above the estimated top still appear darkened like the card interior
        if (lumAboveExpected > insideLum + 18f || insideLum > 85f) {
            return cardRect.top
        }

        val minAllowedTop = (cardRect.bottom - h * 0.45f).coerceAtLeast(outerMargin + 20f * unit).toInt()
        var y = aboveExpectedY
        while (y > minAllowedTop + 8) {
            val lumAt = rowLowPercentileLum(bitmap, xStart, xEnd, y)
            val lumHigher = rowLowPercentileLum(bitmap, xStart, xEnd, (y - 6).coerceAtLeast(0))
            if (lumHigher - lumAt >= 18f && lumHigher > insideLum + 18f) {
                return y.toFloat()
            }
            if (lumAt > insideLum + 26f) {
                return (y + 2).toFloat()
            }
            y -= 2
        }
        return cardRect.top
    }

    private fun rowLowPercentileLum(bitmap: Bitmap, xStart: Int, xEnd: Int, y: Int): Float {
        val count = 20
        val lums = FloatArray(count)
        val span = (xEnd - xStart).coerceAtLeast(1)
        for (i in 0 until count) {
            val x = (xStart + (span * i) / (count - 1)).coerceIn(0, bitmap.width - 1)
            val c = bitmap.getPixel(x, y.coerceIn(0, bitmap.height - 1))
            lums[i] = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c))
        }
        lums.sort()
        return lums[count / 4]
    }

    private fun sampleLocalAverageColor(bitmap: Bitmap, cx: Int, cy: Int, w: Int, h: Int): Int {
        var rSum = 0
        var gSum = 0
        var bSum = 0
        var count = 0
        for (dy in -2..2 step 2) {
            val py = (cy + dy).coerceIn(0, h - 1)
            for (dx in -2..2 step 2) {
                val px = (cx + dx).coerceIn(0, w - 1)
                val c = bitmap.getPixel(px, py)
                rSum += Color.red(c)
                gSum += Color.green(c)
                bSum += Color.blue(c)
                count++
            }
        }
        return Color.rgb(rSum / count, gSum / count, bSum / count)
    }

    private fun interpolateSampleArray(samples: IntArray, idxFloat: Float): Int {
        val maxIdx = samples.size - 1
        val clamped = idxFloat.coerceIn(0f, maxIdx.toFloat())
        val i0 = clamped.toInt().coerceIn(0, maxIdx)
        val i1 = (i0 + 1).coerceIn(0, maxIdx)
        val t = clamped - i0
        val c0 = samples[i0]
        val c1 = samples[i1]
        val r = (Color.red(c0) * (1f - t) + Color.red(c1) * t).toInt().coerceIn(0, 255)
        val g = (Color.green(c0) * (1f - t) + Color.green(c1) * t).toInt().coerceIn(0, 255)
        val b = (Color.blue(c0) * (1f - t) + Color.blue(c1) * t).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun drawUpperRightCircularLogo(
        canvas: Canvas,
        rect: RectF,
        logoBitmap: Bitmap?,
        unit: Float
    ) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val radius = rect.width() / 2f

        val circleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0D0F12")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, radius, circleBg)

        if (logoBitmap != null && !logoBitmap.isRecycled && logoBitmap.width > 0 && logoBitmap.height > 0) {
            canvas.save()
            val clipPath = Path().apply {
                addCircle(cx, cy, radius, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            val minSide = minOf(logoBitmap.width, logoBitmap.height)
            val srcLeft = (logoBitmap.width - minSide) / 2
            val srcTop = (logoBitmap.height - minSide) / 2
            val srcRect = Rect(srcLeft, srcTop, srcLeft + minSide, srcTop + minSide)
            val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(logoBitmap, srcRect, rect, bmpPaint)
            canvas.restore()
        }

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(48, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = (1.0f * unit).coerceAtLeast(1f)
        }
        canvas.drawCircle(cx, cy, radius, ringPaint)
    }

    private fun buildLocationHeadlineWithFlag(gps: GpsSnapshot): String {
        val base = gps.buildLocationHeadline()
        if (!gps.hasFix || containsFlagEmoji(base)) return base

        val countryCandidate = gps.country.trim().ifEmpty {
            base.substringAfterLast(',', "").trim()
        }
        if (countryCandidate.isEmpty()) return base

        val iso = if (countryCandidate.length == 2 && countryCandidate.all { it.isLetter() }) {
            countryCandidate.uppercase(Locale.US)
        } else {
            isoCountryByName[countryCandidate.lowercase(Locale.US)]
        } ?: return base

        val flag = isoToFlagEmoji(iso)
        return if (flag.isNotEmpty()) "$base $flag" else base
    }

    private fun containsFlagEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun isoToFlagEmoji(isoCode: String): String {
        if (isoCode.length != 2) return ""
        val first = isoCode[0].uppercaseChar()
        val second = isoCode[1].uppercaseChar()
        if (first !in 'A'..'Z' || second !in 'A'..'Z') return ""
        val cp1 = 0x1F1E6 + (first - 'A')
        val cp2 = 0x1F1E6 + (second - 'A')
        return String(Character.toChars(cp1)) + String(Character.toChars(cp2))
    }

    private fun formatWatermarkCoordinatesLine(config: WatermarkConfig, gps: GpsSnapshot): String {
        if (!gps.hasFix) return "Lat --.------° Long --.------°"
        return when (config.coordFormat) {
            CoordFormat.DECIMAL -> String.format(
                Locale.US,
                "Lat %.6f° Long %.6f°",
                gps.latitude,
                gps.longitude
            )
            CoordFormat.DMS -> {
                val latDms = formatSingleDms(gps.latitude, isLatitude = true)
                val lonDms = formatSingleDms(gps.longitude, isLatitude = false)
                "Lat $latDms Long $lonDms"
            }
        }
    }

    private fun formatSingleDms(coord: Double, isLatitude: Boolean): String {
        val hemisphere = if (isLatitude) {
            if (coord >= 0) "N" else "S"
        } else {
            if (coord >= 0) "E" else "W"
        }
        val absolute = abs(coord)
        val degrees = absolute.toInt()
        val minutesFull = (absolute - degrees) * 60.0
        val minutes = minutesFull.toInt()
        val seconds = (minutesFull - minutes) * 60.0
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", degrees, minutes, seconds, hemisphere)
    }

    private fun formatWatermarkDateTimeLine(
        config: WatermarkConfig,
        gps: GpsSnapshot,
        nowMillis: Long
    ): String {
        val targetMillis = config.resolveTimestampMillis(gps, nowMillis)
        val targetDate = Date(targetMillis)
        val tz = TimeZone.getDefault()

        val datePattern = when (config.dateFormatPattern) {
            "yyyy-MM-dd" -> "EEEE, yyyy-MM-dd"
            "EEE, dd MMM yyyy" -> "EEE, dd MMM yyyy"
            "dd/MM/yyyy", "dd MMM yyyy" -> "EEEE, dd/MM/yyyy"
            else -> "EEEE, dd/MM/yyyy"
        }
        val timePattern = if (config.is24HourFormat) "HH:mm" else "hh:mm a"

        val datePart = runCatching {
            SimpleDateFormat(datePattern, Locale.US).apply { timeZone = tz }.format(targetDate)
        }.getOrElse {
            SimpleDateFormat("EEEE, dd/MM/yyyy", Locale.US).apply { timeZone = tz }.format(targetDate)
        }

        val timePart = SimpleDateFormat(timePattern, Locale.US)
            .apply { timeZone = tz }
            .format(targetDate)
            .uppercase(Locale.US)

        val offsetMs = tz.getOffset(targetMillis)
        val sign = if (offsetMs >= 0) "+" else "-"
        val totalMinutes = abs(offsetMs) / 60000
        val gmtPart = String.format(Locale.US, "GMT %s%02d:%02d", sign, totalMinutes / 60, totalMinutes % 60)

        return "$datePart $timePart $gmtPart"
    }

    private fun buildLtrLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        maxLines: Int
    ): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.LTR)
            .setLineSpacing(0f, 1.10f)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }
}
