package com.myhexaville.androidwebrtc.app_rtc_sample.web_rtc;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.graphics.Bitmap.Config;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.webrtc.EglBase;
import org.webrtc.EglBase.Context;
import org.webrtc.EglBase10;
import org.webrtc.GlTextureFrameBuffer;
import org.webrtc.GlUtil;
import org.webrtc.Logging;
import org.webrtc.RendererCommon;
import org.webrtc.RendererCommon.GlDrawer;
import org.webrtc.RendererCommon.YuvUploader;
import org.webrtc.ThreadUtils;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRenderer.Callbacks;
import org.webrtc.VideoRenderer.I420Frame;

public class EglRenderer implements Callbacks {
    private static final String TAG = "EglRenderer";
    private static final long LOG_INTERVAL_SEC = 4L;
    private static final int MAX_SURFACE_CLEAR_COUNT = 3;
    private final String name;
    private final Object handlerLock = new Object();
    private Handler renderThreadHandler;
    private final ArrayList<FrameListenerAndParams> frameListeners = new ArrayList();
    private final Object fpsReductionLock = new Object();
    private long nextFrameTimeNs;
    private long minRenderPeriodNs;
    private EglBase eglBase;
    private final YuvUploader yuvUploader = new YuvUploader();
    private GlDrawer drawer;
    private int[] yuvTextures = null;
    private final Object frameLock = new Object();
    private I420Frame pendingFrame;
    private final Object layoutLock = new Object();
    private float layoutAspectRatio;
    private boolean mirror;
    private final Object statisticsLock = new Object();
    private int framesReceived;
    private int framesDropped;
    private int framesRendered;
    private long statisticsStartTimeNs;
    private long renderTimeNs;
    private long renderSwapBufferTimeNs;
    private GlTextureFrameBuffer bitmapTextureFramebuffer;
    private final Runnable renderFrameRunnable = new Runnable() {
        public void run() {
            renderFrameOnRenderThread();
        }
    };
    private final Runnable logStatisticsRunnable = new Runnable() {
        public void run() {
            logStatistics();
            synchronized(handlerLock) {
                if (renderThreadHandler != null) {
                    renderThreadHandler.removeCallbacks(logStatisticsRunnable);
                    renderThreadHandler.postDelayed(logStatisticsRunnable, TimeUnit.SECONDS.toMillis(4L));
                }

            }
        }
    };
    private final EglSurfaceCreation eglSurfaceCreationRunnable = new EglSurfaceCreation();

    public EglRenderer(String name) {
        this.name = name;
    }

    public void init(final Context sharedContext, final int[] configAttributes, GlDrawer drawer) {
        synchronized(this.handlerLock) {
            if (this.renderThreadHandler != null) {
                throw new IllegalStateException(this.name + "Already initialized");
            } else {
                this.logD("Initializing EglRenderer");
                this.drawer = drawer;
                HandlerThread renderThread = new HandlerThread(this.name + "EglRenderer");
                renderThread.start();
                this.renderThreadHandler = new Handler(renderThread.getLooper());
                ThreadUtils.invokeAtFrontUninterruptibly(this.renderThreadHandler, new Runnable() {
                    public void run() {
                        if (sharedContext == null) {
                            logD("EglBase10.create context");
                            eglBase = new EglBase10((org.webrtc.EglBase10.Context)null, configAttributes);
                        } else {
                            logD("EglBase.create shared context");
                            eglBase = EglBase.create(sharedContext, configAttributes);
                        }

                    }
                });
                this.renderThreadHandler.post(this.eglSurfaceCreationRunnable);
                long currentTimeNs = System.nanoTime();
                this.resetStatistics(currentTimeNs);
                this.renderThreadHandler.postDelayed(this.logStatisticsRunnable, TimeUnit.SECONDS.toMillis(4L));
            }
        }
    }

    public void createEglSurface(Surface surface) {
        this.createEglSurfaceInternal(surface);
    }

    public void createEglSurface(SurfaceTexture surfaceTexture) {
        this.createEglSurfaceInternal(surfaceTexture);
    }

    private void createEglSurfaceInternal(Object surface) {
        this.eglSurfaceCreationRunnable.setSurface(surface);
        this.postToRenderThread(this.eglSurfaceCreationRunnable);
    }

    public void release() {
        this.logD("Releasing.");
        final CountDownLatch eglCleanupBarrier = new CountDownLatch(1);
        synchronized(this.handlerLock) {
            if (this.renderThreadHandler == null) {
                this.logD("Already released");
                return;
            }

            this.renderThreadHandler.removeCallbacks(this.logStatisticsRunnable);
            this.renderThreadHandler.postAtFrontOfQueue(new Runnable() {
                public void run() {
                    if (drawer != null) {
                        drawer.release();
                        drawer = null;
                    }

                    if (yuvTextures != null) {
                        GLES20.glDeleteTextures(3, yuvTextures, 0);
                        yuvTextures = null;
                    }

                    if (bitmapTextureFramebuffer != null) {
                        bitmapTextureFramebuffer.release();
                        bitmapTextureFramebuffer = null;
                    }

                    if (eglBase != null) {
                        logD("eglBase detach and release.");
                        eglBase.detachCurrent();
                        eglBase.release();
                        eglBase = null;
                    }

                    eglCleanupBarrier.countDown();
                }
            });
            final Looper renderLooper = this.renderThreadHandler.getLooper();
            this.renderThreadHandler.post(new Runnable() {
                public void run() {
                    logD("Quitting render thread.");
                    renderLooper.quit();
                }
            });
            this.renderThreadHandler = null;
        }

        ThreadUtils.awaitUninterruptibly(eglCleanupBarrier);
        synchronized(this.frameLock) {
            if (this.pendingFrame != null) {
                VideoRenderer.renderFrameDone(this.pendingFrame);
                this.pendingFrame = null;
            }
        }

        this.logD("Releasing done.");
    }

    private void resetStatistics(long currentTimeNs) {
        synchronized(this.statisticsLock) {
            this.statisticsStartTimeNs = currentTimeNs;
            this.framesReceived = 0;
            this.framesDropped = 0;
            this.framesRendered = 0;
            this.renderTimeNs = 0L;
            this.renderSwapBufferTimeNs = 0L;
        }
    }

    public void printStackTrace() {
        synchronized(this.handlerLock) {
            Thread renderThread = this.renderThreadHandler == null ? null : this.renderThreadHandler.getLooper().getThread();
            if (renderThread != null) {
                StackTraceElement[] renderStackTrace = renderThread.getStackTrace();
                if (renderStackTrace.length > 0) {
                    this.logD("EglRenderer stack trace:");
                    StackTraceElement[] arr$ = renderStackTrace;
                    int len$ = renderStackTrace.length;

                    for(int i$ = 0; i$ < len$; ++i$) {
                        StackTraceElement traceElem = arr$[i$];
                        this.logD(traceElem.toString());
                    }
                }
            }

        }
    }

    public void setMirror(boolean mirror) {
        this.logD("setMirror: " + mirror);
        synchronized(this.layoutLock) {
            this.mirror = mirror;
        }
    }

    public void setLayoutAspectRatio(float layoutAspectRatio) {
        this.logD("setLayoutAspectRatio: " + layoutAspectRatio);
        synchronized(this.layoutLock) {
            this.layoutAspectRatio = layoutAspectRatio;
        }
    }

    public void setFpsReduction(float fps) {
        this.logD("setFpsReduction: " + fps);
        synchronized(this.fpsReductionLock) {
            long previousRenderPeriodNs = this.minRenderPeriodNs;
            if (fps <= 0.0F) {
                this.minRenderPeriodNs = 9223372036854775807L;
            } else {
                this.minRenderPeriodNs = (long)((float)TimeUnit.SECONDS.toNanos(1L) / fps);
            }

            if (this.minRenderPeriodNs != previousRenderPeriodNs) {
                this.nextFrameTimeNs = System.nanoTime();
            }

        }
    }

    public void disableFpsReduction() {
        this.setFpsReduction((float) (1.0F / 0.0));
    }

    public void pauseVideo() {
        this.setFpsReduction(0.0F);
    }

    public void addFrameListener(final FrameListener listener, final float scale) {
        this.postToRenderThread(new Runnable() {
            public void run() {
                frameListeners.add(new FrameListenerAndParams(listener, scale, drawer));
            }
        });
    }

    public void addFrameListener(final FrameListener listener, final float scale, final GlDrawer drawer) {
        this.postToRenderThread(new Runnable() {
            public void run() {
                frameListeners.add(new FrameListenerAndParams(listener, scale, drawer));
            }
        });
    }

    public void removeFrameListener(final FrameListener listener) {
        final CountDownLatch latch = new CountDownLatch(1);
        this.postToRenderThread(new Runnable() {
            public void run() {
                latch.countDown();
                Iterator iter = frameListeners.iterator();

                while(iter.hasNext()) {
                    if (((FrameListenerAndParams)iter.next()).listener == listener) {
                        iter.remove();
                    }
                }

            }
        });
        ThreadUtils.awaitUninterruptibly(latch);
    }

    public void renderFrame(I420Frame frame) {
        synchronized(this.statisticsLock) {
            ++this.framesReceived;
        }

        boolean dropOldFrame;
        synchronized(this.handlerLock) {
            if (this.renderThreadHandler == null) {
                this.logD("Dropping frame - Not initialized or already released.");
                VideoRenderer.renderFrameDone(frame);
                return;
            }

            synchronized(this.fpsReductionLock) {
                if (this.minRenderPeriodNs > 0L) {
                    long currentTimeNs = System.nanoTime();
                    if (currentTimeNs < this.nextFrameTimeNs) {
                        this.logD("Dropping frame - fps reduction is active.");
                        VideoRenderer.renderFrameDone(frame);
                        return;
                    } else {
                        this.nextFrameTimeNs += this.minRenderPeriodNs;
                        this.nextFrameTimeNs = Math.max(this.nextFrameTimeNs, currentTimeNs);
                    }
                }
            }

            synchronized(this.frameLock) {
                dropOldFrame = this.pendingFrame != null;
                if (dropOldFrame) {
                    VideoRenderer.renderFrameDone(this.pendingFrame);
                }

                this.pendingFrame = frame;
                this.renderThreadHandler.post(this.renderFrameRunnable);
            }
        }

        if (dropOldFrame) {
            synchronized(this.statisticsLock) {
                ++this.framesDropped;
            }
        }

    }

    public void releaseEglSurface(final Runnable completionCallback) {
        this.eglSurfaceCreationRunnable.setSurface((Object)null);
        synchronized(this.handlerLock) {
            if (this.renderThreadHandler != null) {
                this.renderThreadHandler.removeCallbacks(this.eglSurfaceCreationRunnable);
                this.renderThreadHandler.postAtFrontOfQueue(new Runnable() {
                    public void run() {
                        if (eglBase != null) {
                            eglBase.detachCurrent();
                            eglBase.releaseSurface();
                        }

                        completionCallback.run();
                    }
                });
                return;
            }
        }

        completionCallback.run();
    }

    private void postToRenderThread(Runnable runnable) {
        synchronized(this.handlerLock) {
            if (this.renderThreadHandler != null) {
                this.renderThreadHandler.post(runnable);
            }

        }
    }

    private void clearSurfaceOnRenderThread() {
        if (this.eglBase != null && this.eglBase.hasSurface()) {
            this.logD("clearSurface");
            GLES20.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GLES20.glClear(16384);
            this.eglBase.swapBuffers();
        }

    }

    public void clearImage() {
        synchronized(this.handlerLock) {
            if (this.renderThreadHandler != null) {
                this.renderThreadHandler.postAtFrontOfQueue(new Runnable() {
                    public void run() {
                        clearSurfaceOnRenderThread();
                    }
                });
            }
        }
    }

    private void renderFrameOnRenderThread() {
        I420Frame frame;
        synchronized(this.frameLock) {
            if (this.pendingFrame == null) {
                return;
            }

            frame = this.pendingFrame;
            this.pendingFrame = null;
        }

        if (this.eglBase != null && this.eglBase.hasSurface()) {
            long startTimeNs = System.nanoTime();
            float[] texMatrix = RendererCommon.rotateTextureMatrix(frame.samplingMatrix, (float)frame.rotationDegree);
            float[] drawMatrix;
            int drawnFrameWidth;
            int drawnFrameHeight;
            synchronized(this.layoutLock) {
                float[] layoutMatrix;
                if (this.layoutAspectRatio > 0.0F) {
                    float frameAspectRatio = (float)frame.rotatedWidth() / (float)frame.rotatedHeight();
                    layoutMatrix = RendererCommon.getLayoutMatrix(this.mirror, frameAspectRatio, this.layoutAspectRatio);
                    if (frameAspectRatio > this.layoutAspectRatio) {
                        drawnFrameWidth = (int)((float)frame.rotatedHeight() * this.layoutAspectRatio);
                        drawnFrameHeight = frame.rotatedHeight();
                    } else {
                        drawnFrameWidth = frame.rotatedWidth();
                        drawnFrameHeight = (int)((float)frame.rotatedWidth() / this.layoutAspectRatio);
                    }
                } else {
                    layoutMatrix = this.mirror ? RendererCommon.horizontalFlipMatrix() : RendererCommon.identityMatrix();
                    drawnFrameWidth = frame.rotatedWidth();
                    drawnFrameHeight = frame.rotatedHeight();
                }

                drawMatrix = RendererCommon.multiplyMatrices(texMatrix, layoutMatrix);
            }

            GLES20.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GLES20.glClear(16384);
            if (frame.yuvFrame) {
                if (this.yuvTextures == null) {
                    this.yuvTextures = new int[3];

                    for(int i = 0; i < 3; ++i) {
                        this.yuvTextures[i] = GlUtil.generateTexture(3553);
                    }
                }

                this.yuvUploader.uploadYuvData(this.yuvTextures, frame.width, frame.height, frame.yuvStrides, frame.yuvPlanes);
                this.drawer.drawYuv(this.yuvTextures, drawMatrix, drawnFrameWidth, drawnFrameHeight, 0, 0, this.eglBase.surfaceWidth(), this.eglBase.surfaceHeight());
            } else {
                this.drawer.drawOes(frame.textureId, drawMatrix, drawnFrameWidth, drawnFrameHeight, 0, 0, this.eglBase.surfaceWidth(), this.eglBase.surfaceHeight());
            }

            long swapBuffersStartTimeNs = System.nanoTime();
            this.eglBase.swapBuffers();
            long currentTimeNs = System.nanoTime();
            synchronized(this.statisticsLock) {
                ++this.framesRendered;
                this.renderTimeNs += currentTimeNs - startTimeNs;
                this.renderSwapBufferTimeNs += currentTimeNs - swapBuffersStartTimeNs;
            }

            this.notifyCallbacks(frame, texMatrix);
            VideoRenderer.renderFrameDone(frame);
        } else {
            this.logD("Dropping frame - No surface");
            VideoRenderer.renderFrameDone(frame);
        }
    }

    private void notifyCallbacks(I420Frame frame, float[] texMatrix) {
        if (!this.frameListeners.isEmpty()) {
            ArrayList<FrameListenerAndParams> tmpList = new ArrayList(this.frameListeners);
//            this.frameListeners.clear();
            float[] bitmapMatrix = RendererCommon.multiplyMatrices(RendererCommon.multiplyMatrices(texMatrix, this.mirror ? RendererCommon.horizontalFlipMatrix() : RendererCommon.identityMatrix()), RendererCommon.verticalFlipMatrix());
            Iterator i$ = tmpList.iterator();

//            while(true) {
                while(i$.hasNext()) {
                    FrameListenerAndParams listenerAndParams = (FrameListenerAndParams)i$.next();
                    int scaledWidth = (int)(listenerAndParams.scale * (float)frame.rotatedWidth());
                    int scaledHeight = (int)(listenerAndParams.scale * (float)frame.rotatedHeight());
                    if (scaledWidth != 0 && scaledHeight != 0) {
                        if (this.bitmapTextureFramebuffer == null) {
                            this.bitmapTextureFramebuffer = new GlTextureFrameBuffer(6408);
                        }

                        this.bitmapTextureFramebuffer.setSize(scaledWidth, scaledHeight);
                        GLES20.glBindFramebuffer(36160, this.bitmapTextureFramebuffer.getFrameBufferId());
                        GLES20.glFramebufferTexture2D(36160, 36064, 3553, this.bitmapTextureFramebuffer.getTextureId(), 0);
                        if (frame.yuvFrame) {
                            listenerAndParams.drawer.drawYuv(this.yuvTextures, bitmapMatrix, frame.rotatedWidth(), frame.rotatedHeight(), 0, 0, scaledWidth, scaledHeight);
                        } else {
                            listenerAndParams.drawer.drawOes(frame.textureId, bitmapMatrix, frame.rotatedWidth(), frame.rotatedHeight(), 0, 0, scaledWidth, scaledHeight);
                        }

                        ByteBuffer bitmapBuffer = ByteBuffer.allocateDirect(scaledWidth * scaledHeight * 4);
                        GLES20.glViewport(0, 0, scaledWidth, scaledHeight);
                        GLES20.glReadPixels(0, 0, scaledWidth, scaledHeight, 6408, 5121, bitmapBuffer);
                        GLES20.glBindFramebuffer(36160, 0);
                        GlUtil.checkNoGLES2Error("EglRenderer.notifyCallbacks");
                        Bitmap bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Config.ARGB_8888);
                        bitmap.copyPixelsFromBuffer(bitmapBuffer);
                        listenerAndParams.listener.onFrame(bitmap);
                    } else {
                        listenerAndParams.listener.onFrame((Bitmap)null);
                    }
                }

//                return;
//            }
        }
    }

    private String averageTimeAsString(long sumTimeNs, int count) {
        return count <= 0 ? "NA" : TimeUnit.NANOSECONDS.toMicros(sumTimeNs / (long)count) + " μs";
    }

    private void logStatistics() {
        long currentTimeNs = System.nanoTime();
        synchronized(this.statisticsLock) {
            long elapsedTimeNs = currentTimeNs - this.statisticsStartTimeNs;
            if (elapsedTimeNs > 0L) {
                float renderFps = (float)((long)this.framesRendered * TimeUnit.SECONDS.toNanos(1L)) / (float)elapsedTimeNs;
                this.logD("Duration: " + TimeUnit.NANOSECONDS.toMillis(elapsedTimeNs) + " ms." + " Frames received: " + this.framesReceived + "." + " Dropped: " + this.framesDropped + "." + " Rendered: " + this.framesRendered + "." + " Render fps: " + String.format("%.1f", renderFps) + "." + " Average render time: " + this.averageTimeAsString(this.renderTimeNs, this.framesRendered) + "." + " Average swapBuffer time: " + this.averageTimeAsString(this.renderSwapBufferTimeNs, this.framesRendered) + ".");
                this.resetStatistics(currentTimeNs);
            }
        }
    }

    private void logD(String string) {
        Logging.d("EglRenderer", this.name + string);
    }

    private class EglSurfaceCreation implements Runnable {
        private Object surface;

        private EglSurfaceCreation() {
        }

        public synchronized void setSurface(Object surface) {
            this.surface = surface;
        }

        public synchronized void run() {
            if (this.surface != null && eglBase != null && !eglBase.hasSurface()) {
                if (this.surface instanceof Surface) {
                    eglBase.createSurface((Surface)this.surface);
                } else {
                    if (!(this.surface instanceof SurfaceTexture)) {
                        throw new IllegalStateException("Invalid surface: " + this.surface);
                    }

                    eglBase.createSurface((SurfaceTexture)this.surface);
                }

                eglBase.makeCurrent();
                GLES20.glPixelStorei(3317, 1);
            }

        }
    }

    private static class FrameListenerAndParams {
        public final FrameListener listener;
        public final float scale;
        public final GlDrawer drawer;

        public FrameListenerAndParams(FrameListener listener, float scale, GlDrawer drawer) {
            this.listener = listener;
            this.scale = scale;
            this.drawer = drawer;
        }
    }

    public interface FrameListener {
        void onFrame(Bitmap var1);
    }
}
