/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.hidedisplaycutout;

import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemProperties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.wm.shell.common.DisplayController;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

/**
 * Manages the hide display cutout status.
 */
public class HideDisplayCutoutController implements HideDisplayCutout {
    private static final String TAG = "HideDisplayCutoutController";

    private final Context mContext;
    private final HideDisplayCutoutOrganizer mOrganizer;
    @VisibleForTesting
    boolean mEnabled;

    HideDisplayCutoutController(Context context, HideDisplayCutoutOrganizer organizer) {
        mContext = context;
        mOrganizer = organizer;
        updateStatus();
    }

    /**
     * Creates {@link HideDisplayCutoutController}, returns {@code null} if the feature is not
     * supported.
     */
    @Nullable
    public static HideDisplayCutoutController create(
            Context context, DisplayController displayController, Executor executor) {
        // The SystemProperty is set for devices that support this feature and is used to control
        // whether to create the HideDisplayCutout instance.
        // It's defined in the device.mk (e.g. device/google/crosshatch/device.mk).
        if (!SystemProperties.getBoolean("ro.support_hide_display_cutout", false)) {
            return null;
        }

        HideDisplayCutoutOrganizer organizer =
                new HideDisplayCutoutOrganizer(context, displayController, executor);
        return new HideDisplayCutoutController(context, organizer);
    }

    @VisibleForTesting
    void updateStatus() {
        // The config value is used for controlling enabling/disabling status of the feature and is
        // defined in the config.xml in a "Hide Display Cutout" overlay package (e.g. device/google/
        // crosshatch/crosshatch/overlay/packages/apps/overlays/NoCutoutOverlay).
        final boolean enabled = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_hideDisplayCutoutWithDisplayArea);
        if (enabled == mEnabled) {
            return;
        }

        mEnabled = enabled;
        if (enabled) {
            mOrganizer.enableHideDisplayCutout();
        } else {
            mOrganizer.disableHideDisplayCutout();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        updateStatus();
    }

    @Override
    public void dump(@NonNull PrintWriter pw) {
        final String prefix = "  ";
        pw.print(TAG);
        pw.println(" states: ");
        pw.print(prefix);
        pw.print("mEnabled=");
        pw.println(mEnabled);
        mOrganizer.dump(pw);
    }
}
