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
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import com.fivegmag.BaseActivity

const val TAG_MEDIA_SESSION_HANDLER = "5GMS Media Session Handler"

class MainActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        printDependenciesVersionNumbers()
    }

    private fun printDependenciesVersionNumbers() {
        Log.d(
            TAG_MEDIA_SESSION_HANDLER,
            "5GMS Common Library Version: ${BuildConfig.LIB_VERSION_a5gmscommonlibrary}"
        )
    }
}