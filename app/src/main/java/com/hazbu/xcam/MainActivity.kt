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
import android.widget.LinearLayout
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
    private lateinit var tvModuleStatus: TextView
    private lateinit var layoutScopedApps: LinearLayout

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
        updateModuleStatusUI()
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        updateModuleStatusUI()
    }

    private fun updateModuleStatusUI() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val localScopes = prefs.getStringSet(Constants.KEY_SCOPED_APPS, emptySet()) ?: emptySet()
        
        if (isModuleActive() || localScopes.isNotEmpty()) {
            tvModuleStatus.text = getString(R.string.status_module_active)
            tvModuleStatus.setTextColor(0xFF4CAF50.toInt()) // Green
            refreshLSPosedScope()
        } else {
            tvModuleStatus.text = getString(R.string.status_module_inactive)
            tvModuleStatus.setTextColor(0xFFFF5252.toInt()) // Red
            layoutScopedApps.visibility = View.GONE
        }
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
        tvModuleStatus = findViewById(R.id.tv_module_status)
        layoutScopedApps = findViewById(R.id.layout_scoped_apps)

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

        val activeColor = MaterialColors.getColor(btnMirror, androidx.appcompat.R.attr.colorPrimary)
        val activeIconColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorOnPrimary, android.graphics.Color.WHITE)
        val inactiveColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorSecondaryContainer, android.graphics.Color.LTGRAY)
        val inactiveIconColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorOnSecondaryContainer, android.graphics.Color.DKGRAY)
        
        btnMirror.setBackgroundColor(if (isMirrored) activeColor else inactiveColor)
        btnMirror.iconTint = android.content.res.ColorStateList.valueOf(if (isMirrored) activeIconColor else inactiveIconColor)
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
                val bitmap = retriever.getFrameAtTime(1000000)
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

    private fun isModuleActive(): Boolean {
        // Daftar "Pintu Belakang" (Authorities) resmi dari berbagai framework
        val authorities = arrayOf(
            "org.lsposed.manager.provider",         // LSPosed (Root)
            "org.lsposed.lspatch.manager.provider",  // LSPatch (Standard)
            "top.nkbe.npatch.provider",              // nPatcher (Specific)
            "com.github.metasploit.lspatch.manager.provider" // LSPatch Fork
        )

        for (auth in authorities) {
            try {
                // Menanyakan framework: "Apakah modul saya (xCam) aktif?"
                val uri = Uri.parse("content://$auth/module/$packageName/scope")
                val cursor = contentResolver.query(uri, null, null, null, null)
                if (cursor != null) {
                    cursor.close()
                    android.util.Log.d("xCam", "Framework detected via authority: $auth")
                    return true // Jika framework merespons, berarti modul aktif!
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun getOfficialScope(): List<String> = emptyList()

    private fun refreshLSPosedScope() {
        layoutScopedApps.removeAllViews()
        
        // 1. Try Official Method (Hooked by module in API 101)
        val officialScope = getOfficialScope()
        if (officialScope.isNotEmpty()) {
            officialScope.forEach { pkgName ->
                if (pkgName != packageName) addAppIconToLayout(pkgName)
            }
            layoutScopedApps.visibility = View.VISIBLE
            return
        }

        // 2. Fallback to reverse-engineered method
        val authorities = arrayOf(
            "org.lsposed.manager.provider",        // LSPosed (Root)
            "org.lsposed.lspatch.manager.provider", // LSPatch (Standard)
            "top.nkbe.npatch.provider",             // nPatcher (Specific)
            "com.github.metasploit.lspatch.manager.provider" // Alternative LSPatch
        )

        var foundInProvider = false

        for (authority in authorities) {
            try {
                val uri = Uri.parse("content://$authority/module/${packageName}/scope")
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val pkgIndex = cursor.getColumnIndex("package_name")
                    if (pkgIndex != -1) {
                        while (cursor.moveToNext()) {
                            val pkgName = cursor.getString(pkgIndex)
                            if (pkgName != packageName) {
                                addAppIconToLayout(pkgName)
                                foundInProvider = true
                            }
                        }
                    }
                }
                if (foundInProvider) {
                    android.util.Log.d("xCam", "Found scope data in provider: $authority")
                    break 
                }
            } catch (_: Exception) {}
        }

        // Fallback to local registration if providers are not accessible
        if (!foundInProvider) {
            try {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val localScopes = prefs.getStringSet(Constants.KEY_SCOPED_APPS, emptySet())
                localScopes?.forEach { pkgName ->
                    if (pkgName != packageName) addAppIconToLayout(pkgName)
                }
            } catch (_: Exception) {}
        }

        if (layoutScopedApps.childCount > 0) {
            layoutScopedApps.visibility = View.VISIBLE
        }
    }

    private fun addAppIconToLayout(pkgName: String) {
        try {
            val icon = packageManager.getApplicationIcon(pkgName)
            val size = (32 * resources.displayMetrics.density).toInt()
            val imageView = ImageView(this).apply {
                val params = LinearLayout.LayoutParams(size, size)
                params.setMargins(12, 0, 12, 0)
                layoutParams = params
                setImageDrawable(icon)
                contentDescription = pkgName
                setOnClickListener {
                    Toast.makeText(this@MainActivity, pkgName, Toast.LENGTH_SHORT).show()
                }
            }
            layoutScopedApps.addView(imageView)
        } catch (_: Exception) {}
    }
}
