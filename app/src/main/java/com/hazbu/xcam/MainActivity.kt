package com.hazbu.xcam
import android.content.Context
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
import com.hazbu.xcam.Constants.KEY_IS_ENABLED
import com.hazbu.xcam.Constants.KEY_VIDEO_PATH
import com.hazbu.xcam.Constants.PREFS_NAME
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var switchEnable: SwitchMaterial
    private lateinit var tvVideoPath: TextView
    private lateinit var btnSelectVideo: MaterialButton

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data: Intent? = result.data
            data?.data?.let { uri ->
                copyVideoToInternal(uri)
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

        switchEnable = findViewById(R.id.switch_enable)
        tvVideoPath = findViewById(R.id.tv_video_path)
        btnSelectVideo = findViewById(R.id.btn_select_video)

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            saveEnableState(isChecked)
        }

        btnSelectVideo.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            videoPickerLauncher.launch(intent)
        }
    }

    private fun copyVideoToInternal(uri: Uri) {
        try {
            val internalFile = File(filesDir, "virtual.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(internalFile).use { output ->
                    input.copyTo(output)
                }
            }
            saveVideoPath(internalFile.absolutePath)
            Toast.makeText(this, "Video successfully imported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to import video: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(KEY_IS_ENABLED, false)
        val videoPath = prefs.getString(KEY_VIDEO_PATH, "") ?: ""

        switchEnable.isChecked = isEnabled
        tvVideoPath.text = if (videoPath.isEmpty()) getString(R.string.label_none) else videoPath
    }

    private fun saveEnableState(isEnabled: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(KEY_IS_ENABLED, isEnabled)
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
