package cc.polysfaer.stochapop.ads

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.google.firebase.analytics.FirebaseAnalytics

object  AdManager {
    private const val TAG = "AdMobManager"

    private var appContext: Context? = null
    @Volatile
    private var initialized = false
    @Volatile
    private var initializing = false
    @Volatile
    private var enabled = false
    @Volatile
    private var interstitialAd: InterstitialAd? = null
    @Volatile
    private var rewardedAd: RewardedAd? = null
    @Volatile
    private var interstitialLoading = false
    @Volatile
    private var rewardedLoading = false
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    // =========================================================
    // 初始化
    // =========================================================

    /**
     * 在 Application.onCreate()
     * 或首个 Activity.onCreate() 调用一次
     */
    fun init(context: Context) {

        synchronized(this) {
            if (initialized || initializing) {
                return
            }

            initializing = true
            appContext = context.applicationContext

            firebaseAnalytics =
                FirebaseAnalytics.getInstance(appContext!!)
        }

        Thread {

            try {

                val context = appContext ?: return@Thread

                val initializationConfig =
                    InitializationConfig.Builder(
                        AdUnitId.ADMOB_APP_ID
                    ).build()

                MobileAds.initialize(
                    context,
                    initializationConfig
                ) {

                    Log.d(TAG, "AdMob adapters initialized")

                    initialized = true
                    initializing = false

                    loadInterstitialAd()
                    loadRewardedAd()

                    Log.d(TAG, "AdMob init over")
                }

            } catch (e: Exception) {

                initializing = false

                Log.e(
                    TAG,
                    "AdMob init failed",
                    e
                )
            }

        }.start()
    }

    // =========================================================
    // 开关
    // =========================================================

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean {
        return enabled
    }

    // =========================================================
    // 插屏
    // =========================================================

    fun loadInterstitialAd() {

        if (!initialized) {
            return
        }

        synchronized(this) {

            if (interstitialLoading) {
                return
            }

            if (interstitialAd != null) {
                return
            }

            interstitialLoading = true
        }

        val request =
            AdRequest.Builder(
                AdUnitId.INTERSTITIAL_ADUNITID
            ).build()

        InterstitialAd.load(
            request,
            object : AdLoadCallback<InterstitialAd> {

                override fun onAdLoaded(ad: InterstitialAd) {

                    Log.d(TAG, "Interstitial loaded")

                    interstitialLoading = false
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(
                    error: LoadAdError
                ) {

                    Log.d(
                        TAG,
                        "Interstitial load fail: ${error.message}"
                    )

                    interstitialLoading = false
                    interstitialAd = null
                }
            }
        )
    }


    fun showInterstitialAd(
        activity: Activity,
        afterAdClosed: (() -> Unit)? = null
    ) {

        val ad: InterstitialAd?

        synchronized(this) {

            ad = interstitialAd

            // 广告拿出来以后立刻置空，
            // 防止短时间内 show 两次同一条广告
            if (ad != null) {
                interstitialAd = null
            }
        }

        if (ad == null) {

            activity.runOnUiThread {
                afterAdClosed?.invoke()
            }

            return
        }


        ad.adEventCallback =
            object : InterstitialAdEventCallback {

                override fun onAdShowedFullScreenContent() {

                    Log.d(
                        TAG,
                        "Interstitial showed"
                    )
                }

                override fun onAdDismissedFullScreenContent() {

                    Log.d(
                        TAG,
                        "Interstitial dismissed"
                    )

                    loadInterstitialAd()

                    activity.runOnUiThread {
                        afterAdClosed?.invoke()
                    }
                }

                override fun onAdFailedToShowFullScreenContent(
                    error: FullScreenContentError
                ) {

                    Log.d(
                        TAG,
                        "Interstitial show fail: ${error.message}"
                    )

                    loadInterstitialAd()

                    activity.runOnUiThread {
                        afterAdClosed?.invoke()
                    }
                }

                override fun onAdPaid(
                    value: AdValue
                ) {

                    logAdRevenue(
                        adValue = value,
                        format = "interstitial",
                        unitName = "interstitialAd"
                    )
                }
            }


        activity.runOnUiThread {

            ad.show(activity)
        }
    }

    fun isInterstitialReady(): Boolean {
        return interstitialAd != null
    }

    // =========================================================
    // 激励广告
    // =========================================================

    fun loadRewardedAd() {

        if (!initialized) {
            return
        }

        synchronized(this) {

            if (rewardedLoading) {
                return
            }

            if (rewardedAd != null) {
                return
            }

            rewardedLoading = true
        }

        val request =
            AdRequest.Builder(
                AdUnitId.REWARDVIDEO_ADUNITID
            ).build()


        RewardedAd.load(
            request,
            object : AdLoadCallback<RewardedAd> {

                override fun onAdLoaded(ad: RewardedAd) {

                    Log.d(TAG, "Rewarded loaded")

                    rewardedLoading = false
                    rewardedAd = ad
                }

                override fun onAdFailedToLoad(
                    error: LoadAdError
                ) {

                    Log.d(
                        TAG,
                        "Rewarded load fail: ${error.message}"
                    )

                    rewardedLoading = false
                    rewardedAd = null
                }
            }
        )
    }


    fun showRewardedAd(
        activity: Activity,
        rewardListener: OnUserEarnedRewardListener? = null,
        afterAdClosed: (() -> Unit)? = null
    ) {

        val ad: RewardedAd?

        synchronized(this) {

            ad = rewardedAd

            if (ad != null) {
                rewardedAd = null
            }
        }


        if (ad == null) {

            activity.runOnUiThread {
                afterAdClosed?.invoke()
            }

            return
        }


        ad.adEventCallback =
            object : RewardedAdEventCallback {

                override fun onAdShowedFullScreenContent() {

                    Log.d(
                        TAG,
                        "Rewarded showed"
                    )
                }

                override fun onAdDismissedFullScreenContent() {

                    Log.d(
                        TAG,
                        "Rewarded dismissed"
                    )

                    loadRewardedAd()

                    activity.runOnUiThread {
                        afterAdClosed?.invoke()
                    }
                }

                override fun onAdFailedToShowFullScreenContent(
                    error: FullScreenContentError
                ) {

                    Log.d(
                        TAG,
                        "Rewarded show fail: ${error.message}"
                    )

                    loadRewardedAd()

                    activity.runOnUiThread {
                        afterAdClosed?.invoke()
                    }
                }

                override fun onAdPaid(
                    value: AdValue
                ) {

                    logAdRevenue(
                        adValue = value,
                        format = "rewarded",
                        unitName = "rewardedAd"
                    )
                }
            }


        activity.runOnUiThread {

            ad.show(activity) { reward ->

                // Next-Gen 广告回调可能运行在后台线程，
                // 保持你以前的使用习惯，切回主线程
                activity.runOnUiThread {

                    rewardListener?.onUserEarnedReward(
                        reward
                    )
                }
            }
        }
    }


    fun isRewardedReady(): Boolean {
        return rewardedAd != null
    }

    // =========================================================
    // 概率触发
    // =========================================================

    /**
     * 简洁版：
     * 50% 触发广告
     * 其中激励 40%
     * 插屏 60%
     */
    fun maybeShowAd(
        activity: Activity
    ) {

        if (!enabled) {
            return
        }

        if (Math.random() < 0.5) {

            if (Math.random() < 0.4) {

                showRewardedAdConfirmDialog(
                    activity
                )

            } else {

                showInterstitialAd(
                    activity
                )
            }
        }
    }


    /**
     * 带回调版本
     */
    fun maybeShowAd(
        activity: Activity,
        adRate: Double = 0.1,
        afterAdClosed: (() -> Unit)? = null
    ) {

        Log.d(
            TAG,
            "enabled = $enabled"
        )


        if (!enabled) {

            activity.runOnUiThread {
                afterAdClosed?.invoke()
            }

            return
        }


        if (Math.random() < adRate) {

            val pickRewarded =
                Math.random() < 0.4


            if (pickRewarded) {

                showRewardedAdConfirmDialog(
                    activity,
                    afterAdClosed
                )

            } else {

                showInterstitialAd(
                    activity,
                    afterAdClosed
                )
            }

        } else {

            activity.runOnUiThread {
                afterAdClosed?.invoke()
            }
        }
    }


    // =========================================================
    // 激励确认弹窗
    // =========================================================

    private fun showRewardedAdConfirmDialog(
        activity: Activity,
        afterAdClosed: (() -> Unit)? = null
    ) {

        if (rewardedAd == null) {

            Log.d(
                TAG,
                "RewardedAd not ready, skip dialog"
            )

            activity.runOnUiThread {
                afterAdClosed?.invoke()
            }

            return
        }


        activity.runOnUiThread {

            AlertDialog.Builder(activity)
                .setTitle(
                    "WATCH AD TO SUPPORT US?"
                )
                .setMessage(
                    "Would you?"
                )
                .setCancelable(true)
                .setPositiveButton(
                    "YES"
                ) { dialog, _ ->

                    dialog.dismiss()

                    showRewardedAd(
                        activity,
                        afterAdClosed = afterAdClosed
                    )
                }
                .setNegativeButton(
                    "NO"
                ) { dialog, _ ->

                    dialog.dismiss()

                    afterAdClosed?.invoke()
                }
                .show()
        }
    }


    // =========================================================
    // 广告收益
    // =========================================================

    private fun logAdRevenue(
        adValue: AdValue,
        format: String,
        unitName: String
    ) {

        val value =
            adValue.valueMicros / 1_000_000.0

        val currency =
            adValue.currencyCode


        val bundle =
            Bundle().apply {

                putString(
                    FirebaseAnalytics.Param.AD_PLATFORM,
                    "AdMob"
                )

                putString(
                    FirebaseAnalytics.Param.AD_SOURCE,
                    "Google"
                )

                putString(
                    FirebaseAnalytics.Param.AD_FORMAT,
                    format
                )

                putString(
                    FirebaseAnalytics.Param.AD_UNIT_NAME,
                    unitName
                )

                putString(
                    FirebaseAnalytics.Param.CURRENCY,
                    currency
                )

                putDouble(
                    FirebaseAnalytics.Param.VALUE,
                    value
                )
            }


        firebaseAnalytics.logEvent(
            FirebaseAnalytics.Event.AD_IMPRESSION,
            bundle
        )
    }
}