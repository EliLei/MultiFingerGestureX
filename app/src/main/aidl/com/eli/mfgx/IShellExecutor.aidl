package com.eli.mfgx;

import com.eli.mfgx.IShellCallback;
import android.os.ParcelFileDescriptor;

oneway interface IShellExecutor {
    void execute(String command, boolean runAsRoot, IShellCallback callback);
    void savePngToGallery(in ParcelFileDescriptor png, String displayName, IShellCallback callback);
}
