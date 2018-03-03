package com.hrvfinger.mabe.productions.hrvfinger;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import android.hardware.Camera;
import android.os.Build;
import android.os.PowerManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.commons.lang3.ArrayUtils;

import java.util.concurrent.atomic.AtomicBoolean;

import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;

public class MeasurementActivity extends AppCompatActivity {

    public static int bpm;
    private static SurfaceView preview = null;
    private static SurfaceHolder previewHolder = null;
    private static Camera camera = null;


    private static int averageIndex = 0;
    private static final int averageArraySize = 100;
    private static final int[] averageArray = new int[averageArraySize];
    private static final AtomicBoolean processing = new AtomicBoolean(false);
    public static enum TYPE {
        GREEN, RED
    };

    private static TYPE currentType = TYPE.GREEN;

    public static TYPE getCurrent() {
        return currentType;
    }

    private static int beatsIndex = 0;
    private static final int beatsArraySize = 14;
    private static final int[] beatsArray = new int[beatsArraySize];
    private static final long[] timesArray = new long[beatsArraySize];
    private static double beats = 0;
    private static long startTime = 0;


    static int counter = 0;

    private static final int sampleSize = 256;
    private static final CircularFifoQueue sampleQueue = new CircularFifoQueue(
            sampleSize);
    private static final CircularFifoQueue timeQueue = new CircularFifoQueue(
            sampleSize);

    public static final CircularFifoQueue bpmQueue = new CircularFifoQueue(40);

    private static final FFT fft = new FFT(sampleSize);


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_measurement);

        preview = (SurfaceView) findViewById(R.id.camera_preview);
        previewHolder = preview.getHolder();
        previewHolder.addCallback(surfaceCallback);
        previewHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.CAMERA}, 1);
            }
        }




    }

    @Override
    public void onPause() {
        super.onPause();


        camera.setPreviewCallback(null);
        camera.stopPreview();
        camera.release();
        camera = null;
        // try {
        // out.close();
        // } catch (IOException e) {
        // e.printStackTrace();
        // }

        bpm = -1;
    }

    @Override
    public void onResume() {
        super.onResume();



        camera = Camera.open();

        // File file = new File(this.getFilesDir(), "data.txt");
        // try {
        // out = new BufferedWriter(new FileWriter(file));
        // } catch (IOException e) {
        // Log.e(TAG,"unexpected Error", e);
        // e.printStackTrace();
        // }

    }

    private static SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {

        /**
         * {@inheritDoc}
         */
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.i("TEST","surfaceCreated");
            try {
                camera.setPreviewDisplay(previewHolder);
                camera.setPreviewCallback(previewCallback);
            } catch (Throwable t) {
                Log.e("TEST",
                        "Exception in setPreviewDisplay()", t);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width,
                                   int height) {
            Camera.Parameters parameters = camera.getParameters();
            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            Camera.Size size = getSmallestPreviewSize(width, height, parameters);
            if (size != null) {
                parameters.setPreviewSize(size.width, size.height);
                Log.d("TEST", "Using width=" + size.width + " height="
                        + size.height);
            }
            camera.setParameters(parameters);
            camera.startPreview();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            camera.release();
            // Ignore
        }
    };


    private static Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {

        /**
         * {@inheritDoc}
         */
        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {
            if (data == null)
                throw new NullPointerException();
            Camera.Size size = cam.getParameters().getPreviewSize();
            if (size == null)
                throw new NullPointerException();

            if (!processing.compareAndSet(false, true))
                return;

            int width = size.width;
            int height = size.height;

            int imgAvg = ImageProcessing.decodeYUV420SPtoRedAvg(data.clone(),
                    height, width);

            // try {
            // out.write(imgAvg + ",");
            // } catch (IOException e) {
            // e.printStackTrace();
            // }

            sampleQueue.add((double) imgAvg);
            timeQueue.add(System.currentTimeMillis());

            double[] y = new double[sampleSize];
            double[] x = ArrayUtils.toPrimitive((Double[]) sampleQueue
                    .toArray(new Double[0]));
            long[] time = ArrayUtils.toPrimitive((Long[]) timeQueue
                    .toArray(new Long[0]));

            if (timeQueue.size() < sampleSize) {
                processing.set(false);

                return;
            }

            double Fs = ((double) timeQueue.size())
                    / (double) (time[timeQueue.size() - 1] - time[0]) * 1000;

            fft.fft(x, y);

            int low = Math.round((float) (sampleSize * 40 / 60 / Fs));
            int high = Math.round((float) (sampleSize * 160 / 60 / Fs));

            int bestI = 0;
            double bestV = 0;
            for (int i = low; i < high; i++) {
                double value = Math.sqrt(x[i] * x[i] + y[i] * y[i]);

                if (value > bestV) {
                    bestV = value;
                    bestI = i;
                }
            }

            bpm = Math.round((float) (bestI * Fs * 60 / sampleSize));
            Log.i("TEST", String.valueOf(bpm));
            bpmQueue.add(bpm);


        }

        };


        private static Camera.Size getSmallestPreviewSize(int width, int height,
                                                          Camera.Parameters parameters) {
            Camera.Size result = null;

            for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
                if (size.width <= width && size.height <= height) {
                    if (result == null) {
                        result = size;
                    } else {
                        int resultArea = result.width * result.height;
                        int newArea = size.width * size.height;

                        if (newArea < resultArea)
                            result = size;
                    }
                }
            }

            return result;
        }


    }
