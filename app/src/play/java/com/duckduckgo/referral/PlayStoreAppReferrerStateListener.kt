/*
 * Copyright (c) 2019 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.referral

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse.*
import com.android.installreferrer.api.InstallReferrerStateListener
import com.duckduckgo.app.pixels.AppPixelName.DMA_CHOICE_SCREEN_DEFAULT_BROWSER_LEGACY_INSTALL
import com.duckduckgo.app.pixels.AppPixelName.DMA_CHOICE_SCREEN_SEARCH_CHOICE_LEGACY_INSTALL
import com.duckduckgo.app.playstore.PlayStoreAndroidUtils.Companion.PLAY_STORE_PACKAGE
import com.duckduckgo.app.playstore.PlayStoreAndroidUtils.Companion.PLAY_STORE_REFERRAL_SERVICE
import com.duckduckgo.app.referral.*
import com.duckduckgo.app.referral.AppInstallationReferrerStateListener.Companion.MAX_REFERRER_WAIT_TIME_MS
import com.duckduckgo.app.referral.ParseFailureReason.*
import com.duckduckgo.app.referral.ParsedReferrerResult.*
import com.duckduckgo.app.statistics.AtbInitializerListener
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.appbuildconfig.api.AppBuildConfig
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.experiments.api.VariantManager
import com.duckduckgo.experiments.impl.VariantManagerImpl.Companion.RESERVED_EU_BROWSER_CHOICE_AUCTION_VARIANT
import com.duckduckgo.experiments.impl.VariantManagerImpl.Companion.RESERVED_EU_SEARCH_CHOICE_AUCTION_VARIANT
import dagger.SingleInstanceIn
import javax.inject.Inject
import kotlinx.coroutines.delay
import timber.log.Timber

@SingleInstanceIn(AppScope::class)
class PlayStoreAppReferrerStateListener @Inject constructor(
    val context: Context,
    private val packageManager: PackageManager,
    private val appInstallationReferrerParser: AppInstallationReferrerParser,
    private val appReferrerDataStore: AppReferrerDataStore,
    private val variantManager: VariantManager,
    private val appBuildConfig: AppBuildConfig,
    private val pixel: Pixel,
) : InstallReferrerStateListener, AppInstallationReferrerStateListener, AtbInitializerListener {

    private val referralClient = InstallReferrerClient.newBuilder(context).build()
    private var initialisationStartTime: Long = 0

    private var referralResult: ParsedReferrerResult = ReferrerInitialising

    /**
     * Initialises the referrer service. This should only be called once.
     */
    override fun initialiseReferralRetrieval() {
        try {
            initialisationStartTime = System.currentTimeMillis()

            if (appReferrerDataStore.referrerCheckedPreviously) {
                referralResult = if (appReferrerDataStore.installedFromEuAuction) {
                    EuAuctionSearchChoiceReferrerFound(fromCache = true)
                } else {
                    loadPreviousReferrerData()
                }

                Timber.i("Already inspected this referrer data")
                return
            }

            if (playStoreReferralServiceInstalled()) {
                referralClient.startConnection(this)
            } else {
                referralResult = ParseFailure(ReferralServiceUnavailable)
            }
        } catch (e: RuntimeException) {
            Timber.w(e, "Failed to obtain referrer information")
            referralResult = ParseFailure(UnknownError)
        }
    }

    private fun loadPreviousReferrerData(): ParsedReferrerResult {
        val suffix = loadFromDataStore()
        return if (suffix == null) {
            Timber.i("Already saw referrer data, but no campaign suffix saved")
            ReferrerNotFound(fromCache = true)
        } else {
            Timber.i("Already have referrer data from previous run - $suffix")
            CampaignReferrerFound(suffix, fromCache = true)
        }
    }

    override fun onInstallReferrerSetupFinished(responseCode: Int) {
        val referrerRetrievalDurationMs = System.currentTimeMillis() - initialisationStartTime
        Timber.i("Took ${referrerRetrievalDurationMs}ms to get initial referral data callback")
        try {
            when (responseCode) {
                OK -> {
                    kotlin.runCatching {
                        Timber.d("Successfully connected to Referrer service")
                        val response = referralClient.installReferrer
                        val referrer = response.installReferrer
                        val parsedResult = appInstallationReferrerParser.parse(referrer)
                        referralResultReceived(parsedResult)
                    }.onFailure {
                        Timber.e(it, "Error getting install referrer")
                        referralResultFailed(UnknownError)
                    }
                }
                FEATURE_NOT_SUPPORTED -> referralResultFailed(FeatureNotSupported)
                SERVICE_UNAVAILABLE -> referralResultFailed(ServiceUnavailable)
                DEVELOPER_ERROR -> referralResultFailed(DeveloperError)
                SERVICE_DISCONNECTED -> referralResultFailed(ServiceDisconnected)
                else -> referralResultFailed(UnknownError)
            }

            referralClient.endConnection()
        } catch (e: RuntimeException) {
            Timber.w(e, "Failed to retrieve referrer data")
            referralResultFailed(UnknownError)
        }
    }

    /**
     * Retrieves the app installation referral code.
     * This might return a result immediately or might wait for a result to become available. There is no guarantee that a result will ever be returned.
     *
     * It is the caller's responsibility to guard against this function not returning a result in a timely manner, or not returning a result ever.
     */
    override suspend fun waitForReferrerCode(): ParsedReferrerResult {
        if (referralResult != ReferrerInitialising) {
            Timber.d("Referrer already determined (%s); immediately answering", referralResult.javaClass.simpleName)
            return referralResult
        }

        Timber.i("Referrer: Retrieving referral code from Play Store referrer service")

        // poll, awaiting referral result to become available
        while (referralResult == ReferrerInitialising) {
            Timber.v("Still initialising - waiting")
            delay(10)
        }

        return referralResult
    }

    private fun loadFromDataStore(): String? {
        return appReferrerDataStore.campaignSuffix
    }

    private fun playStoreReferralServiceInstalled(): Boolean {
        val playStoreConnectionServiceIntent = Intent()
        playStoreConnectionServiceIntent.component = ComponentName(PLAY_STORE_PACKAGE, PLAY_STORE_REFERRAL_SERVICE)
        return getMatchingServices(playStoreConnectionServiceIntent).size > 0
    }

    private fun getMatchingServices(serviceIntent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(serviceIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            packageManager.queryIntentServices(serviceIntent, 0)
        }

    private fun referralResultReceived(result: ParsedReferrerResult) {
        referralResult = result

        when (result) {
            is CampaignReferrerFound -> {
                variantManager.updateAppReferrerVariant(result.campaignSuffix)
                appReferrerDataStore.campaignSuffix = result.campaignSuffix
            }
            is EuAuctionSearchChoiceReferrerFound -> {
                variantManager.updateAppReferrerVariant(RESERVED_EU_SEARCH_CHOICE_AUCTION_VARIANT)
                appReferrerDataStore.installedFromEuAuction = true
                // to be removed June 10th 2024 -> https://app.asana.com/0/1205278999335242/1207268538033883/f
                if (appBuildConfig.sdkInt < Build.VERSION_CODES.TIRAMISU) {
                    pixel.fire(DMA_CHOICE_SCREEN_SEARCH_CHOICE_LEGACY_INSTALL)
                }
            }
            is EuAuctionBrowserChoiceReferrerFound -> {
                variantManager.updateAppReferrerVariant(RESERVED_EU_BROWSER_CHOICE_AUCTION_VARIANT)
                appReferrerDataStore.installedFromEuAuction = true
                // to be removed June 10th 2024 -> https://app.asana.com/0/1205278999335242/1207268538033883/f
                if (appBuildConfig.sdkInt < Build.VERSION_CODES.TIRAMISU) {
                    pixel.fire(DMA_CHOICE_SCREEN_DEFAULT_BROWSER_LEGACY_INSTALL)
                }
            }
            else -> {}
        }

        appReferrerDataStore.referrerCheckedPreviously = true
    }

    private fun referralResultFailed(reason: ParseFailureReason) {
        referralResult = ParseFailure(reason)
    }

    override fun onInstallReferrerServiceDisconnected() {
        Timber.i("Referrer: ServiceDisconnected")
    }

    override suspend fun beforeAtbInit() {
        waitForReferrerCode()
    }

    override fun beforeAtbInitTimeoutMillis(): Long = MAX_REFERRER_WAIT_TIME_MS
}
