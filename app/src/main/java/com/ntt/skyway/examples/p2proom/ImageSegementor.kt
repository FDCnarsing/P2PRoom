package com.ntt.skyway.examples.p2proom

import android.content.Context
import android.content.res.Resources
import android.graphics.*
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.Imgproc.INTER_LINEAR
import org.opencv.imgproc.Imgproc.INTER_NEAREST
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

// 画像セグメンテーションインタープリタ
class ImageSegmentor(  // システム
    private val context: Context
) {
    private val IS_QUANTIZED = false
    private val interpreter: Interpreter
    private val imageBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val colors = IntArray(NUM_CLASSES)

    // 入力
    private val inBitmap: Bitmap
    private val inCanvas: Canvas
    private val inBitmapSrc = Rect()
    private val inBitmapDst = Rect(0, 0, INPUT_SIZE, INPUT_SIZE)
    private val inBuffer: ByteBuffer

    // 出力
    private val outSegmentationMasks: ByteBuffer
    var pixels: IntArray? = null
    var resultPixels: IntArray? = null
    var backgroundPixels: IntArray? = null
    var background_width_px = 0
    var background_height_px = 0

    // コンストラクタ
    init {

        // 色の初期化
        for (i in 0 until NUM_CLASSES) {
            if (i == 15) {
                colors[i] = Color.TRANSPARENT
            } else {
                colors[i] = Color.GREEN
            }
        }

        // モデルの読み込み
        val model = loadModel("deeplabv3_257_mv_gpu.tflite")

        // インタプリタの生成
        val options = Interpreter.Options()
        //options.setUseNNAPI(true); //NNAPI
//        options.addDelegate(new GpuDelegate()); //GPU
        options.setNumThreads(1) // スレッド数
        interpreter = Interpreter(model!!, options)

        // 入力の初期化
        inBitmap = Bitmap.createBitmap(
            INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888
        )
        inCanvas = Canvas(inBitmap)
        val numBytesPerChannel = if (IS_QUANTIZED) 1 else 4
        inBuffer = ByteBuffer.allocateDirect(
            BATCH_SIZE * INPUT_SIZE * INPUT_SIZE * INPUT_PIXELS * numBytesPerChannel
        )
        inBuffer.order(ByteOrder.nativeOrder())
        inBuffer.rewind()

        // 出力の初期化
        outSegmentationMasks = ByteBuffer.allocateDirect(
            1 * INPUT_SIZE * INPUT_SIZE * 21 * 4
        )
        outSegmentationMasks.order(ByteOrder.nativeOrder())
    }

    // モデルの読み込み
    private fun loadModel(modelPath: String): MappedByteBuffer? {
        return try {
            val fd = context.assets.openFd(modelPath)
            val `in` = FileInputStream(fd.fileDescriptor)
            val fc = `in`.channel
            fc.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun setBackgroundImage(r: Resources?, resid: Int) {
        val background = BitmapFactory.decodeResource(r, resid)
        background_width_px = background.width
        background_height_px = background.height
        backgroundPixels = IntArray(background_width_px * background_height_px)
        background.getPixels(
            backgroundPixels,
            0,
            background_width_px,
            0,
            0,
            background_width_px,
            background_height_px
        )
    }

    fun process(mat: Mat): Bitmap {

        /* Convert cv::mat to bitmap for drawing */
        val bitmap = Bitmap.createBitmap(
            mat.cols(),
            mat.rows(),
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(mat, bitmap)
        val bitmap_width_px = bitmap.width
        val bitmap_height_px = bitmap.height
        pixels = IntArray(bitmap_width_px * bitmap_height_px)
        bitmap.getPixels(pixels, 0, bitmap_width_px, 0, 0, bitmap_width_px, bitmap_height_px)
        val result = predict(bitmap)
        val result_width_px = result.width
        val result_height_px = result.height
        resultPixels = IntArray(result_width_px * result_height_px)
        result.getPixels(resultPixels, 0, result_width_px, 0, 0, result_width_px, result_height_px)

        // Pixel 操作部分
        for (y in 0 until bitmap_height_px) {
            for (x in 0 until bitmap_width_px) {
                val pointOnResult = mappedPoint(
                    x,
                    y,
                    result_width_px,
                    result_height_px,
                    bitmap_width_px,
                    bitmap_height_px
                )
                val resultPixel =
                    resultPixels!![pointOnResult.x + pointOnResult.y * result_width_px]
                val pointOnBackground = mappedPoint(
                    x,
                    y,
                    background_width_px,
                    background_height_px,
                    bitmap_width_px,
                    bitmap_height_px
                )
                val backgroundPixel =
                    backgroundPixels!![pointOnBackground.x + pointOnBackground.y * background_width_px]
                if (resultPixel == Color.GREEN) {
                    pixels!![x + y * bitmap_width_px] = Color.argb(
                        Color.alpha(backgroundPixel),
                        Color.red(backgroundPixel),
                        Color.green(backgroundPixel),
                        Color.blue(backgroundPixel)
                    )
                }
            }
        }

        // Bitmap に Pixel を設定
        bitmap.setPixels(pixels, 0, bitmap_width_px, 0, 0, bitmap_width_px, bitmap_height_px)
        return bitmap
    }


    fun processBitmap(image: ImageProxy, flipMode: Int): Bitmap {

        /* Create cv::mat(RGB888) from image(NV21) */
        val matOrg = getMatFromImage(image)

        /* Fix image rotation (it looks image in PreviewView is automatically fixed by CameraX???) */
        val mat = fixMatRotation(matOrg, flipMode)

        return process(mat)
    }



    fun processNV21(image: ImageProxy, flipMode: Int): ByteArray {

        /* Create cv::mat(RGB888) from image(NV21) */
        val matOrg = getMatFromImage(image)

        /* Fix image rotation (it looks image in PreviewView is automatically fixed by CameraX???) */
        val mat = fixMatRotation(matOrg, flipMode)

        val bitmap = process (mat)

        val bitmap_width_px = bitmap.width
        val bitmap_height_px = bitmap.height

        return getNV21(bitmap_width_px, bitmap_height_px, bitmap)
    }


    fun doMosaic(img: Mat, msize: Int): Boolean? {
        var i = 0
        while (i < img.cols() - msize) {
            var j = 0
            while (j < img.rows() - msize) {
                val r = org.opencv.core.Rect(i, j, msize, msize)
                val mosaic: Mat = Mat(img,r)
                mosaic.setTo(Core.mean(mosaic))
                j += msize
            }
            i += msize
        }
        return true
    }

    fun processPixelPlateBlur(image: ImageProxy?, flipMode: Int): Bitmap? {

        /* Create cv::mat(RGB888) from image(NV21) */
        val matOrg = getMatFromImage(image!!)
        val dstMat = Mat()

        /* Fix image rotation (it looks image in PreviewView is automatically fixed by CameraX???) */
        val mat = fixMatRotation(matOrg, flipMode)

        val pixel = Size(60.0, 60.0)
        Imgproc.resize(mat, dstMat, pixel, 0.0, 0.0, INTER_LINEAR)
        Imgproc.resize(dstMat, dstMat, mat.size(), 0.0, 0.0, INTER_NEAREST)

        val ksize = Size(10.0, 10.0)
//        val anchor = org.opencv.core.Point(4.0, 4.0)
        val anchor = org.opencv.core.Point(-1.0, -1.0)
        //Imgproc.blur(mat, dstMat, ksize, anchor, Core.BORDER_DEFAULT)

        // Imgproc.medianBlur(mat,dstMat,31)
        Imgproc.blur(dstMat,dstMat,ksize)
        val bitmap = Bitmap.createBitmap(dstMat.cols(), dstMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dstMat, bitmap)
        return bitmap
    }

    // 推論
    fun predict(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val max = Math.max(w, h)
        inBitmapSrc[(w - max) / 2, (h - max) / 2, (w + max) / 2] = (h + max) / 2
        inCanvas.drawBitmap(bitmap, inBitmapSrc, inBitmapDst, null)

        // 推論
        bmpToInBuffer(inBitmap)
        outSegmentationMasks.rewind()
        interpreter.run(inBuffer, outSegmentationMasks)

        // 結果の取得
        return bufferToBitmap(outSegmentationMasks)
    }

    // Bitmap → 入力バッファ
    private fun bmpToInBuffer(bitmap: Bitmap) {
        inBuffer.rewind()
        bitmap.getPixels(
            imageBuffer, 0, bitmap.width,
            0, 0, bitmap.width, bitmap.height
        )
        var pixel = 0
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                addPixelValue(imageBuffer[pixel++])
            }
        }
    }

    // ピクセル値の追加
    private fun addPixelValue(pixelValue: Int) {
        if (IS_QUANTIZED) {
            inBuffer.put((pixelValue shr 16 and 0xFF).toByte())
            inBuffer.put((pixelValue shr 8 and 0xFF).toByte())
            inBuffer.put((pixelValue and 0xFF).toByte())
        } else {
            inBuffer.putFloat(((pixelValue shr 16 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
            inBuffer.putFloat(((pixelValue shr 8 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
            inBuffer.putFloat(((pixelValue and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
        }
    }

    // ByteBuffer → Bitmap
    private fun bufferToBitmap(segmentationMasks: ByteBuffer): Bitmap {
        val maskBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                var maxVal = 0f

                // 確率の高いクラス
                var classIndex = 0
                for (c in 0 until NUM_CLASSES) {
                    val value =
                        segmentationMasks.getFloat((y * INPUT_SIZE * NUM_CLASSES + x * NUM_CLASSES + c) * 4)
                    if (c == 0 || value > maxVal) {
                        maxVal = value
                        classIndex = c
                    }
                }

                // 色の指定
                maskBitmap.setPixel(x, y, colors[classIndex])
            }
        }
        return maskBitmap
    }

    private fun getMatFromImage(image: ImageProxy): Mat {
        /* https://stackoverflow.com/questions/30510928/convert-android-camera2-api-yuv-420-888-to-rgb */
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer[nv21, 0, ySize]
        vBuffer[nv21, ySize, vSize]
        uBuffer[nv21, ySize + vSize, uSize]
        val yuv = Mat(image.height + image.height / 2, image.width, CvType.CV_8UC1)
        yuv.put(0, 0, nv21)
        val mat = Mat()
        Imgproc.cvtColor(yuv, mat, Imgproc.COLOR_YUV2RGB_NV21, 3)
        return mat
    }

    private fun fixMatRotation(matOrg: Mat, flipMode: Int): Mat {
        val mat: Mat
        when (flipMode) {
            0 -> {
                //                Log.i("Rotation", "ROTATION_0");
                mat = Mat(matOrg.cols(), matOrg.rows(), matOrg.type())
                Core.transpose(matOrg, mat)
                Core.flip(mat, mat, 1)
            }
            1 -> //                Log.i("Rotation", "ROTATION_90");
                mat = matOrg
            2 -> {
                //                Log.i("Rotation", "ROTATION_180");
                mat = Mat(matOrg.cols(), matOrg.rows(), matOrg.type())
                Core.transpose(matOrg, mat)
                Core.flip(mat, mat, -1)
            }
            3 -> {
                //                Log.i("Rotation", "ROTATION_270");
                mat = matOrg
                Core.flip(mat, mat, -1)
            }
            else -> {
                mat = Mat(matOrg.cols(), matOrg.rows(), matOrg.type())
                Core.transpose(matOrg, mat)
                Core.flip(mat, mat, 1)
            }
        }
        return mat
    }

    private fun mappedPoint(
        x: Int,
        y: Int,
        w_t: Int,
        h_t: Int,
        w_r: Int,
        h_r: Int
    ): Point {
        val ratio = Math.min(w_t.toDouble() / w_r, h_t.toDouble() / h_r)
        val x_new = ((w_t - ratio * w_r) / 2 + ratio * x).toInt()
        val y_new = ((h_t - ratio * h_r) / 2 + ratio * y).toInt()
        return Point(x_new, y_new)
    }

    // untested function
    fun getNV21(inputWidth: Int, inputHeight: Int, scaled: Bitmap): ByteArray {
        val argb = IntArray(inputWidth * inputHeight)
        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val yuv = ByteArray(inputWidth * inputHeight * 3 / 2)
        encodeYUV420SP(yuv, argb, inputWidth, inputHeight)
        scaled.recycle()
        return yuv
    }

    fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var a: Int
        var R: Int
        var G: Int
        var B: Int
        var Y: Int
        var U: Int
        var V: Int
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                a = argb[index] and -0x1000000 shr 24 // a is not used obviously
                R = argb[index] and 0xff0000 shr 16
                G = argb[index] and 0xff00 shr 8
                B = argb[index] and 0xff shr 0

                // well known RGB to YUV algorithm
                Y = (66 * R + 129 * G + 25 * B + 128 shr 8) + 16
                U = (-38 * R - 74 * G + 112 * B + 128 shr 8) + 128
                V = (112 * R - 94 * G - 18 * B + 128 shr 8) + 128

                // NV21 has a plane of Y and interleaved planes of VU each sampled by a factor of 2
                //    meaning for every 4 Y pixels there are 1 V and 1 U.  Note the sampling is every other
                //    pixel AND every other scanline.
                yuv420sp[yIndex++] = (if (Y < 0) 0 else if (Y > 255) 255 else Y).toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (if (V < 0) 0 else if (V > 255) 255 else V).toByte()
                    yuv420sp[uvIndex++] = (if (U < 0) 0 else if (U > 255) 255 else U).toByte()
                }
                index++
            }
        }
    }

    companion object {
        // パラメータ定数
        private const val BATCH_SIZE = 1 //バッチサイズ
        private const val INPUT_PIXELS = 3 //入力ピクセル
        private const val INPUT_SIZE = 257 // 入力サイズ
        private const val NUM_CLASSES = 21 // クラス数
        private const val IMAGE_MEAN = 128.0f
        private const val IMAGE_STD = 128.0f
    }
}