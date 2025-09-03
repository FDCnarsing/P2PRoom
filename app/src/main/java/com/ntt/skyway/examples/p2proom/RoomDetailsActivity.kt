package com.ntt.skyway.examples.p2proom

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.common.util.concurrent.ListenableFuture
import com.ntt.skyway.core.content.Stream
import com.ntt.skyway.core.content.local.LocalDataStream
import com.ntt.skyway.core.content.local.LocalVideoStream
import com.ntt.skyway.core.content.local.source.AudioSource
import com.ntt.skyway.core.content.local.source.CustomVideoFrameSource
import com.ntt.skyway.core.content.local.source.VideoSource
import com.ntt.skyway.core.content.local.source.DataSource
import com.ntt.skyway.core.content.remote.RemoteAudioStream
import com.ntt.skyway.core.content.remote.RemoteDataStream
import com.ntt.skyway.core.content.remote.RemoteVideoStream
import com.ntt.skyway.examples.p2proom.adapter.RecyclerViewAdapterRoomMember
import com.ntt.skyway.examples.p2proom.adapter.RecyclerViewAdapterRoomPublication
import com.ntt.skyway.examples.p2proom.databinding.ActivityRoomDetailsBinding
import com.ntt.skyway.examples.p2proom.listener.RoomPublicationAdapterListener
import com.ntt.skyway.room.RoomPublication
import com.ntt.skyway.room.RoomSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias ImageAnalysisListener = () -> Unit

class RoomDetailsActivity : AppCompatActivity() {
    private val tag = this.javaClass.simpleName
    private val scope = CoroutineScope(Dispatchers.IO)

    private lateinit var binding: ActivityRoomDetailsBinding

    private var localVideoStream: LocalVideoStream? = null
    private var localDataStream: LocalDataStream? = null

    private var recyclerViewAdapterRoomMember: RecyclerViewAdapterRoomMember? = null
    private var recyclerViewAdapterRoomPublication: RecyclerViewAdapterRoomPublication? = null

    private var source = CustomVideoFrameSource(480, 640)
    private var publication: RoomPublication? = null
    private var subscription: RoomSubscription? = null

    private var interpriter: ImageSegmentationInterpriter? = null
    private var interpriterKt: ImageSegmentor? = null

    /*** Views  */
    //private var previewView: PreviewView? = null
    private val imageView: ImageView? = null

    /*** For CameraX  */
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
   // private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var cameraExecutor: ExecutorService
    //private lateinit var cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var cameraSelector:CameraSelector
    private lateinit var cameraProviderFuture:ListenableFuture<ProcessCameraProvider>
    private val mContext: Context? = null

    //private val mTextureByteList = Stack<ByteBuffer>()
    //private val _bConnected = false



    //companion object{
    //    init {
    //        System.loadLibrary("opencv_java4");
    //    }
    //}



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        OpenCVLoader.initDebug()

        this.binding = ActivityRoomDetailsBinding.inflate(layoutInflater)
        setContentView(this.binding.root)

        initUI()

        //previewView = findViewById(R.id.local_renderer)

        interpriter = ImageSegmentationInterpriter(this@RoomDetailsActivity)
        interpriterKt = ImageSegmentor(this@RoomDetailsActivity)
        //interpriter = ImageSegmentationInterpriter(this@RoomDetailsActivity.baseContext)

        interpriter!!.setBackgroundImage(resources, R.drawable.machinami)
        interpriterKt!!.setBackgroundImage(resources, R.drawable.machinami)

        cameraExecutor = Executors.newSingleThreadExecutor()

        scope.launch(Dispatchers.Main) {
            initSurfaceViews()
        }
    }

    companion object {
        val TAG = "RoomDetailsActivity"
    }

    private fun initUI() {
        binding.memberName.text = RoomManager.localPerson?.name

        recyclerViewAdapterRoomMember = RecyclerViewAdapterRoomMember()
        binding.rvUserList.layoutManager = LinearLayoutManager(this)
        binding.rvUserList.adapter = recyclerViewAdapterRoomMember

        RoomManager.localPerson?.onPublicationListChangedHandler = {
            Log.d(tag, "localPerson onPublicationListChangedHandler")
        }

        RoomManager.localPerson?.onSubscriptionListChangedHandler = {
            Log.d(tag, "localPerson onSubscriptionListChangedHandler")
        }

        recyclerViewAdapterRoomPublication =
            RecyclerViewAdapterRoomPublication(roomPublicationAdapterListener)

        binding.rvPublicationList.layoutManager = LinearLayoutManager(this)
        binding.rvPublicationList.adapter = recyclerViewAdapterRoomPublication


        RoomManager.room?.members?.toMutableList()
            ?.let { recyclerViewAdapterRoomMember?.setData(it) }
        RoomManager.room?.publications?.toMutableList()
            ?.let { recyclerViewAdapterRoomPublication?.setData(it) }

        initButtons()
        initRoomFunctions()
    }


    private fun initSurfaceViews() {
        //binding.localRenderer.setup()
        binding.remoteRenderer.setup()

        /*/val device = CameraSource.getFrontCameras(applicationContext).first()
        CameraSource.startCapturing(
            applicationContext,
            device,
            CameraSource.CapturingOptions(800, 800)
        )*/


        //source = CustomVideoFrameSource(480, 640)
        localVideoStream = source.createStream()
        startCamera()
        //localVideoStream?.addRenderer(binding.localRenderer)

//        localVideoStream = CameraSource.createStream()
//        localVideoStream?.addRenderer(binding.localRenderer)
    }

    private fun initButtons() {
        binding.btnLeaveRoom.setOnClickListener {
            scope.launch(Dispatchers.Main) {
                RoomManager.room!!.leave(RoomManager.localPerson!!)
                finish()
            }
        }

        binding.btnPublish.setOnClickListener {
            publishCameraVideoStream()
        }

        binding.btnAudio.setOnClickListener {
            publishAudioStream()
        }

        binding.btnPublishData.setOnClickListener {
            publishDataStream()
        }

        binding.btnSendData.setOnClickListener {
            val text = binding.textData.text.toString()
            localDataStream?.write(text)
        }
    }

    private fun initRoomFunctions() {
        RoomManager.room?.apply {
            onMemberListChangedHandler = {
                Log.d(tag, "$tag onMemberListChanged")
                runOnUiThread {
                    RoomManager.room?.members?.toMutableList()
                        ?.let { recyclerViewAdapterRoomMember?.setData(it) }
                }
            }

            onPublicationListChangedHandler = {
                Log.d(tag, "$tag onPublicationListChanged")
                runOnUiThread {
                    RoomManager.room?.publications?.toMutableList()
                        ?.let { recyclerViewAdapterRoomPublication?.setData(it) }
                }
            }

            onStreamPublishedHandler = {
                Log.d(tag, "$tag onStreamPublished: ${it.id}")
            }
        }
    }

    private fun publishCameraVideoStream() {
        Log.d(tag, "publishCameraVideoStream()")
        scope.launch(Dispatchers.Main) {
            publication = localVideoStream?.let { RoomManager.localPerson?.publish(it) }
            Log.d(tag, "publication state: ${publication?.state}")

            publication?.onEnabledHandler = {
                Log.d(tag, "onEnabledHandler ${publication?.state}")
            }

            publication?.onDisabledHandler = {
                Log.d(tag, "onDisabledHandler ${publication?.state}")
            }
        }
    }

    private fun publishAudioStream() {
        Log.d(tag, "publishAudioStream()")
        AudioSource.start()
        val localAudioStream = AudioSource.createStream()
        val options = RoomPublication.Options()
        scope.launch(Dispatchers.Main) {
            publication = RoomManager.localPerson?.publish(localAudioStream, options)
        }
    }

    private fun publishDataStream() {
        val localDataSource = DataSource()
        localDataStream = localDataSource.createStream()
        val options = RoomPublication.Options()
        scope.launch(Dispatchers.Main) {
            publication = RoomManager.localPerson?.publish(localDataStream!!, options)
        }
    }
    

    private var roomPublicationAdapterListener: RoomPublicationAdapterListener = object: RoomPublicationAdapterListener{
        override fun onUnPublishClick(publication: RoomPublication) {
            scope.launch(Dispatchers.Default) {
                RoomManager.localPerson?.unpublish(publication)
            }
        }

        override fun onSubscribeClick(publicationId: String) {
            scope.launch(Dispatchers.Main) {
                subscription = RoomManager.localPerson?.subscribe(publicationId)
                when (subscription?.contentType) {
                    Stream.ContentType.VIDEO -> {
                        (subscription?.stream as RemoteVideoStream).addRenderer(binding. remoteRenderer)
                    }
                    Stream.ContentType.AUDIO -> {
                        (subscription?.stream as RemoteAudioStream)
                    }
                    Stream.ContentType.DATA -> {
                        (subscription?.stream as RemoteDataStream).onDataHandler = {
                            Log.d(tag, "data received: $it")
                        }

                        (subscription?.stream as RemoteDataStream).onDataBufferHandler = {
                            Log.d(tag, "data received byte: ${it.contentToString()}")
                            Log.d(tag, "data received string: ${String(it)}")
                        }
                    }
                    null -> {

                    }
                }
            }
        }

        override fun onUnSubscribeClick() {
            scope.launch(Dispatchers.Main) {
                RoomManager.localPerson?.unsubscribe(subscription?.id!!)
            }
        }

    }

    inner class MyImageAnalyzer(private val listener: ImageAnalysisListener) : ImageAnalysis.Analyzer {
        @SuppressLint("UnsafeExperimentalUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            listener()
            imageProxy.close()
        }
    }

    inner class MyImageAnalyzeKt : ImageAnalysis.Analyzer {
        // bitmap関連
        private var nv21: ByteArray? = null
        private var bmp: Bitmap? = null

        @SuppressLint("UnsafeExperimentalUsageError")
        override fun analyze(image: ImageProxy) {
            scope.launch(Dispatchers.Main) {
                //nv21 = interpriterKt?.process(image, 2)
                //bmp = getBitmapImageFromYUV(nv21, image.height, image.width)
                //bmp = interpriterKt?.processBitmap(image, 2)
                bmp = interpriterKt?.processPixelPlateBlur(image, 2)
                bmp?.let {
                    this@RoomDetailsActivity.runOnUiThread {
                        binding.imageView.setImageBitmap(it)
                    }
                    source.updateFrame(it, 0)
                }

                image.close()
            }
        }
    }


    private fun startCamera() {
        cameraProviderFuture =
            ProcessCameraProvider.getInstance(this@RoomDetailsActivity)
        cameraSelector =
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

        //MyImageAnalyzeKt

        imageAnalysis = ImageAnalysis.Builder().build()
        imageAnalysis!!.setAnalyzer(Executors.newSingleThreadExecutor(),MyImageAnalyzeKt())

        /*imageAnalysis = ImageAnalysis.Builder()
            // enable the following line if RGBA output is needed.
            // .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            //.setTargetResolution(Size(480, 640))
            //.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().apply {
            setAnalyzer(Executors.newSingleThreadExecutor(), MyImageAnalyzer {

                scope.launch(Dispatchers.Main) {
                    val bitmap = binding.localRenderer.bitmap
                    val img = Mat()
                    bitmap?.let {
                        Utils.bitmapToMat(bitmap, img)
                        val bmp = interpriter?.processMat(img, 2)
                        bitmap?.recycle()
                        bmp?.let {
                            binding.imageView.setImageBitmap(bmp)
                            source.updateFrame(it, 0)
                        }
                    }
                }

            })
        }*/

        val context: Context = this@RoomDetailsActivity
        //val context: Context = this@RoomDetailsActivity.applicationContext
        //val context: Context = this@RoomDetailsActivity.baseContext
        cameraProviderFuture.addListener({
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                preview = Preview.Builder().build().also{
                    it.setSurfaceProvider(binding.localRenderer?.surfaceProvider)
                }

                try{
                    // clear all the previous use cases first.
                    cameraProvider.unbindAll()

                   // imageAnalysis = ImageAnalysis.Builder().build()
                    //imageAnalysis?.setAnalyzer(cameraExecutor, MyImageAnalyzer())
                    camera = cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        imageAnalysis,
                        preview
                    )

                } catch (e: Exception) {
                    Log.d(TAG, "Use case binding failed")
                }
        }, ContextCompat.getMainExecutor(this@RoomDetailsActivity))
    }

    fun getBitmapImageFromYUV(
        data: ByteArray?,
        width: Int,
        height: Int
    ): Bitmap? {
        val yuvimage =
            YuvImage(data, ImageFormat.NV21, width, height, null)
        val baos = ByteArrayOutputStream()
        yuvimage.compressToJpeg(Rect(0, 0, width, height), 80, baos)
        val jdata = baos.toByteArray()
        val bitmapFatoryOptions =
            BitmapFactory.Options()
        bitmapFatoryOptions.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeByteArray(jdata, 0, jdata.size, bitmapFatoryOptions)
    }

    fun Image.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer // Y
        val vuBuffer = planes[2].buffer // VU

        val ySize = yBuffer.remaining()
        val vuSize = vuBuffer.remaining()

        val nv21 = ByteArray(ySize + vuSize)

        yBuffer.get(nv21, 0, ySize)
        vuBuffer.get(nv21, ySize, vuSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 50, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    fun ImageProxy.convertImageProxyToBitmap(): Bitmap {
        val buffer = planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

}
