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

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import java.util.Arrays;

/**
 * A class to keep track of the enrollment state for a given client.
 */
public abstract class EnrollClient extends AcquisitionClient {

    private static final String TAG = "Biometrics/EnrollClient";

    protected final byte[] mHardwareAuthToken;
    protected final int mTimeoutSec;
    private final BiometricUtils mBiometricUtils;
    private final boolean mShouldVibrate;

    private long mEnrollmentStartTimeMs;

    public EnrollClient(@NonNull Context context, @NonNull IBinder token,
            @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull byte[] hardwareAuthToken, boolean restricted, String owner,
            @NonNull BiometricUtils utils, int timeoutSec, int statsModality, int sensorId,
            boolean shouldVibrate) {
        super(context, token, listener, userId, restricted, owner, 0 /* cookie */, sensorId,
                statsModality, BiometricsProtoEnums.ACTION_ENROLL,
                BiometricsProtoEnums.CLIENT_UNKNOWN);
        mBiometricUtils = utils;
        mHardwareAuthToken = Arrays.copyOf(hardwareAuthToken, hardwareAuthToken.length);
        mTimeoutSec = timeoutSec;
        mShouldVibrate = shouldVibrate;
    }

    public boolean onEnrollResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        if (remaining == 0) {
            mBiometricUtils.addBiometricForUser(getContext(), getTargetUserId(), identifier);
            logOnEnrolled(getTargetUserId(),
                    System.currentTimeMillis() - mEnrollmentStartTimeMs,
                    true /* enrollSuccessful */);
        }
        notifyUserActivity();
        return sendEnrollResult(identifier, remaining);
    }

    /*
     * @return true if we're done.
     */
    private boolean sendEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (mShouldVibrate) {
            vibrateSuccess();
        }

        try {
            final ClientMonitorCallbackConverter listener = getListener();
            if (listener != null) {
                listener.onEnrollResult(identifier, remaining);
            }
            return remaining == 0;
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to notify EnrollResult:", e);
            return true;
        }
    }

    @Override
    public int start() {
        mEnrollmentStartTimeMs = System.currentTimeMillis();
        try {
            final int result = startHalOperation();
            if (result != 0) {
                Slog.w(TAG, "startEnroll failed, result=" + result);
                onError(BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "startEnroll failed", e);
        }
        return 0; // success
    }

    @Override
    public int stop(boolean initiatedByClient) {
        if (mAlreadyCancelled) {
            Slog.w(TAG, "stopEnroll: already cancelled!");
            return 0;
        }

        try {
            final int result = stopHalOperation();
            if (result != 0) {
                Slog.w(TAG, "startEnrollCancel failed, result = " + result);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "stopEnrollment failed", e);
        }
        mAlreadyCancelled = true;
        return 0;
    }

    /**
     * Called when we get notification from the biometric's HAL that an error has occurred with the
     * current operation.
     */
    @Override
    public void onError(int error, int vendorCode) {
        logOnEnrolled(getTargetUserId(), System.currentTimeMillis() - mEnrollmentStartTimeMs,
                false /* enrollSuccessful */);
        super.onError(error, vendorCode);
    }
}
