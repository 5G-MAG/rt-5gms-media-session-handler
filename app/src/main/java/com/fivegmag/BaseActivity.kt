package com.fivegmag

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.Html
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity;
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
                val formattedText = getString(R.string.license_text)
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
                addVersionNumber(dialogView)
                setClickListeners(dialogView)
                val builder = AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                    }
                val dialog = builder.create()
                dialog.show()
                return true
            }

            R.id.actionAttribution -> {
                OssLicensesMenuActivity.setActivityTitle(getString(R.string.action_attribution_notice))
                val licensesIntent = Intent(this, OssLicensesMenuActivity::class.java)
                startActivity(licensesIntent)
                return true
            }

        }
        return super.onOptionsItemSelected(item)
    }

    private fun addVersionNumber(dialogView: View) {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = packageInfo.versionName
        val versionTextView = dialogView.findViewById<TextView>(R.id.versionNumberView)
        val versionText = getString(R.string.version_text_field, versionName)
        versionTextView.text = versionText
    }

    private fun setClickListeners(dialogView: View) {
        val twitterTextView = dialogView.findViewById<TextView>(R.id.twitterLink)
        twitterTextView.setOnClickListener {
            val url = getString(R.string.twitter_url)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }

        val linkedInView = dialogView.findViewById<TextView>(R.id.linkedInLink)
        linkedInView.setOnClickListener {
            val url = getString(R.string.linked_in_url)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }

        val slackView = dialogView.findViewById<TextView>(R.id.slackLink)
        slackView.setOnClickListener {
            val url = getString(R.string.slack_url)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }

        val websiteView = dialogView.findViewById<TextView>(R.id.websiteLink)
        websiteView.setOnClickListener {
            val url = getString(R.string.website_url)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }
    }



}