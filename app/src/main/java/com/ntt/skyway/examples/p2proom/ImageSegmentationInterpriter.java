package com.ntt.skyway.examples.p2proom;

import static org.opencv.core.Core.BORDER_DEFAULT;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;

import androidx.camera.core.ImageProxy;


import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

// 画像セグメンテーションインタープリタ
public class ImageSegmentationInterpriter {
    // パラメータ定数
    private final static int BATCH_SIZE = 1; //バッチサイズ
    private final static int INPUT_PIXELS = 3; //入力ピクセル
    private final static int INPUT_SIZE = 257; // 入力サイズ
    private final static int NUM_CLASSES = 21; // クラス数
    private final boolean IS_QUANTIZED = false;
    private final static float IMAGE_MEAN = 128.0f;
    private final static float IMAGE_STD = 128.0f;

    // システム
    private Context context;
    private Interpreter interpreter;
    private int[] imageBuffer = new int[INPUT_SIZE * INPUT_SIZE];
    private int[] colors = new int[NUM_CLASSES];

    // 入力
    private Bitmap inBitmap;
    private Canvas inCanvas;
    private Rect inBitmapSrc = new Rect();
    private Rect inBitmapDst = new Rect(0, 0, INPUT_SIZE, INPUT_SIZE);
    private ByteBuffer inBuffer;

    // 出力
    private ByteBuffer outSegmentationMasks;

    int pixels[];
    int resultPixels[];
    int backgroundPixels[];
    int background_width_px;
    int background_height_px;

    // コンストラクタ
    public ImageSegmentationInterpriter(Context context) {
        this.context = context;

        // 色の初期化
        for (int i = 0; i < NUM_CLASSES; i++) {
            if(i == 15){
                this.colors[i] = Color.TRANSPARENT;
            }else{
                this.colors[i] = Color.GREEN;
            }
        }

        // モデルの読み込み
        MappedByteBuffer model = loadModel("deeplabv3_257_mv_gpu.tflite");

        // インタプリタの生成
        Interpreter.Options options = new Interpreter.Options();
        //options.setUseNNAPI(true); //NNAPI
//        options.addDelegate(new GpuDelegate()); //GPU
        options.setNumThreads(1); // スレッド数
        this.interpreter = new Interpreter(model, options);

        // 入力の初期化
        this.inBitmap = Bitmap.createBitmap(
                INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        this.inCanvas = new Canvas(inBitmap);
        int numBytesPerChannel = IS_QUANTIZED ? 1 : 4;
        this.inBuffer = ByteBuffer.allocateDirect(
                BATCH_SIZE * INPUT_SIZE * INPUT_SIZE * INPUT_PIXELS * numBytesPerChannel);
        this.inBuffer.order(ByteOrder.nativeOrder());
        this.inBuffer.rewind();

        // 出力の初期化
        this.outSegmentationMasks = ByteBuffer.allocateDirect(
                1 * INPUT_SIZE * INPUT_SIZE * 21 * 4);
        this.outSegmentationMasks.order(ByteOrder.nativeOrder());
    }

    // モデルの読み込み
    private MappedByteBuffer loadModel(String modelPath) {
        try {
            AssetFileDescriptor fd = this.context.getAssets().openFd(modelPath);
            FileInputStream in = new FileInputStream(fd.getFileDescriptor());
            FileChannel fc = in.getChannel();
            return fc.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void setBackgroundImage(Resources r, int resid) {
        Bitmap background = BitmapFactory.decodeResource(r, resid);

        background_width_px = background.getWidth();
        background_height_px = background.getHeight();
        backgroundPixels = new int[background_width_px * background_height_px];
        background.getPixels(backgroundPixels,0, background_width_px, 0, 0, background_width_px, background_height_px);
    }

    public Bitmap processMat(Mat matOrg, int flipMode) {

        /* Fix image rotation (it looks image in PreviewView is automatically fixed by CameraX???) */
        Mat mat = fixMatRotation(matOrg, flipMode);

        /* Convert cv::mat to bitmap for drawing */
        Bitmap bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(),Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat, bitmap);
        int bitmap_width_px = bitmap.getWidth();
        int bitmap_height_px = bitmap.getHeight();
        pixels = new int[bitmap_width_px * bitmap_height_px];
        bitmap.getPixels(pixels,0, bitmap_width_px, 0, 0, bitmap_width_px, bitmap_height_px);

        Bitmap result = this.predict(bitmap);
        int result_width_px = result.getWidth();
        int result_height_px = result.getHeight();
        resultPixels = new int[result_width_px * result_height_px];
        result.getPixels(resultPixels,0, result_width_px, 0, 0, result_width_px, result_height_px);

        // Pixel 操作部分
        for (int y = 0; y < bitmap_height_px; y++) {
            for (int x = 0; x < bitmap_width_px; x++) {
                Point pointOnResult= mappedPoint(x, y, result_width_px, result_height_px, bitmap_width_px, bitmap_height_px);
                int resultPixel = resultPixels[pointOnResult.x + pointOnResult.y * result_width_px];

                Point pointOnBackground= mappedPoint(x, y, background_width_px, background_height_px, bitmap_width_px, bitmap_height_px);
                int backgroundPixel = backgroundPixels[pointOnBackground.x + pointOnBackground.y * background_width_px];

                if(resultPixel == Color.GREEN) {
                    pixels[x + y * bitmap_width_px] = Color.argb(
                            Color.alpha(backgroundPixel),
                            Color.red(backgroundPixel),
                            Color.green(backgroundPixel),
                            Color.blue(backgroundPixel)
                    );
                }
            }
        }

        // Bitmap に Pixel を設定
        bitmap.setPixels(pixels, 0, bitmap_width_px, 0, 0, bitmap_width_px, bitmap_height_px);

        return bitmap;
    }

    public byte [] process(ImageProxy image, int flipMode) {

        /* Create cv::mat(RGB888) from image(NV21) */
        Mat matOrg = getMatFromImage(image);

        Bitmap bitmap = processMat(matOrg , flipMode);
        int bitmap_width_px = bitmap.getWidth();
        int bitmap_height_px = bitmap.getHeight();

        byte [] nv21 = getNV21(bitmap_width_px, bitmap_height_px, bitmap);
        return nv21;
    }

    public Bitmap processBitmap(ImageProxy image, int flipMode) {

        /* Create cv::mat(RGB888) from image(NV21) */
        Mat matOrg = getMatFromImage(image);

        return processMat(matOrg , flipMode);
    }


    public Bitmap processBlur(ImageProxy image, int flipMode) {

        /* Create cv::mat(RGB888) from image(NV21) */
        Mat matOrg = getMatFromImage(image);
        Mat dstMat = new Mat();

        /* Fix image rotation (it looks image in PreviewView is automatically fixed by CameraX???) */
        Mat mat = fixMatRotation(matOrg, flipMode);

        Size ksize = new Size(5,5);
        org.opencv.core.Point anchor = new org.opencv.core.Point(4,4);

        Imgproc.blur(mat,dstMat,ksize,anchor,BORDER_DEFAULT);

        Bitmap bitmap = Bitmap.createBitmap(dstMat.cols(), dstMat.rows(),Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(dstMat, bitmap);

        return bitmap;
    }

    // 推論
    public Bitmap predict(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int max = Math.max(w, h);
        this.inBitmapSrc.set(
                (w - max)/2, (h - max)/2,
                (w + max)/2, (h + max)/2
        );
        inCanvas.drawBitmap(bitmap, this.inBitmapSrc, this.inBitmapDst, null);

        // 推論
        bmpToInBuffer(inBitmap);
        this.outSegmentationMasks.rewind();
        this.interpreter.run(this.inBuffer, this.outSegmentationMasks);

        // 結果の取得
        return bufferToBitmap(this.outSegmentationMasks);
    }

    // Bitmap → 入力バッファ
    private void bmpToInBuffer(Bitmap bitmap) {
        this.inBuffer.rewind();
        bitmap.getPixels(this.imageBuffer, 0, bitmap.getWidth(),
                0, 0, bitmap.getWidth(), bitmap.getHeight());
        int pixel = 0;
        for (int i = 0; i < INPUT_SIZE; ++i) {
            for (int j = 0; j < INPUT_SIZE; ++j) {
                addPixelValue(imageBuffer[pixel++]);
            }
        }
    }

    // ピクセル値の追加
    private void addPixelValue(int pixelValue) {
        if (IS_QUANTIZED) {
            inBuffer.put((byte)((pixelValue >> 16) & 0xFF));
            inBuffer.put((byte)((pixelValue >> 8) & 0xFF));
            inBuffer.put((byte)(pixelValue & 0xFF));
        } else {
            inBuffer.putFloat((((pixelValue >> 16) & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
            inBuffer.putFloat((((pixelValue >> 8) & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
            inBuffer.putFloat(((pixelValue & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
        }
    }

    // ByteBuffer → Bitmap
    private Bitmap bufferToBitmap(ByteBuffer segmentationMasks) {
        Bitmap maskBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                float maxVal = 0f;

                // 確率の高いクラス
                int classIndex = 0;
                for (int c = 0; c < NUM_CLASSES; c++) {
                    float value = segmentationMasks.getFloat((y*INPUT_SIZE*NUM_CLASSES+x*NUM_CLASSES+c)*4);
                    if (c == 0 || value > maxVal) {
                        maxVal = value;
                        classIndex = c;
                    }
                }

                // 色の指定
                maskBitmap.setPixel(x, y, colors[classIndex]);
            }
        }
        return maskBitmap;
    }


    private Mat getMatFromImage(ImageProxy image) {
        /* https://stackoverflow.com/questions/30510928/convert-android-camera2-api-yuv-420-888-to-rgb */
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();
        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();
        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);
        Mat yuv = new Mat(image.getHeight() + image.getHeight() / 2, image.getWidth(), CvType.CV_8UC1);
        yuv.put(0, 0, nv21);
        Mat mat = new Mat();
        Imgproc.cvtColor(yuv, mat, Imgproc.COLOR_YUV2RGB_NV21, 3);
        return mat;
    }

    private Mat fixMatRotation(Mat matOrg, int flipMode) {
        Mat mat;
        switch (flipMode){
            default:
            case 0:
//                Log.i("Rotation", "ROTATION_0");
                mat = new Mat(matOrg.cols(), matOrg.rows(), matOrg.type());
                Core.transpose(matOrg, mat);
                Core.flip(mat, mat, 1);
                break;
            case 1:
//                Log.i("Rotation", "ROTATION_90");
                mat = matOrg;
                break;
            case 2:
//                Log.i("Rotation", "ROTATION_180");
                mat = new Mat(matOrg.cols(), matOrg.rows(), matOrg.type());
                Core.transpose(matOrg, mat);
                Core.flip(mat, mat, -1);
                break;
            case 3:
//                Log.i("Rotation", "ROTATION_270");
                mat = matOrg;
                Core.flip(mat, mat, -1);
                break;
        }
        return mat;
    }

    private Point mappedPoint(int x, int y, int w_t, int h_t, int w_r, int h_r) {
        double ratio = Math.min((double)w_t/w_r, (double)h_t/h_r);
        int x_new = (int)((w_t - ratio * w_r)/2 + ratio * x);
        int y_new = (int)((h_t - ratio * h_r)/2 + ratio * y);
        Point point = new Point(x_new, y_new);
        return point;
    }

    // untested function
    byte [] getNV21(int inputWidth, int inputHeight, Bitmap scaled) {

        int [] argb = new int[inputWidth * inputHeight];

        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight);

        byte [] yuv = new byte[inputWidth*inputHeight*3/2];
        encodeYUV420SP(yuv, argb, inputWidth, inputHeight);

        scaled.recycle();

        return yuv;
    }

    void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height) {
        final int frameSize = width * height;

        int yIndex = 0;
        int uvIndex = frameSize;

        int a, R, G, B, Y, U, V;
        int index = 0;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {

                a = (argb[index] & 0xff000000) >> 24; // a is not used obviously
                R = (argb[index] & 0xff0000) >> 16;
                G = (argb[index] & 0xff00) >> 8;
                B = (argb[index] & 0xff) >> 0;

                // well known RGB to YUV algorithm
                Y = ( (  66 * R + 129 * G +  25 * B + 128) >> 8) +  16;
                U = ( ( -38 * R -  74 * G + 112 * B + 128) >> 8) + 128;
                V = ( ( 112 * R -  94 * G -  18 * B + 128) >> 8) + 128;

                // NV21 has a plane of Y and interleaved planes of VU each sampled by a factor of 2
                //    meaning for every 4 Y pixels there are 1 V and 1 U.  Note the sampling is every other
                //    pixel AND every other scanline.
                yuv420sp[yIndex++] = (byte) ((Y < 0) ? 0 : ((Y > 255) ? 255 : Y));
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (byte)((V<0) ? 0 : ((V > 255) ? 255 : V));
                    yuv420sp[uvIndex++] = (byte)((U<0) ? 0 : ((U > 255) ? 255 : U));
                }

                index ++;
            }
        }
    }
}
