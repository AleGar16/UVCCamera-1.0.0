package com.cordova.plugin;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.PixelFormat;
import android.hardware.usb.UsbDevice;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.Size;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class NativeStillCaptureBackend implements HighResPhotoCaptureBackend {
    private static final String TAG = "NativeStillBackend";

    interface UvcCameraHandleProvider {
        UVCCamera getCurrentUvcCamera();
    }

    private final UvcCameraHandleProvider cameraHandleProvider;

    public NativeStillCaptureBackend(UvcCameraHandleProvider cameraHandleProvider) {
        this.cameraHandleProvider = cameraHandleProvider;
    }

    @Override
    public String getBackendName() {
        return "native-still-capture";
    }

    @Override
    public boolean supportsDevice(UsbDevice device) {
        return device != null;
    }

    @Override
    public void initialize() {
        // No-op.
    }

    @Override
    public HighResPhotoResult capture(UsbDevice device, HighResPhotoRequest request) throws Exception {
        UVCCamera uvcCamera = cameraHandleProvider != null ? cameraHandleProvider.getCurrentUvcCamera() : null;
        if (uvcCamera == null) {
            throw new IllegalStateException("Underlying UVCCamera not available");
        }

        int[] captureSize = resolveCaptureSize(uvcCamera, request);
        int captureWidth = captureSize[0];
        int captureHeight = captureSize[1];
        if (captureWidth <= 0 || captureHeight <= 0) {
            throw new IllegalStateException("Invalid native still capture size");
        }

        Log.i(TAG, "Starting native still capture at " + captureWidth + "x" + captureHeight);

        HandlerThread handlerThread = new HandlerThread("NativeStillCapture");
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper());
        ImageReader imageReader = null;
        Surface captureSurface = null;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<byte[]> jpegBytesRef = new AtomicReference<>();
        final AtomicReference<Exception> errorRef = new AtomicReference<>();

        try {
            imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
            captureSurface = imageReader.getSurface();

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) {
                        return;
                    }
                    byte[] jpegBytes = compressImageToJpeg(image, request.getJpegQuality());
                    if (jpegBytes == null || jpegBytes.length == 0) {
                        throw new IllegalStateException("Native still capture produced empty JPEG");
                    }
                    jpegBytesRef.set(jpegBytes);
                    latch.countDown();
                } catch (Exception exception) {
                    errorRef.compareAndSet(null, exception);
                    latch.countDown();
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }, handler);

            uvcCamera.startCapture(captureSurface);

            if (!latch.await(request.getTimeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Native still capture timed out");
            }

            if (errorRef.get() != null) {
                throw errorRef.get();
            }

            byte[] jpegBytes = jpegBytesRef.get();
            if (jpegBytes == null || jpegBytes.length == 0) {
                throw new IllegalStateException("Native still capture did not produce an image");
            }

            writeJpegIfRequested(jpegBytes, request.getOutputPath());

            return new HighResPhotoResult(
                    Base64.encodeToString(jpegBytes, Base64.NO_WRAP),
                    captureWidth,
                    captureHeight,
                    jpegBytes.length,
                    getBackendName()
            );
        } finally {
            try {
                uvcCamera.stopCapture();
            } catch (Exception exception) {
                Log.w(TAG, "Failed stopping native still capture", exception);
            }
            if (captureSurface != null) {
                captureSurface.release();
            }
            if (imageReader != null) {
                try {
                    imageReader.setOnImageAvailableListener(null, null);
                } catch (Exception ignored) {
                }
                imageReader.close();
            }
            handlerThread.quitSafely();
        }
    }

    @Override
    public void release() {
        // No-op.
    }

    private int[] resolveCaptureSize(UVCCamera uvcCamera, HighResPhotoRequest request) {
        try {
            Size previewSize = uvcCamera.getPreviewSize();
            if (previewSize != null && previewSize.width > 0 && previewSize.height > 0) {
                return new int[] { previewSize.width, previewSize.height };
            }
        } catch (Exception exception) {
            Log.w(TAG, "Unable to read current UVCCamera preview size", exception);
        }
        return new int[] {
                Math.max(1, request.getRequestedWidth()),
                Math.max(1, request.getRequestedHeight())
        };
    }

    private byte[] compressImageToJpeg(Image image, int jpegQuality) throws Exception {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) {
            throw new IllegalStateException("ImageReader returned no planes");
        }

        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        if (pixelStride <= 0 || rowStride <= 0) {
            throw new IllegalStateException("Invalid RGBA plane strides");
        }

        int bitmapWidth = Math.max(width, rowStride / pixelStride);
        Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, height, Config.ARGB_8888);
        buffer.rewind();
        bitmap.copyPixelsFromBuffer(buffer);

        Bitmap croppedBitmap = bitmap;
        if (bitmapWidth != width) {
            croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(width * height);
        try {
            boolean compressed = croppedBitmap.compress(Bitmap.CompressFormat.JPEG, clampJpegQuality(jpegQuality), outputStream);
            if (!compressed) {
                throw new IllegalStateException("Bitmap JPEG compression failed");
            }
            return outputStream.toByteArray();
        } finally {
            try {
                outputStream.close();
            } catch (Exception ignored) {
            }
            if (croppedBitmap != bitmap) {
                croppedBitmap.recycle();
            }
            bitmap.recycle();
        }
    }

    private int clampJpegQuality(int jpegQuality) {
        if (jpegQuality < 1) {
            return 1;
        }
        return Math.min(jpegQuality, 100);
    }

    private void writeJpegIfRequested(byte[] jpegBytes, String outputPath) {
        if (outputPath == null || outputPath.isEmpty() || jpegBytes == null || jpegBytes.length == 0) {
            return;
        }
        FileOutputStream outputStream = null;
        try {
            File outputFile = new File(outputPath);
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            outputStream = new FileOutputStream(outputFile, false);
            outputStream.write(jpegBytes);
            outputStream.flush();
        } catch (Exception exception) {
            Log.w(TAG, "Unable to persist native still capture JPEG to output path", exception);
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
