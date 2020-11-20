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
package com.android.server.location;

import static androidx.test.ext.truth.location.LocationSubject.assertThat;

import static com.android.internal.location.ProviderRequest.EMPTY_REQUEST;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.location.Criteria;
import android.location.Location;
import android.location.LocationResult;
import android.location.util.identity.CallerIdentity;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.server.location.test.FakeProvider;
import com.android.server.location.test.ProviderListenerCapture;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class MockableLocationProviderTest {

    private ProviderListenerCapture mListener;

    private AbstractLocationProvider mRealProvider;
    private MockLocationProvider mMockProvider;

    private MockableLocationProvider mProvider;

    @Before
    public void setUp() {
        Object lock = new Object();
        mListener = new ProviderListenerCapture(lock);

        mRealProvider = spy(new FakeProvider());
        mMockProvider = spy(new MockLocationProvider(new ProviderProperties(
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE),
                CallerIdentity.forTest(0, 1, "testpackage", "test")));

        mProvider = new MockableLocationProvider(lock, mListener);
        mProvider.setRealProvider(mRealProvider);
    }

    @Test
    public void testSetProvider() {
        assertThat(mProvider.getProvider()).isEqualTo(mRealProvider);

        mProvider.setMockProvider(mMockProvider);
        assertThat(mProvider.getProvider()).isEqualTo(mMockProvider);

        mProvider.setMockProvider(null);
        assertThat(mProvider.getProvider()).isEqualTo(mRealProvider);

        mProvider.setRealProvider(null);
        assertThat(mProvider.getProvider()).isNull();
    }

    @Test
    public void testSetRequest() {
        assertThat(mProvider.getCurrentRequest()).isEqualTo(EMPTY_REQUEST);
        verify(mRealProvider, times(1)).onSetRequest(EMPTY_REQUEST);

        ProviderRequest request = new ProviderRequest.Builder().setIntervalMillis(1).build();
        mProvider.setRequest(request);

        assertThat(mProvider.getCurrentRequest()).isEqualTo(request);
        verify(mRealProvider, times(1)).onSetRequest(request);

        mProvider.setMockProvider(mMockProvider);
        assertThat(mProvider.getCurrentRequest()).isEqualTo(request);
        verify(mRealProvider, times(2)).onSetRequest(EMPTY_REQUEST);
        verify(mMockProvider, times(1)).onSetRequest(request);

        mProvider.setMockProvider(null);
        assertThat(mProvider.getCurrentRequest()).isEqualTo(request);
        verify(mMockProvider, times(1)).onSetRequest(EMPTY_REQUEST);
        verify(mRealProvider, times(2)).onSetRequest(request);

        mProvider.setRealProvider(null);
        assertThat(mProvider.getCurrentRequest()).isEqualTo(request);
        verify(mRealProvider, times(3)).onSetRequest(EMPTY_REQUEST);
    }

    @Test
    public void testFlush() {
        Runnable listener = mock(Runnable.class);
        mProvider.flush(listener);
        verify(mRealProvider).onFlush(listener);
        verify(listener).run();

        listener = mock(Runnable.class);
        mProvider.setMockProvider(mMockProvider);
        mProvider.flush(listener);
        verify(mMockProvider).onFlush(listener);
        verify(listener).run();
    }

    @Test
    public void testSendExtraCommand() {
        mProvider.sendExtraCommand(0, 0, "command", null);
        verify(mRealProvider, times(1)).onExtraCommand(0, 0, "command", null);

        mProvider.setMockProvider(mMockProvider);
        mProvider.sendExtraCommand(0, 0, "command", null);
        verify(mMockProvider, times(1)).onExtraCommand(0, 0, "command", null);
    }

    @Test
    public void testSetState() {
        assertThat(mProvider.getState().allowed).isFalse();

        AbstractLocationProvider.State newState;

        mRealProvider.setAllowed(true);
        newState = mListener.getNextNewState();
        assertThat(newState).isNotNull();
        assertThat(newState.allowed).isTrue();

        mProvider.setMockProvider(mMockProvider);
        newState = mListener.getNextNewState();
        assertThat(newState).isNotNull();
        assertThat(newState.allowed).isFalse();

        mMockProvider.setAllowed(true);
        newState = mListener.getNextNewState();
        assertThat(newState).isNotNull();
        assertThat(newState.allowed).isTrue();

        mRealProvider.setAllowed(false);
        assertThat(mListener.getNextNewState()).isNull();

        mProvider.setMockProvider(null);
        newState = mListener.getNextNewState();
        assertThat(newState).isNotNull();
        assertThat(newState.allowed).isFalse();
    }

    @Test
    public void testReportLocation() {
        LocationResult realLocation = LocationResult.create(new Location("real"));
        LocationResult mockLocation = LocationResult.create(new Location("mock"));

        mRealProvider.reportLocation(realLocation);
        assertThat(mListener.getNextLocationResult()).isEqualTo(realLocation);

        mProvider.setMockProvider(mMockProvider);
        mRealProvider.reportLocation(realLocation);
        mMockProvider.reportLocation(mockLocation);
        assertThat(mListener.getNextLocationResult()).isEqualTo(mockLocation);
    }
}
