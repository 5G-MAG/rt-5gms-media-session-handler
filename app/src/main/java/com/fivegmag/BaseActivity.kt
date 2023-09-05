package com.fivegmag

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.text.Html
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fivegmag.a5gmsmediasessionhandler.R

open class BaseActivity : AppCompatActivity() {

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.actionLicense -> {
                val dialogView = LayoutInflater.from(this).inflate(R.layout.activity_license, null)
                val textView = dialogView.findViewById<TextView>(R.id.licenseTextView)
                val formattedText = getString(R.string.licenseText)
                textView.text = Html.fromHtml(formattedText, Html.FROM_HTML_MODE_LEGACY)
                val builder = AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                    }
                val dialog = builder.create()
                dialog.show()
                return true
            }
            R.id.actionAbout -> {
                val dialogView = LayoutInflater.from(this).inflate(R.layout.activity_about, null)
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val versionName = packageInfo.versionName
                val versionTextView = dialogView.findViewById<TextView>(R.id.versionNumberView)
                val versionText = getString(R.string.versionTextField, versionName)
                versionTextView.text = versionText
                val builder = AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                    }
                val dialog = builder.create()
                dialog.show()
                return true
            }

        }
        return super.onOptionsItemSelected(item)
    }



}