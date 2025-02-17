/*
 *  Copyright 2015 The WebRTC@AnyRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Camera;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;

import org.webrtc.CameraEnumerationAndroid.CaptureFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Android specific implementation of VideoCapturer.
// An instance of this class can be created by an application using
// VideoCapturerAndroid.create();
// This class extends VideoCapturer with a method to easily switch between the
// front and back camera. It also provides methods for enumerating valid device
// names.
//
// Threading notes: this class is called from C++ code, Android Camera callbacks, and possibly
// arbitrary Java threads. All public entry points are thread safe, and delegate the work to the
// camera thread. The internal *OnCameraThread() methods must check |camera| for null to check if
// the camera has been stopped.
// TODO(magjed): This class name is now confusing - rename to Camera1VideoCapturer.
@SuppressWarnings("deprecation")
public class VideoCapturerAndroid implements
        CameraVideoCapturer,
    android.hardware.Camera.PreviewCallback,
    SurfaceTextureHelper.OnTextureFrameAvailableListener {
  private final static String TAG = "VideoCapturerAndroid";
  private static final int CAMERA_STOP_TIMEOUT_MS = 7000;

  private android.hardware.Camera camera;  // Only non-null while capturing.
  private final Object handlerLock = new Object();
  // |cameraThreadHandler| must be synchronized on |handlerLock| when not on the camera thread,
  // or when modifying the reference. Use maybePostOnCameraThread() instead of posting directly to
  // the handler - this way all callbacks with a specifed token can be removed at once.
  private Handler cameraThreadHandler;
  private Context applicationContext;
  // Synchronization lock for |id|.
  private final Object cameraIdLock = new Object();
  private int id;
  private android.hardware.Camera.CameraInfo info;
  private CameraStatistics cameraStatistics;
  // Remember the requested format in case we want to switch cameras.
  private int requestedWidth;
  private int requestedHeight;
  private int requestedFramerate;
  // The capture format will be the closest supported format to the requested format.
  private CaptureFormat captureFormat;
  private final Object pendingCameraSwitchLock = new Object();
  private volatile boolean pendingCameraSwitch;
  private CapturerObserver frameObserver = null;
  private final CameraEventsHandler eventsHandler;
  private boolean firstFrameReported;
  // Arbitrary queue depth.  Higher number means more memory allocated & held,
  // lower number means more sensitivity to processing time in the client (and
  // potentially stalling the capturer if it runs out of buffers to write to).
  private static final int NUMBER_OF_CAPTURE_BUFFERS = 3;
  private final Set<byte[]> queuedBuffers = new HashSet<byte[]>();
  private final boolean isCapturingToTexture;
  private SurfaceTextureHelper surfaceHelper;
  private final static int MAX_OPEN_CAMERA_ATTEMPTS = 3;
  private final static int OPEN_CAMERA_DELAY_MS = 500;
  private int openCameraAttempts;
  private CaptureTextureCallback mCaptureTextureCallback = null;

  // Camera error callback.
  private final android.hardware.Camera.ErrorCallback cameraErrorCallback =
      new android.hardware.Camera.ErrorCallback() {
    @Override
    public void onError(int error, android.hardware.Camera camera) {
      String errorMessage;
      if (error == android.hardware.Camera.CAMERA_ERROR_SERVER_DIED) {
        errorMessage = "Camera server died!";
      } else {
        errorMessage = "Camera error: " + error;
      }
      Logging.e(TAG, errorMessage);
      if (eventsHandler != null) {
        eventsHandler.onCameraError(errorMessage);
      }
    }
  };

  public void setCaptureTextureCallback(CaptureTextureCallback captureTextureCallback) {
    mCaptureTextureCallback = captureTextureCallback;
  }

  public static VideoCapturerAndroid create(String name,
                                            CameraEventsHandler eventsHandler) {
    return VideoCapturerAndroid.create(name, eventsHandler, true /* captureToTexture */);
  }

  // Use ctor directly instead.
  @Deprecated
  public static VideoCapturerAndroid create(String name,
                                            CameraEventsHandler eventsHandler, boolean captureToTexture) {
    try {
      return new VideoCapturerAndroid(name, eventsHandler, captureToTexture);
    } catch (RuntimeException e) {
      Logging.e(TAG, "Couldn't create camera.", e);
      return null;
    }
  }

  public void printStackTrace() {
    Thread cameraThread = null;
    synchronized (handlerLock) {
      if (cameraThreadHandler != null) {
        cameraThread = cameraThreadHandler.getLooper().getThread();
      }
    }
    if (cameraThread != null) {
      StackTraceElement[] cameraStackTraces = cameraThread.getStackTrace();
      if (cameraStackTraces.length > 0) {
        Logging.d(TAG, "VideoCapturerAndroid stacks trace:");
        for (StackTraceElement stackTrace : cameraStackTraces) {
          Logging.d(TAG, stackTrace.toString());
        }
      }
    }
  }

  public void focusOnTouch(final int x, final int y, final int surfaceView_w, final int surfaceView_h) {
    boolean didPost = maybePostOnCameraThread(new Runnable() {
      @Override
      public void run() {
        focusOnTouchOnCameraThread(x, y, surfaceView_w, surfaceView_h);
      }
    });
  }

  private void focusOnTouchOnCameraThread(int x, int y, final int surfaceView_width, final int surfaceView_height) {
    if (camera != null) {
      camera.cancelAutoFocus();
      Rect focusRect = calculateTapArea(x, y, surfaceView_width, surfaceView_height, 1f);
      Rect meteringRect = calculateTapArea(x, y, surfaceView_width, surfaceView_height, 1.5f);

      Camera.Parameters parameters = camera.getParameters();
      boolean supportFocus = false;
      boolean supportMetering = false;

      List<String> focusModes = parameters.getSupportedFocusModes();
      if (focusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
        parameters.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
      }

      if (parameters.getMaxNumFocusAreas() > 0) {
        List<Camera.Area> focusAreas = new ArrayList<Camera.Area>();
        focusAreas.add(new Camera.Area(focusRect, 1000));
        parameters.setFocusAreas(focusAreas);
        supportFocus = true;
      }

      if (parameters.getMaxNumMeteringAreas() > 0) {
        List<Camera.Area> meteringAreas = new ArrayList<Camera.Area>();
        meteringAreas.add(new Camera.Area(meteringRect, 1000));
        parameters.setMeteringAreas(meteringAreas);
        supportMetering = true;
      }

      if (supportFocus || supportMetering) {
        camera.setParameters(parameters);
        camera.autoFocus(null);
      }
    }
  }

  /**
   * Convert touch position x:y to {@link Camera.Area} position -1000:-1000 to 1000:1000.
   */
  private Rect calculateTapArea(float x, float y, int surfaceView_width, int surfaceView_height, float coefficient) {
    int focusAreaSize = 500;
    int areaSize = Float.valueOf(focusAreaSize * coefficient).intValue();
    int left = clamp(((int) x - areaSize / 2) * 2000 / surfaceView_width  - 1000, -1000, 1000 - areaSize);
    int top = clamp(((int) y - areaSize / 2) * 2000 / surfaceView_height  - 1000, -1000, 1000 - areaSize);
    RectF rectF = new RectF(left, top, left + areaSize, top + areaSize);

    return new Rect(Math.round(rectF.left), Math.round(rectF.top), Math.round(rectF.right), Math.round(rectF.bottom));
  }
  private int clamp(int x, int min, int max) {
    if (x > max) {
      return max;
    }
    if (x < min) {
      return min;
    }
    return x;
  }
  // Switch camera to the next valid camera id. This can only be called while
  // the camera is running.
  @Override
  public void switchCamera(final int cameraId, final CameraSwitchHandler switchEventsHandler) {
    if (android.hardware.Camera.getNumberOfCameras() < 2) {
      if (switchEventsHandler != null) {
        switchEventsHandler.onCameraSwitchError("No camera to switch to.");
      }
      return;
    }
    synchronized (pendingCameraSwitchLock) {
      if (pendingCameraSwitch) {
        // Do not handle multiple camera switch request to avoid blocking
        // camera thread by handling too many switch request from a queue.
        Logging.w(TAG, "Ignoring camera switch request.");
        if (switchEventsHandler != null) {
          switchEventsHandler.onCameraSwitchError("Pending camera switch already in progress.");
        }
        return;
      }
      pendingCameraSwitch = true;
    }
    final boolean didPost = maybePostOnCameraThread(new Runnable() {
      @Override
      public void run() {
        switchCameraOnCameraThread(cameraId);
        synchronized (pendingCameraSwitchLock) {
          pendingCameraSwitch = false;
        }
        if (switchEventsHandler != null) {
          switchEventsHandler.onCameraSwitchDone(
              info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT);
        }
      }
    });
    if (!didPost && switchEventsHandler != null) {
      switchEventsHandler.onCameraSwitchError("Camera is stopped.");
    }
  }

  // Requests a new output format from the video capturer. Captured frames
  // by the camera will be scaled/or dropped by the video capturer.
  // It does not matter if width and height are flipped. I.E, |width| = 640, |height| = 480 produce
  // the same result as |width| = 480, |height| = 640.
  // TODO(magjed/perkj): Document what this function does. Change name?
  @Override
  public void onOutputFormatRequest(final int width, final int height, final int framerate) {
    maybePostOnCameraThread(new Runnable() {
      @Override public void run() {
        onOutputFormatRequestOnCameraThread(width, height, framerate);
      }
    });
  }

  // Reconfigure the camera to capture in a new format. This should only be called while the camera
  // is running.
  @Override
  public void changeCaptureFormat(final int width, final int height, final int framerate) {
    maybePostOnCameraThread(new Runnable() {
      @Override public void run() {
        startPreviewOnCameraThread(width, height, framerate);
      }
    });
  }

  // Helper function to retrieve the current camera id synchronously. Note that the camera id might
  // change at any point by switchCamera() calls.
  private int getCurrentCameraId() {
    synchronized (cameraIdLock) {
      return id;
    }
  }

  @Override
  public List<CaptureFormat> getSupportedFormats() {
    return Camera1Enumerator.getSupportedFormats(getCurrentCameraId());
  }

  // Returns true if this VideoCapturer is setup to capture video frames to a SurfaceTexture.
  public boolean isCapturingToTexture() {
    return isCapturingToTexture;
  }

  public VideoCapturerAndroid(String cameraName, CameraEventsHandler eventsHandler,
      boolean captureToTexture) {
    if (android.hardware.Camera.getNumberOfCameras() == 0) {
      throw new RuntimeException("No cameras available");
    }
    if (cameraName == null || cameraName.equals("")) {
      this.id = 0;
    } else {
      this.id = Camera1Enumerator.getCameraIndex(cameraName);
    }
    this.eventsHandler = eventsHandler;
    isCapturingToTexture = captureToTexture;
    Logging.d(TAG, "VideoCapturerAndroid isCapturingToTexture : " + isCapturingToTexture);
  }

  private void checkIsOnCameraThread() {
    synchronized (handlerLock) {
      if (cameraThreadHandler == null) {
        Logging.e(TAG, "Camera is stopped - can't check thread.");
      } else if (Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
        throw new IllegalStateException("Wrong thread");
      }
    }
  }

  private boolean maybePostOnCameraThread(Runnable runnable) {
    return maybePostDelayedOnCameraThread(0 /* delayMs */, runnable);
  }

  private boolean maybePostDelayedOnCameraThread(int delayMs, Runnable runnable) {
    synchronized (handlerLock) {
      return cameraThreadHandler != null
          && cameraThreadHandler.postAtTime(
              runnable, this /* token */, SystemClock.uptimeMillis() + delayMs);
    }
  }

  @Override
  public void dispose() {
    Logging.d(TAG, "dispose");
  }

  // Note that this actually opens the camera, and Camera callbacks run on the
  // thread that calls open(), so this is done on the CameraThread.
  @Override
  public void startCapture(
          final int width, final int height, final int framerate,
          final SurfaceTextureHelper surfaceTextureHelper, final Context applicationContext,
          final CapturerObserver inframeObserver) {
    Logging.d(TAG, "startCapture requested: " + width + "x" + height + "@" + framerate);
    this.frameObserver = new CaptureFilterObserver(inframeObserver, surfaceTextureHelper, mCaptureTextureCallback);
    if (surfaceTextureHelper == null) {
      frameObserver.onCapturerStarted(false /* success */);
      if (eventsHandler != null) {
        eventsHandler.onCameraError("No SurfaceTexture created.");
      }
      return;
    }
    if (applicationContext == null) {
      throw new IllegalArgumentException("applicationContext not set.");
    }
    if (frameObserver == null) {
      throw new IllegalArgumentException("frameObserver not set.");
    }
    synchronized (handlerLock) {
      if (this.cameraThreadHandler != null) {
        throw new RuntimeException("Camera has already been started.");
      }
      this.cameraThreadHandler = surfaceTextureHelper.getHandler();
      this.surfaceHelper = surfaceTextureHelper;
      final boolean didPost = maybePostOnCameraThread(new Runnable() {
        @Override
        public void run() {
          openCameraAttempts = 0;
          startCaptureOnCameraThread(width, height, framerate, frameObserver,
              applicationContext);
        }
      });
      if (!didPost) {
        frameObserver.onCapturerStarted(false);
        if (eventsHandler != null) {
          eventsHandler.onCameraError("Could not post task to camera thread.");
        }
      }
    }
  }

  private void startCaptureOnCameraThread(
      final int width, final int height, final int framerate, final CapturerObserver frameObserver,
      final Context applicationContext) {
    synchronized (handlerLock) {
      if (cameraThreadHandler == null) {
        Logging.e(TAG, "startCaptureOnCameraThread: Camera is stopped");
        return;
      } else {
        checkIsOnCameraThread();
      }
    }
    if (camera != null) {
      Logging.e(TAG, "startCaptureOnCameraThread: Camera has already been started.");
      return;
    }
    this.applicationContext = applicationContext;
    this.frameObserver = frameObserver;
    this.firstFrameReported = false;

    try {
      try {
        synchronized (cameraIdLock) {
          Logging.d(TAG, "Opening camera " + id);
          if (eventsHandler != null) {
            eventsHandler.onCameraOpening(id);
          }
          camera = android.hardware.Camera.open(id);
          info = new android.hardware.Camera.CameraInfo();
          android.hardware.Camera.getCameraInfo(id, info);
        }
      } catch (RuntimeException e) {
        openCameraAttempts++;
        if (openCameraAttempts < MAX_OPEN_CAMERA_ATTEMPTS) {
          Logging.e(TAG, "Camera.open failed, retrying", e);
          maybePostDelayedOnCameraThread(OPEN_CAMERA_DELAY_MS, new Runnable() {
            @Override public void run() {
              startCaptureOnCameraThread(width, height, framerate, frameObserver,
                  applicationContext);
            }
          });
          return;
        }
        throw e;
      }

      camera.setPreviewTexture(surfaceHelper.getSurfaceTexture());

      Logging.d(TAG, "Camera orientation: " + info.orientation +
          " .Device orientation: " + getDeviceOrientation());
      camera.setErrorCallback(cameraErrorCallback);
      startPreviewOnCameraThread(width, height, framerate);
      frameObserver.onCapturerStarted(true);
      if (isCapturingToTexture) {
        surfaceHelper.startListening(this);
      }

      // Start camera observer.
      cameraStatistics = new CameraStatistics(surfaceHelper, eventsHandler);
    } catch (IOException|RuntimeException e) {
      Logging.e(TAG, "startCapture failed", e);
      // Make sure the camera is released.
      stopCaptureOnCameraThread(true /* stopHandler */);
      frameObserver.onCapturerStarted(false);
      if (eventsHandler != null) {
        eventsHandler.onCameraError("Camera can not be started.");
      }
     }
  }

  // (Re)start preview with the closest supported format to |width| x |height| @ |framerate|.
  private void startPreviewOnCameraThread(int width, int height, int framerate) {
    synchronized (handlerLock) {
      if (cameraThreadHandler == null || camera == null) {
        Logging.e(TAG, "startPreviewOnCameraThread: Camera is stopped");
        return;
      } else {
        checkIsOnCameraThread();
      }
    }
    Logging.d(
        TAG, "startPreviewOnCameraThread requested: " + width + "x" + height + "@" + framerate);

    requestedWidth = width;
    requestedHeight = height;
    requestedFramerate = framerate;

    // Find closest supported format for |width| x |height| @ |framerate|.
    final android.hardware.Camera.Parameters parameters = camera.getParameters();
    final List<CaptureFormat.FramerateRange> supportedFramerates =
        Camera1Enumerator.convertFramerates(parameters.getSupportedPreviewFpsRange());
    Logging.d(TAG, "Available fps ranges: " + supportedFramerates);

    final CaptureFormat.FramerateRange fpsRange =
        CameraEnumerationAndroid.getClosestSupportedFramerateRange(supportedFramerates, framerate);

    final Size previewSize = CameraEnumerationAndroid.getClosestSupportedSize(
        Camera1Enumerator.convertSizes(parameters.getSupportedPreviewSizes()), width, height);

    final CaptureFormat captureFormat =
        new CaptureFormat(previewSize.width, previewSize.height, fpsRange);

    // Check if we are already using this capture format, then we don't need to do anything.
    if (captureFormat.equals(this.captureFormat)) {
      return;
    }

    // Update camera parameters.
    Logging.d(TAG, "isVideoStabilizationSupported: " +
        parameters.isVideoStabilizationSupported());
    if (parameters.isVideoStabilizationSupported()) {
      parameters.setVideoStabilization(true);
    }
    // Note: setRecordingHint(true) actually decrease frame rate on N5.
    // parameters.setRecordingHint(true);
    if (captureFormat.framerate.max > 0) {
      parameters.setPreviewFpsRange(captureFormat.framerate.min, captureFormat.framerate.max);
    }
    parameters.setPreviewSize(previewSize.width, previewSize.height);

    if (!isCapturingToTexture) {
      parameters.setPreviewFormat(captureFormat.imageFormat);
    }
    // Picture size is for taking pictures and not for preview/video, but we need to set it anyway
    // as a workaround for an aspect ratio problem on Nexus 7.
    final Size pictureSize = CameraEnumerationAndroid.getClosestSupportedSize(
        Camera1Enumerator.convertSizes(parameters.getSupportedPictureSizes()), width, height);
    parameters.setPictureSize(pictureSize.width, pictureSize.height);

    // Temporarily stop preview if it's already running.
    if (this.captureFormat != null) {
      camera.stopPreview();
      // Calling |setPreviewCallbackWithBuffer| with null should clear the internal camera buffer
      // queue, but sometimes we receive a frame with the old resolution after this call anyway.
      camera.setPreviewCallbackWithBuffer(null);
    }

    // (Re)start preview.
    Logging.d(TAG, "Start capturing: " + captureFormat);
    this.captureFormat = captureFormat;

    List<String> focusModes = parameters.getSupportedFocusModes();
    if (focusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
      parameters.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
    }

    camera.setParameters(parameters);
    // Calculate orientation manually and send it as CVO instead.
    camera.setDisplayOrientation(0 /* degrees */);
    if (!isCapturingToTexture) {
      queuedBuffers.clear();
      final int frameSize = captureFormat.frameSize();
      for (int i = 0; i < NUMBER_OF_CAPTURE_BUFFERS; ++i) {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(frameSize);
        queuedBuffers.add(buffer.array());
        camera.addCallbackBuffer(buffer.array());
      }
      camera.setPreviewCallbackWithBuffer(this);
    }
    Logging.e(TAG, "startPreview");
    camera.startPreview();
  }

  // Blocks until camera is known to be stopped.
  @Override
  public void stopCapture() throws InterruptedException {
    Logging.d(TAG, "stopCapture");
    final CountDownLatch barrier = new CountDownLatch(1);
    final boolean didPost = maybePostOnCameraThread(new Runnable() {
      @Override public void run() {
        stopCaptureOnCameraThread(true /* stopHandler */);
        barrier.countDown();
      }
    });
    if (!didPost) {
      Logging.e(TAG, "Calling stopCapture() for already stopped camera.");
      return;
    }
    if (!barrier.await(CAMERA_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
      Logging.e(TAG, "Camera stop timeout");
      printStackTrace();
      if (eventsHandler != null) {
        eventsHandler.onCameraError("Camera stop timeout");
      }
    }
    Logging.d(TAG, "stopCapture done");
  }

  private void stopCaptureOnCameraThread(boolean stopHandler) {
    synchronized (handlerLock) {
      if (cameraThreadHandler == null) {
        Logging.e(TAG, "stopCaptureOnCameraThread: Camera is stopped");
      } else {
        checkIsOnCameraThread();
      }
    }
    Logging.d(TAG, "stopCaptureOnCameraThread");
    // Note that the camera might still not be started here if startCaptureOnCameraThread failed
    // and we posted a retry.

    // Make sure onTextureFrameAvailable() is not called anymore.
    if (surfaceHelper != null) {
      surfaceHelper.stopListening();
    }
    if (stopHandler) {
      synchronized (handlerLock) {
        // Clear the cameraThreadHandler first, in case stopPreview or
        // other driver code deadlocks. Deadlock in
        // android.hardware.Camera._stopPreview(Native Method) has
        // been observed on Nexus 5 (hammerhead), OS version LMY48I.
        // The camera might post another one or two preview frames
        // before stopped, so we have to check for a null
        // cameraThreadHandler in our handler. Remove all pending
        // Runnables posted from |this|.
        if (cameraThreadHandler != null) {
          cameraThreadHandler.removeCallbacksAndMessages(this /* token */);
          cameraThreadHandler = null;
        }
        surfaceHelper = null;
      }
    }
    if (cameraStatistics != null) {
      cameraStatistics.release();
      cameraStatistics = null;
    }
    Logging.d(TAG, "Stop preview.");
    if (camera != null) {
      camera.stopPreview();
      camera.setPreviewCallbackWithBuffer(null);
    }
    queuedBuffers.clear();
    captureFormat = null;

    Logging.d(TAG, "Release camera.");
    if (camera != null) {
      camera.release();
      camera = null;
    }
    if (eventsHandler != null) {
      eventsHandler.onCameraClosed();
    }
    Logging.d(TAG, "stopCaptureOnCameraThread done");
  }

  private void switchCameraOnCameraThread(int cameraId) {
    synchronized (handlerLock) {
      if (cameraThreadHandler == null) {
        Logging.e(TAG, "switchCameraOnCameraThread: Camera is stopped");
        return;
      } else {
        checkIsOnCameraThread();
      }
    }
    Logging.d(TAG, "switchCameraOnCameraThread");
    stopCaptureOnCameraThread(false /* stopHandler */);
    synchronized (cameraIdLock) {
    if (cameraId == -1) {
        id = (id + 1) % android.hardware.Camera.getNumberOfCameras();

    } else {
        id = cameraId;
    }
    }
    startCaptureOnCameraThread(requestedWidth, requestedHeight, requestedFramerate, frameObserver,
        applicationContext);
    Logging.d(TAG, "switchCameraOnCameraThread done");
  }

  private void onOutputFormatRequestOnCameraThread(int width, int height, int framerate) {
    synchronized (handlerLock) {
      if (cameraThreadHandler == null || camera == null) {
        Logging.e(TAG, "onOutputFormatRequestOnCameraThread: Camera is stopped");
        return;
      } else {
        checkIsOnCameraThread();
      }
    }
    Logging.d(TAG, "onOutputFormatRequestOnCameraThread: " + width + "x" + height +
        "@" + framerate);
    frameObserver.onOutputFormatRequest(width, height, framerate);
  }

  // Exposed for testing purposes only.
  Handler getCameraThreadHandler() {
    return cameraThreadHandler;
  }

  private int getDeviceOrientation() {
    int orientation = 0;

    WindowManager wm = (WindowManager) applicationContext.getSystemService(
        Context.WINDOW_SERVICE);
    switch(wm.getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_90:
        orientation = 90;
        break;
      case Surface.ROTATION_180:
        orientation = 180;
        break;
      case Surface.ROTATION_270:
        orientation = 270;
        break;
      case Surface.ROTATION_0:
      default:
        orientation = 0;
        break;
    }
    return orientation;
  }

  private int getFrameOrientation() {
    int rotation = getDeviceOrientation();
    if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK) {
      rotation = 360 - rotation;
    }
    return (info.orientation + rotation) % 360;
  }

  // Called on cameraThread so must not "synchronized".
  @Override
  public void onPreviewFrame(byte[] data, android.hardware.Camera callbackCamera) {
    synchronized (handlerLock) {
      if (cameraThreadHandler == null) {
        Logging.e(TAG, "onPreviewFrame: Camera is stopped");
        return;
      } else {
        checkIsOnCameraThread();
      }
    }
    if (!queuedBuffers.contains(data)) {
      // |data| is an old invalid buffer.
      return;
    }
    if (camera != callbackCamera) {
      throw new RuntimeException("Unexpected camera in callback!");
    }

    final long captureTimeNs =
        TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());

    if (eventsHandler != null && !firstFrameReported) {
      eventsHandler.onFirstFrameAvailable();
      firstFrameReported = true;
    }

    cameraStatistics.addFrame();
    frameObserver.onByteBufferFrameCaptured(data, captureFormat.width, captureFormat.height,
        getFrameOrientation(), captureTimeNs);
    camera.addCallbackBuffer(data);
  }

  @Override
  public void onTextureFrameAvailable(
      int oesTextureId, float[] transformMatrix, long timestampNs) {
    synchronized (handlerLock) {
      if (cameraThreadHandler == null) {
        Logging.e(TAG, "onTextureFrameAvailable: Camera is stopped");
        surfaceHelper.returnTextureFrame();
        return;
      } else {
        checkIsOnCameraThread();
      }
    }
    if (eventsHandler != null && !firstFrameReported) {
      eventsHandler.onFirstFrameAvailable();
      firstFrameReported = true;
    }

    int rotation = getFrameOrientation();
    if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) {
      // Undo the mirror that the OS "helps" us with.
      // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
      transformMatrix =
          RendererCommon.multiplyMatrices(transformMatrix, RendererCommon.horizontalFlipMatrix());
    }
    cameraStatistics.addFrame();
    frameObserver.onTextureFrameCaptured(captureFormat.width, captureFormat.height, oesTextureId,
        transformMatrix, rotation, timestampNs);
  }
}
