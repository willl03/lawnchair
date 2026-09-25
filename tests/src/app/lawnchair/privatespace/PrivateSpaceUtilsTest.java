/*
 * Copyright (C) 2024 The Android Open Source Project
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

package app.lawnchair.privatespace;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@RunWith(AndroidJUnit4.class)
public class PrivateSpaceUtilsTest {

    private static final UserHandle PRIVATE_HANDLE = new UserHandle(11);
    private static final UserHandle MAIN_HANDLE = Process.myUserHandle();

    private final Context mContext = getApplicationContext();
    @Mock
    private UserManager mUserManager;

    @Before
    public void setUp() {
        initMocks(this);
    }

    @Test
    public void lockPrivateSpace_requestsQuietModeAsTrue() {
        PrivateSpaceUtils.setQuietModeSafely(mContext, mUserManager, PRIVATE_HANDLE, true);

        verify(mUserManager).requestQuietModeEnabled(true, PRIVATE_HANDLE);
    }

    @Test
    public void unlockPrivateSpace_requestsQuietModeAsFalse() {
        PrivateSpaceUtils.setQuietModeSafely(mContext, mUserManager, PRIVATE_HANDLE, false);

        verify(mUserManager).requestQuietModeEnabled(false, PRIVATE_HANDLE);
    }

    @Test
    public void securityException_isSwallowedAndFallbackScheduled() throws Exception {
        doThrow(new SecurityException()).when(mUserManager)
                .requestQuietModeEnabled(anyBoolean(), any(UserHandle.class));

        PrivateSpaceUtils.setQuietModeSafely(mContext, mUserManager, PRIVATE_HANDLE, true);

        // Flush the fallback posted to the main executor, ensuring it does not crash.
        MAIN_EXECUTOR.submit(() -> null).get();
    }
}
