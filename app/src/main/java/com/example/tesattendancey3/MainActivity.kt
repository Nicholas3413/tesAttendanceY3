package com.example.tesattendancey3

import android.Manifest
import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.InputType
import android.util.Pair
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import com.example.tesattendancey3.SimilarityClassifier.Recognition
import com.example.tesattendancey3.databinding.ActivityMainBinding
import com.google.android.gms.tasks.Task
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetector
import org.tensorflow.lite.Interpreter
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors



class MainActivity : AppCompatActivity(), MainCallBack {
    private var binding: ActivityMainBinding? = null
    private var viewModel: MainViewModel? = null
    private var tfLite: Interpreter? = null
    private var detector: FaceDetector? = null
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cam_face = CameraSelector.LENS_FACING_BACK
    private var registered = HashMap<String?, Recognition?>()
    private var flipX = false
    private var start = true
    private lateinit var embeddings: Array<FloatArray>

    @RequiresApi(api = Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)

        viewModel = ViewModelProvider(this).get(MainViewModel::class.java)
        viewModel!!.init(this)
        binding?.setContract(this)
        binding?.setViewModel(viewModel)
        checkCameraPermission()
        registered = viewModel!!.readFromSP()!!
        try {
            tfLite = viewModel!!.getModel(this@MainActivity)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        detector = viewModel!!.detector
        cameraBind()
    }

    override fun onActionClick() {
        val builder = AlertDialog.Builder(this@MainActivity)
        builder.setTitle("Select Action:")
        val names = arrayOf(
            "View Recognition List",
            "Save Recognitions",
            "Load Recognitions",
            "Clear All Recognitions"
        )
        builder.setItems(
            names
        ) { _: DialogInterface?, which: Int ->
            when (which) {
                0 -> displayNameListView()
                1 -> viewModel!!.insertToSP(registered, false, viewModel!!.readFromSP())
                2 -> viewModel!!.readFromSP()?.let { registered.putAll(it) }
                3 -> clearNameList()
            }
        }
        var dialog = builder.create()
        dialog.show()
    }

    override fun onCameraSwitchClick() {
        if (cam_face == CameraSelector.LENS_FACING_BACK) {
            cam_face = CameraSelector.LENS_FACING_FRONT
            flipX = true
        } else {
            cam_face = CameraSelector.LENS_FACING_BACK
            flipX = false
        }
        cameraProvider!!.unbindAll()
        cameraBind()
    }

    @SuppressLint("SetTextI18n")
    override fun onRecognizeClick() {
        if (binding?.recognize?.getText().toString().equals("Recognize")) {
            start = true
            viewModel!!.view_call(1)
        } else {
            viewModel!!.view_call(2)
        }
    }

    override fun onAddFaceClick() {
        addFace()
    }

    private fun cameraBind() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture!!.addListener({
            try {
                cameraProvider = cameraProviderFuture!!.get()
                bindPreview(cameraProvider!!)
            } catch (ignored: ExecutionException) {
            } catch (ignored: InterruptedException) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build()
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(cam_face)
            .build()
        val imageAnalysis: ImageAnalysis = ImageRecognitionUtils.getImageAnalysis(preview, binding!!)
        val executor: Executor = Executors.newSingleThreadExecutor()
        imageAnalysis.setAnalyzer(executor) { imageProxy: ImageProxy ->
            var image: InputImage? = null

            @SuppressLint("UnsafeExperimentalUsageError") val mediaImage =
                imageProxy.image
            if (mediaImage != null) {
                image = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees
                )
            }
            assert(image != null)
            @SuppressLint("SetTextI18n") val result =
                detector!!.process(image!!).addOnSuccessListener { faces: List<Face> ->
                    if (faces.size != 0) {
                        val face = faces[0]
                        val frame_bmp: Bitmap = BitmapUtils.toBitmap(mediaImage!!)
                        val rot = imageProxy.imageInfo.rotationDegrees
                        val frame_bmp1: Bitmap =
                            BitmapUtils.rotateBitmap(frame_bmp, rot, false, false)
                        val boundingBox = RectF(face.boundingBox)
                        var cropped_face: Bitmap =
                            BitmapUtils.getCropBitmapByCPU(frame_bmp1, boundingBox)
                        if (flipX) {
                            cropped_face =
                                BitmapUtils.rotateBitmap(cropped_face, 0, true, false)
                        }
                        val scaled: Bitmap =
                            BitmapUtils.getResizedBitmap(cropped_face, 112, 112)
                        if (start) {
                            recognizeImage(scaled, binding, tfLite, registered)
                        }
                        try {
                            Thread.sleep(10)
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                        }
                    } else {
                        if (registered.isEmpty()) {
                            viewModel!!.recoNameText.set("Add Face")
                        } else {
                            viewModel!!.recoNameText.set("No Face Detected")
                        }
                    }
                }
                    .addOnCompleteListener { task: Task<List<Face?>?>? -> imageProxy.close() }
        }
        cameraProvider.bindToLifecycle(
            (this as LifecycleOwner),
            cameraSelector,
            imageAnalysis,
            preview
        )
    }

    fun recognizeImage(
        bitmap: Bitmap?,
        binding: ActivityMainBinding?,
        tfLite: Interpreter?,
        registered: HashMap<String?, Recognition?>
    ) {
        val inputSize = 112
        val OUTPUT_SIZE = 192
        val IMAGE_MEAN = 128.0f
        val IMAGE_STD = 128.0f
        binding?.facePreview?.setImageBitmap(bitmap)
        val imgData: ByteBuffer =
            ImageRecognitionUtils.getImgData(inputSize, bitmap!!, IMAGE_MEAN, IMAGE_STD)
        val inputArray = arrayOf<Any>(imgData)
        val outputMap: MutableMap<Int, Any> = HashMap()
        embeddings = Array(1) { FloatArray(OUTPUT_SIZE) }
        outputMap[0] = embeddings
        tfLite!!.runForMultipleInputsOutputs(inputArray, outputMap)
        val distance: Float
        if (registered.size > 0) {
            val nearest: Pair<String, Float>? = ImageRecognitionUtils.findNearest(
                embeddings[0], registered
            )
            if (nearest != null) {
                val name = nearest.first
                distance = nearest.second
                if (distance < 1.000f) {
                    viewModel!!.recoNameText.set(name)
                } else {
                    viewModel!!.recoNameText.set("Unknown")
                }
            }
        }
    }

    private fun addFace() {
        start = false
        val builder = AlertDialog.Builder(this@MainActivity)
        builder.setTitle("Enter Name")
        val input = EditText(this@MainActivity)
        input.inputType = InputType.TYPE_CLASS_TEXT
        builder.setView(input)
        builder.setPositiveButton("ADD") { dialog: DialogInterface?, which: Int ->
            val result = Recognition(
                "0", "", -1f
            )
            result.extra = embeddings
            registered[input.text.toString()] = result
            start = true
        }
        builder.setNegativeButton("Cancel") { dialog: DialogInterface, which: Int ->
            start = true
            dialog.cancel()
        }
        builder.show()
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun checkCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), MY_CAMERA_REQUEST_CODE)
        }
    }

    private fun clearNameList() {
        val builder = AlertDialog.Builder(this@MainActivity)
        builder.setTitle("Do you want to delete all Recognitions?")
        builder.setPositiveButton("Delete All") { dialog: DialogInterface?, which: Int ->
            registered.clear()
            Toast.makeText(this@MainActivity, "Recognitions Cleared", Toast.LENGTH_SHORT).show()
        }
        viewModel!!.insertToSP(registered, true, viewModel!!.readFromSP())
        builder.setNegativeButton("Cancel", null)
        val dialog = builder.create()
        dialog.show()
    }

    private fun displayNameListView() {
        val builder = AlertDialog.Builder(this@MainActivity)
        if (registered.isEmpty()) builder.setTitle("No Faces Added!!") else builder.setTitle("Recognitions:")
        val names = arrayOfNulls<String>(registered.size)
        val checkedItems = BooleanArray(registered.size)
        var i = 0
        for ((key) in registered) {
            names[i] = key
            checkedItems[i] = false
            i = i + 1
        }
        builder.setItems(names, null)
        val dialog = builder.create()
        dialog.show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MY_CAMERA_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission granted", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val MY_CAMERA_REQUEST_CODE = 100
    }
}