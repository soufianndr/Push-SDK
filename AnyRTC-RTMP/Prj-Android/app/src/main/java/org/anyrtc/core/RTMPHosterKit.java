/**
 *  Copyright (c) 2016 The AnyRTC project authors. All Rights Reserved.
 *
 *  Please visit https://www.anyrtc.io for detail.
 *
 * The GNU General Public License is a free, copyleft license for
 * software and other kinds of works.
 *
 * The licenses for most software and other practical works are designed
 * to take away your freedom to share and change the works.  By contrast,
 * the GNU General Public License is intended to guarantee your freedom to
 * share and change all versions of a program--to make sure it remains free
 * software for all its users.  We, the Free Software Foundation, use the
 * GNU General Public License for most of our software; it applies also to
 * any other work released this way by its authors.  You can apply it to
 * your programs, too.
 * See the GNU LICENSE file for more info.
 */
package org.anyrtc.core;

import android.app.Activity;
import android.util.Log;

import org.anyrtc.PA.FBOTextureCallback;
import org.anyrtc.PA.gles.FBO_NEW;
import org.webrtc.CameraEnumerationAndroid;
import org.webrtc.EglBase;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoCapturerAndroid;

/**
 * Created by Eric on 2016/7/25.
 */
public class RTMPHosterKit {
    private static final String TAG = "RTMPHosterKit";
    public enum RTMPNetAdjustMode
    {
        RTMP_NA_Nor(0),		    // Normal
        RTMP_NA_Fast(1),		// When network is bad, we will drop some video frame.
        RTMP_NA_AutoBitrate(2);	// When network is bad, we will adjust video bitrate to match.

        public final int level;
        RTMPNetAdjustMode(int level) {
            this.level = level;
        };
    };
    /**
     * 构造访问jni底层库的对象
     */
    private long fNativeAppId;
    private Activity   mActivity;
    private RTMPHosterHelper mHosterHelper;
    private final LooperExecutor mExecutor;
    private final EglBase mEglBase;

    private int mCameraId = 0;
    private VideoCapturerAndroid mVideoCapturer;
    private FBO_NEW mFBO;
    private int mSendbps = 0;

    public RTMPHosterKit(Activity act, final RTMPHosterHelper hosterHelper) {
        RTMPUtils.assertIsTrue(act != null && hosterHelper != null);
        mActivity = act;
        mHosterHelper = hosterHelper;
        AnyRTMP.Inst().Init(mActivity.getApplicationContext());

        mExecutor = AnyRTMP.Inst().Executor();
        mEglBase = AnyRTMP.Inst().Egl();

        mFBO = new FBO_NEW(mActivity.getApplicationContext());
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                fNativeAppId = nativeCreate(hosterHelper);
            }
        });
    }

    public void Clear() {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if(mVideoCapturer != null) {
                    try {
                        mVideoCapturer.stopCapture();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    nativeSetVideoCapturer(null, null, 0);
                    mVideoCapturer = null;
                }
                nativeDestroy();
            }
        });
    }

    //* Common function
    public void SetAudioEnable(final boolean enabled){
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                nativeSetAudioEnable(enabled);
            }
        });
    }
    public void SetVideoEnable(final boolean enabled){
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                nativeSetVideoEnable(enabled);
            }
        });
    }
    public void SetVideoCapturer(final EglBase.Context context, final long renderPointer, final boolean front){
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {

                if(mVideoCapturer == null) {
                    mCameraId = 0;
                    String cameraDeviceName = CameraEnumerationAndroid.getDeviceName(mCameraId);
                    String frontCameraDeviceName =
                            CameraEnumerationAndroid.getNameOfFrontFacingDevice();
                    int numberOfCameras = CameraEnumerationAndroid.getDeviceCount();
                    if (numberOfCameras > 1 && frontCameraDeviceName != null && front) {
                        cameraDeviceName = frontCameraDeviceName;
                        mCameraId = 1;
                    }
                    Log.d(TAG, "Opening camera: " + cameraDeviceName);
                    mVideoCapturer = VideoCapturerAndroid.create(cameraDeviceName, null);
                    if (mVideoCapturer == null) {
                        Log.e("sys", "Failed to open camera");
                        return;
                    }
                    mVideoCapturer.setCaptureTextureCallback(new FBOTextureCallback(mActivity.getApplicationContext(), mFBO));
                }
                nativeSetVideoCapturer(context, mVideoCapturer, renderPointer);
            }
        });
    }
    public void SwitchCamera(){
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if(mVideoCapturer != null && CameraEnumerationAndroid.getDeviceCount() > 1) {
                    mCameraId = (mCameraId + 1) % CameraEnumerationAndroid.getDeviceCount();
                    mVideoCapturer.switchCamera(-1, null);
                }
            }
        });
    }

    public void SwitchCamera(final int CameraId){
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if(mVideoCapturer != null && CameraEnumerationAndroid.getDeviceCount() > CameraId) {
                    mVideoCapturer.switchCamera(CameraId, null);
                }
            }
        });
    }

    //* Rtmp function for push rtmp stream
    public void StartRtmpStream(final String strUrl){
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                nativeStartRtmpStream(strUrl);
            }
        });
    }
    public void StopRtmpStream(){
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                nativeStopRtmpStream();
            }
        });
    }

    public void SetAudioParam(final int sampleRate, final int sampleBit, final int channels) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                nativeSetAudioParam(sampleRate, sampleBit, channels);
            }
        });
    }

    public void SetVideoParam(final int resolution, final int fps, final int bitRate) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                nativeSetVideoParam(resolution, fps, bitRate);
            }
        });
    }

    public void SetFocusAtPoint(final int x, final int y, final int surfaceView_width, final int surfaceView_height) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mVideoCapturer.focusOnTouch(x, y, surfaceView_width, surfaceView_height);
            }
        });
    }

    public void SetBeautyFace(boolean beautyFace) {
        mFBO.setFilterEnable(beautyFace);
        // TODO
    }

    public void SetCameraBeautyFilterWithSmooth(float smooth, float white, float pink) {
        mFBO.setFilterWithSmooth(smooth, white, pink);
        // TODO
    }

    public void SetVolume(int volume) {
        // TODO
    }

    /**
     *  Native function
     */
    private native long nativeSetAudioParam(int sampleRate, int sampleBit, int channels);
    private native long nativeSetVideoParam(int resolution, int bitRate, int fps);
    private native long nativeCreate(Object obj);
    private native void nativeSetAudioEnable(boolean enabled);
    private native void nativeSetVideoEnable(boolean enabled);
    private native void nativeSetVideoCapturer(EglBase.Context context, VideoCapturer capturer, long nativeRenderer);
    private native void nativeStartRtmpStream(String strUrl);
    private native void nativeStopRtmpStream();
    private native void nativeDestroy();
}
