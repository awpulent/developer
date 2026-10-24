package com.awpulent.flashrev.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.awpulent.flashrev.FlashRevApp
import com.awpulent.flashrev.R
import com.awpulent.flashrev.chart.SongImporter
import com.awpulent.flashrev.demo.DemoInstaller

class MainActivity : Activity() {

    private lateinit var app: FlashRevApp
    private lateinit var statusText: TextView
    private lateinit var buttons: List<Button>
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).padForSystemBars()
        app = FlashRevApp.from(this)
        statusText = findViewById(R.id.status)

        val play = findViewById<Button>(R.id.btn_play)
        val import = findViewById<Button>(R.id.btn_import)
        val calibrate = findViewById<Button>(R.id.btn_calibrate)
        val settings = findViewById<Button>(R.id.btn_settings)
        buttons = listOf(play, import, calibrate, settings)

        play.setOnClickListener { startActivity(Intent(this, SongSelectActivity::class.java)) }
        import.setOnClickListener { showImportDialog() }
        calibrate.setOnClickListener { startActivity(Intent(this, CalibrationActivity::class.java)) }
        settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        ensureDemoInstalled()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun ensureDemoInstalled() {
        if (DemoInstaller.isInstalled(this, app.settings)) return
        setBusy(getString(R.string.preparing_demo))
        Thread {
            val err = runCatching { DemoInstaller.install(this, app.settings) }.exceptionOrNull()
            app.library.invalidate()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setBusy(null)
                if (err != null) Toast.makeText(this, err.message ?: err.javaClass.simpleName, Toast.LENGTH_LONG).show()
                updateStatus()
            }
        }.start()
    }

    private fun setBusy(message: String?) {
        busy = message != null
        buttons.forEach { it.isEnabled = !busy }
        if (message != null) statusText.text = message
    }

    private fun updateStatus() {
        if (busy) return
        val count = app.library.songs.size
        statusText.text = resources.getQuantityString(R.plurals.songs_in_library, count, count)
    }

    private fun showImportDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_title)
            .setMessage(R.string.import_message)
            .setPositiveButton(R.string.import_folder) { _, _ -> pickFolder() }
            .setNeutralButton(R.string.import_zip) { _, _ -> pickZip() }
            .setNegativeButton(R.string.import_cancel, null)
            .show()
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivityForResult(intent, REQUEST_FOLDER) }
            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
    }

    private fun pickZip() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        runCatching { startActivityForResult(intent, REQUEST_ZIP) }
            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_FOLDER -> runImport { it.importTree(uri) }
            REQUEST_ZIP -> runImport { it.importZip(uri) }
        }
    }

    private fun runImport(block: (SongImporter) -> SongImporter.Result) {
        val dialog = AlertDialog.Builder(this)
            .setMessage(R.string.importing)
            .setCancelable(false)
            .show()
        Thread {
            val outcome = runCatching { block(SongImporter(this, app.songsDir)) }
            app.library.invalidate()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dialog.dismiss()
                val message = outcome.fold(
                    onSuccess = { r -> if (r.imported > 0) getString(R.string.import_done, r.imported) else getString(R.string.import_none) },
                    onFailure = { e -> getString(R.string.import_failed, e.message ?: e.javaClass.simpleName) },
                )
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                updateStatus()
            }
        }.start()
    }

    @Suppress("unused")
    private fun Uri.describe() = lastPathSegment ?: toString()

    private companion object {
        const val REQUEST_FOLDER = 101
        const val REQUEST_ZIP = 102
    }
}
