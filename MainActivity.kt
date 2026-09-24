package com.example.imagetopdf

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class ImageRef(val uri: Uri, val displayName: String)

class MainActivity : AppCompatActivity() {
    private val images = mutableListOf<ImageRef>()
    private lateinit var countText: TextView
    private lateinit var progressText: TextView
    private lateinit var filenameInput: EditText
    private lateinit var convertButton: Button
    private lateinit var cancelButton: Button
    private lateinit var list: LinearLayout
    private var conversionJob: Job? = null

    private val picker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val clip = data.clipData
            val selected = if (clip != null) {
                (0 until clip.itemCount).map { clip.getItemAt(it).uri }
            } else {
                listOfNotNull(data.data)
            }
            addUris(selected)
        }
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) startConversion(treeUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val title = TextView(this).apply {
            text = "Image to PDF"
            textSize = 26f
            gravity = Gravity.CENTER
        }
        root.addView(title, lp())

        val add = Button(this).apply {
            text = "Add Images"
            setOnClickListener { openPicker("image/*") }
        }
        root.addView(add, lp())

        val browse = Button(this).apply {
            text = "Browse Files"
            setOnClickListener { openPicker("*/*") }
        }
        root.addView(browse, lp())

        countText = TextView(this).apply { text = "Selected: 0" }
        root.addView(countText, lp())

        val clear = Button(this).apply {
            text = "Clear Selection"
            setOnClickListener { images.clear(); refreshList() }
        }
        root.addView(clear, lp())

        filenameInput = EditText(this).apply {
            hint = "Output filename"
            setText("converted_images.pdf")
            singleLine = true
        }
        root.addView(filenameInput, lp())

        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        convertButton = Button(this).apply {
            text = "Convert to PDF"
            setOnClickListener {
                if (images.isEmpty()) {
                    toast("Add at least one image.")
                } else {
                    folderPicker.launch(null)
                }
            }
        }
        root.addView(convertButton, lp())

        cancelButton = Button(this).apply {
            text = "Cancel"
            isEnabled = false
            setOnClickListener { conversionJob?.cancel() }
        }
        root.addView(cancelButton, lp())

        progressText = TextView(this).apply { text = "Ready" }
        root.addView(progressText, lp())

        setContentView(root)
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 8 }

    private fun openPicker(type: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = type
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        picker.launch(intent)
    }

    private fun addUris(uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val refs = uris.mapNotNull { uri ->
                runCatching {
                    val name = queryName(uri) ?: uri.lastPathSegment ?: "image"
                    ImageRef(uri, name)
                }.getOrNull()
            }
            withContext(Dispatchers.Main) {
                val existing = images.map { it.uri }.toHashSet()
                refs.filter { existing.add(it.uri) }.forEach { images.add(it) }
                refreshList()
            }
        }
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        return null
    }

    private fun refreshList() {
        countText.text = "Selected: ${images.size}"
        list.removeAllViews()
        images.take(200).forEachIndexed { i, ref ->
            list.addView(TextView(this).apply {
                text = "${i + 1}. ${ref.displayName}"
                textSize = 14f
            })
        }
        if (images.size > 200) {
            list.addView(TextView(this).apply {
                text = "… ${images.size - 200} more selected"
            })
        }
    }

    private fun startConversion(treeUri: Uri) {
        val safeName = filenameInput.text.toString().trim().ifBlank { "converted_images.pdf" }
            .let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }

        conversionJob?.cancel()
        conversionJob = lifecycleScope.launch(Dispatchers.IO) {
            setBusy(true)
            try {
                val result = PdfConverter(this@MainActivity, images.toList()).convert(
                    treeUri, safeName
                ) { done, total, current ->
                    runOnUiThread {
                        progressText.text = "Processing $done / $total\n$current"
                    }
                }
                withContext(Dispatchers.Main) {
                    progressText.text = "Success: ${result.displayName}"
                    toast("PDF saved successfully.")
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    progressText.text = "Conversion cancelled."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressText.text = "Error: ${e.message ?: "Conversion failed"}"
                    toast("Conversion failed.")
                }
            } finally {
                withContext(Dispatchers.Main) { setBusy(false) }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        convertButton.isEnabled = !busy
        cancelButton.isEnabled = busy
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

class PdfConverter(
    private val activity: Activity,
    private val refs: List<ImageRef>
) {
    private val resolver = activity.contentResolver

    data class Result(val displayName: String)

    suspend fun convert(
        treeUri: Uri,
        fileName: String,
        onProgress: (Int, Int, String) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val docs = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        )
        // Creating a unique output document avoids overwriting an existing file unexpectedly.
        val finalUri = android.provider.DocumentsContract.createDocument(
            resolver, treeUri, "application/pdf", fileName
        ) ?: throw IOException("Could not create output PDF.")

        val tempFile = File(activity.cacheDir, "pdf_${System.currentTimeMillis()}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                val document = PdfDocument()
                try {
                    refs.forEachIndexed { index, ref ->
                        ensureActive()
                        onProgress(index, refs.size, ref.displayName)
                        val bitmap = decodeNormalized(ref.uri)
                        try {
                            val w = bitmap.width
                            val h = bitmap.height
                            if (w <= 0 || h <= 0) throw IOException("Invalid image dimensions.")

                            // Android PdfDocument uses points for page dimensions.
                            // 1 pixel = 1 point as required. Android's practical page size
                            // must fit in its supported integer range.
                            if (w > 14400 || h > 14400) {
                                throw IOException(
                                    "Image is too large for a PDF page without scaling: ${w}x${h}"
                                )
                            }

                            val pageInfo = PdfDocument.PageInfo.Builder(w, h, index + 1).create()
                            val page = document.startPage(pageInfo)
                            try {
                                val canvas: Canvas = page.canvas
                                val src = Rect(0, 0, w, h)
                                val dst = Rect(0, 0, w, h)

                                // Exact edge-to-edge placement. No crop, cover, padding, or margin.
                                canvas.drawBitmap(bitmap, src, dst, null)

                                // Mathematical validation: same rectangle and aspect ratio.
                                val ratioA = w.toDouble() / h.toDouble()
                                val ratioB = pageInfo.pageWidth.toDouble() /
                                    pageInfo.pageHeight.toDouble()
                                if (kotlin.math.abs(ratioA - ratioB) > 1e-9) {
                                    throw IOException("Page aspect-ratio validation failed.")
                                }
                                document.finishPage(page)
                            } catch (t: Throwable) {
                                runCatching { document.finishPage(page) }
                                throw t
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    onProgress(refs.size, refs.size, "Finalizing PDF…")
                    document.writeTo(fos)
                } finally {
                    document.close()
                }
            }

            resolver.openOutputStream(finalUri, "w")?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
            } ?: throw IOException("Could not open output stream.")

            tempFile.delete()
            Result(fileName)
        } catch (t: Throwable) {
            tempFile.delete()
            // Best-effort deletion of incomplete destination if supported.
            runCatching { DocumentsContract.deleteDocument(resolver, finalUri) }
            throw t
        }
    }

    private fun decodeNormalized(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw IOException("Unable to decode image: $uri")

        // Decode one full-resolution bitmap only. EXIF normalization is then applied.
        val raw = resolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input)
        } ?: throw IOException("Unable to decode image: $uri")

        val orientation = readExifOrientation(uri)
        return when (orientation) {
            2 -> flip(raw, true, false)
            3 -> rotate(raw, 180f)
            4 -> flip(raw, false, true)
            5 -> rotateFlip(raw, 90f, true)
            6 -> rotate(raw, 90f)
            7 -> rotateFlip(raw, 270f, true)
            8 -> rotate(raw, 270f)
            else -> raw
        }
    }

    private fun readExifOrientation(uri: Uri): Int {
        return runCatching {
            resolver.openInputStream(uri).use { input ->
                androidx.exifinterface.media.ExifInterface(input!!).getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrDefault(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
    }

    private fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also {
            if (it !== src) src.recycle()
        }
    }

    private fun flip(src: Bitmap, x: Boolean, y: Boolean): Bitmap {
        val m = Matrix().apply { postScale(if (x) -1f else 1f, if (y) -1f else 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also {
            if (it !== src) src.recycle()
        }
    }

    private fun rotateFlip(src: Bitmap, degrees: Float, flipX: Boolean): Bitmap {
        val m = Matrix().apply {
            postRotate(degrees)
            postScale(if (flipX) -1f else 1f, 1f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also {
            if (it !== src) src.recycle()
        }
    }
}
