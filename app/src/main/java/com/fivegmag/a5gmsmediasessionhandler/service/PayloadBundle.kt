package com.fivegmag.a5gmsmediasessionhandler.service

import android.os.Bundle
import android.os.Parcelable

/** The payload of an event or a reply (keys of SessionHandlerBundleKeys) as a bundle; entries without a value are left out */
fun Map<String, Any?>.toBundle(): Bundle {
    val bundle = Bundle()
    for ((key, value) in this) {
        when (value) {
            null -> {}
            is String -> bundle.putString(key, value)
            is Boolean -> bundle.putBoolean(key, value)
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Parcelable -> bundle.putParcelable(key, value)
            is ArrayList<*> -> {
                @Suppress("UNCHECKED_CAST")
                bundle.putParcelableArrayList(key, value as ArrayList<out Parcelable>)
            }
            else -> throw IllegalArgumentException("The payload item [$key] has a type that a bundle cannot carry")
        }
    }
    return bundle
}
