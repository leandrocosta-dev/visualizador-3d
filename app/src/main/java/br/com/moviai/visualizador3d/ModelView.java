package br.com.moviai.visualizador3d;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

/**
 * Gestos:
 *  1 dedo arrastando  = girar
 *  2 dedos pinça      = zoom
 *  2 dedos arrastando = mover (pan)
 *  toque duplo        = reenquadrar
 *  toque simples      = repassado ao TapListener (medir, alinhar)
 */
final class ModelView extends GLSurfaceView {

    interface TapListener {
        void onTap(float x, float y);
    }

    private TapListener tapListener;

    void setTapListener(TapListener l) {
        tapListener = l;
    }

    final ModelRenderer renderer;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    private float lastX, lastY;
    private int lastCount = -1;

    ModelView(Context ctx) {
        super(ctx);
        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(true);
        renderer = new ModelRenderer(ctx.getResources().getDisplayMetrics().density);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                renderer.zoom(d.getScaleFactor());
                requestRender();
                return true;
            }
        });

        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tapListener != null) tapListener.onTap(e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                renderer.resetView();
                requestRender();
                return true;
            }
        });
    }

    void setMesh(Mesh m) {
        renderer.setMesh(m);
        requestRender();
    }

    void setMode(int mode) {
        renderer.setMode(mode);
        requestRender();
    }

    void resetView() {
        renderer.resetView();
        requestRender();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);

        int count = e.getPointerCount();
        float cx = 0, cy = 0;
        for (int i = 0; i < count; i++) {
            cx += e.getX(i);
            cy += e.getY(i);
        }
        cx /= count;
        cy /= count;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                lastCount = -1; // recomeça a referência para não dar "pulo"
                break;
            case MotionEvent.ACTION_MOVE:
                if (count != lastCount) {
                    lastCount = count;
                } else {
                    float dx = cx - lastX, dy = cy - lastY;
                    if (count == 1 && !scaleDetector.isInProgress()) {
                        renderer.rotate(dx, dy);
                    } else if (count >= 2) {
                        renderer.pan(dx, dy);
                    }
                    requestRender();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastCount = -1;
                break;
            default:
                break;
        }
        lastX = cx;
        lastY = cy;
        return true;
    }
}
