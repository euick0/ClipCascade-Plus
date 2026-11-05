package com.clipcascade.sync;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.clipcascade.sync.IClipChangedCallback;

interface IClipboardWatcher {
    /** Returns an empty string on success, otherwise why the listener couldn't be registered. */
    String watch(IClipChangedCallback callback, int userId) = 0;
    /** Keys: text (String), uris (ArrayList of String), image (boolean), sensitive (boolean). */
    Bundle readClip(int userId) = 1;
    /** Opened here because the read grant for a copied URI goes to the caller, which is shell. */
    ParcelFileDescriptor openUri(String uri) = 2;
    String displayName(String uri) = 3;
    void destroy() = 16777114;
}
