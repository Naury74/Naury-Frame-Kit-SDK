package com.naury.framekit.image.export

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo

/** Memory the export may use for bitmaps, based on the app's memory class. */
public object ImageMemoryBudget {
    private const val BYTES_PER_MEGABYTE = 1024L * 1024L

    public fun bytes(context: Context): Long {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val largeHeap = context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP != 0
        val megabytes = if (largeHeap) activityManager.largeMemoryClass else activityManager.memoryClass
        return megabytes * BYTES_PER_MEGABYTE
    }

    /** ARGB_8888 bitmap size: four bytes per pixel. */
    internal fun argbBytes(width: Int, height: Int): Long = width.toLong() * height.toLong() * 4L
}
