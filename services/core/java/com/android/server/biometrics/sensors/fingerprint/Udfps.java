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

package com.android.server.biometrics.sensors.fingerprint;

/**
 * Interface for under-display fingerprint sensors.
 * {@link com.android.server.biometrics.sensors.ClientMonitor} subclass that require knowledge of
 * finger position (e.g. enroll, authenticate) should implement this.
 */
public interface Udfps {
    void onFingerDown(int x, int y, float minor, float major);
    void onFingerUp();
}
