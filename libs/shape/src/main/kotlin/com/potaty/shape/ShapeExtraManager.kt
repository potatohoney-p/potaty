/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.shape

import com.potaty.livedata.LiveData
import com.potaty.livedata.MutableLiveData
import com.potaty.shape.extra.LineExtra
import com.potaty.shape.extra.RectangleExtra
import com.potaty.shape.extra.manager.predefined.PredefinedAnchorChar
import com.potaty.shape.extra.manager.predefined.PredefinedRectangleFillStyle
import com.potaty.shape.extra.manager.predefined.PredefinedStraightStrokeStyle
import com.potaty.shape.extra.style.AnchorChar
import com.potaty.shape.extra.style.RectangleFillStyle
import com.potaty.shape.extra.style.StraightStrokeDashPattern
import com.potaty.shape.extra.style.StraightStrokeStyle
import com.potaty.shape.extra.style.TextAlign

/**
 * A manager class for managing shape extras
 */
object ShapeExtraManager {
    var defaultRectangleExtra: RectangleExtra = RectangleExtra(
        isFillEnabled = false,
        userSelectedFillStyle = PredefinedRectangleFillStyle.PREDEFINED_STYLES[0],
        isBorderEnabled = true,
        userSelectedBorderStyle = PredefinedStraightStrokeStyle.PREDEFINED_STYLES[0],
        dashPattern = StraightStrokeDashPattern.SOLID,
        isRoundedCorner = false
    )
        private set

    var defaultLineExtra: LineExtra = LineExtra(
        isStrokeEnabled = true,
        PredefinedStraightStrokeStyle.PREDEFINED_STYLES[0],

        isStartAnchorEnabled = false,
        userSelectedStartAnchor = PredefinedAnchorChar.PREDEFINED_ANCHOR_CHARS[0],

        isEndAnchorEnabled = false,
        userSelectedEndAnchor = PredefinedAnchorChar.PREDEFINED_ANCHOR_CHARS[0],

        dashPattern = StraightStrokeDashPattern.SOLID,

        isRoundedCorner = false
    )
        private set

    var defaultTextAlign: TextAlign =
        TextAlign(TextAlign.HorizontalAlign.MIDDLE, TextAlign.VerticalAlign.MIDDLE)
        private set

    private val defaultExtraStateUpdateMutableLiveData = MutableLiveData(Unit)
    val defaultExtraStateUpdateLiveData: LiveData<Unit> = defaultExtraStateUpdateMutableLiveData

    fun setDefaultValues(
        isFillEnabled: Boolean? = null,
        fillStyleId: String? = null,

        isBorderEnabled: Boolean? = null,
        borderStyleId: String? = null,
        isBorderRoundedCorner: Boolean? = null,

        borderDashPattern: StraightStrokeDashPattern? = null,

        isLineStrokeEnabled: Boolean? = null,
        lineStrokeStyleId: String? = null,
        isLineStrokeRoundedCorner: Boolean? = null,

        lineDashPattern: StraightStrokeDashPattern? = null,

        isStartHeadAnchorCharEnabled: Boolean? = null,
        startHeadAnchorCharId: String? = null,

        isEndHeadAnchorCharEnabled: Boolean? = null,
        endHeadAnchorCharId: String? = null,

        textHorizontalAlign: TextAlign.HorizontalAlign? = null,
        textVerticalAlign: TextAlign.VerticalAlign? = null
    ) {
        defaultRectangleExtra = RectangleExtra(
            isFillEnabled ?: defaultRectangleExtra.isFillEnabled,
            getRectangleFillStyle(fillStyleId),
            isBorderEnabled ?: defaultRectangleExtra.isBorderEnabled,
            getRectangleBorderStyle(borderStyleId),
            borderDashPattern ?: defaultRectangleExtra.dashPattern,
            isRoundedCorner = isBorderRoundedCorner ?: defaultRectangleExtra.isRoundedCorner
        )

        defaultLineExtra = LineExtra(
            isStrokeEnabled = isLineStrokeEnabled ?: defaultLineExtra.isStrokeEnabled,
            userSelectedStrokeStyle = getLineStrokeStyle(lineStrokeStyleId),

            isStartAnchorEnabled = isStartHeadAnchorCharEnabled
                ?: defaultLineExtra.isStartAnchorEnabled,
            userSelectedStartAnchor = getStartHeadAnchorChar(startHeadAnchorCharId),

            isEndAnchorEnabled = isEndHeadAnchorCharEnabled ?: defaultLineExtra.isEndAnchorEnabled,
            userSelectedEndAnchor = getEndHeadAnchorChar(endHeadAnchorCharId),

            dashPattern = lineDashPattern ?: StraightStrokeDashPattern.SOLID,
            isRoundedCorner = isLineStrokeRoundedCorner ?: defaultLineExtra.isRoundedCorner
        )

        defaultTextAlign = TextAlign(
            textHorizontalAlign ?: defaultTextAlign.horizontalAlign,
            textVerticalAlign ?: defaultTextAlign.verticalAlign
        )

        defaultExtraStateUpdateMutableLiveData.value = Unit
    }

    fun getRectangleFillStyle(
        id: String?,
        default: RectangleFillStyle = defaultRectangleExtra.userSelectedFillStyle
    ): RectangleFillStyle = PredefinedRectangleFillStyle.PREDEFINED_STYLE_MAP[id] ?: default

    fun getAllPredefinedRectangleFillStyles(): List<RectangleFillStyle> =
        PredefinedRectangleFillStyle.PREDEFINED_STYLES

    fun getRectangleBorderStyle(
        id: String?,
        default: StraightStrokeStyle = defaultRectangleExtra.userSelectedBorderStyle
    ): StraightStrokeStyle = PredefinedStraightStrokeStyle.getStyle(id) ?: default

    fun getLineStrokeStyle(
        id: String?,
        default: StraightStrokeStyle = defaultLineExtra.userSelectedStrokeStyle
    ): StraightStrokeStyle = PredefinedStraightStrokeStyle.getStyle(id) ?: default

    fun getAllPredefinedStrokeStyles(): List<StraightStrokeStyle> =
        PredefinedStraightStrokeStyle.PREDEFINED_STYLES

    fun getStartHeadAnchorChar(
        id: String?,
        default: AnchorChar = defaultLineExtra.userSelectedStartAnchor
    ): AnchorChar = PredefinedAnchorChar.PREDEFINED_ANCHOR_CHAR_MAP[id] ?: default

    fun getEndHeadAnchorChar(
        id: String?,
        default: AnchorChar = defaultLineExtra.userSelectedEndAnchor
    ): AnchorChar = PredefinedAnchorChar.PREDEFINED_ANCHOR_CHAR_MAP[id] ?: default

    fun getAllPredefinedAnchorChars(): List<AnchorChar> =
        PredefinedAnchorChar.PREDEFINED_ANCHOR_CHARS
}
