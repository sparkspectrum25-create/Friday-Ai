package com.friday.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class IconPathHandler(private val ctx: Context) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val pkg = path.trim('/')
        return try {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 96, 96)
            d.draw(Canvas(bmp))
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            WebResourceResponse("image/png", null, ByteArrayInputStream(out.toByteArray()))
        } catch (e: Exception) {
            null
        }
    }
}
