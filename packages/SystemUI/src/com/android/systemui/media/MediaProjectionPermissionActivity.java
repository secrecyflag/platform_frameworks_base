/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.media;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static com.android.systemui.screenrecord.ScreenShareOptionKt.ENTIRE_SCREEN;
import static com.android.systemui.screenrecord.ScreenShareOptionKt.SINGLE_APP;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.BidiFormatter;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Window;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.screenrecord.MediaProjectionPermissionDialog;
import com.android.systemui.screenrecord.ScreenShareOption;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.util.Utils;

public class MediaProjectionPermissionActivity extends Activity
        implements DialogInterface.OnClickListener, DialogInterface.OnCancelListener {
    private static final String TAG = "MediaProjectionPermissionActivity";
    private static final float MAX_APP_NAME_SIZE_PX = 500f;
    private static final String ELLIPSIS = "\u2026";

    private String mPackageName;
    private int mUid;
    private IMediaProjectionManager mService;
    private FeatureFlags mFeatureFlags;

    private AlertDialog mDialog;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mFeatureFlags = Dependency.get(FeatureFlags.class);
        mPackageName = getCallingPackage();
        IBinder b = ServiceManager.getService(MEDIA_PROJECTION_SERVICE);
        mService = IMediaProjectionManager.Stub.asInterface(b);

        if (mPackageName == null) {
            finish();
            return;
        }

        PackageManager packageManager = getPackageManager();
        ApplicationInfo aInfo;
        try {
            aInfo = packageManager.getApplicationInfo(mPackageName, 0);
            mUid = aInfo.uid;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "unable to look up package name", e);
            finish();
            return;
        }

        try {
            if (mService.hasProjectionPermission(mUid, mPackageName)) {
                setResult(RESULT_OK, getMediaProjectionIntent(mUid, mPackageName));
                finish();
                return;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error checking projection permissions", e);
            finish();
            return;
        }

        TextPaint paint = new TextPaint();
        paint.setTextSize(42);

        CharSequence dialogText = null;
        CharSequence dialogTitle = null;
        String appName = null;
        if (Utils.isHeadlessRemoteDisplayProvider(packageManager, mPackageName)) {
            dialogText = getString(R.string.media_projection_dialog_service_text);
            dialogTitle = getString(R.string.media_projection_dialog_service_title);
        } else {
            String label = aInfo.loadLabel(packageManager).toString();

            // If the label contains new line characters it may push the security
            // message below the fold of the dialog. Labels shouldn't have new line
            // characters anyways, so just truncate the message the first time one
            // is seen.
            final int labelLength = label.length();
            int offset = 0;
            while (offset < labelLength) {
                final int codePoint = label.codePointAt(offset);
                final int type = Character.getType(codePoint);
                if (type == Character.LINE_SEPARATOR
                        || type == Character.CONTROL
                        || type == Character.PARAGRAPH_SEPARATOR) {
                    label = label.substring(0, offset) + ELLIPSIS;
                    break;
                }
                offset += Character.charCount(codePoint);
            }

            if (label.isEmpty()) {
                label = mPackageName;
            }

            String unsanitizedAppName = TextUtils.ellipsize(label,
                    paint, MAX_APP_NAME_SIZE_PX, TextUtils.TruncateAt.END).toString();
            appName = BidiFormatter.getInstance().unicodeWrap(unsanitizedAppName);

            boolean appCanRecordAudio = packageManager.checkPermission(android.Manifest.permission.RECORD_AUDIO,
                    mPackageName) == PackageManager.PERMISSION_GRANTED;

            String actionText = getString(appCanRecordAudio ?
                    R.string.media_projection_dialog_text :
                    R.string.media_projection_dialog_text_no_audio, appName);
            SpannableString message = new SpannableString(actionText);

            int appNameIndex = actionText.indexOf(appName);
            if (appNameIndex >= 0) {
                message.setSpan(new StyleSpan(Typeface.BOLD),
                        appNameIndex, appNameIndex + appName.length(), 0);
            }
            dialogText = message;
            dialogTitle = getString(R.string.media_projection_dialog_title, appName);
        }

        if (isPartialScreenSharingEnabled()) {
            mDialog = new MediaProjectionPermissionDialog(this, () -> {
                ScreenShareOption selectedOption =
                        ((MediaProjectionPermissionDialog) mDialog).getSelectedScreenShareOption();
                grantMediaProjectionPermission(selectedOption.getMode());
            }, appName);
        } else {
            AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this,
                    R.style.Theme_SystemUI_Dialog)
                    .setTitle(dialogTitle)
                    .setIcon(R.drawable.ic_media_projection_permission)
                    .setMessage(dialogText)
                    .setPositiveButton(R.string.media_projection_action_text, this)
                    .setNeutralButton(android.R.string.cancel, this);
            mDialog = dialogBuilder.create();
        }

        SystemUIDialog.registerDismissListener(mDialog);
        SystemUIDialog.applyFlags(mDialog);
        SystemUIDialog.setDialogSize(mDialog);

        mDialog.setOnCancelListener(this);
        mDialog.create();
        mDialog.getButton(DialogInterface.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);

        final Window w = mDialog.getWindow();
        w.addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);

        mDialog.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDialog != null) {
            mDialog.dismiss();
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == AlertDialog.BUTTON_POSITIVE) {
            grantMediaProjectionPermission(ENTIRE_SCREEN);
        }
    }

    private void grantMediaProjectionPermission(int screenShareMode) {
        try {
            if (screenShareMode == ENTIRE_SCREEN) {
                setResult(RESULT_OK, getMediaProjectionIntent(mUid, mPackageName));
            }
            if (isPartialScreenSharingEnabled() && screenShareMode == SINGLE_APP) {
                IMediaProjection projection = createProjection(mUid, mPackageName);
                final Intent intent = new Intent(this, MediaProjectionAppSelectorActivity.class);
                intent.putExtra(MediaProjectionManager.EXTRA_MEDIA_PROJECTION,
                        projection.asBinder());
                intent.setFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
                startActivity(intent);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error granting projection permission", e);
            setResult(RESULT_CANCELED);
        } finally {
            if (mDialog != null) {
                mDialog.dismiss();
            }
            finish();
        }
    }

    private IMediaProjection createProjection(int uid, String packageName) throws RemoteException {
        return mService.createProjection(uid, packageName,
                MediaProjectionManager.TYPE_SCREEN_CAPTURE, false /* permanentGrant */);
    }

    private Intent getMediaProjectionIntent(int uid, String packageName)
            throws RemoteException {
        IMediaProjection projection = createProjection(uid, packageName);
        Intent intent = new Intent();
        intent.putExtra(MediaProjectionManager.EXTRA_MEDIA_PROJECTION, projection.asBinder());
        return intent;
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        finish();
    }

    private boolean isPartialScreenSharingEnabled() {
        return mFeatureFlags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING);
    }
}
