package com.sevketbuyukdemir.tensorflow_application;

import android.Manifest;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.nio.ByteBuffer;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.sevketbuyukdemir.tensorflow_application.DetectionRelated.ImageUtils;

public abstract class CameraActivity extends AppCompatActivity implements OnImageAvailableListener,
        Camera.PreviewCallback,
        CompoundButton.OnCheckedChangeListener,
        View.OnClickListener {

    private static final int PERMISSIONS_REQUEST = 1;
    private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
    protected int preview_width = 0;
    protected int preview_height = 0;
    private boolean debug = false;
    private Handler handler;
    private HandlerThread handler_thread;
    private boolean use_camera2_API;
    private boolean is_processing_frame = false;
    private byte[][] yuv_bytes = new byte[3][];
    private int[] rgb_bytes = null;
    private int y_row_stride;
    private Runnable post_inference_callback;
    private Runnable image_converter;

    // For bottom settings bar
    private LinearLayout bottom_settings_bar;
    private LinearLayout settings_swipe_layout;
    private BottomSheetBehavior<LinearLayout> bottom_sheet_behavior;
    private ImageView settings_swipe_layout_arrow;
    // For bottom settings bar

    public void init() {
        // For bottom settings bar init
        bottom_settings_bar = findViewById(R.id.bottom_settings_bar);
        settings_swipe_layout = findViewById(R.id.settings_swipe_layout);
        bottom_sheet_behavior = BottomSheetBehavior.from(bottom_settings_bar);
        settings_swipe_layout_arrow = findViewById(R.id.settings_swipe_layout_arrow);
        // For bottom settings bar init
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_camera);
        init();

        // Our icon toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayShowTitleEnabled(false);
        // Our icon toolbar

        // for camera fragments MUST!!!
        if (hasPermission()) {
            setFragment();
        } else {
            requestPermission();
        }
        // for camera fragments MUST!!!


        // For bottom settings bar
        ViewTreeObserver vto = settings_swipe_layout.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
                            settings_swipe_layout.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                        } else {
                            settings_swipe_layout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                        // int width = bottomSheetLayout.getMeasuredWidth();
                        int height = settings_swipe_layout.getMeasuredHeight();

                        bottom_sheet_behavior.setPeekHeight(height);
                    }
                });
        bottom_sheet_behavior.setHideable(false);
        bottom_sheet_behavior.setBottomSheetCallback(
                new BottomSheetBehavior.BottomSheetCallback() {
                    @Override
                    public void onStateChanged(@NonNull View bottomSheet, int newState) {
                        switch (newState) {
                            case BottomSheetBehavior.STATE_HIDDEN:
                                break;
                            case BottomSheetBehavior.STATE_EXPANDED:
                            {
                                settings_swipe_layout_arrow.setImageResource(R.drawable.bottom_settings_down_arrow);
                            }
                            break;
                            case BottomSheetBehavior.STATE_COLLAPSED:
                            {
                                settings_swipe_layout_arrow.setImageResource(R.drawable.bottom_settings_up_arrow);
                            }
                            break;
                            case BottomSheetBehavior.STATE_DRAGGING:
                                break;
                            case BottomSheetBehavior.STATE_SETTLING:
                                settings_swipe_layout_arrow.setImageResource(R.drawable.bottom_settings_up_arrow);
                                break;
                        }
                    }

                    @Override
                    public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
                });
        // For bottom settings bar
    }//onCreate

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            handler.post(r);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode, final String[] permissions, final int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (allPermissionsGranted(grantResults)) {
                setFragment();
            } else {
                requestPermission();
            }
        }
    }

    private static boolean allPermissionsGranted(final int[] grantResults) {
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
                Toast.makeText(
                        CameraActivity.this, "Camera permission is required for eraser", Toast.LENGTH_LONG).show();
            }
            requestPermissions(new String[] {PERMISSION_CAMERA}, PERMISSIONS_REQUEST);
        }
    }

    private boolean isHardwareLevelSupported(
            CameraCharacteristics characteristics, int requiredLevel) {
        int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            return requiredLevel == deviceLevel;
        }
        // deviceLevel is not LEGACY, can use numerical sort
        return requiredLevel <= deviceLevel;
    }

    private String chooseCamera() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                final StreamConfigurationMap map =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                if (map == null) {
                    continue;
                }

                use_camera2_API =
                        (facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                                || isHardwareLevelSupported(
                                characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
                return cameraId;
            }
        } catch (CameraAccessException e) {
        }

        return null;
    }

    protected void setFragment() {
        String cameraId = chooseCamera();

        Fragment fragment;
        if (use_camera2_API) {
            Camera2Fragment camera2Fragment =
                    Camera2Fragment.newInstance(
                            new Camera2Fragment.ConnectionCallback() {
                                @Override
                                public void onPreviewSizeChosen(final Size size, final int rotation) {
                                    preview_height = size.getHeight();
                                    preview_width = size.getWidth();
                                    CameraActivity.this.onPreviewSizeChosen(size, rotation);
                                }
                            },
                            this,
                            getLayoutId(),
                            getDesiredPreviewFrameSize());

            camera2Fragment.setCamera(cameraId);
            fragment = camera2Fragment;
        } else {
            fragment = new CameraFragment(this, getLayoutId(), getDesiredPreviewFrameSize());
        }
        getFragmentManager().beginTransaction().replace(R.id.container, fragment).commit();
    }

    protected void fillBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }

    public boolean isDebug() {
        return debug;
    }

    protected int[] getRgbBytes() {
        image_converter.run();
        return rgb_bytes;
    }

    protected int getLuminanceStride() {
        return y_row_stride;
    }

    protected byte[] getLuminance() {
        return yuv_bytes[0];
    }

    @Override
    public void onPreviewFrame(final byte[] bytes, final Camera camera) {
        if (is_processing_frame) {
            return;
        }

        try {
            if (rgb_bytes == null) {
                Camera.Size previewSize = camera.getParameters().getPreviewSize();
                preview_height = previewSize.height;
                preview_width = previewSize.width;
                rgb_bytes = new int[preview_width * preview_height];
                onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 90);
            }
        } catch (final Exception e) {
            return;
        }

        is_processing_frame = true;
        yuv_bytes[0] = bytes;
        y_row_stride = preview_width;

        image_converter =
                new Runnable() {
                    @Override
                    public void run() {
                        ImageUtils.convertYUV420SPToARGB8888(bytes, preview_width, preview_height, rgb_bytes);
                    }
                };

        post_inference_callback =
                new Runnable() {
                    @Override
                    public void run() {
                        camera.addCallbackBuffer(bytes);
                        is_processing_frame = false;
                    }
                };
        processImage();
    }

    @Override
    public void onImageAvailable(final ImageReader reader) {
        if (preview_width == 0 || preview_height == 0) {
            return;
        }
        if (rgb_bytes == null) {
            rgb_bytes = new int[preview_width * preview_height];
        }
        try {
            final Image image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            if (is_processing_frame) {
                image.close();
                return;
            }
            is_processing_frame = true;
            Trace.beginSection("imageAvailable");
            final Image.Plane[] planes = image.getPlanes();
            fillBytes(planes, yuv_bytes);
            y_row_stride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();

            image_converter =
                    new Runnable() {
                        @Override
                        public void run() {
                            ImageUtils.convertYUV420ToARGB8888(
                                    yuv_bytes[0],
                                    yuv_bytes[1],
                                    yuv_bytes[2],
                                    preview_width,
                                    preview_height,
                                    y_row_stride,
                                    uvRowStride,
                                    uvPixelStride,
                                    rgb_bytes);
                        }
                    };

            post_inference_callback =
                    new Runnable() {
                        @Override
                        public void run() {
                            image.close();
                            is_processing_frame = false;
                        }
                    };

            processImage();
        } catch (final Exception e) {
            Trace.endSection();
            return;
        }
        Trace.endSection();
    }

    protected void readyForNextImage() {
        if (post_inference_callback != null) {
            post_inference_callback.run();
        }
    }

    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        setUseNNAPI(isChecked);
    }

    protected abstract void processImage();

    protected abstract void onPreviewSizeChosen(final Size size, final int rotation);

    protected abstract int getLayoutId();

    protected abstract Size getDesiredPreviewFrameSize();

    protected abstract void setUseNNAPI(boolean isChecked);

}//abstract class CameraActivity


