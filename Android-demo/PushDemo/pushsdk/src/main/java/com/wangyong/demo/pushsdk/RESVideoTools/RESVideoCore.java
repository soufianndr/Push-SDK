package com.wangyong.demo.pushsdk.RESVideoTools;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.wangyong.demo.pushsdk.BasicClasses.Loging;
import com.wangyong.demo.pushsdk.MagicFilter.filter.advanced.MagicBeautyFilter;
import com.wangyong.demo.pushsdk.RESPushStreamManager;
import com.wangyong.demo.pushsdk.RESVideoTools.Filters.GPUImageCompatibleFilter;
import com.wangyong.demo.pushsdk.RESVideoTools.Filters.IconHardFilter;
import com.wangyong.demo.pushsdk.RESVideoTools.Filters.TextHardFilter;
import com.wangyong.demo.pushsdk.RESVideoTools.Filters.TimeStampHardFilter;
import com.wangyong.demo.pushsdk.RESVideoTools.Tools.GLHelper;
import com.wangyong.demo.pushsdk.RESVideoTools.Filters.HardVideoGroupFilter;
import com.wangyong.demo.pushsdk.RESVideoTools.Tools.MediaCodecGLWapper;
import com.wangyong.demo.pushsdk.RESVideoTools.Tools.MediaCodecHelper;
import com.wangyong.demo.pushsdk.RESVideoTools.Tools.OffScreenGLWapper;
import com.wangyong.demo.pushsdk.RESVideoTools.Tools.RESConfig;
import com.wangyong.demo.pushsdk.RESVideoTools.Tools.RESCoreParameters;
import com.wangyong.demo.pushsdk.RESVideoTools.Tools.RESFrameRateMeter;
import com.wangyong.demo.pushsdk.RESVideoTools.Tools.ScreenGLWapper;
import com.wangyong.demo.pushsdk.RESVideoTools.Tools.Size;
import com.wangyong.demo.pushsdk.RESVideoTools.Filters.BaseHardVideoFilter;

/**
 * Created by wangyong on 17-9-1.
 */

public class RESVideoCore {

    private static final String TAG = "RESVideoCore";

    RESCoreParameters resCoreParameters;
    private final Object syncOp = new Object();
    //filter
    private Lock lockVideoFilter = null;
    private BaseHardVideoFilter videoFilter;

    private LinkedList<BaseHardVideoFilter> filterList = null;
    private IconHardFilter iconHardFilter = null;
    private TimeStampHardFilter timeStampHardFilter = null;

    private MediaCodec dstVideoEncoder;
    private MediaFormat dstVideoFormat;
    private final Object syncPreview = new Object();
    private HandlerThread videoGLHandlerThread;
    private VideoGLHandler videoGLHander;

    private final Object syncIsLooping = new Object();
    private boolean isPreviewing = false;
    private boolean isStreaming = false;
    private int loopingInterval;

    private RESPushStreamManager resPushStreamManager = null;
    private MagicBeautyFilter faceBeautyFilter = null;

    public RESVideoCore(RESCoreParameters parameters) {
        resCoreParameters = parameters;
        lockVideoFilter = new ReentrantLock(false);
    }

    public void onFrameAvailable() {
        if (videoGLHandlerThread != null) {
            if (null != resPushStreamManager)
                resPushStreamManager.onVideoCapturedFrameArrived();

            videoGLHander.addFrameNum();
        }
    }

    public boolean prepare() {
        synchronized (syncOp) {
//            resCoreParameters.renderingMode = resConfig.getRenderingMode();
//            resCoreParameters.mediacdoecAVCBitRate = resConfig.getBitRate();
//            resCoreParameters.videoBufferQueueNum = resConfig.getVideoBufferQueueNum();
//            resCoreParameters.mediacodecAVCIFrameInterval = resConfig.getVideoGOP();
            resCoreParameters.mediacodecAVCIFrameInterval = resCoreParameters.videoGOP;
            resCoreParameters.mediacodecAVCFrameRate = resCoreParameters.videoFPS;
            loopingInterval = 1000 / resCoreParameters.videoFPS;
            dstVideoFormat = new MediaFormat();
            videoGLHandlerThread = new HandlerThread("GLThread");
            videoGLHandlerThread.start();
            videoGLHander = new VideoGLHandler(videoGLHandlerThread.getLooper());
            videoGLHander.sendEmptyMessage(VideoGLHandler.WHAT_INIT);
            return true;
        }
    }

    public void addVideoIcon(Bitmap bitmap, Rect rect) {
        lockVideoFilter.lock();

        filterList = new LinkedList<BaseHardVideoFilter>();

        timeStampHardFilter = new TimeStampHardFilter(null, Color.TRANSPARENT, 30);
        timeStampHardFilter.setPostion(TextHardFilter.Gravity.BOTTOM | TextHardFilter.Gravity.RIGHT, 30, 30);
        filterList.add(timeStampHardFilter);

        iconHardFilter = new IconHardFilter(bitmap, rect);
        filterList.add(iconHardFilter);
//        filterList.add(new GPUImageCompatibleFilter<>(new MagicFairytaleFilter()));
        faceBeautyFilter = new MagicBeautyFilter();
        faceBeautyFilter.setBeautyLevel(0);
        filterList.add(new GPUImageCompatibleFilter<>(faceBeautyFilter));

        videoFilter = new HardVideoGroupFilter(filterList);

        lockVideoFilter.unlock();
    }

    public void updateIcon(Bitmap bitmap, Rect rect) {
        if (null != iconHardFilter) {
            iconHardFilter.updateIcon(bitmap, rect);
        }
    }

    public void removeFilter(int index) {
        lockVideoFilter.lock();

        if (0 > index || null == filterList || index >= filterList.size()) {
            lockVideoFilter.unlock();
            return;
        }

        filterList.remove(index);

        if (true == filterList.isEmpty()) {
            videoFilter = null;
        } else {
            videoFilter = new HardVideoGroupFilter(filterList);
        }

        lockVideoFilter.unlock();
    }

    public void setBeautyLevel(int smooth, int white, int pink) {
        if (null != faceBeautyFilter)
            faceBeautyFilter.setBeautyLevel(smooth);
    }

    public void startPreview(SurfaceTexture surfaceTexture, int visualWidth, int visualHeight) {
        synchronized (syncOp) {
            videoGLHander.sendMessage(videoGLHander.obtainMessage(VideoGLHandler.WHAT_START_PREVIEW,
                    visualWidth, visualHeight, surfaceTexture));
            synchronized (syncIsLooping) {
                if (!isPreviewing && !isStreaming) {
                    videoGLHander.removeMessages(VideoGLHandler.WHAT_DRAW);
                    videoGLHander.sendMessageDelayed(videoGLHander.obtainMessage(VideoGLHandler.WHAT_DRAW, SystemClock.uptimeMillis() + loopingInterval), loopingInterval);
                }
                isPreviewing = true;
            }
        }
    }

    public void updatePreview(int visualWidth, int visualHeight) {
        synchronized (syncOp) {
            synchronized (syncPreview) {
                videoGLHander.updatePreview(visualWidth, visualHeight);
            }
        }
    }

    public void stopPreview(boolean releaseTexture) {
        synchronized (syncOp) {
            videoGLHander.sendMessage(videoGLHander.obtainMessage(VideoGLHandler.WHAT_STOP_PREVIEW, releaseTexture));
            synchronized (syncIsLooping) {
                isPreviewing = false;
            }
        }
    }

    public boolean startStreaming(RESPushStreamManager manager) {

        resPushStreamManager = manager;

        synchronized (syncOp) {
            videoGLHander.sendMessage(videoGLHander.obtainMessage(VideoGLHandler.WHAT_START_STREAMING, resPushStreamManager));
            synchronized (syncIsLooping) {
                if (!isPreviewing && !isStreaming) {
                    videoGLHander.removeMessages(VideoGLHandler.WHAT_DRAW);
                    videoGLHander.sendMessageDelayed(videoGLHander.obtainMessage(VideoGLHandler.WHAT_DRAW, SystemClock.uptimeMillis() + loopingInterval), loopingInterval);
                }
                isStreaming = true;
            }
        }
        return true;
    }

    public void updateCamTexture(SurfaceTexture camTex) {
        synchronized (syncOp) {
            if (videoGLHander != null) {
                videoGLHander.updateCamTexture(camTex);
            }
        }
    }

    public boolean stopStreaming() {
        synchronized (syncOp) {
            videoGLHander.sendEmptyMessage(VideoGLHandler.WHAT_STOP_STREAMING);
            synchronized (syncIsLooping) {
                isStreaming = false;
            }
        }
        return true;
    }

    public boolean destroy() {
        synchronized (syncOp) {
            videoGLHander.sendEmptyMessage(VideoGLHandler.WHAT_UNINIT);
            videoGLHandlerThread.quitSafely();
            try {
                videoGLHandlerThread.join();
            } catch (InterruptedException ignored) {
            }
            videoGLHandlerThread = null;
            videoGLHander = null;
            return true;
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    public void reSetVideoBitrate(int bitrate) {
        synchronized (syncOp) {
            if (videoGLHander != null) {
                videoGLHander.sendMessage(videoGLHander.obtainMessage(VideoGLHandler.WHAT_RESET_BITRATE, bitrate, 0));
                resCoreParameters.mediacdoecAVCBitRate = bitrate;
                dstVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, resCoreParameters.mediacdoecAVCBitRate);
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    public int getVideoBitrate() {
        synchronized (syncOp) {
            return resCoreParameters.mediacdoecAVCBitRate;
        }
    }

    public void reSetVideoFPS(int fps) {
        synchronized (syncOp) {
            resCoreParameters.videoFPS = fps;
            loopingInterval = 1000 / resCoreParameters.videoFPS;
        }
    }

    public void reSetVideoSize(RESCoreParameters newParameters) {
        synchronized (syncOp) {
            synchronized (syncIsLooping) {
                if (isPreviewing || isStreaming) {
                    videoGLHander.sendMessage(videoGLHander.obtainMessage(VideoGLHandler.WHAT_RESET_VIDEO, newParameters));
                }
            }
        }
    }

    public void setCurrentCamera(int cameraIndex) {
        synchronized (syncOp) {
            if (videoGLHander != null) {
                videoGLHander.updateCameraIndex(cameraIndex);
            }
        }
    }


    public BaseHardVideoFilter acquireVideoFilter() {
        lockVideoFilter.lock();
        return videoFilter;
    }

    public void releaseVideoFilter() {
        lockVideoFilter.unlock();
    }

    public void setVideoFilter(BaseHardVideoFilter baseHardVideoFilter) {
        lockVideoFilter.lock();
        videoFilter = baseHardVideoFilter;
        lockVideoFilter.unlock();
    }

//    public void takeScreenShot(RESScreenShotListener listener) {
//        synchronized (syncResScreenShotListener) {
//            resScreenShotListener = listener;
//        }
//    }
//
//    public void setVideoChangeListener(RESVideoChangeListener listener) {
//        synchronized (syncResVideoChangeListener) {
//            resVideoChangeListener = listener;
//        }
//    }

    public float getDrawFrameRate() {
        synchronized (syncOp) {
            return videoGLHander == null ? 0 : videoGLHander.getDrawFrameRate();
        }
    }

    private class VideoGLHandler extends Handler {
        static final int WHAT_INIT = 0x001;
        static final int WHAT_UNINIT = 0x002;
        static final int WHAT_FRAME = 0x003;
        static final int WHAT_DRAW = 0x004;
        static final int WHAT_RESET_VIDEO = 0x005;
        static final int WHAT_START_PREVIEW = 0x010;
        static final int WHAT_STOP_PREVIEW = 0x020;
        static final int WHAT_START_STREAMING = 0x100;
        static final int WHAT_STOP_STREAMING = 0x200;
        static final int WHAT_RESET_BITRATE = 0x300;
        private Size screenSize;
        //=========================
        public static final int FILTER_LOCK_TOLERATION = 3;//3ms
        private final Object syncFrameNum = new Object();
        private int frameNum = 0;
        //gl stuff
        private final Object syncCameraTex = new Object();
        private SurfaceTexture cameraTexture;

        private SurfaceTexture screenTexture;

        private MediaCodecGLWapper mediaCodecGLWapper;
        private ScreenGLWapper screenGLWapper;
        private OffScreenGLWapper offScreenGLWapper;

        private int sample2DFrameBuffer;
        private int sample2DFrameBufferTexture;
        private int frameBuffer;
        private int frameBufferTexture;
        private FloatBuffer shapeVerticesBuffer;
        private FloatBuffer mediaCodecTextureVerticesBuffer;
        private FloatBuffer screenTextureVerticesBuffer;
        private int currCamera;
        private final Object syncCameraTextureVerticesBuffer = new Object();
        private FloatBuffer camera2dTextureVerticesBuffer;
        private FloatBuffer cameraTextureVerticesBuffer;
        private ShortBuffer drawIndecesBuffer;
        private BaseHardVideoFilter innerVideoFilter = null;
        private RESFrameRateMeter drawFrameRateMeter;
        private int directionFlag;
        //sender
        private VideoEncoderThread videoSenderThread;

        boolean hasNewFrame = false;
        public boolean dropNextFrame = false;

        public VideoGLHandler(Looper looper) {
            super(looper);
            screenGLWapper = null;
            mediaCodecGLWapper = null;
            drawFrameRateMeter = new RESFrameRateMeter();
            screenSize = new Size(1, 1);
            initBuffer();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case WHAT_FRAME: {
                    GLHelper.makeCurrent(offScreenGLWapper);
                    synchronized (syncFrameNum) {
                        synchronized (syncCameraTex) {
                            if (cameraTexture != null) {
                                while (frameNum != 0) {
                                    cameraTexture.updateTexImage();
                                    --frameNum;
                                    if (!dropNextFrame) {
                                        hasNewFrame = true;
                                    } else {
                                        dropNextFrame = false;
                                        hasNewFrame=false;
                                    }
                                }
                            } else {
                                break;
                            }
                        }
                    }
                    drawSample2DFrameBuffer(cameraTexture);
                }
                break;
                case WHAT_DRAW: {
                    long time = (Long) msg.obj;
                    long interval = time + loopingInterval - SystemClock.uptimeMillis();
                    synchronized (syncIsLooping) {
                        if (isPreviewing || isStreaming) {
                            if (interval > 0) {
                                videoGLHander.sendMessageDelayed(videoGLHander.obtainMessage(
                                        VideoGLHandler.WHAT_DRAW,
                                        SystemClock.uptimeMillis() + interval),
                                        interval);
                            } else {
                                videoGLHander.sendMessage(videoGLHander.obtainMessage(
                                        VideoGLHandler.WHAT_DRAW,
                                        SystemClock.uptimeMillis() + loopingInterval));
                            }
                        }
                    }
                    if (hasNewFrame) {
                        drawFrameBuffer();
                        drawMediaCodec(time * 1000000);
                        drawScreen();
                        drawFrameRateMeter.count();
                        hasNewFrame = false;
                    }
                }
                break;
                case WHAT_INIT: {
                    initOffScreenGL();
                }
                break;
                case WHAT_UNINIT: {
                    lockVideoFilter.lock();
                    if (innerVideoFilter != null) {
                        innerVideoFilter.onDestroy();
                        innerVideoFilter = null;
                    }
                    lockVideoFilter.unlock();
                    uninitOffScreenGL();
                }
                break;
                case WHAT_START_PREVIEW: {
                    initScreenGL((SurfaceTexture) msg.obj);
                    updatePreview(msg.arg1, msg.arg2);
                }
                break;
                case WHAT_STOP_PREVIEW: {
                    uninitScreenGL();
                    boolean releaseTexture = (boolean) msg.obj;
                    if (releaseTexture) {
                        screenTexture.release();
                        screenTexture = null;
                    }
                }
                break;
                case WHAT_START_STREAMING: {
                    if (dstVideoEncoder == null) {
                        dstVideoEncoder = MediaCodecHelper.createHardVideoMediaCodec(resCoreParameters, dstVideoFormat);
                        if (dstVideoEncoder == null) {
                            throw new RuntimeException("create Video MediaCodec failed");
                        }
                    }
                    dstVideoEncoder.configure(dstVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    initMediaCodecGL(dstVideoEncoder.createInputSurface());
                    dstVideoEncoder.start();
                    videoSenderThread = new VideoEncoderThread("VideoSenderThread", dstVideoEncoder, (RESPushStreamManager) msg.obj);
                    videoSenderThread.start();
                }
                break;
                case WHAT_STOP_STREAMING: {
                    videoSenderThread.quit();
                    try {
                        videoSenderThread.join();
                    } catch (InterruptedException e) {
                        Loging.trace(TAG, "RESHardVideoCore,stopStreaming()failed", e);
                    }
                    videoSenderThread = null;
                    uninitMediaCodecGL();
                    dstVideoEncoder.stop();
                    dstVideoEncoder.release();
                    dstVideoEncoder = null;
                }
                break;
                case WHAT_RESET_BITRATE: {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && mediaCodecGLWapper != null) {
                        Bundle bitrateBundle = new Bundle();
                        bitrateBundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, msg.arg1);
                        dstVideoEncoder.setParameters(bitrateBundle);
                    }
                }
                break;
                case WHAT_RESET_VIDEO: {
                    RESCoreParameters newParameters = (RESCoreParameters) msg.obj;
                    resCoreParameters.videoWidth = newParameters.videoWidth;
                    resCoreParameters.videoHeight = newParameters.videoHeight;
                    resCoreParameters.cropRatio = newParameters.cropRatio;
                    updateCameraIndex(currCamera);
                    resetFrameBuff();
                    if (mediaCodecGLWapper != null) {
                        uninitMediaCodecGL();
                        dstVideoEncoder.stop();
                        dstVideoEncoder.release();
                        dstVideoEncoder = MediaCodecHelper.createHardVideoMediaCodec(resCoreParameters, dstVideoFormat);
                        if (dstVideoEncoder == null) {
                            throw new RuntimeException("create Video MediaCodec failed");
                        }
                        dstVideoEncoder.configure(dstVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                        initMediaCodecGL(dstVideoEncoder.createInputSurface());
                        dstVideoEncoder.start();
                        videoSenderThread.updateMediaCodec(dstVideoEncoder);
                    }
//                    synchronized (syncResVideoChangeListener) {
//                        if (resVideoChangeListener != null) {
//                            CallbackDelivery.i().post(new RESVideoChangeListener.RESVideoChangeRunable(resVideoChangeListener,
//                                    resCoreParameters.videoWidth,
//                                    resCoreParameters.videoHeight));
//                        }
//                    }
                }
                break;
                default:
            }
        }


        private void drawSample2DFrameBuffer(SurfaceTexture cameraTexture) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sample2DFrameBuffer);
            GLES20.glUseProgram(offScreenGLWapper.cam2dProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, RESConfig.OVERWATCH_TEXTURE_ID);
            GLES20.glUniform1i(offScreenGLWapper.cam2dTextureLoc, 0);
            synchronized (syncCameraTextureVerticesBuffer) {
                GLHelper.enableVertex(offScreenGLWapper.cam2dPostionLoc, offScreenGLWapper.cam2dTextureCoordLoc,
                        shapeVerticesBuffer, camera2dTextureVerticesBuffer);
            }
            float[] textureMatrix = new float[16];
            cameraTexture.getTransformMatrix(textureMatrix);
            GLES20.glUniformMatrix4fv(offScreenGLWapper.cam2dTextureMatrix, 1, false, textureMatrix, 0);
            GLES20.glViewport(0, 0, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
            doGLDraw();
            GLES20.glFinish();
            GLHelper.disableVertex(offScreenGLWapper.cam2dPostionLoc, offScreenGLWapper.cam2dTextureCoordLoc);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            GLES20.glUseProgram(0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        private void drawOriginFrameBuffer() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer);
            GLES20.glUseProgram(offScreenGLWapper.camProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sample2DFrameBufferTexture);
            GLES20.glUniform1i(offScreenGLWapper.camTextureLoc, 0);
            synchronized (syncCameraTextureVerticesBuffer) {
                GLHelper.enableVertex(offScreenGLWapper.camPostionLoc, offScreenGLWapper.camTextureCoordLoc,
                        shapeVerticesBuffer, cameraTextureVerticesBuffer);
            }
            GLES20.glViewport(0, 0, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
            doGLDraw();
            GLES20.glFinish();
            GLHelper.disableVertex(offScreenGLWapper.camPostionLoc, offScreenGLWapper.camTextureCoordLoc);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glUseProgram(0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        private void drawFrameBuffer() {
            GLHelper.makeCurrent(offScreenGLWapper);
            boolean isFilterLocked = lockVideoFilter();
            if (isFilterLocked) {
                if (videoFilter != innerVideoFilter) {
                    if (innerVideoFilter != null) {
                        innerVideoFilter.onDestroy();
                    }
                    innerVideoFilter = videoFilter;
                    if (innerVideoFilter != null) {
                        innerVideoFilter.onInit(resCoreParameters.videoWidth, resCoreParameters.videoHeight);
                    }
                }
                if (innerVideoFilter != null) {
                    synchronized (syncCameraTextureVerticesBuffer) {
                        innerVideoFilter.onDirectionUpdate(directionFlag);
                        innerVideoFilter.onDraw(sample2DFrameBufferTexture, frameBuffer, shapeVerticesBuffer, cameraTextureVerticesBuffer);
                    }
                } else {
                    drawOriginFrameBuffer();
                }
                unlockVideoFilter();
            } else {
                drawOriginFrameBuffer();
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer);
//            checkScreenShot();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        private void drawMediaCodec(long currTime) {
            if (mediaCodecGLWapper != null) {
                GLHelper.makeCurrent(mediaCodecGLWapper);
                GLES20.glUseProgram(mediaCodecGLWapper.drawProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameBufferTexture);
                GLES20.glUniform1i(mediaCodecGLWapper.drawTextureLoc, 0);
                GLHelper.enableVertex(mediaCodecGLWapper.drawPostionLoc, mediaCodecGLWapper.drawTextureCoordLoc,
                        shapeVerticesBuffer, mediaCodecTextureVerticesBuffer);
                doGLDraw();
                GLES20.glFinish();
                GLHelper.disableVertex(mediaCodecGLWapper.drawPostionLoc, mediaCodecGLWapper.drawTextureCoordLoc);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                GLES20.glUseProgram(0);
                EGLExt.eglPresentationTimeANDROID(mediaCodecGLWapper.eglDisplay, mediaCodecGLWapper.eglSurface, currTime);
                if (!EGL14.eglSwapBuffers(mediaCodecGLWapper.eglDisplay, mediaCodecGLWapper.eglSurface)) {
                    throw new RuntimeException("eglSwapBuffers,failed!");
                }
            }
        }

        private void drawScreen() {
            if (screenGLWapper != null) {
                GLHelper.makeCurrent(screenGLWapper);
                GLES20.glUseProgram(screenGLWapper.drawProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameBufferTexture);
                GLES20.glUniform1i(screenGLWapper.drawTextureLoc, 0);
                GLHelper.enableVertex(screenGLWapper.drawPostionLoc, screenGLWapper.drawTextureCoordLoc,
                        shapeVerticesBuffer, screenTextureVerticesBuffer);
                GLES20.glViewport(0, 0, screenSize.getWidth(), screenSize.getHeight());
                doGLDraw();
                GLES20.glFinish();
                GLHelper.disableVertex(screenGLWapper.drawPostionLoc, screenGLWapper.drawTextureCoordLoc);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                GLES20.glUseProgram(0);
                if (!EGL14.eglSwapBuffers(screenGLWapper.eglDisplay, screenGLWapper.eglSurface)) {
                    throw new RuntimeException("eglSwapBuffers,failed!");
                }
            }
        }

        private void doGLDraw() {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawIndecesBuffer.limit(), GLES20.GL_UNSIGNED_SHORT, drawIndecesBuffer);
        }

        /**
         * @return ture if filter locked & filter!=null
         */

        private boolean lockVideoFilter() {
            try {
                return lockVideoFilter.tryLock(FILTER_LOCK_TOLERATION, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }

        private void unlockVideoFilter() {
            lockVideoFilter.unlock();
        }

//        private void checkScreenShot() {
//            synchronized (syncResScreenShotListener) {
//                if (resScreenShotListener != null) {
//                    Bitmap result = null;
//                    try {
//                        IntBuffer pixBuffer = IntBuffer.allocate(resCoreParameters.videoWidth * resCoreParameters.videoHeight);
//                        GLES20.glReadPixels(0, 0, resCoreParameters.videoWidth, resCoreParameters.videoHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixBuffer);
//                        int[] glPixel = pixBuffer.array();
//                        int[] argbPixel = new int[resCoreParameters.videoWidth * resCoreParameters.videoHeight];
//                        ColorHelper.FIXGLPIXEL(glPixel, argbPixel, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
//                        result = Bitmap.createBitmap(argbPixel,
//                                resCoreParameters.videoWidth,
//                                resCoreParameters.videoHeight,
//                                Bitmap.Config.ARGB_8888);
//                    } catch (Exception e) {
//                        Loging.trace(TAG, "takescreenshot failed:", e);
//                    } finally {
//                        CallbackDelivery.i().post(new RESScreenShotListener.RESScreenShotListenerRunable(resScreenShotListener, result));
//                        resScreenShotListener = null;
//                    }
//                }
//            }
//        }

        private void initOffScreenGL() {
            if (offScreenGLWapper == null) {
                offScreenGLWapper = new OffScreenGLWapper();
                GLHelper.initOffScreenGL(offScreenGLWapper);
                GLHelper.makeCurrent(offScreenGLWapper);
                //camera
                offScreenGLWapper.camProgram = GLHelper.createCameraProgram();
                GLES20.glUseProgram(offScreenGLWapper.camProgram);
                offScreenGLWapper.camTextureLoc = GLES20.glGetUniformLocation(offScreenGLWapper.camProgram, "uTexture");
                offScreenGLWapper.camPostionLoc = GLES20.glGetAttribLocation(offScreenGLWapper.camProgram, "aPosition");
                offScreenGLWapper.camTextureCoordLoc = GLES20.glGetAttribLocation(offScreenGLWapper.camProgram, "aTextureCoord");
                //camera2d
                offScreenGLWapper.cam2dProgram = GLHelper.createCamera2DProgram();
                GLES20.glUseProgram(offScreenGLWapper.cam2dProgram);
                offScreenGLWapper.cam2dTextureLoc = GLES20.glGetUniformLocation(offScreenGLWapper.cam2dProgram, "uTexture");
                offScreenGLWapper.cam2dPostionLoc = GLES20.glGetAttribLocation(offScreenGLWapper.cam2dProgram, "aPosition");
                offScreenGLWapper.cam2dTextureCoordLoc = GLES20.glGetAttribLocation(offScreenGLWapper.cam2dProgram, "aTextureCoord");
                offScreenGLWapper.cam2dTextureMatrix = GLES20.glGetUniformLocation(offScreenGLWapper.cam2dProgram, "uTextureMatrix");
                int[] fb = new int[1], fbt = new int[1];
                GLHelper.createCamFrameBuff(fb, fbt, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
                sample2DFrameBuffer = fb[0];
                sample2DFrameBufferTexture = fbt[0];
                GLHelper.createCamFrameBuff(fb, fbt, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
                frameBuffer = fb[0];
                frameBufferTexture = fbt[0];
            } else {
                throw new IllegalStateException("initOffScreenGL without uninitOffScreenGL");
            }
        }

        private void uninitOffScreenGL() {
            if (offScreenGLWapper != null) {
                GLHelper.makeCurrent(offScreenGLWapper);
                GLES20.glDeleteProgram(offScreenGLWapper.camProgram);
                GLES20.glDeleteProgram(offScreenGLWapper.cam2dProgram);
                GLES20.glDeleteFramebuffers(1, new int[]{frameBuffer}, 0);
                GLES20.glDeleteTextures(1, new int[]{frameBufferTexture}, 0);
                GLES20.glDeleteFramebuffers(1, new int[]{sample2DFrameBuffer}, 0);
                GLES20.glDeleteTextures(1, new int[]{sample2DFrameBufferTexture}, 0);
                EGL14.eglDestroySurface(offScreenGLWapper.eglDisplay, offScreenGLWapper.eglSurface);
                EGL14.eglDestroyContext(offScreenGLWapper.eglDisplay, offScreenGLWapper.eglContext);
                EGL14.eglTerminate(offScreenGLWapper.eglDisplay);
                EGL14.eglMakeCurrent(offScreenGLWapper.eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            } else {
                throw new IllegalStateException("uninitOffScreenGL without initOffScreenGL");
            }
        }

        private void initScreenGL(SurfaceTexture screenSurfaceTexture) {
            if (screenGLWapper == null) {
                screenTexture = screenSurfaceTexture;
                screenGLWapper = new ScreenGLWapper();
                GLHelper.initScreenGL(screenGLWapper, offScreenGLWapper.eglContext, screenSurfaceTexture);
                GLHelper.makeCurrent(screenGLWapper);
                screenGLWapper.drawProgram = GLHelper.createScreenProgram();
                GLES20.glUseProgram(screenGLWapper.drawProgram);
                screenGLWapper.drawTextureLoc = GLES20.glGetUniformLocation(screenGLWapper.drawProgram, "uTexture");
                screenGLWapper.drawPostionLoc = GLES20.glGetAttribLocation(screenGLWapper.drawProgram, "aPosition");
                screenGLWapper.drawTextureCoordLoc = GLES20.glGetAttribLocation(screenGLWapper.drawProgram, "aTextureCoord");
            } else {
                throw new IllegalStateException("initScreenGL without unInitScreenGL");
            }
        }

        private void uninitScreenGL() {
            if (screenGLWapper != null) {
                GLHelper.makeCurrent(screenGLWapper);
                GLES20.glDeleteProgram(screenGLWapper.drawProgram);
                EGL14.eglDestroySurface(screenGLWapper.eglDisplay, screenGLWapper.eglSurface);
                EGL14.eglDestroyContext(screenGLWapper.eglDisplay, screenGLWapper.eglContext);
                EGL14.eglTerminate(screenGLWapper.eglDisplay);
                EGL14.eglMakeCurrent(screenGLWapper.eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                screenGLWapper = null;
            } else {
                throw new IllegalStateException("unInitScreenGL without initScreenGL");
            }
        }

        private void initMediaCodecGL(Surface mediacodecSurface) {
            if (mediaCodecGLWapper == null) {
                mediaCodecGLWapper = new MediaCodecGLWapper();
                GLHelper.initMediaCodecGL(mediaCodecGLWapper, offScreenGLWapper.eglContext, mediacodecSurface);
                GLHelper.makeCurrent(mediaCodecGLWapper);
                GLES20.glEnable(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
                mediaCodecGLWapper.drawProgram = GLHelper.createMediaCodecProgram();
                GLES20.glUseProgram(mediaCodecGLWapper.drawProgram);
                mediaCodecGLWapper.drawTextureLoc = GLES20.glGetUniformLocation(mediaCodecGLWapper.drawProgram, "uTexture");
                mediaCodecGLWapper.drawPostionLoc = GLES20.glGetAttribLocation(mediaCodecGLWapper.drawProgram, "aPosition");
                mediaCodecGLWapper.drawTextureCoordLoc = GLES20.glGetAttribLocation(mediaCodecGLWapper.drawProgram, "aTextureCoord");
            } else {
                throw new IllegalStateException("initMediaCodecGL without uninitMediaCodecGL");
            }
        }

        private void uninitMediaCodecGL() {
            if (mediaCodecGLWapper != null) {
                GLHelper.makeCurrent(mediaCodecGLWapper);
                GLES20.glDeleteProgram(mediaCodecGLWapper.drawProgram);
                EGL14.eglDestroySurface(mediaCodecGLWapper.eglDisplay, mediaCodecGLWapper.eglSurface);
                EGL14.eglDestroyContext(mediaCodecGLWapper.eglDisplay, mediaCodecGLWapper.eglContext);
                EGL14.eglTerminate(mediaCodecGLWapper.eglDisplay);
                EGL14.eglMakeCurrent(mediaCodecGLWapper.eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                mediaCodecGLWapper = null;
            } else {
                throw new IllegalStateException("uninitMediaCodecGL without initMediaCodecGL");
            }
        }

        private void resetFrameBuff() {
            GLHelper.makeCurrent(offScreenGLWapper);
            GLES20.glDeleteFramebuffers(1, new int[]{frameBuffer}, 0);
            GLES20.glDeleteTextures(1, new int[]{frameBufferTexture}, 0);
            GLES20.glDeleteFramebuffers(1, new int[]{sample2DFrameBuffer}, 0);
            GLES20.glDeleteTextures(1, new int[]{sample2DFrameBufferTexture}, 0);
            int[] fb = new int[1], fbt = new int[1];
            GLHelper.createCamFrameBuff(fb, fbt, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
            sample2DFrameBuffer = fb[0];
            sample2DFrameBufferTexture = fbt[0];
            GLHelper.createCamFrameBuff(fb, fbt, resCoreParameters.videoWidth, resCoreParameters.videoHeight);
            frameBuffer = fb[0];
            frameBufferTexture = fbt[0];
        }

        private void initBuffer() {
            shapeVerticesBuffer = GLHelper.getShapeVerticesBuffer();
            mediaCodecTextureVerticesBuffer = GLHelper.getMediaCodecTextureVerticesBuffer();
            screenTextureVerticesBuffer = GLHelper.getScreenTextureVerticesBuffer();
            updateCameraIndex(currCamera);
            drawIndecesBuffer = GLHelper.getDrawIndecesBuffer();
            cameraTextureVerticesBuffer = GLHelper.getCameraTextureVerticesBuffer();
        }

        void updateCameraIndex(int cameraIndex) {
            synchronized (syncCameraTextureVerticesBuffer) {
                currCamera = cameraIndex;
                if (currCamera == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    directionFlag = resCoreParameters.frontCameraDirectionMode ^ RESConfig.DirectionMode.FLAG_DIRECTION_FLIP_HORIZONTAL;
                } else {
                    directionFlag = resCoreParameters.backCameraDirectionMode;
                }
                camera2dTextureVerticesBuffer = GLHelper.getCamera2DTextureVerticesBuffer(directionFlag, resCoreParameters.cropRatio);
            }
        }

        float getDrawFrameRate() {
            return drawFrameRateMeter.getFps();
        }


        void updateCamTexture(SurfaceTexture surfaceTexture) {
            synchronized (syncCameraTex) {
                if (surfaceTexture != cameraTexture) {
                    cameraTexture = surfaceTexture;
                    frameNum = 0;
                    dropNextFrame = true;
                }
            }
        }


        void addFrameNum() {
            synchronized (syncFrameNum) {
                ++frameNum;
                this.removeMessages(WHAT_FRAME);
                this.sendMessageAtFrontOfQueue(this.obtainMessage(VideoGLHandler.WHAT_FRAME));
            }
        }

        void updatePreview(int w, int h) {
            screenSize = new Size(w, h);
        }
    }
}
