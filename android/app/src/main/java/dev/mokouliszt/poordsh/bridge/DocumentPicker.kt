package dev.mokouliszt.poordsh.bridge

import android.net.Uri

/** The system file picker, opened by the activity for the bridge. Empty when the user backs out. */
fun interface DocumentPicker {
    suspend fun pick(types: Array<String>, multiple: Boolean): List<Uri>
}
