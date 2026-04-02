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
    private static final float MIN_FULL_FRAME_COVERAGE = 0.92f;

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

        forceHighResolutionPreviewIfPossible(uvcCamera, captureWidth, captureHeight);

        Log.i(TAG, "Starting native still capture at " + captureWidth + "x" + captureHeight);

        EncodedJpeg encodedJpeg = captureOnce(uvcCamera, request, captureWidth, captureHeight);

        writeJpegIfRequested(encodedJpeg.bytes, request.getOutputPath());

        return new HighResPhotoResult(
                Base64.encodeToString(encodedJpeg.bytes, Base64.NO_WRAP),
                encodedJpeg.width,
                encodedJpeg.height,
                encodedJpeg.bytes.length,
                getBackendName()
        );
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

    private EncodedJpeg captureOnce(UVCCamera uvcCamera, HighResPhotoRequest request, int captureWidth, int captureHeight) throws Exception {
        HandlerThread handlerThread = new HandlerThread("NativeStillCapture");
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper());
        ImageReader imageReader = null;
        Surface captureSurface = null;
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch callbackFinishedLatch = new CountDownLatch(1);
        final AtomicReference<EncodedJpeg> encodedJpegRef = new AtomicReference<>();
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
                    EncodedJpeg encodedJpeg = compressImageToJpeg(image, request.getJpegQuality());
                    if (encodedJpeg == null || encodedJpeg.bytes == null || encodedJpeg.bytes.length == 0) {
                        throw new IllegalStateException("Native still capture produced empty JPEG");
                    }
                    encodedJpegRef.set(encodedJpeg);
                } catch (Exception exception) {
                    errorRef.compareAndSet(null, exception);
                } finally {
                    if (image != null) {
                        image.close();
                    }
                    latch.countDown();
                    callbackFinishedLatch.countDown();
                }
            }, handler);

            uvcCamera.startCapture(captureSurface);

            if (!latch.await(request.getTimeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Native still capture timed out");
            }

            if (errorRef.get() != null) {
                throw errorRef.get();
            }

            EncodedJpeg encodedJpeg = encodedJpegRef.get();
            if (encodedJpeg == null || encodedJpeg.bytes == null || encodedJpeg.bytes.length == 0) {
                throw new IllegalStateException("Native still capture did not produce an image");
            }
            return encodedJpeg;
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
            try {
                callbackFinishedLatch.await(750, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
            try {
                handlerThread.join(1000);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private EncodedJpeg compressImageToJpeg(Image image, int jpegQuality) throws Exception {
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

        Bitmap exactBitmap = bitmap;
        if (bitmapWidth != width) {
            exactBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
        }

        ContentBounds contentBounds = detectUsefulContent(exactBitmap);
        if (!coversAlmostFullFrame(contentBounds, width, height)) {
            throw new PartialFrameException("Native still capture content does not fill the requested frame: bounds="
                    + contentBounds.width + "x" + contentBounds.height + " inside " + width + "x" + height);
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(width * height);
        try {
            boolean compressed = exactBitmap.compress(Bitmap.CompressFormat.JPEG, clampJpegQuality(jpegQuality), outputStream);
            if (!compressed) {
                throw new IllegalStateException("Bitmap JPEG compression failed");
            }
            return new EncodedJpeg(
                    outputStream.toByteArray(),
                    exactBitmap.getWidth(),
                    exactBitmap.getHeight()
            );
        } finally {
            try {
                outputStream.close();
            } catch (Exception ignored) {
            }
            if (exactBitmap != bitmap) {
                exactBitmap.recycle();
            }
            bitmap.recycle();
        }
    }

    private ContentBounds detectUsefulContent(Bitmap bitmap) {
        if (bitmap == null) {
            throw new IllegalArgumentException("bitmap == null");
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        final int threshold = 12;

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int color = pixels[rowOffset + x];
                int alpha = (color >>> 24) & 0xff;
                int red = (color >>> 16) & 0xff;
                int green = (color >>> 8) & 0xff;
                int blue = color & 0xff;
                if (alpha <= threshold && red <= threshold && green <= threshold && blue <= threshold) {
                    continue;
                }
                if (x < minX) {
                    minX = x;
                }
                if (y < minY) {
                    minY = y;
                }
                if (x > maxX) {
                    maxX = x;
                }
                if (y > maxY) {
                    maxY = y;
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return new ContentBounds(0, 0, 0, 0);
        }

        int croppedWidth = maxX - minX + 1;
        int croppedHeight = maxY - minY + 1;
        return new ContentBounds(minX, minY, croppedWidth, croppedHeight);
    }

    private boolean coversAlmostFullFrame(ContentBounds contentBounds, int width, int height) {
        if (contentBounds == null || width <= 0 || height <= 0) {
            return false;
        }
        float widthCoverage = (float) contentBounds.width / (float) width;
        float heightCoverage = (float) contentBounds.height / (float) height;
        return widthCoverage >= MIN_FULL_FRAME_COVERAGE
                && heightCoverage >= MIN_FULL_FRAME_COVERAGE;
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

    private void forceHighResolutionPreviewIfPossible(UVCCamera uvcCamera, int width, int height) {
        if (uvcCamera == null || width <= 0 || height <= 0) {
            return;
        }
        try {
            uvcCamera.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_MJPEG);
            Log.i(TAG, "Requested native preview size " + width + "x" + height + " with MJPEG");
            return;
        } catch (Exception mjpegException) {
            Log.w(TAG, "Unable to force MJPEG preview size " + width + "x" + height, mjpegException);
        }
        try {
            uvcCamera.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_YUYV);
            Log.i(TAG, "Requested native preview size " + width + "x" + height + " with YUYV");
        } catch (Exception yuyvException) {
            Log.w(TAG, "Unable to force YUYV preview size " + width + "x" + height, yuyvException);
        }
    }

    private static final class EncodedJpeg {
        final byte[] bytes;
        final int width;
        final int height;

        EncodedJpeg(byte[] bytes, int width, int height) {
            this.bytes = bytes;
            this.width = width;
            this.height = height;
        }
    }

    private static final class ContentBounds {
        final int left;
        final int top;
        final int width;
        final int height;

        ContentBounds(int left, int top, int width, int height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }

    private static final class PartialFrameException extends IllegalStateException {
        PartialFrameException(String message) {
            super(message);
        }
    }
}
