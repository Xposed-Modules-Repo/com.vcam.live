package com.hazbu.xcam

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.hazbu.xcam.Constants.KEY_IS_MIRRORED
import com.hazbu.xcam.Constants.KEY_ROTATION_ANGLE
import com.hazbu.xcam.Constants.KEY_MEDIA_PATH
import com.hazbu.xcam.Constants.PREFS_NAME
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var ivPreview: ImageView
    private lateinit var tvNoPreview: TextView
    private lateinit var btnDeleteMedia: MaterialButton
    private lateinit var btnMirror: MaterialButton
    private lateinit var btnRotateLeft: MaterialButton
    private lateinit var btnRotateRight: MaterialButton
    private lateinit var btnSelectVideo: MaterialButton

    private var isMirrored = false
    private var rotationAngle = 0

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data: Intent? = result.data
            data?.data?.let { uri ->
                copyMediaToInternal(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        setupWindowInsets()
        setupUI()
        loadSettings()
    }

    private fun setupWindowInsets() {
        val mainView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val basePadding = (24 * density).toInt()
            v.setPadding(
                systemBars.left + basePadding,
                systemBars.top + basePadding,
                systemBars.right + basePadding,
                systemBars.bottom + basePadding
            )
            insets
        }
    }

    private fun setupUI() {
        val tvTitle = findViewById<TextView>(R.id.tv_title)
        setupTitleSpannable(tvTitle)

        ivPreview = findViewById(R.id.iv_preview)
        tvNoPreview = findViewById(R.id.tv_no_preview)
        btnDeleteMedia = findViewById(R.id.btn_delete_media)
        btnMirror = findViewById(R.id.btn_mirror)
        btnRotateLeft = findViewById(R.id.btn_rotate_left)
        btnRotateRight = findViewById(R.id.btn_rotate_right)
        btnSelectVideo = findViewById(R.id.btn_select_video)

        btnMirror.setOnClickListener {
            isMirrored = !isMirrored
            saveSettings()
            updatePreviewTransform()
        }

        btnRotateLeft.setOnClickListener {
            rotationAngle = (rotationAngle - 90 + 360) % 360
            saveSettings()
            updatePreviewTransform()
        }

        btnRotateRight.setOnClickListener {
            rotationAngle = (rotationAngle + 90) % 360
            saveSettings()
            updatePreviewTransform()
        }

        btnDeleteMedia.setOnClickListener {
            deleteMedia()
        }

        btnSelectVideo.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            }
            videoPickerLauncher.launch(intent)
        }
    }

    private fun copyMediaToInternal(uri: Uri) {
        try {
            val mimeType = contentResolver.getType(uri)
            val extension = if (mimeType?.startsWith("image") == true) "jpg" else "mp4"
            
            // Clean up existing files
            File(filesDir, "virtual.mp4").delete()
            File(filesDir, "virtual.jpg").delete()
            
            val internalFile = File(filesDir, "virtual.$extension")
            
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(internalFile).use { output ->
                    input.copyTo(output)
                }
            }
            saveMediaPath(internalFile.absolutePath)
            Toast.makeText(this, R.string.toast_media_imported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            val errorMsg = getString(R.string.toast_media_import_failed, e.message)
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val mediaPath = prefs.getString(KEY_MEDIA_PATH, "") ?: ""
        isMirrored = prefs.getBoolean(KEY_IS_MIRRORED, false)
        rotationAngle = prefs.getInt(KEY_ROTATION_ANGLE, 0)

        updatePreview(mediaPath)
        updatePreviewTransform()
    }

    private fun updatePreviewTransform() {
        ivPreview.scaleX = if (isMirrored) -1f else 1f
        ivPreview.rotation = rotationAngle.toFloat()
    }

    private fun updatePreview(path: String) {
        if (path.isEmpty() || !File(path).exists()) {
            ivPreview.setImageBitmap(null)
            tvNoPreview.visibility = View.VISIBLE
            btnDeleteMedia.visibility = View.GONE
            return
        }

        tvNoPreview.visibility = View.GONE
        btnDeleteMedia.visibility = View.VISIBLE
        try {
            if (path.endsWith(".mp4", ignoreCase = true)) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                val bitmap = retriever.getFrameAtTime(1000000) // 1 second in
                ivPreview.setImageBitmap(bitmap)
                retriever.release()
            } else {
                val bitmap = BitmapFactory.decodeFile(path)
                ivPreview.setImageBitmap(bitmap)
            }
        } catch (_: Exception) {
            ivPreview.setImageBitmap(null)
            tvNoPreview.visibility = View.VISIBLE
        }
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(KEY_IS_MIRRORED, isMirrored)
            putInt(KEY_ROTATION_ANGLE, rotationAngle)
        }
    }

    private fun saveMediaPath(path: String) {
        isMirrored = false
        rotationAngle = 0
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_MEDIA_PATH, path)
            putBoolean(KEY_IS_MIRRORED, isMirrored)
            putInt(KEY_ROTATION_ANGLE, rotationAngle)
        }
        updatePreview(path)
        updatePreviewTransform()
    }

    private fun deleteMedia() {
        File(filesDir, "virtual.mp4").delete()
        File(filesDir, "virtual.jpg").delete()
        saveMediaPath("")
        Toast.makeText(this, R.string.toast_media_removed, Toast.LENGTH_SHORT).show()
    }

    private fun setupTitleSpannable(tvTitle: TextView) {
        val titleText = tvTitle.text.toString()
        val spannable = SpannableStringBuilder(titleText)
        val primaryColor = MaterialColors.getColor(
            this,
            androidx.appcompat.R.attr.colorPrimary,
            "#FF6200EE".toColorInt()
        )
        spannable.setSpan(
            ForegroundColorSpan(primaryColor),
            0, 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvTitle.text = spannable
    }
}
