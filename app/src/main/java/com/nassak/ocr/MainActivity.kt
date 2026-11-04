package com.nassak.ocr

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.nassak.ocr.databinding.ActivityMainBinding
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var cameraFile: File? = null

    companion object {
        private const val REQUEST_CAMERA = 101
        private const val REQUEST_IMAGE = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCamera.setOnClickListener { openCamera() }
        binding.btnGallery.setOnClickListener { openGallery() }
        binding.btnCopy.setOnClickListener { copyText() }
        binding.btnShare.setOnClickListener { shareText() }
        binding.btnClear.setOnClickListener {
            binding.etResult.text?.clear()
            showStatus("تم مسح النص")
        }
    }

    private fun openCamera() {
        try {
            val file = File(getExternalFilesDir("Pictures"), "nassak_capture.jpg")
            file.parentFile?.mkdirs()
            cameraFile = file
            val outputUri = FileProvider.getUriForFile(
                this,
                "$packageName.provider",
                file
            )
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            startActivityForResult(intent, REQUEST_CAMERA)
        } catch (e: Exception) {
            showStatus("تعذر فتح الكاميرا: ${e.localizedMessage ?: "خطأ غير معروف"}")
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    @Deprecated("Legacy activity result API retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_CAMERA -> {
                val file = cameraFile
                val bitmap = if (file != null && file.exists() && file.length() > 0) {
                    decodeFile(file)
                } else {
                    data?.extras?.get("data") as? Bitmap
                }
                if (bitmap != null) processImage(bitmap)
                else showStatus("لم تصل صورة من الكاميرا. جرّب اختيار صورة من الملفات.")
            }
            REQUEST_IMAGE -> data?.data?.let { loadImage(it) }
        }
    }

    private fun decodeFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun loadImage(uri: Uri) {
        showStatus("جارٍ تحميل الصورة...")
        executor.execute {
            try {
                val bitmap = contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        BitmapFactory.Options().apply {
                            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
                        }
                    )
                }
                if (bitmap == null) showStatus("تعذر قراءة الصورة")
                else runOnUiThread { processImage(bitmap) }
            } catch (e: Exception) {
                showStatus("حدث خطأ أثناء تحميل الصورة")
            }
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w > 2800 || h > 2800) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun ensureTessdata(): File {
        val baseDir = File(filesDir, "tesseract")
        val tessdataDir = File(baseDir, "tessdata")
        if (!tessdataDir.exists()) tessdataDir.mkdirs()
        assets.list("tessdata")?.forEach { name ->
            val target = File(tessdataDir, name)
            if (!target.exists() || target.length() == 0L) {
                assets.open("tessdata/$name").use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
        }
        return baseDir
    }

    private fun processImage(bitmap: Bitmap) {
        showStatus("جارٍ استخراج النصوص العربية والإنجليزية...")
        executor.execute {
            var tess: TessBaseAPI? = null
            try {
                val baseDir = ensureTessdata()
                val dataDir = File(baseDir, "tessdata")
                val hasArabic = File(dataDir, "ara.traineddata").exists()
                val hasEnglish = File(dataDir, "eng.traineddata").exists()
                if (!hasArabic && !hasEnglish) {
                    showStatus("ملفات اللغات غير موجودة. أعد رفع المشروع وشغّل سير العمل المتضمن تنزيل ملفات OCR.")
                    return@execute
                }
                val languages = listOfNotNull(
                    if (hasArabic) "ara" else null,
                    if (hasEnglish) "eng" else null
                ).joinToString("+")
                tess = TessBaseAPI()
                if (!tess.init(baseDir.absolutePath, languages)) {
                    showStatus("تعذر تهيئة محرك OCR")
                    return@execute
                }
                tess.setImage(bitmap)
                val result = tess.uTF8Text ?: ""
                runOnUiThread {
                    binding.etResult.setText(result)
                    showStatus(if (result.isBlank()) "لم يتم العثور على نص واضح" else "اكتمل استخراج النص")
                }
            } catch (e: Exception) {
                showStatus("تعذر معالجة الصورة: ${e.localizedMessage ?: "خطأ"}")
            } finally {
                tess?.recycle()
            }
        }
    }

    private fun copyText() {
        val text = binding.etResult.text.toString()
        if (text.isBlank()) {
            toast("لا يوجد نص لنسخه")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Nassak OCR", text))
        toast("تم نسخ النص")
    }

    private fun shareText() {
        val text = binding.etResult.text.toString()
        if (text.isBlank()) {
            toast("لا يوجد نص للمشاركة")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "مشاركة النص"))
    }

    private fun showStatus(message: String) {
        runOnUiThread { binding.tvStatus.text = message }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
