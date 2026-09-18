package com.xilu.sdk.core.manager;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import com.xilu.sdk.ADXiluSdk;
import com.xilu.sdk.ad.ADXiluSplashAd;
import com.xilu.sdk.ad.adapter.ADXiluAdapterBaseAdListener;
import com.xilu.sdk.ad.data.ADXiluAdInfo;
import com.xilu.sdk.ad.data.ADXiluAdType;
import com.xilu.sdk.ad.data.ADXiluBaseAdInfo;
import com.xilu.sdk.ad.data.ADXiluOnceShowAdInfo;
import com.xilu.sdk.ad.data.ADXiluSplashAdInfo;
import com.xilu.sdk.ad.data.IBasePosInfo;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluSplashAdListener;
import com.xilu.sdk.ad.utils.ADXiluLogUtil;
import com.xilu.sdk.core.api.AdRequestReportApi;
import com.xilu.sdk.core.model.InitData;

import java.lang.ref.WeakReference;
import java.util.Map;

/**
 * 热启动开屏广告管理器
 * 负责管理热启动时的开屏广告展示逻辑
 */
public class HotStartSplashManager {
    private static final String TAG = "HotStartSplashManager";

    private static volatile HotStartSplashManager INSTANCE;

    // 配置（由 applySplashLaunch 应用服务端配置）
    private boolean mEnabled = false;                          // 热启动开关（以后端 hotStart 为准）：是否允许 SDK 自己弹
    /** 预加载开关，与 {@link #mEnabled} 解耦；默认与它对齐 */
    private boolean mPreloadEnabled = false;
    private String mPosId = "";                                // 开屏广告位ID（从服务端 posIdMap 获取）
    private long mMinShowInterval = 0;                          // 两次展示最小间隔（毫秒，以后端 hotTime 为准，0 表示不限制）
    private int mHotDiffSlot = 0;                              // 是否区分冷热广告位：0不区分 1区分
    private int mHotDailyLimit = 0;                           // 每日展示次数上限：0不限制

    // 每日展示计数 SP key（按日期区分，跨天自动重置）
    private static final String SP_KEY_DAILY_COUNT = "hot_start_daily_count";
    private static final String SP_KEY_DAILY_DATE = "hot_start_daily_date";

    /**
     * 广告展示时调用，更新展示时间戳（冷启动和热启动通用）
     */
    public void onAdDisplayed() {
        mLastShowTime = System.currentTimeMillis();
    }

    // 状态
    private long mLastShowTime = 0;                            // 上次展示时间
    private WeakReference<Activity> mCurrentActivityRef;       // 当前Activity引用
    private WeakReference<ViewGroup> mDecorViewRef;            // DecorView引用
    private FrameLayout mSplashContainer;                      // 开屏广告容器
    private ADXiluSplashAd mSplashAd;                          // 开屏广告对象
    private boolean mIsShowing = false;                        // 是否正在展示
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private Runnable mAutoCloseRunnable;                       // 超时自动关闭Runnable
    private static final long AUTO_CLOSE_DELAY_MS = 5000;      // 超时自动关闭时间：5秒

    // 当前展示广告的上报信息
    private String mCurrentPlatform;
    private String mCurrentMaterialId;
    private double mCurrentEcpm;
    private boolean mDisplayReported;                           // display是否已上报（防重复）
    private boolean mCloseReported;                             // close是否已上报（防重复）
    private long mLastClickTime;                                // 上次点击时间（防重复点击）

    private HotStartSplashManager() {
    }

    public static HotStartSplashManager getInstance() {
        if (INSTANCE == null) {
            synchronized (HotStartSplashManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new HotStartSplashManager();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 初始化热启动开屏配置。
     * 服务端 splashLaunch 配置通过 applySplashLaunch() 应用。
     */
    public void init() {
        android.util.Log.d(TAG, "【强制日志】热启动开屏初始化开始（enabled=" + mEnabled
                + ", interval=" + mMinShowInterval + "ms）");
    }

    /**
     * 应用服务端 splashLaunch 配置（后端为准，后端没返回则重置为关闭状态）。
     * 在 InitData 解析完成后调用（本地缓存加载 + 服务端返回两个路径，服务端返回会覆盖本地缓存）。
     */
    public void applySplashLaunch(InitData.SplashLaunch splashLaunch) {
        if (splashLaunch == null) {
            // 后端没返回 splashLaunch，重置为关闭状态
            mEnabled = false;
            mPreloadEnabled = false;
            mMinShowInterval = 0;
            mHotDiffSlot = 0;
            mHotDailyLimit = 0;
            ADXiluLogUtil.d(TAG, "服务端未返回 splashLaunch，重置为关闭状态: enabled=false");
            return;
        }
        // hotStart：以后端为准（1开 0关）
        mEnabled = (splashLaunch.getHotStart() == 1);
        // 预加载开关默认与 hotStart 对齐：任何"只调 applySplashLaunch、不改本标志"的接入方
        // （原生端、历史版本行为）预加载判定与改动前完全一致。
        mPreloadEnabled = mEnabled;
        // hotTime：以后端为准（0 表示不限制间隔）
        mMinShowInterval = splashLaunch.getHotTime() * 1000L;
        // hotDiffSlot / hotDailyLimit：以后端为准
        mHotDiffSlot = splashLaunch.getHotDiffSlot();
        mHotDailyLimit = splashLaunch.getHotDailyLimit();
        ADXiluLogUtil.d(TAG, "应用服务端 splashLaunch: enabled=" + mEnabled
                + ", preloadEnabled=" + mPreloadEnabled
                + ", interval=" + mMinShowInterval + "ms, hotDiffSlot=" + mHotDiffSlot
                + ", dailyLimit=" + mHotDailyLimit);
    }

    /** 单独开关热启动预加载，不影响是否由 SDK 自己展示热启动 */
    public void setPreloadEnabled(boolean enabled) {
        this.mPreloadEnabled = enabled;
        ADXiluLogUtil.d(TAG, "setPreloadEnabled: " + enabled + " (displayEnabled=" + mEnabled + ")");
    }

    /**
     * 从服务端配置获取开屏广告位ID
     */
    private void fetchSplashPosId() {
        if (!TextUtils.isEmpty(mPosId)) {
            return; // 已经获取过了
        }

        InitData initData = ADSdkManager.getInstance().getInitData();
        if (initData != null && initData.getPosIdMap() != null) {
            for (Map.Entry<String, IBasePosInfo> entry : initData.getPosIdMap().entrySet()) {
                IBasePosInfo posInfo = entry.getValue();
                if (posInfo != null && ADXiluAdType.TYPE_SPLASH.equals(posInfo.getAdType())) {
                    mPosId = entry.getKey();
                    break;
                }
            }
        }
    }

    /**
     * 处理热启动
     * @param activity 当前Activity
     * @param backgroundInterval 在后台的时间间隔
     */
    public void onHotStart(Activity activity, long backgroundInterval) {
        // 先获取开屏广告位ID
        fetchSplashPosId();

        ADXiluLogUtil.d(TAG, "热启动检测: activity=" + activity.getClass().getSimpleName()
                + ", 后台时间=" + backgroundInterval + "ms");

        // 检查是否启用
        if (!mEnabled) {
            ADXiluLogUtil.d(TAG, "热启动开屏未启用");
            return;
        }

        // 检查广告位ID
        if (TextUtils.isEmpty(mPosId)) {
            ADXiluLogUtil.w(TAG, "热启动开屏广告位ID未配置");
            return;
        }

        // 检查展示间隔（距上次展示的时间）
        long currentTime = System.currentTimeMillis();
        if (mMinShowInterval > 0 && currentTime - mLastShowTime < mMinShowInterval) {
            ADXiluLogUtil.d(TAG, "展示间隔不足: " + (currentTime - mLastShowTime) + "ms < " + mMinShowInterval + "ms");
            return;
        }

        // 检查每日展示次数上限
        if (isDailyLimitReached()) {
            ADXiluLogUtil.d(TAG, "今日热启动展示次数已达上限: " + mHotDailyLimit);
            return;
        }

        // 检查当前是否正在展示
        if (mIsShowing) {
            ADXiluLogUtil.d(TAG, "开屏广告正在展示，跳过");
            return;
        }

        // 先检查缓存：命中才展示缓存广告，未命中则忽略（不展示），二者都触发预加载竞价
        HotStartSplashPreloadManager.PreloadCache cache = HotStartSplashPreloadManager.getInstance().getValidCache(mPosId);
        boolean cacheHit = (cache != null && cache.adInfo instanceof ADXiluSplashAdInfo);
        android.util.Log.d("HotStartDebug", "onHotStart: posId=" + mPosId + ", cacheHit=" + cacheHit + ", cache=" + (cache != null ? cache.platform + "/" + cache.adInfo : "null"));

        if (cacheHit) {
            // 缓存命中：展示缓存广告
            hideSoftKeyboard(activity);
            ADXiluLogUtil.d(TAG, "热启动命中预加载缓存，展示广告: posId=" + mPosId);
            showSplashAd(activity);
        } else {
            // 缓存未命中：不展示广告（按需求"不可用就忽略"），仅触发预加载竞价
            android.util.Log.d("HotStartDebug", "缓存未命中，不展示广告，仅触发预加载竞价");
        }

        // 无论缓存是否命中，都触发下一轮预加载竞价（过滤 BZ/MS）
        // 延迟1秒，避免竞价网络请求与广告渲染争抢资源
        final Activity preloadActivity = activity;
        mMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                HotStartSplashPreloadManager.getInstance().startPreloadBid(mPosId, preloadActivity, true);
            }
        }, 1000);
    }

    /**
     * 展示开屏广告
     */
    private void showSplashAd(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        try {
            // 获取DecorView
            ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
            if (decorView == null) {
                return;
            }

            mCurrentActivityRef = new WeakReference<>(activity);
            mDecorViewRef = new WeakReference<>(decorView);

            // 创建广告容器
            mSplashContainer = new FrameLayout(activity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            params.gravity = Gravity.CENTER;
            mSplashContainer.setLayoutParams(params);

            // 设置可点击，拦截所有触摸事件
            mSplashContainer.setClickable(true);
            mSplashContainer.setFocusable(true);
            mSplashContainer.setFocusableInTouchMode(true);

            // 处理返回键
            mSplashContainer.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                        // 返回键跳过广告
                        skipSplashAd();
                        return true;
                    }
                    return false;
                }
            });

            // 添加到DecorView
            decorView.addView(mSplashContainer);
            // 必须在addView后requestFocus，否则OnKeyListener无法接收返回键
            mSplashContainer.requestFocus();
            mIsShowing = true;
            mLastShowTime = System.currentTimeMillis();
            // 决定展示时计数+1（按日期区分，跨天自动重置）
            incrementDailyCount();

            // 启动5秒超时自动关闭，防止SDK无回调导致mIsShowing卡死
            startAutoCloseTimer();

            // 加载广告
            loadAndShowSplashAd(activity);

        } catch (Exception e) {
            ADXiluLogUtil.e(TAG, "展示开屏广告异常: " + e.getMessage());
            cleanup();
        }
    }

    /**
     * 加载并展示开屏广告
     */
    private void loadAndShowSplashAd(Activity activity) {
        mSplashAd = new ADXiluSplashAd(activity);
        // 热启动模式：由本管理器统一控制预加载竞价，避免 SplashAdLooper 重复触发
        mSplashAd.setHotStartMode(true);
        mSplashAd.setListener(createSplashAdListener());

        // 优先使用预加载缓存
        HotStartSplashPreloadManager.PreloadCache cache = HotStartSplashPreloadManager.getInstance().getValidCache(mPosId);
        if (cache != null && cache.adInfo instanceof ADXiluSplashAdInfo) {
            ADXiluLogUtil.d(TAG, "热启动命中预加载缓存: posId=" + mPosId + ", platform=" + cache.platform);
            ADXiluSplashAdInfo splashAdInfo = (ADXiluSplashAdInfo) cache.adInfo;
            // 保存广告信息供上报使用
            mCurrentPlatform = cache.platform;
            mCurrentMaterialId = splashAdInfo.getMaterialId();
            mCurrentEcpm = splashAdInfo.getECPM();
            mDisplayReported = false;
            mCloseReported = false;
            mLastClickTime = 0;
            if (splashAdInfo instanceof ADXiluBaseAdInfo) {
                ((ADXiluBaseAdInfo<ADXiluSplashAdListener, ?>) splashAdInfo).setAdListener(mSplashAd.getListener());
            }
            // 更新平台适配器 Listener 的回调监听器，使 SDK 回调能传递到 HotStartSplashManager
            if (cache.adapterListener != null) {
                @SuppressWarnings("unchecked")
                ADXiluAdapterBaseAdListener<ADXiluSplashAdListener> adapterListener =
                        (ADXiluAdapterBaseAdListener<ADXiluSplashAdListener>) cache.adapterListener;
                adapterListener.setAdListener(mSplashAd.getListener());
            }
            mSplashAd.setAdXiluSplashAdInfo(splashAdInfo);
            // 缓存展示路径需标记为 loadOnly，否则 showSplash 会因 isOnlyLoad=false 跳过
            mSplashAd.setLoadOnly(true);
            if (mSplashContainer != null) {
                // 给 ADXiluSplashAdContainer 设置 listener 和 adInfo，
                // 确保容器倒计时结束（onFinish→c(false)）能触发 onAdClose 回调。
                // mSplashContainer 是 FrameLayout，真正的容器是 mSplashAd.getContainer()。
                // 冷启动中 SplashAdLooper.onAdReceive 会调用 container.render() 设置 adInfo(this.e)，
                // 热启动需在此处补充调用，否则 c(false) 会因 this.e==null 直接 return。
                // 注意：BQT/BZ/CSJ 的 showSplash 不会设置 setSplashAdListener（只在 Loader 中设置），
                // GDT 也只在 onADLoaded 中设置（预加载容器），所以这里必须显式设置。
                if (mSplashAd.getContainer() != null) {
                    mSplashAd.getContainer().setSplashAdListener(mSplashAd.getListener());
                    mSplashAd.getContainer().render(splashAdInfo, true, mSplashAd);
                }
                mSplashAd.showSplash(mSplashContainer);
            }
            return;
        }

        // 缓存未命中：不展示广告，不调 loadOnly（避免与 onHotStart 中的 startPreloadBid 重复发起竞价）
        // 预加载竞价由 onHotStart 统一触发，胜出后缓存供下次热启动使用
        ADXiluLogUtil.d(TAG, "热启动未命中缓存，不展示广告，等待预加载竞价完成: posId=" + mPosId);
    }

    /**
     * 创建热启动开屏广告监听器
     */
    private ADXiluSplashAdListener createSplashAdListener() {
        return new ADXiluSplashAdListener() {
            @Override
            public void onADTick(long l) {
            }

            @Override
            public void onAdReceive(ADXiluAdInfo adInfo) {
                // 展示广告
                if (mSplashContainer != null && mSplashAd != null) {
                    mSplashAd.showSplash(mSplashContainer);
                }
            }

            @Override
            public void onReward(ADXiluAdInfo adInfo) {
            }

            @Override
            public void onAdSkip(ADXiluAdInfo adInfo) {
                ADXiluLogUtil.d(TAG, "热启动开屏广告跳过");
                reportCloseEvent(adInfo);
                cleanup();
            }

            @Override
            public void onAdExpose(ADXiluAdInfo adInfo) {
                reportDisplayEvent(adInfo);
            }

            @Override
            public void onAdClick(ADXiluAdInfo adInfo) {
                reportClickEvent(adInfo);
            }

            @Override
            public void onAdClose(ADXiluAdInfo adInfo) {
                reportCloseEvent(adInfo);
                cleanup();
            }

            @Override
            public void onAdFailed(ADXiluError adxiluError) {
                ADXiluLogUtil.e(TAG, "热启动开屏广告加载失败: " + (adxiluError != null ? adxiluError.toString() : "null"));
                cleanup();
            }
        };
    }

    /**
     * 上报 display 事件（防重复）
     */
    private void reportDisplayEvent(ADXiluAdInfo adInfo) {
        if (mDisplayReported) {
            return;
        }
        mDisplayReported = true;
        String materialId = adInfo != null ? adInfo.getMaterialId() : mCurrentMaterialId;
        double ecpm = adInfo != null ? adInfo.getECPM() : mCurrentEcpm;
        if (ecpm < 0) ecpm = 0;
        ADXiluLogUtil.d(TAG, "热启动开屏广告上报display: platform=" + mCurrentPlatform + ", materialId=" + materialId + ", ecpm=" + ecpm);
        AdRequestReportApi.reportQuick(ADXiluSdk.getInstance().getContext(),
                AdRequestReportApi.EVENT_DISPLAY, mPosId, AdRequestReportApi.AD_FORMAT_SPLASH_LAUNCH,
                null, mCurrentPlatform, materialId, ecpm);
    }

    /**
     * 上报 click 事件（防重复：1秒内只报一次）
     */
    private void reportClickEvent(ADXiluAdInfo adInfo) {
        long now = System.currentTimeMillis();
        if (now - mLastClickTime < 1000) {
            ADXiluLogUtil.d(TAG, "热启动开屏广告跳过重复click上报，间隔=" + (now - mLastClickTime) + "ms");
            return;
        }
        mLastClickTime = now;
        String platform = adInfo != null ? adInfo.getPlatform() : mCurrentPlatform;
        String materialId = adInfo != null ? adInfo.getMaterialId() : mCurrentMaterialId;
        double ecpm = adInfo != null ? adInfo.getECPM() : mCurrentEcpm;
        if (ecpm < 0) ecpm = 0;
        ADXiluLogUtil.d(TAG, "热启动开屏广告上报click: platform=" + platform + ", materialId=" + materialId + ", ecpm=" + ecpm);
        AdRequestReportApi.reportQuick(ADXiluSdk.getInstance().getContext(),
                AdRequestReportApi.EVENT_CLICK, mPosId, AdRequestReportApi.AD_FORMAT_SPLASH_LAUNCH,
                null, platform, materialId, ecpm);
    }

    /**
     * 上报 close 事件（防重复）
     */
    private void reportCloseEvent(ADXiluAdInfo adInfo) {
        if (mCloseReported) {
            return;
        }
        mCloseReported = true;
        String platform = adInfo != null ? adInfo.getPlatform() : mCurrentPlatform;
        String materialId = adInfo != null ? adInfo.getMaterialId() : mCurrentMaterialId;
        double ecpm = adInfo != null ? adInfo.getECPM() : mCurrentEcpm;
        if (ecpm < 0) ecpm = 0;
        ADXiluLogUtil.d(TAG, "热启动开屏广告上报close: platform=" + platform + ", materialId=" + materialId + ", ecpm=" + ecpm);
        AdRequestReportApi.reportQuick(ADXiluSdk.getInstance().getContext(),
                AdRequestReportApi.EVENT_CLOSE, mPosId, AdRequestReportApi.AD_FORMAT_SPLASH_LAUNCH,
                null, platform, materialId, ecpm);
    }

    /**
     * 跳过广告（用户按返回键）
     */
    private void skipSplashAd() {
        reportCloseEvent(null);
        cleanup();
    }

    /**
     * 启动超时自动关闭定时器
     * 防止SDK无回调（onAdClose/onAdSkip/onAdFailed）导致mIsShowing卡死
     */
    private void startAutoCloseTimer() {
        cancelAutoCloseTimer();
        mAutoCloseRunnable = new Runnable() {
            @Override
            public void run() {
                if (mIsShowing) {
                    ADXiluLogUtil.d(TAG, "热启动开屏广告超时自动关闭（5秒无回调）");
                    reportCloseEvent(null);
                    cleanup();
                }
            }
        };
        mMainHandler.postDelayed(mAutoCloseRunnable, AUTO_CLOSE_DELAY_MS);
    }

    /**
     * 取消超时自动关闭定时器
     */
    private void cancelAutoCloseTimer() {
        if (mAutoCloseRunnable != null) {
            mMainHandler.removeCallbacks(mAutoCloseRunnable);
            mAutoCloseRunnable = null;
        }
    }

    /**
     * 清理资源
     */
    private void cleanup() {
        cancelAutoCloseTimer();
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // 释放广告对象
                    if (mSplashAd != null) {
                        mSplashAd.release();
                        mSplashAd = null;
                    }

                    // 从DecorView移除容器
                    if (mDecorViewRef != null && mSplashContainer != null) {
                        ViewGroup decorView = mDecorViewRef.get();
                        if (decorView != null) {
                            decorView.removeView(mSplashContainer);
                        }
                    }

                    mSplashContainer = null;
                    mIsShowing = false;

                    ADXiluLogUtil.d(TAG, "热启动开屏广告资源已清理");

                } catch (Exception e) {
                    ADXiluLogUtil.e(TAG, "清理资源异常: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 隐藏软键盘
     */
    private void hideSoftKeyboard(Activity activity) {
        try {
            if (activity != null && activity.getCurrentFocus() != null) {
                InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(activity.getCurrentFocus().getWindowToken(), 0);
                }
            }
        } catch (Exception e) {
            ADXiluLogUtil.e(TAG, "隐藏软键盘异常: " + e.getMessage());
        }
    }

    /**
     * 检查今日热启动展示次数是否已达上限。
     * mHotDailyLimit=0 表示不限制。按日期区分，跨天自动重置。
     */
    public boolean isDailyLimitReached() {
        if (mHotDailyLimit <= 0) {
            return false;
        }
        long todayStart = getTodayStartMillis();
        long savedDate = SPManager.getInstance().getLong(SP_KEY_DAILY_DATE, 0L);
        if (savedDate != todayStart) {
            // 跨天，主动重置计数（避免SP残留旧数据）
            SPManager.getInstance().putLong(SP_KEY_DAILY_DATE, todayStart);
            SPManager.getInstance().putLong(SP_KEY_DAILY_COUNT, 0L);
            return false;
        }
        long count = SPManager.getInstance().getLong(SP_KEY_DAILY_COUNT, 0L);
        return count >= mHotDailyLimit;
    }

    /**
     * 展示成功后计数+1（按日期区分，跨天自动重置）。
     * 仅在 mHotDailyLimit>0 时才真正写入。
     */
    private void incrementDailyCount() {
        if (mHotDailyLimit <= 0) {
            return;
        }
        long todayStart = getTodayStartMillis();
        long savedDate = SPManager.getInstance().getLong(SP_KEY_DAILY_DATE, 0L);
        long count = (savedDate == todayStart)
                ? SPManager.getInstance().getLong(SP_KEY_DAILY_COUNT, 0L)
                : 0L;
        count++;
        SPManager.getInstance().putLong(SP_KEY_DAILY_DATE, todayStart);
        SPManager.getInstance().putLong(SP_KEY_DAILY_COUNT, count);
        ADXiluLogUtil.d(TAG, "今日热启动展示计数: " + count + "/" + mHotDailyLimit);
    }

    /**
     * 获取今日 0 点的毫秒时间戳作为日期标识（跨天判断依据）
     */
    private long getTodayStartMillis() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /**
     * 是否正在展示
     */
    public boolean isShowing() {
        return mIsShowing;
    }

    /**
     * 是否启用（**只表示"是否允许 SDK 自己弹热启动"**，不表示是否允许预加载）。
     */
    public boolean isEnabled() {
        return mEnabled;
    }

    /** 是否允许热启动预加载；默认与 {@link #isEnabled()} 一致 */
    public boolean isPreloadEnabled() {
        return mPreloadEnabled;
    }

    /**
     * 获取是否区分冷热广告位（hotDiffSlot）。
     * @return 0:不区分 1:区分
     */
    public int getHotDiffSlot() {
        return mHotDiffSlot;
    }
}