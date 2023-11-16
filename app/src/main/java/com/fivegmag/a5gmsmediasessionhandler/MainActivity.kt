/*
License: 5G-MAG Public License (v1.0)
Author: Daniel Silhavy
Copyright: (C) 2023 Fraunhofer FOKUS
For full license terms please see the LICENSE file distributed with this
program. If this file is missing then the license can be retrieved from
https://drive.google.com/file/d/1cinCiA778IErENZ3JN52VFW-1ffHpx7Z/view
*/

package com.fivegmag.a5gmsmediasessionhandler

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.TextView

const val TAG_MEDIA_SESSION_HANDLER = "5GMS Aware Application"

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setApplicationVersionNumber()
        printDependenciesVersionNumbers()
    }

    private fun setApplicationVersionNumber() {
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName
            val versionTextView = findViewById<TextView>(R.id.versionNumber)
            val versionText = getString(R.string.versionTextField, versionName)
            versionTextView.text = versionText
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
    }

    private fun printDependenciesVersionNumbers() {
        Log.d(TAG_MEDIA_SESSION_HANDLER, "5GMS Common Library Version: ${BuildConfig.LIB_VERSION_a5gmscommonlibrary}")
    }
}