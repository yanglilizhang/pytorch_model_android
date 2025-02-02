package com.example.pytorch_model_prepare

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.media.Image
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.camera.core.*
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.pytorch.Tensor
import java.util.*
import java.util.concurrent.ExecutorService
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.pytorch_model_prepare.databinding.ActivityMainBinding
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * https://proandroiddev.com/chaquopy-using-python-in-android-apps-dd5177c9ab6b
 * https://chaquo.com/chaquopy/ ！！！！
 * https://chaquo.com/chaquopy/doc/current/android.html
 * https://medium.com/@umerfarooq_26378/tools-to-run-python-on-android-9060663972b4
 * https://betterstack.com/community/questions/how-to-run-python-on-android/
 * https://ourcodeworld.com/articles/read/1656/how-to-use-chaquopy-to-run-python-code-and-obtain-its-output-using-java-in-your-android-app
 */
class MainActivity : AppCompatActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        if (allPermissionsGranted()) {
            Log.d("output", "all permissions granted")
            startCamera()

        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // working code from here

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null


    private lateinit var cameraExecutor: ExecutorService
    private fun convertBitmapToFloatArray(bitmap: Any): FloatArray {
        var intValues = IntArray(256 * 256)
        var floatValues = FloatArray(256 * 256 * 3)
        var bitmap = bitmap as Bitmap
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in 0..255) {
            for (j in 0..255) {
                val pixelValue = intValues[i * 256 + j]
                floatValues[i * 256 + j] = ((pixelValue shr 16 and 0xFF) - 0) / 255.0f
                floatValues[256 * 256 + i * 256 + j] = ((pixelValue shr 8 and 0xFF) - 0) / 255.0f
                floatValues[2 * 256 * 256 + i * 256 + j] = ((pixelValue and 0xFF) - 0) / 255.0f
            }
        }
        return floatValues

    }

    private fun assetFilePath(s: String): String? {
        val file = File(filesDir, s)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }
        try {
            applicationContext.assets.open(s).use { `is` ->
                FileOutputStream(file).use { os ->
                    val buffer = ByteArray(4 * 1024)
                    var read: Int
                    while (`is`.read(buffer).also { read = it } != -1) {
                        os.write(buffer, 0, read)
                    }
                    os.flush()
                }
                return file.absolutePath
            }
        } catch (e: IOException) {
            Log.e("pyto", "Error process asset $s to file path")
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startCamera() {
        Log.d("output", "camera started")
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        } catch (e: Exception) {
            Log.d("output", e.toString())
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
//                .setTargetResolution( Size(640, 480))
                .build().also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            // image analysis

            val imageAnalyzer = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetResolution(Size(256, 256))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

            val analyzer = ImageAnalysis.Analyzer { image_input ->

                // load the model
//                var module = LiteModuleLoader.load(assetFilePath("model.ptl"))
                var module = LiteModuleLoader.load(assetFilePath("model_fixes_quantized.ptl"))
                Log.d("output", "model loaded")
                val planes = image_input.planes
                val buffer = planes[0].buffer
                val bytes = ByteArray(buffer.capacity())

                buffer.get(bytes)
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val width = image_input.width
                val height = image_input.height
//                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                buffer.rewind()
//                bitmap.copyPixelsFromBuffer(buffer)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                image_input.close()

                //center crop the image to 400x400
                val croppedBitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
                Log.d("output", croppedBitmap.width.toString())
                Log.d("output", croppedBitmap.height.toString())

                val xOffset = (bitmap.width - 400) / 2
                val yOffset = (bitmap.height - 400) / 2

                val canvas = Canvas(croppedBitmap)
                //crop postion
                canvas.drawBitmap(bitmap, Rect(xOffset, yOffset, xOffset + 400, yOffset + 400), Rect(0, 0, 400, 400), null)
//                 resize the image to 256x256
                val resizedBitmap = Bitmap.createScaledBitmap(croppedBitmap, 256, 256, true)
                val normalizedBitmap = resizedBitmap.copy(Bitmap.Config.ARGB_8888, true)

                val normalizedBitmap2 = findViewById<ImageView>(R.id.normalizedBitmap)
                runOnUiThread {
                    normalizedBitmap2.setImageBitmap(croppedBitmap)
                }

//              convert the image to a float array
                var imgData: FloatArray = convertBitmapToFloatArray(normalizedBitmap)

                Log.d("output", "image loaded")
//        convert the image to tensor of shape [1, 3, 256, 256]

                var inputTensor = Tensor.fromBlob(imgData, longArrayOf(1, 3, 256, 256))

                // run the model
//                var outputTensor = module.forward(IValue.from(inputTensor)).toTensor()
                val outputTensor = module.forward(IValue.from(inputTensor)).toTuple()
                val out_3d = outputTensor[0].toTensor()
                val out_2d = outputTensor[1].toTensor()
                Log.d("output", out_3d.toString())
//        convert the tensor to array
                val out_3d_array = out_3d.dataAsFloatArray


                val py = Python.getInstance()
                val obj: PyObject = py.getModule("plot")
//        convert out_3d tensor to numpy array keeping the shape

                val obj1: PyObject = obj.callAttr("main", out_3d_array, out_2d)
                Log.d("output", "python script loaded")
                val str = obj1.toString()
//        Log.d("output", str)
                try {
                    val data: ByteArray = Base64.getDecoder().decode(str)
                    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                    val image_to_view = findViewById<ImageView>(R.id.imageView)
                    image_to_view.setImageBitmap(bmp)
                } catch (e: Exception) {
                    Log.d("output", e.toString())
                }
                val data: ByteArray = Base64.getDecoder().decode(str)
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                val image_to_view = findViewById<ImageView>(R.id.imageView)
                image_to_view.setImageBitmap(bmp)
//                image_input.close()
            }
            imageAnalyzer.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer)


            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
                Log.d("output", "life cycle bound")

            } catch (exc: Exception) {
                Log.d("hello", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
