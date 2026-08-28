package com.pdocxles.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout

class TouchZoomLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr),
    ScaleGestureDetector.OnScaleGestureListener,
    GestureDetector.OnGestureListener {

    private var scale = 1.0f
    private var posX = 0.0f
    private var posY = 0.0f

    private val minScale = 1.0f
    private val maxScale = 5.0f

    private val scaleDetector = ScaleGestureDetector(context, this)
    private val gestureDetector = GestureDetector(context, this)

    private val transformMatrix = Matrix()

    init {
        // Double-tap zoom explicitly disabled everywhere as requested
        gestureDetector.setOnDoubleTapListener(null)
        setWillNotDraw(false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)

        if (scale > 1.0f) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else {
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        // Apply inverted matrix to touch events so child views receive correct touch coordinates
        val transformedEvent = MotionEvent.obtain(ev)
        val inverseMatrix = Matrix()
        transformMatrix.invert(inverseMatrix)
        transformedEvent.transform(inverseMatrix)
        val handled = super.dispatchTouchEvent(transformedEvent)
        transformedEvent.recycle()
        return handled
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        transformMatrix.reset()
        transformMatrix.postScale(scale, scale, width / 2f, height / 2f)
        transformMatrix.postTranslate(posX, posY)
        canvas.concat(transformMatrix)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        scale *= detector.scaleFactor
        scale = scale.coerceIn(minScale, maxScale)

        clampPosition()
        invalidate()
        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true
    override fun onScaleEnd(detector: ScaleGestureDetector) {}

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (scale > 1.0f) {
            posX -= distanceX
            posY -= distanceY
            clampPosition()
            invalidate()
            return true
        }
        return false
    }

    private fun clampPosition() {
        if (scale <= 1.0f) {
            posX = 0f
            posY = 0f
            return
        }
        val maxPosX = (width * (scale - 1f)) / 2f
        val maxPosY = (height * (scale - 1f)) / 2f
        posX = posX.coerceIn(-maxPosX, maxPosX)
        posY = posY.coerceIn(-maxPosY, maxPosY)
    }

    override fun onDown(e: MotionEvent): Boolean = true
    override fun onShowPress(e: MotionEvent) {}
    override fun onSingleTapUp(e: MotionEvent): Boolean = false
    override fun onLongPress(e: MotionEvent) {}
    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean = false
}
