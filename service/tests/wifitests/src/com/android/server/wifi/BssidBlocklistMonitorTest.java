/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wifi;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.util.LocalLog;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link com.android.server.wifi.BssidBlocklistMonitor}.
 */
@SmallTest
public class BssidBlocklistMonitorTest {
    private static final int TEST_NUM_MAX_FIRMWARE_SUPPORT_BSSIDS = 3;
    private static final String TEST_SSID_1 = "TestSSID1";
    private static final String TEST_SSID_2 = "TestSSID2";
    private static final String TEST_SSID_3 = "TestSSID3";
    private static final String TEST_BSSID_1 = "0a:08:5c:67:89:00";
    private static final String TEST_BSSID_2 = "0a:08:5c:67:89:01";
    private static final String TEST_BSSID_3 = "0a:08:5c:67:89:02";
    private static final int TEST_L2_FAILURE = BssidBlocklistMonitor.REASON_ASSOCIATION_REJECTION;
    private static final int TEST_DHCP_FAILURE = BssidBlocklistMonitor.REASON_DHCP_FAILURE;
    private static final long BASE_BLOCKLIST_DURATION = TimeUnit.MINUTES.toMillis(5); // 5 minutes
    private static final long MAX_BLOCKLIST_DURATION = TimeUnit.HOURS.toMillis(18); // 18 hours
    private static final int FAILURE_STREAK_CAP = 7;
    private static final int NUM_FAILURES_TO_BLOCKLIST = 3;

    @Mock private WifiConnectivityHelper mWifiConnectivityHelper;
    @Mock private WifiLastResortWatchdog mWifiLastResortWatchdog;
    @Mock private Clock mClock;
    @Mock private LocalLog mLocalLog;
    @Mock private WifiScoreCard mWifiScoreCard;

    private BssidBlocklistMonitor mBssidBlocklistMonitor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mWifiConnectivityHelper.isFirmwareRoamingSupported()).thenReturn(true);
        when(mWifiConnectivityHelper.getMaxNumBlacklistBssid())
                .thenReturn(TEST_NUM_MAX_FIRMWARE_SUPPORT_BSSIDS);
        mBssidBlocklistMonitor = new BssidBlocklistMonitor(mWifiConnectivityHelper,
                mWifiLastResortWatchdog, mClock, mLocalLog, mWifiScoreCard);
    }

    private void verifyAddTestBssidToBlocklist() {
        mBssidBlocklistMonitor.handleBssidConnectionFailure(
                TEST_BSSID_1, TEST_SSID_1,
                BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
    }

    // Verify adding 2 BSSID for SSID_1 and 1 BSSID for SSID_2 to the blocklist.
    private void verifyAddMultipleBssidsToBlocklist() {
        when(mClock.getWallClockMillis()).thenReturn(0L);
        mBssidBlocklistMonitor.handleBssidConnectionFailure(TEST_BSSID_1,
                TEST_SSID_1, BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA);
        when(mClock.getWallClockMillis()).thenReturn(1L);
        mBssidBlocklistMonitor.handleBssidConnectionFailure(TEST_BSSID_2,
                TEST_SSID_1, BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA);
        mBssidBlocklistMonitor.handleBssidConnectionFailure(TEST_BSSID_3,
                TEST_SSID_2, BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA);

        // Verify that we have 3 BSSIDs in the blocklist.
        Set<String> bssidList = mBssidBlocklistMonitor.updateAndGetBssidBlocklist();
        assertEquals(3, bssidList.size());
        assertTrue(bssidList.contains(TEST_BSSID_1));
        assertTrue(bssidList.contains(TEST_BSSID_2));
        assertTrue(bssidList.contains(TEST_BSSID_3));
    }

    private void handleBssidConnectionFailureMultipleTimes(String bssid, int reason, int times) {
        handleBssidConnectionFailureMultipleTimes(bssid, TEST_SSID_1, reason, times);
    }

    private void handleBssidConnectionFailureMultipleTimes(String bssid, String ssid, int reason,
            int times) {
        for (int i = 0; i < times; i++) {
            mBssidBlocklistMonitor.handleBssidConnectionFailure(bssid, ssid, reason);
        }
    }

    /**
     * Verify getNumBlockedBssidsForSsid returns the correct number of blocked BSSIDs.
     */
    @Test
    public void testGetNumBlockedBssidsForSsid() {
        verifyAddMultipleBssidsToBlocklist();
        assertEquals(2, mBssidBlocklistMonitor.getNumBlockedBssidsForSsid(TEST_SSID_1));
        assertEquals(1, mBssidBlocklistMonitor.getNumBlockedBssidsForSsid(TEST_SSID_2));
    }

    /**
     * Verify that updateAndGetBssidBlocklist removes expired blocklist entries and clears
     * all failure counters for those networks.
     */
    @Test
    public void testBssidIsRemovedFromBlocklistAfterTimeout() {
        verifyAddTestBssidToBlocklist();
        // Verify TEST_BSSID_1 is not removed from the blocklist until sufficient time have passed.
        when(mClock.getWallClockMillis()).thenReturn(BASE_BLOCKLIST_DURATION);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));

        // Verify that TEST_BSSID_1 is removed from the blocklist after the timeout duration.
        // By default there is no blocklist streak so the timeout duration is simply
        // BASE_BLOCKLIST_DURATION
        when(mClock.getWallClockMillis()).thenReturn(BASE_BLOCKLIST_DURATION + 1);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
    }

    /**
     * Verify that when adding a AP that had already been failing (therefore has a blocklist
     * streak), we are setting the blocklist duration using an exponential backoff technique.
     */
    @Test
    public void testBssidIsRemoveFromBlocklistAfterTimoutExponentialBackoff() {
        verifyAddTestBssidToBlocklist();
        int multiplier = 2;
        for (int i = 1; i <= FAILURE_STREAK_CAP; i++) {
            when(mWifiScoreCard.getBssidBlocklistStreak(anyString(), anyString(), anyInt()))
                    .thenReturn(i);
            when(mClock.getWallClockMillis()).thenReturn(0L);
            verifyAddTestBssidToBlocklist();

            // calculate the expected blocklist duration then verify that timeout happens
            // exactly after the duration.
            long duration = multiplier * BASE_BLOCKLIST_DURATION;
            when(mClock.getWallClockMillis()).thenReturn(duration);
            assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
            when(mClock.getWallClockMillis()).thenReturn(duration + 1);
            assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

            multiplier *= 2;
        }

        // finally verify that the timout is capped at some max value
        when(mWifiScoreCard.getBssidBlocklistStreak(anyString(), anyString(), anyInt()))
                .thenReturn(FAILURE_STREAK_CAP + 1);
        when(mClock.getWallClockMillis()).thenReturn(0L);
        verifyAddTestBssidToBlocklist();
        when(mClock.getWallClockMillis()).thenReturn(MAX_BLOCKLIST_DURATION);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
        when(mClock.getWallClockMillis()).thenReturn(MAX_BLOCKLIST_DURATION + 1);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
    }

    /**
     * Verify that consecutive failures will add a BSSID to blocklist.
     */
    @Test
    public void testRepeatedConnectionFailuresAddToBlocklist() {
        // First verify that n-1 failrues does not add the BSSID to blocklist
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Simulate a long time passing to make sure failure counters are not being cleared through
        // some time based check
        when(mClock.getWallClockMillis()).thenReturn(10 * BASE_BLOCKLIST_DURATION);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Verify that 1 more failure will add the BSSID to blacklist.
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE, 1);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
    }

    /**
     * Verify that when the BSSID blocklist streak is greater or equal to 1, then we block a
     * BSSID on a single failure regardless of failure type.
     */
    @Test
    public void testBlocklistStreakExpeditesAddingToBlocklist() {
        when(mWifiScoreCard.getBssidBlocklistStreak(anyString(), anyString(), anyInt()))
                .thenReturn(1);
        assertTrue(mBssidBlocklistMonitor.handleBssidConnectionFailure(
                TEST_BSSID_1, TEST_SSID_1, TEST_L2_FAILURE));
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
    }

    /**
     * Verify that onSuccessfulConnection resets L2 related failure counts.
     */
    @Test
    public void testL2FailureCountIsResetAfterSuccessfulConnection() {
        // First simulate getting a particular L2 failure n-1 times
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);

        // Verify that a connection success event will clear the failure count.
        mBssidBlocklistMonitor.handleBssidConnectionSuccess(TEST_BSSID_1, TEST_SSID_1);
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);

        // Verify we have not blocklisted anything yet because the failure count was cleared.
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Verify that TEST_BSSID_1 is added to blocklist after 1 more failure.
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE, 1);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
    }

    /**
     * Verify that handleDhcpProvisioningSuccess resets DHCP failure counts.
     */
    @Test
    public void testL3FailureCountIsResetAfterDhcpConfiguration() {
        // First simulate getting an DHCP failure n-1 times.
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_DHCP_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);

        // Verify that a dhcp provisioning success event will clear appropirate failure counts.
        mBssidBlocklistMonitor.handleDhcpProvisioningSuccess(TEST_BSSID_1, TEST_SSID_1);
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_DHCP_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);

        // Verify we have not blocklisted anything yet because the failure count was cleared.
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Verify that TEST_BSSID_1 is added to blocklist after 1 more failure.
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_DHCP_FAILURE, 1);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
    }

    /**
     * Verify that handleBssidConnectionSuccess resets appropriate blocklist streak counts.
     */
    @Test
    public void testNetworkConnectionResetsBlocklistStreak() {
        mBssidBlocklistMonitor.handleBssidConnectionSuccess(TEST_BSSID_1, TEST_SSID_1);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_WRONG_PASSWORD);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_EAP_FAILURE);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_ASSOCIATION_REJECTION);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_ASSOCIATION_TIMEOUT);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_AUTHENTICATION_FAILURE);
    }

    /**
     * Verify that handleDhcpProvisioningSuccess resets appropriate blocklist streak counts.
     */
    @Test
    public void testDhcpProvisioningResetsBlocklistStreak() {
        mBssidBlocklistMonitor.handleDhcpProvisioningSuccess(TEST_BSSID_1, TEST_SSID_1);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_DHCP_FAILURE);
    }

    /**
     * Verify that handleNetworkValidationSuccess resets appropriate blocklist streak counts.
     */
    @Test
    public void testNetworkValidationResetsBlocklistStreak() {
        mBssidBlocklistMonitor.handleNetworkValidationSuccess(TEST_BSSID_1, TEST_SSID_1);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_NETWORK_VALIDATION_FAILURE);
    }

    /**
     * Verify that L3 failure counts are not affected when L2 failure counts are reset.
     */
    @Test
    public void testL3FailureCountIsNotResetByConnectionSuccess() {
        // First simulate getting an L3 failure n-1 times.
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_DHCP_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Verify that the failure counter is not cleared by |handleBssidConnectionSuccess|.
        mBssidBlocklistMonitor.handleBssidConnectionSuccess(TEST_BSSID_1, TEST_SSID_1);
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_DHCP_FAILURE, 1);
        assertEquals(1, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
    }

    /**
     * Verify that the blocklist streak is incremented after adding a BSSID to blocklist.
     * And then verify the blocklist streak is not reset by a regular timeout.
     */
    public void testIncrementingBlocklistStreakCount() {
        verifyAddTestBssidToBlocklist();
        // verify that the blocklist streak is incremented
        verify(mWifiScoreCard).incrementBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA);

        // Verify that TEST_BSSID_1 is removed from the blocklist after the timeout duration.
        when(mClock.getWallClockMillis()).thenReturn(BASE_BLOCKLIST_DURATION + 1);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // But the blacklist streak count is not cleared
        verify(mWifiScoreCard, never()).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA);
    }

    /**
     * Verify that when a failure signal is received for a BSSID with different SSID from before,
     * then the failure counts are reset.
     */
    @Test
    public void testFailureCountIsResetIfSsidChanges() {
        // First simulate getting a particular L2 failure n-1 times
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);

        // Verify that when the SSID changes, the failure counts are cleared.
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_SSID_2, TEST_L2_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);

        // Verify we have not blocklisted anything yet because the failure count was cleared.
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Verify that TEST_BSSID_1 is added to blocklist after 1 more failure.
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_SSID_2, TEST_L2_FAILURE, 1);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
    }

    /**
     * Verify that a BSSID is not added to blocklist as long as
     * mWifiLastResortWatchdog.shouldIgnoreBssidUpdate is returning true.
     */
    @Test
    public void testWatchdogIsGivenChanceToTrigger() {
        // Verify that |shouldIgnoreBssidUpdate| can prevent a BSSID from being added to blocklist.
        when(mWifiLastResortWatchdog.shouldIgnoreBssidUpdate(anyString())).thenReturn(true);
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST * 2);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Verify that after watchdog is okay with blocking a BSSID, it gets blocked after 1
        // more failure.
        when(mWifiLastResortWatchdog.shouldIgnoreBssidUpdate(anyString())).thenReturn(false);
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE, 1);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
    }

    /**
     * Verify that we are correctly filtering by SSID when sending a blocklist down to firmware.
     */
    @Test
    public void testSendBlocklistToFirmwareFilterBySsid() {
        verifyAddMultipleBssidsToBlocklist();

        // Verify we are sending 2 BSSIDs down to the firmware for SSID_1.
        ArrayList<String> blocklist1 = new ArrayList<>();
        blocklist1.add(TEST_BSSID_2);
        blocklist1.add(TEST_BSSID_1);
        mBssidBlocklistMonitor.updateFirmwareRoamingConfiguration(TEST_SSID_1);
        verify(mWifiConnectivityHelper).setFirmwareRoamingConfiguration(eq(blocklist1),
                eq(new ArrayList<>()));

        // Verify we are sending 1 BSSID down to the firmware for SSID_2.
        ArrayList<String> blocklist2 = new ArrayList<>();
        blocklist2.add(TEST_BSSID_3);
        mBssidBlocklistMonitor.updateFirmwareRoamingConfiguration(TEST_SSID_2);
        verify(mWifiConnectivityHelper).setFirmwareRoamingConfiguration(eq(blocklist2),
                eq(new ArrayList<>()));

        // Verify we are not sending any BSSIDs down to the firmware since there does not
        // exists any BSSIDs for TEST_SSID_3 in the blocklist.
        mBssidBlocklistMonitor.updateFirmwareRoamingConfiguration(TEST_SSID_3);
        verify(mWifiConnectivityHelper).setFirmwareRoamingConfiguration(eq(new ArrayList<>()),
                eq(new ArrayList<>()));
    }

    /**
     * Verify that when sending the blocklist down to firmware, the list is sorted by latest
     * end time first.
     * Also verify that when there are more blocklisted BSSIDs than the allowed limit by the
     * firmware, the list sent down is trimmed.
     */
    @Test
    public void testMostRecentBlocklistEntriesAreSentToFirmware() {
        // Add BSSIDs to blocklist
        String bssid = "0a:08:5c:67:89:0";
        for (int i = 0; i < 10; i++) {
            when(mClock.getWallClockMillis()).thenReturn((long) i);
            mBssidBlocklistMonitor.handleBssidConnectionFailure(bssid + i,
                    TEST_SSID_1, BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA);

            // This will build a List of BSSIDs starting from the latest added ones that is at
            // most size |TEST_NUM_MAX_FIRMWARE_SUPPORT_BSSIDS|.
            // Then verify that the blocklist is sent down in this sorted order.
            ArrayList<String> blocklist = new ArrayList<>();
            for (int j = i; j > i - TEST_NUM_MAX_FIRMWARE_SUPPORT_BSSIDS; j--) {
                if (j < 0) {
                    break;
                }
                blocklist.add(bssid + j);
            }
            mBssidBlocklistMonitor.updateFirmwareRoamingConfiguration(TEST_SSID_1);
            verify(mWifiConnectivityHelper).setFirmwareRoamingConfiguration(eq(blocklist),
                    eq(new ArrayList<>()));
        }
        assertEquals(10, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
    }

    /**
     * Verifies that when firmware roaming is disabled, the blocklist does not get plumbed to
     * hardware, but the blocklist should still accessible by the framework.
     */
    @Test
    public void testFirmwareRoamingDisabled() {
        when(mWifiConnectivityHelper.isFirmwareRoamingSupported()).thenReturn(false);
        verifyAddTestBssidToBlocklist();

        mBssidBlocklistMonitor.updateFirmwareRoamingConfiguration(TEST_SSID_1);
        verify(mWifiConnectivityHelper, never()).setFirmwareRoamingConfiguration(any(), any());
    }

    /**
     * Verify that clearBssidBlocklist resets internal state.
     */
    @Test
    public void testClearBssidBlocklist() {
        verifyAddTestBssidToBlocklist();
        mBssidBlocklistMonitor.clearBssidBlocklist();
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
    }

    /**
     * Verify that the BSSID blocklist is cleared for the entire network.
     */
    @Test
    public void testClearBssidBlocklistForSsid() {
        verifyAddMultipleBssidsToBlocklist();

        // Clear the blocklist for SSID 1.
        mBssidBlocklistMonitor.clearBssidBlocklistForSsid(TEST_SSID_1);

        // Verify that the blocklist is deleted for SSID 1 and the BSSID for SSID 2 still remains.
        Set<String> bssidList = mBssidBlocklistMonitor.updateAndGetBssidBlocklist();
        assertEquals(1, bssidList.size());
        assertTrue(bssidList.contains(TEST_BSSID_3));
    }
}
