package com.cesia.input

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * 流式候选布局：按子 View 实际宽度从左到右排布，自动换行。
 * 用于候选面板 —— 每个候选词一个 chip，长词占更宽格子、短词占窄格子，
 * 一行能放几个放几个（默认尽量 4~5 个），优先让格子适配字词，其次才缩字。
 */
class CesiaFlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ViewGroup(context, attrs, defStyle) {

    private var horizontalSpacing = 4
    private var verticalSpacing = 4

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)

        var lineWidth = 0
        var totalHeight = 0
        var lineHeight = 0
        var maxWidth = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, totalHeight)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (lineWidth + childWidth > width - paddingLeft - paddingRight && lineWidth > 0) {
                maxWidth = maxOf(maxWidth, lineWidth)
                totalHeight += lineHeight + verticalSpacing
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += childWidth + horizontalSpacing
            lineHeight = maxOf(lineHeight, childHeight)
        }
        maxWidth = maxOf(maxWidth, lineWidth)
        totalHeight += lineHeight

        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> width
            MeasureSpec.AT_MOST -> minOf(maxWidth + paddingLeft + paddingRight, width)
            else -> maxWidth + paddingLeft + paddingRight
        }
        val measuredHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(totalHeight + paddingTop + paddingBottom,
                MeasureSpec.getSize(heightMeasureSpec))
            else -> totalHeight + paddingTop + paddingBottom
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val parentLeft = paddingLeft
        val parentRight = r - l - paddingRight
        val parentTop = paddingTop

        var x = parentLeft
        var y = parentTop
        var lineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (x + lp.leftMargin + childWidth + lp.rightMargin > parentRight && x > parentLeft) {
                x = parentLeft
                y += lineHeight + verticalSpacing
                lineHeight = 0
            }

            val left = x + lp.leftMargin
            val top = y + lp.topMargin
            child.layout(left, top, left + childWidth, top + childHeight)

            x += lp.leftMargin + childWidth + lp.rightMargin + horizontalSpacing
            lineHeight = maxOf(lineHeight, lp.topMargin + childHeight + lp.bottomMargin)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams =
        MarginLayoutParams(p)
}
