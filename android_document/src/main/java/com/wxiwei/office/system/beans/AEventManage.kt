package com.wxiwei.office.system.beans

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.Scroller
import android.widget.Toast
import com.wxiwei.office.constant.EventConstant
import com.wxiwei.office.constant.MainConstant
import com.wxiwei.office.system.IControl
import com.wxiwei.office.system.IMainFrame
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

abstract class AEventManage(
    context: Context,
    @JvmField
    protected var control: IControl
) : View.OnTouchListener, GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener,
    View.OnClickListener {

    @JvmField
    protected var isFling = false
    @JvmField
    protected var isScroll = false
    protected var singleTabup = false
    protected var zoomChange = false
    protected var mMinimumVelocity = 0
    protected var mMaximumVelocity = 0
    protected var midXDoublePoint = 0
    protected var midYDoublePoint = 0
    protected var mActivePointerId = -1
    protected var distance = 0f
    protected var gesture: GestureDetector = GestureDetector(context, this, null, true)
    protected var mVelocityTracker: VelocityTracker? = null
    protected var mScroller: Scroller = Scroller(context)
    protected var toast: Toast = Toast.makeText(context, "", Toast.LENGTH_SHORT)

    init {
        val configuration = ViewConfiguration.get(context)
        mMinimumVelocity = configuration.scaledMinimumFlingVelocity
        mMaximumVelocity = configuration.scaledMaximumFlingVelocity
    }

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        var ret = false
        try {
            val currentGesture = gesture
            val currentControl = control
            currentControl.getMainFrame().onEventMethod(v, event, null, -1.0f, -1.0f, IMainFrame.ON_TOUCH)
            if (event.pointerCount == 2) {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    // the detector gets no two-finger events: without this its long press of the
                    // first finger fires mid-pinch (in edit mode that starts a drag and takes the touch)
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    currentGesture.onTouchEvent(cancel)
                    cancel.recycle()
                }
                return zoom(event)
            }
            currentGesture.onTouchEvent(event)
            if (mVelocityTracker == null) {
                mVelocityTracker = VelocityTracker.obtain()
            }
            mVelocityTracker?.addMovement(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    stopFling()
                    mActivePointerId = event.getPointerId(0)
                }
                MotionEvent.ACTION_UP -> {
                    if (!singleTabup) {
                        val velocityTracker = mVelocityTracker
                        velocityTracker?.computeCurrentVelocity(1000, mMaximumVelocity.toFloat())
                        val initialYVelocity = velocityTracker?.getYVelocity(mActivePointerId)?.toInt() ?: 0
                        val initialXVelocity = velocityTracker?.getXVelocity(mActivePointerId)?.toInt() ?: 0
                        if (abs(initialYVelocity) > mMinimumVelocity || abs(initialXVelocity) > mMinimumVelocity) {
                            if (!isScroll) {
                                isScroll = currentControl.getApplicationType().toInt() == MainConstant.APPLICATION_TYPE_PPT.toInt()
                            }
                            if (!zoomChange) {
                                fling(-initialXVelocity, -initialYVelocity)
                            }
                            ret = true
                        }
                        midXDoublePoint = -1
                        midYDoublePoint = -1
                        mActivePointerId = -1
                        mVelocityTracker?.recycle()
                        mVelocityTracker = null
                        toast.cancel()
                        if (isScroll) {
                            isScroll = false
                            if (currentControl.getApplicationType().toInt() == MainConstant.APPLICATION_TYPE_WP.toInt() && zoomChange && !currentControl.getMainFrame().isZoomAfterLayoutForWord()) {
                                currentControl.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)
                            }
                            if (currentControl.getApplicationType().toInt() == MainConstant.APPLICATION_TYPE_PPT.toInt()) {
                                if (!currentControl.isSlideShow()) {
                                    currentControl.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)
                                }
                            } else {
                                currentControl.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)
                            }
                            currentControl.actionEvent(EventConstant.SYS_UPDATE_TOOLSBAR_BUTTON_STATUS, null)
                        }
                        if (currentControl.getApplicationType().toInt() != MainConstant.APPLICATION_TYPE_WP.toInt()) {
                            zoomChange = false
                        }
                    }
                    singleTabup = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    mActivePointerId = -1
                    mVelocityTracker?.recycle()
                    mVelocityTracker = null
                }
            }
        } catch (e: Exception) {
            control.getSysKit().getErrorKit().writerLog(e)
        }
        return ret
    }

    protected open fun zoom(event: MotionEvent): Boolean {
        val currentControl = control
        if (!currentControl.getMainFrame().isTouchZoom()) return true
        var zoom = (currentControl.getActionValue(EventConstant.APP_ZOOM_ID, null) as Float).toDouble()
        val fitZoom = (currentControl.getActionValue(EventConstant.APP_FIT_ZOOM_ID, null) as Float).toDouble()
        val isMinZoom = (zoom * MainConstant.STANDARD_RATE).toInt() == (fitZoom * MainConstant.STANDARD_RATE).toInt()
        var zoomRateChanged = false
        var dist = distance
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                val x1 = event.getX(0)
                val y1 = event.getY(0)
                val x2 = event.getX(1)
                val y2 = event.getY(1)
                midXDoublePoint = (min(x1, x2) + abs(x1 - x2) / 2).toInt()
                midYDoublePoint = (min(y1, y2) + abs(y1 - y2) / 2).toInt()
                distance = sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)) / 2
            }
            MotionEvent.ACTION_MOVE -> {
                val tx1 = event.getX(0)
                val ty1 = event.getY(0)
                val tx2 = event.getX(1)
                val ty2 = event.getY(1)
                dist = sqrt((tx1 - tx2) * (tx1 - tx2) + (ty1 - ty2) * (ty1 - ty2)) / 2
                if (abs(distance - dist) > 8) {
                    val increased = dist > distance
                    if (!(abs(zoom - fitZoom) < 0.01 && !increased && isMinZoom) && !(abs(zoom - 3.0) < 0.001 && increased)) {
                        zoom += if (increased) 0.1 else -0.1
                        zoom = zoom.coerceIn(fitZoom, 3.0)
                        if (increased && isMinZoom) zoom = (zoom * 10).toInt() / 10.0
                        zoomRateChanged = true
                    }
                    if (zoomRateChanged) distance = dist
                }
            }
        }
        if (zoomRateChanged) {
            isScroll = true
            zoomChange = true
            currentControl.actionEvent(EventConstant.APP_ZOOM_ID, intArrayOf((zoom * MainConstant.STANDARD_RATE).toInt(), midXDoublePoint, midYDoublePoint))
            currentControl.getView().postInvalidate()
            if (currentControl.getMainFrame().isShowZoomingMsg()) {
                if (currentControl.getApplicationType().toInt() == MainConstant.APPLICATION_TYPE_PPT.toInt() && currentControl.isSlideShow()) return true
                toast.setText("${(zoom * 100).roundToInt()}%")
                toast.show()
            }
        }
        return true
    }

    override fun onDown(e: MotionEvent): Boolean = control.getMainFrame().onEventMethod(control.getView(), e, null, -1.0f, -1.0f, IMainFrame.ON_DOWN)
    override fun onShowPress(e: MotionEvent) { control.getMainFrame().onEventMethod(control.getView(), e, null, -1.0f, -1.0f, IMainFrame.ON_SHOW_PRESS) }
    override fun onSingleTapUp(e: MotionEvent): Boolean {
        if (!isScroll) singleTabup = true
        return control.getMainFrame().onEventMethod(control.getView(), e, null, -1.0f, -1.0f, IMainFrame.ON_SINGLE_TAP_UP)
    }
    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        isScroll = true
        return control.getMainFrame().onEventMethod(control.getView(), e1, e2, distanceX, distanceY, IMainFrame.ON_SCROLL)
    }
    override fun onLongPress(e: MotionEvent) { control.getMainFrame().onEventMethod(control.getView(), e, null, -1.0f, -1.0f, IMainFrame.ON_LONG_PRESS) }
    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean = control.getMainFrame().onEventMethod(control.getView(), e1, e2, velocityX, velocityY, IMainFrame.ON_FLING)
    override fun onSingleTapConfirmed(e: MotionEvent): Boolean = control.getMainFrame().onEventMethod(control.getView(), e, null, -1.0f, -1.0f, IMainFrame.ON_SINGLE_TAP_CONFIRMED)
    override fun onDoubleTap(e: MotionEvent): Boolean = control.getMainFrame().onEventMethod(control.getView(), e, null, -1.0f, -1.0f, IMainFrame.ON_DOUBLE_TAP)
    override fun onDoubleTapEvent(e: MotionEvent): Boolean = control.getMainFrame().onEventMethod(control.getView(), e, null, -1.0f, -1.0f, IMainFrame.ON_DOUBLE_TAP_EVENT)
    override fun onClick(v: View?) { control.getMainFrame().onEventMethod(control.getView(), null, null, -1.0f, -1.0f, IMainFrame.ON_CLICK) }

    open fun computeScroll() {
        if (isFling && mScroller.isFinished) {
            isFling = false
            control.actionEvent(EventConstant.APP_GENERATED_PICTURE_ID, null)
            control.actionEvent(EventConstant.SYS_UPDATE_TOOLSBAR_BUTTON_STATUS, null)
        }
    }

    open fun fling(velocityX: Int, velocityY: Int) {}
    fun stopFling() { if (!mScroller.isFinished) { isFling = true; mScroller.abortAnimation() } }
    fun getMiddleXOfDoublePoint(): Int = midXDoublePoint
    fun getMiddleYOfDoublePoint(): Int = midYDoublePoint
    open fun dispose() {
        toast.cancel()
        mScroller.abortAnimation()
        mVelocityTracker = null
    }
}
