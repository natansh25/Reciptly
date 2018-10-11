package infinity1087.android.com.myapplication

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.annotation.RequiresApi
import android.util.Log
import android.view.View
import android.widget.Toast
import com.google.firebase.ml.custom.*
import com.otaliastudios.cameraview.CameraListener
import kotlinx.android.synthetic.main.activity_main.*
import com.google.firebase.ml.custom.model.FirebaseCloudModelSource
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import javax.security.auth.Subject.doAs


val recipts: Array<String> = arrayOf("target","walmart");



class MainActivity : AppCompatActivity() {

    companion object {
        /** Dimensions of inputs.  */
        const val DIM_IMG_SIZE_X = 224
        const val DIM_IMG_SIZE_Y = 224
        const val DIM_BATCH_SIZE = 1
        const val DIM_PIXEL_SIZE = 3
        const val IMAGE_MEAN = 128
        private const val IMAGE_STD = 128.0f
    }

    private lateinit var currentBitmap: Bitmap
    private val intValues = IntArray(DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y)
    private var imgData: ByteBuffer =ByteBuffer.allocateDirect(
            4 * DIM_BATCH_SIZE * DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y * DIM_PIXEL_SIZE)


    private lateinit var fireBaseInterpreter: FirebaseModelInterpreter
    private lateinit var inputOutputOptions: FirebaseModelInputOutputOptions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        camera.setLifecycleOwner(this);

        // Build a FirebaseCloudModelSource object by specifying the name you assigned the model
        // when you uploaded it in the Firebase console.
        val cloudSource = FirebaseCloudModelSource.Builder("test1")
                .enableModelUpdates(true)
                //.setInitialDownloadConditions(conditions)
                //.setUpdatesDownloadConditions(conditions)
                .build()
        FirebaseModelManager.getInstance().registerCloudModelSource(cloudSource)


        val options = FirebaseModelOptions.Builder()
                .setCloudModelName("test1")
                //.setLocalModelName("my_local_model")
                .build()
         fireBaseInterpreter = FirebaseModelInterpreter.getInstance(options)!!

        //Specify the input and outputs of the model
        inputOutputOptions = FirebaseModelInputOutputOptions.Builder()
                .setInputFormat(0, FirebaseModelDataType.FLOAT32, intArrayOf(1, 224, 224, 3))
                .setOutputFormat(0, FirebaseModelDataType.FLOAT32, intArrayOf(1, 2))
                .build()


        capture.setOnClickListener {
            camera.capturePicture()
            camera.addCameraListener(object : CameraListener() {
                @RequiresApi(Build.VERSION_CODES.N)
                override fun onPictureTaken(jpeg: ByteArray) {
                    //isRefreshVisible = true
                    //convertByteArrayToBitmap(jpeg)
                    Log.d("image", jpeg.toString())
                    convertByteArrayToBitmap(jpeg)
                }
            })
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun convertByteArrayToBitmap(byteArray: ByteArray) {

        //Handle this shit in bg
        doAsync {
            val exifInterface = ExifInterface(ByteArrayInputStream(byteArray))
            val orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)

            //to fix images coming out to be rotated
            //https://github.com/google/cameraview/issues/22#issuecomment-269321811
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90F)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180F)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270F)
            }
            //Create a new bitmap with fixed rotation
            //Crop a part of image that's inside viewfinder and perform detection on that image
            //https://stackoverflow.com/a/8180576/5471095
            //TODO : Need to find a better way to do this than creating a new bitmap
            val cropX = (bitmap.width * 0.2).toInt()
            val cropY = (bitmap.height * 0.25).toInt()

            currentBitmap = Bitmap.createBitmap(bitmap, cropX, cropY, bitmap.width - 2 * cropX, bitmap.height - 2 * cropY, m, true)
            //free up the original bitmap
            bitmap.recycle()

            //create a scaled bitmap for Tensorflow
            val scaledBitmap = Bitmap.createScaledBitmap(currentBitmap, 224, 224, false)
            uiThread {
                getPokemonFromBitmap(scaledBitmap)
            }
        }
    }

    private fun getPokemonFromBitmap(bitmap: Bitmap?) {
        val inputs = FirebaseModelInputs.Builder()
                .add(convertBitmapToByteBuffer(bitmap)) // add() as many input arrays as your model requires
                .build()

        fireBaseInterpreter.run(inputs, inputOutputOptions)
                ?.addOnSuccessListener {
                    //val pokeList = mutableListOf<Pokemon>()
                    /**
                     * Run a foreach loop through the output float array containing the probabilities
                     * corresponding to each label
                     * @see pokeArray to know what labels are supported
                     */
                    it.getOutput<Array<FloatArray>>(0)[0].forEachIndexed { index, fl ->
                        //Only consider a pokemon when the accuracy is more than 20%
                        if (fl > .20)
                            //pokeList.add(Pokemon(pokeArray[index], fl))
                            Log.d("resulto", recipts[index] + " = "+ fl)
                    }
                   /* itemAdapter.setList(pokeList)
                    sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED*/
                }
                ?.addOnFailureListener {
                    it.printStackTrace()
                    Toast.makeText(this,"Sorry, there was an error",Toast.LENGTH_SHORT).show();
                }



    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap?): ByteBuffer {
        //Clear the ByteBuffer for a new image
        imgData.rewind()
        bitmap?.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        // Convert the image to floating point.
        var pixel = 0
        for (i in 0 until DIM_IMG_SIZE_X) {
            for (j in 0 until DIM_IMG_SIZE_Y) {
                val currPixel = intValues[pixel++]
                imgData.putFloat(((currPixel shr 16 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                imgData.putFloat(((currPixel shr 8 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                imgData.putFloat(((currPixel and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
            }
        }
        return imgData
    }


    override fun onResume() {
        super.onResume()
        camera.start()
    }

    override fun onPause() {
        super.onPause()
        camera.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        camera.destroy()
    }


}
