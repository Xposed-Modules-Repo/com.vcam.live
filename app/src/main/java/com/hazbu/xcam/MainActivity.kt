package com.hazbu.xcam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
import com.google.android.material.switchmaterial.SwitchMaterial
import com.hazbu.xcam.Constants.KEY_IS_MIRRORED
import com.hazbu.xcam.Constants.KEY_VIDEO_PATH
import com.hazbu.xcam.Constants.PREFS_NAME
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tvVideoPath: TextView
    private lateinit var switchMirror: SwitchMaterial
    private lateinit var btnSelectVideo: MaterialButton

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
        val mainView = findViewById<android.view.View>(R.id.main)
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

        tvVideoPath = findViewById(R.id.tv_video_path)
        switchMirror = findViewById(R.id.switch_mirror)
        btnSelectVideo = findViewById(R.id.btn_select_video)

        switchMirror.setOnCheckedChangeListener { _, isChecked ->
            saveMirrorState(isChecked)
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
            saveVideoPath(internalFile.absolutePath)
            Toast.makeText(this, "Media successfully imported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to import media: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val videoPath = prefs.getString(KEY_VIDEO_PATH, "") ?: ""
        val isMirrored = prefs.getBoolean(KEY_IS_MIRRORED, false)

        tvVideoPath.text = videoPath.ifEmpty { getString(R.string.label_none) }
        switchMirror.isChecked = isMirrored
    }

    private fun saveMirrorState(isMirrored: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(KEY_IS_MIRRORED, isMirrored)
        }
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }

    private fun saveVideoPath(path: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_VIDEO_PATH, path)
        }
        tvVideoPath.text = path
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
