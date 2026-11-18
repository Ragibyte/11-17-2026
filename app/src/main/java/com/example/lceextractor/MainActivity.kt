package com.example.lceextractor

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private var sourceUri: Uri? = null
    private var extracted: List<ExtractedFile> = emptyList()
    private lateinit var sourceLabel: TextView
    private lateinit var status: TextView
    private lateinit var extractButton: Button
    private lateinit var exportButton: Button
    private lateinit var endianSpinner: Spinner

    data class ExtractedFile(val name: String, val bytes: ByteArray)
    data class IndexCandidate(val endian: String, val offset: Long, val count: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        content.addView(TextView(this).apply {
            text = "LCE Savegame Extractor"
            textSize = 25f
            setTextColor(0xFF17212B.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Extract files from supported Minecraft Legacy Console Edition save files."
            textSize = 14f
            setTextColor(0xFF52606D.toInt())
            setPadding(0, dp(6), 0, dp(18))
        })

        content.addView(Button(this).apply {
            text = "1. Select save file"
            setOnClickListener { chooseSave() }
        }, fullWidth())
        sourceLabel = TextView(this).apply {
            text = "No save file selected"
            textSize = 13f
            setTextColor(0xFF52606D.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(14))
        }
        content.addView(sourceLabel)

        content.addView(TextView(this).apply {
            text = "Endianness"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        endianSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto-detect", "Big-endian (PS3 / Wii U / Xbox 360)", "Little-endian (Switch)"))
        }
        content.addView(endianSpinner, fullWidth())

        extractButton = Button(this).apply {
            text = "2. Extract save"
            isEnabled = false
            setOnClickListener { extractSelected() }
        }
        content.addView(extractButton, fullWidth())

        exportButton = Button(this).apply {
            text = "3. Export extracted files as ZIP"
            isEnabled = false
            setOnClickListener { chooseZipDestination() }
        }
        content.addView(exportButton, fullWidth())

        content.addView(TextView(this).apply {
            text = "Results"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, dp(6))
        })
        status = TextView(this).apply {
            text = "Select a save file to begin."
            textSize = 13f
            setTextColor(0xFF263238.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFF1F4F7.toInt())
            minHeight = dp(120)
            gravity = Gravity.TOP
        }
        content.addView(status, fullWidth())

        content.addView(TextView(this).apply {
            text = "Notes: Xbox 360 CON/PIRS files need to be decrypted and decompressed separately first. " +
                "The original project warns that compatibility varies by edition/version; PS Vita is not listed as tested."
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
            setPadding(dp(2), dp(18), dp(2), dp(8))
        })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun chooseSave() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_OPEN)
    }

    @Deprecated("Legacy activity result API kept for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        if (requestCode == REQUEST_OPEN) {
            sourceUri = data.data
            try {
                contentResolver.takePersistableUriPermission(data.data!!, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some document providers do not grant persistable permissions.
            }
            sourceLabel.text = data.data!!.lastPathSegment ?: "Save selected"
            extracted = emptyList()
            extractButton.isEnabled = true
            exportButton.isEnabled = false
            status.text = "Save selected. Choose endianness, then extract."
        } else if (requestCode == REQUEST_EXPORT) {
            exportZip(data.data!!)
        }
    }

    private fun extractSelected() {
        val uri = sourceUri ?: return
        extractButton.isEnabled = false
        exportButton.isEnabled = false
        status.text = "Reading and extracting…"
        val endianMode = endianSpinner.selectedItemPosition
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not open the selected file.")
                val files = extract(bytes, endianMode)
                runOnUiThread {
                    extracted = files
                    status.text = "Extracted ${files.size} file(s).\n\n" +
                        files.take(30).joinToString("\n") { "• ${it.name}" } +
                        if (files.size > 30) "\n…and ${files.size - 30} more" else ""
                    exportButton.isEnabled = files.isNotEmpty()
                    extractButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Extraction failed:\n${e.message ?: e.javaClass.simpleName}"
                    extractButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun extract(dataOriginal: ByteArray, mode: Int): List<ExtractedFile> {
        var data = dataOriginal
        val head = data.take(16).joinToString("") { "%02x".format(it) }
        if (head.contains("789c")) {
            if (data.size <= 8) throw IllegalArgumentException("Compressed save is too short.")
            data = InflaterInputStream(ByteArrayInputStream(data, 8, data.size - 8)).use { it.readBytes() }
        }
        if (data.size >= 3) {
            val header = String(data, 0, 3, Charsets.US_ASCII)
            if (header == "CON" || header == "PIR") {
                throw IllegalArgumentException("This looks like an Xbox 360 CON/PIRS container. Decrypt/decompress it before using this extractor.")
            }
        }
        val endians = when (mode) { 1 -> listOf("big"); 2 -> listOf("little"); else -> listOf("big", "little") }
        if (data.size < 8) throw IllegalArgumentException("File is too small to contain the 8-byte save header.")
        var chosenEndian: String? = null
        var indexOffset = -1L
        var headerFileCount = 0L
        for (candidateEndian in endians) {
            val candidateOffset = u32(data, 0, candidateEndian)
            val candidateCount = u32(data, 4, candidateEndian)
            if (candidateOffset >= 8 && candidateOffset <= data.size.toLong() - 144L) {
                chosenEndian = candidateEndian
                indexOffset = candidateOffset
                headerFileCount = candidateCount
                break
            }
        }
        val endian = chosenEndian ?: run {
            val preview = data.take(16).joinToString(" ") { "%02X".format(it) }
            val bigOffset = u32(data, 0, "big")
            val littleOffset = u32(data, 0, "little")
            throw IllegalArgumentException("Header did not contain a plausible index offset. Size: ${data.size} bytes; first 16 bytes: $preview; big-endian offset: $bigOffset; little-endian offset: $littleOffset. This may be the wrong save file, a container, or an unsupported edition/version.")
        }
        val result = ArrayList<ExtractedFile>()
        var cursor = indexOffset.toInt()
        var recordsRead = 0
        // Match the supplied Python extractor: it reports the header count but reads 144-byte records to EOF.
        while (cursor <= data.size - 144) {
            val rawName = String(data, cursor, 80, Charsets.US_ASCII).substringBefore('\\u0000').trim()
            val length = u32(data, cursor + 128, endian)
            val fileOffset = u32(data, cursor + 132, endian)
            cursor += 144
            recordsRead++
            if (rawName.isEmpty()) continue
            if (length > Int.MAX_VALUE || fileOffset > data.size.toLong() || length > data.size.toLong() - fileOffset) {
                throw IllegalArgumentException("Invalid file bounds for '$rawName' (length=$length, offset=$fileOffset, save size=${data.size}).")
            }
            val safeName = safeEntryName(rawName)
            if (safeName.isNotEmpty()) {
                result.add(ExtractedFile(safeName, data.copyOfRange(fileOffset.toInt(), (fileOffset + length).toInt())))
            }
        }
        if (result.isEmpty()) throw IllegalArgumentException("Index offset $indexOffset ($endian-endian) was plausible, but no named files were extracted. Header file-count field: $headerFileCount; 144-byte records scanned: $recordsRead.")
        return result
    }

    private fun u32(bytes: ByteArray, at: Int, endian: String): Long {
        val b = (0..3).map { bytes[at + it].toLong() and 0xff }
        return if (endian == "big")
            (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
        else
            (b[3] shl 24) or (b[2] shl 16) or (b[1] shl 8) or b[0]
    }

    private fun safeEntryName(name: String): String {
        val normalized = name.replace('\\', '/').trimStart('/')
        val parts = normalized.split('/').filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return ""
        return parts.joinToString("/")
    }

    private fun chooseZipDestination() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "LCESaveGameExtractor.zip")
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private fun exportZip(uri: Uri) {
        val files = extracted
        Thread {
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    ZipOutputStream(output).use { zip ->
                        for (file in files) {
                            zip.putNextEntry(ZipEntry(file.name))
                            zip.write(file.bytes)
                            zip.closeEntry()
                        }
                    }
                } ?: throw IllegalStateException("Could not create the ZIP file.")
                runOnUiThread { status.append("\n\nZIP export completed.") }
            } catch (e: Exception) {
                runOnUiThread { status.append("\n\nZIP export failed: ${e.message}") }
            }
        }.start()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun fullWidth() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }

    companion object {
        private const val REQUEST_OPEN = 100
        private const val REQUEST_EXPORT = 101
    }
}
