package com.clipcascade.sync

import android.content.ClipData
import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

/**
 * Runs under Shizuku as com.android.shell, which holds READ_CLIPBOARD_IN_BACKGROUND.
 *
 * Written fresh for this project. The mechanism follows ClipShare's clipshare_clipboard_listener
 * (MIT) and the Batestinha/kdeconnect-android-shizuku fork (GPL): talk to IClipboard directly and
 * pick arguments by reflection, because the method signatures change between Android versions.
 *
 * Shizuku calls this constructor with a Context in the shell's process.
 */
class ClipboardUserService(private val context: Context) : IClipboardWatcher.Stub() {
    private val clipboard: Any by lazy {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
        Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    // Held so it isn't collected while registered with the system.
    private var listenerBinder: Binder? = null

    override fun watch(callback: IClipChangedCallback, userId: Int): String = try {
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == FIRST_CALL_TRANSACTION) {
                    runCatching { callback.onClipChanged() }
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
        val listener = Class.forName("android.content.IOnPrimaryClipChangedListener\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val method = clipboard.javaClass.methods.first { it.name == "addPrimaryClipChangedListener" }
        method.invoke(clipboard, *arguments(method.parameterTypes, userId, listener))
        listenerBinder = binder
        ""
    } catch (e: Exception) {
        val cause = e.cause ?: e
        "${cause.javaClass.simpleName}: ${cause.message}"
    }

    override fun readClip(userId: Int): Bundle {
        val method = clipboard.javaClass.methods.first { it.name == "getPrimaryClip" }
        val clip = method.invoke(clipboard, *arguments(method.parameterTypes, userId, null)) as ClipData?
        val out = Bundle()
        clip ?: return out
        out.putBoolean("sensitive", clip.isSensitive())
        val items = (0 until clip.itemCount).map { clip.getItemAt(it) }
        val uris = items.mapNotNull { it.uri }.filter { it.scheme == "content" || it.scheme == "file" }
        if (uris.isNotEmpty()) {
            out.putStringArrayList("uris", ArrayList(uris.map(Uri::toString)))
            val mime = runCatching { context.contentResolver.getType(uris.first()) }.getOrNull()
            out.putBoolean("image", uris.size == 1 && (mime?.startsWith("image/") ?: clip.description.hasMimeType("image/*")))
        } else {
            val text = items.mapNotNull { it.text?.toString() ?: it.uri?.toString() }.joinToString("\n")
            if (text.isNotEmpty()) out.putString("text", text)
        }
        return out
    }

    override fun openUri(uri: String): ParcelFileDescriptor? =
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")

    override fun displayName(uri: String): String? = runCatching {
        context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    /** Fills IClipboard's parameters by type: strings are package then attribution tag, ints user then device. */
    private fun arguments(types: Array<Class<*>>, userId: Int, listener: Any?): Array<Any?> {
        var strings = 0
        var ints = 0
        return Array(types.size) { index ->
            val type = types[index]
            when {
                type == String::class.java -> if (strings++ == 0) SHELL_PACKAGE else null
                type == Int::class.javaPrimitiveType -> if (ints++ == 0) userId else 0
                type == Boolean::class.javaPrimitiveType -> false
                else -> listener
            }
        }
    }

    override fun destroy() {
        System.exit(0)
    }

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
