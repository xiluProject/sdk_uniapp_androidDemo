package com.xilu.sdk.core.base;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.xilu.sdk.ADXiluSdk;
import com.xilu.sdk.ad.adapter.ADXiluAdapterIniter;
import com.xilu.sdk.ad.adapter.ADXiluAdapterLoader;
import com.xilu.sdk.ad.adapter.ADXiluAdapterParams;
import com.xilu.sdk.ad.adapter.ADXiluPreloadableLoader;
import com.xilu.sdk.ad.api.ADXiluNetworkRequestInfo;
import com.xilu.sdk.ad.data.ADXiluAdInfo;
import com.xilu.sdk.ad.data.ADXiluAdType;
import com.xilu.sdk.ad.data.ADXiluBaseAdInfo;
import com.xilu.sdk.ad.data.IBasePlatformInfo;
import com.xilu.sdk.ad.data.IBasePlatformPosInfo;
import com.xilu.sdk.ad.data.IBasePosInfo;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluAdapterSizeListener;
import com.xilu.sdk.ad.listener.ADXiluAdListener;
import com.xilu.sdk.ad.listener.ADXiluSplashAdListener;
import com.xilu.sdk.ad.scene.SceneAd;
import com.xilu.sdk.ad.utils.ADXiluLogUtil;
import com.xilu.sdk.core.adx.data.AdxSplashAdInfo;
import com.xilu.sdk.core.adx.model.AdxAd;
import com.xilu.sdk.bid.manager.BidManagerFactory;
import com.xilu.sdk.bid.manager.PreLoaderCacheManager;
import com.xilu.sdk.config.ADXiluConfig;
import com.xilu.sdk.config.ADXiluErrorConfig;
import com.xilu.sdk.core.api.AdxApi;
import com.xilu.sdk.core.api.AdRequestReportApi;
import com.xilu.sdk.core.api.ReportAdPositionApi;
import com.xilu.sdk.core.comparator.ECpmComparator;
import com.xilu.sdk.core.frequency.FrequencyManager;
import com.xilu.sdk.core.looper.bid.BidSortOrder;
import com.xilu.sdk.core.looper.bid.BidSortOrderCallback;
import com.xilu.sdk.core.looper.bid.WaterfallFlowOrder;
import com.xilu.sdk.core.looper.parallel.ParallelCallback;
import com.xilu.sdk.core.looper.parallel.ParallelLoader;
import com.xilu.sdk.core.manager.ADSdkManager;
import com.xilu.sdk.core.manager.ApiAdLoadManager;
import com.xilu.sdk.core.manager.HotStartSplashManager;
import com.xilu.sdk.core.model.PlatformInfo;
import com.xilu.sdk.core.model.PlatformPosInfo;
import com.xilu.sdk.core.model.PosInfo;
import com.xilu.sdk.core.parallel.ParallelObserver;
import com.xilu.sdk.core.parallel.ParallelSubject;
import com.xilu.sdk.core.parallel.manager.ADXiluParallelManager;
import com.xilu.sdk.core.parallel.status.ParallelStatus;
import com.xilu.sdk.core.util.AdLoaderUtil;
import com.xilu.sdk.core.util.ClassUtil;
import com.xilu.sdk.core.util.RandomUtil;
import com.xilu.sdk.util.ADXiluAdUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Created by zhangqinglou on 2025/4/16.
 */
@SuppressWarnings("unchecked")
public abstract class BaseAdLoadLooper<K extends BaseAdStatus, T extends ADXiluAdInfo, R extends ADXiluAdListener<T>, E extends IBaseXiluAd<R>> implements IBaseAdLoadLooper, ADXiluAdListener<T> {

    private E mXiluAd;
    private boolean mAdLoadFailed;
    private boolean mIsApiLoad;
    private List<IBasePlatformPosInfo> mLoadPlatformPosList;
    private ArrayList<IBasePlatformPosInfo> mAllPlatformPosIdList;
    private ADXiluAdapterLoader mAdapterLoader;
    private IBasePlatformPosInfo mCurPlatformPosId;
    private int count;
    protected boolean mAdLoading;
    private String mPosId;
    private long mGroupId;
    private int mApi;
    private int mReward;
    private String mAdType;
    private boolean mIsReleased;
    private boolean mIsPreloadMode;                  // 是否为预加载模式（不展示，仅缓存）
    private boolean mFilterBzMs;                     // 是否过滤 BZ/MS 平台
    private int mHotDiffSlot;                        // 是否区分冷热广告位：0不区分 1区分（仅热启动预加载生效）
    private ADXiluError mLoadAdError;

    // 热启动预加载竞价需过滤的平台集合，后续如需扩展直接 add 即可
    private static final Set<String> HOT_START_PRELOAD_FILTERED_PLATFORMS = new HashSet<>(Arrays.asList(
            "bz", "ms", AdxApi.ADX_PLATFORM
    ));
    private int mSingleSourceTimeout;
    private int mTotalTimeout;
    private int mBiddingTimeout;
    private BidSortOrder mBidSortOrder;

    @Nullable
    private ParallelStatus mParallelStatus;
    private ParallelLoader mParallelLoader;
    private IBasePosInfo mXiluPosId;
    private Map<Integer, K> mAdStatusMap = new HashMap<>();
    private ADXiluError mError = new ADXiluError();
    private int mCurIndex = -1;
    private int mSrcParallelReqCount = 1;
    private boolean w = false;
    private boolean C = false;

    private long mLoadStartTime = 0;                             // 整次loadAd的开始时间戳（用于计算总耗时）

    // 适配器初始化等待机制
    private static final int MAX_ADAPTER_INIT_RETRY = 0;         // 普通广告：不重试
    private static final int MAX_SPLASH_ADAPTER_INIT_RETRY = 0;  // 开屏广告：不重试
    private static final long ADAPTER_INIT_RETRY_DELAY_MS = 500; // 普通广告：间隔500ms
    private static final long SPLASH_ADAPTER_INIT_RETRY_DELAY_MS = 300; // 开屏广告：间隔300ms
    private int mAdapterInitRetryCount = 0;
    private Runnable mWaitAdapterInitRunnable;

    // 广告加载失败重试机制
    private static final int MAX_AD_LOAD_RETRY = 0;              // 普通广告：不重试
    private static final int MAX_SPLASH_AD_LOAD_RETRY = 0;       // 开屏广告：不重试
    private static final long AD_LOAD_RETRY_DELAY_MS = 1000;     // 普通广告：间隔1秒
    private static final long SPLASH_AD_LOAD_RETRY_DELAY_MS = 500; // 开屏广告：间隔500ms（如果启用）
    private int mAdLoadRetryCount = 0;
    private Runnable mRetryLoadAdRunnable;

    private Handler mSingleAdLoadHandler = new Handler(Looper.getMainLooper());

    private Handler mMainHandler;

    private ParallelSubject mParallelSubject = new ParallelSubject();
    private ECpmComparator mEcpmComparator = new ECpmComparator();
    private List<ADXiluAdapterLoader> mParallelAdLoaderList = new ArrayList<>();
    private List<ADXiluAdapterLoader> mParallelAdLoadControllerList = new ArrayList<>();

    public BaseAdLoadLooper(E ad, Handler handler) {
        this.mXiluAd = ad;
        this.mMainHandler = handler;
        this.mAdType = ad.getAdType();
        this.mError.setAdType(this.mAdType);
    }

    /**
     * 设置是否为预加载模式。
     * 预加载模式下胜出者进入缓存而不展示给开发者。
     *
     * @param preloadMode 是否为预加载模式
     * @param filterBzMs  是否过滤 BZ/MS 平台
     */
    public void setPreloadMode(boolean preloadMode, boolean filterBzMs) {
        this.mIsPreloadMode = preloadMode;
        this.mFilterBzMs = filterBzMs;
    }

    /**
     * 设置热启动是否区分冷热广告位（hotDiffSlot）。
     * 所有预加载（preloadAd）均为热启动预加载，都会调用此方法。
     * hotDiffSlot=1 时按 launchType 过滤冷启广告位。
     * @param hotDiffSlot 0:不区分 1:区分
     */
    public void setHotDiffSlot(int hotDiffSlot) {
        this.mHotDiffSlot = hotDiffSlot;
    }

    public boolean isPreloadMode() {
        return this.mIsPreloadMode;
    }

    /**
     * 预加载模式下的上报 adFormat 值（如 "splashLaunch"），null 表示用 mAdType
     * 仅开屏预加载通过 setReportAdFormat("splashLaunch") 设置
     */
    private String mReportAdFormat;

    /**
     * 设置预加载模式下的上报 adFormat
     */
    public void setReportAdFormat(String adFormat) {
        this.mReportAdFormat = adFormat;
    }

    /**
     * 获取上报用的 adFormat 值
     * 预加载且设置了 mReportAdFormat 时返回 mReportAdFormat，否则返回 mAdType
     */
    private String getReportAdFormat() {
        return (this.mIsPreloadMode && this.mReportAdFormat != null) ? this.mReportAdFormat : this.mAdType;
    }

    public boolean isFilterBzMs() {
        return this.mFilterBzMs;
    }

    @Override
    public void loadAd(IBasePosInfo posInfo, int count) {
        if (this.mAdLoading || this.mIsReleased) {
            return;
        }
        this.mAdLoading = true;
        this.mCurIndex = -1;

        this.mAdapterInitRetryCount = 0;
        // this.mAdLoadRetryCount = 0; // 重试调用loadAd时不应清零重试计数器
        this.mLoadStartTime = System.currentTimeMillis();  // 记录整次加载的开始时间

        this.mXiluPosId = posInfo;
        this.mPosId = posInfo.getPosId();
        this.mGroupId = posInfo.getGroupId();
        this.mSingleSourceTimeout = posInfo.getSingleSourceTimeout();
        this.mTotalTimeout = posInfo.getTotalTimeout();
        this.mBiddingTimeout = posInfo.getBiddingTimeout();
        this.mSrcParallelReqCount = posInfo.getSrcParallelReqCount();

        // 全局超时从发起竞价开始算起，竞价模式也立即启动总超时
        startTotalAdLoadTimer(posInfo.isHeadingBid());

        this.mAllPlatformPosIdList = new ArrayList<>();
        if (posInfo.getPlatformPosIdList() != null) {
            this.mAllPlatformPosIdList.addAll(posInfo.getPlatformPosIdList());
        }

        this.mLoadPlatformPosList = new ArrayList<>();
        if (posInfo.getPlatformPosIdList() != null) {
            for (IBasePlatformPosInfo basePlatformPosInfo : posInfo.getPlatformPosIdList()) {
                if (basePlatformPosInfo instanceof PlatformPosInfo) {
                    this.mLoadPlatformPosList.add(new PlatformPosInfo((PlatformPosInfo) basePlatformPosInfo));
                }
            }
        }
        FrequencyManager.getInstance().updateFrequencyInfo(posInfo, this.mLoadPlatformPosList);

        // 冷热广告位过滤：预加载过滤 launchType=1（冷启），冷启动过滤 launchType=2（热启）
        // hotDiffSlot=1 时才过滤，hotDiffSlot=0 不区分冷热
        if (this.mIsPreloadMode) {
            // 预加载模式（热启动预加载）：过滤 launchType=1（冷启广告位）
            // 第一层：launchType 冷热过滤（hotDiffSlot=1 时仅保留 不区分/热启 广告位）
            filterByLaunchType(this.mLoadPlatformPosList, 1);
            filterByLaunchType(this.mAllPlatformPosIdList, 1);
            // 第二层：BZ/MS/ADX 平台过滤
            if (this.mFilterBzMs) {
                filterHotStartPreloadPlatforms(this.mLoadPlatformPosList);
                filterHotStartPreloadPlatforms(this.mAllPlatformPosIdList);
            }
        } else if (ADXiluAdType.TYPE_SPLASH.equals(this.mAdType)) {
            // 冷启动开屏模式：过滤 launchType=2（热启广告位），冷启动不展示热启专用广告位
            // 仅开屏广告区分冷热启动，其他广告类型（Banner/Native 等）不执行此过滤
            // 冷启动路径未调用 setHotDiffSlot，此处直接读取 HotStartSplashManager 的配置
            this.mHotDiffSlot = HotStartSplashManager.getInstance().getHotDiffSlot();
            filterByLaunchType(this.mLoadPlatformPosList, 2);
            filterByLaunchType(this.mAllPlatformPosIdList, 2);
        }

        //过滤频率完成的数据
        filterFrequencyFinished(this.mLoadPlatformPosList);
        //过滤请求次数频率
        //filterRequestRate(this.mPlatformPosIdList);

        if (posInfo instanceof PosInfo) {
            PosInfo posId = (PosInfo) posInfo;
            this.mApi = posId.getApi();
            this.mReward = posId.getReward();
        }
        if (this.mError != null) {
            this.mError.setPosId(this.mPosId);
        }
        if (posInfo.isHeadingBid()) {
            this.count = 1;
        } else if (count < 1) {
            this.count = 1;
        } else if (count > 3) {
            this.count = 3;
        } else {
            this.count = count;
        }
        //比价预加载
        if (posInfo.isHeadingBid()) {
            mSrcParallelReqCount = posInfo.getPlatformPosIdList().size();
            //预加载
            parallelLoadAd(posInfo);
        } else {
            startLoopLoadAd();
        }
    }

    @Override
    public void loadAd(IBasePosInfo posInfo, int count, ADXiluNetworkRequestInfo requestInfo) {
        if (this.mAdLoading || this.mIsReleased) {
            return;
        }
        this.mXiluPosId = posInfo;
        this.mAdLoading = true;
        this.mPosId = posInfo.getPosId();
        this.mGroupId = posInfo.getGroupId();
        this.mLoadPlatformPosList = posInfo.getPlatformPosIdList();
        this.mSrcParallelReqCount = posInfo.getSrcParallelReqCount();
        if (posInfo instanceof PosInfo) {
            PosInfo posId = (PosInfo) posInfo;
            this.mApi = posId.getApi();
            this.mReward = posId.getReward();
        }
        if (this.mError != null) {
            this.mError.setPosId(this.mPosId);
        }
        if (count < 1) {
            this.count = 1;
        } else if (count > 3) {
            this.count = 3;
        } else {
            this.count = count;
        }
        loopLoadAd(null, requestInfo);
    }

    @Override
    public void onResume(boolean resumed) {
        if (this.mAdapterLoader != null) {
            if (resumed) {
                this.mAdapterLoader.onResumed();
            } else {
                this.mAdapterLoader.onPaused();
            }
        }
    }

    private void startLoopLoadAd() {
        ADSdkManager.getInstance().n();
        ADSdkManager.getInstance().a();
        ReportAdPositionApi.report("request", this.mPosId, this.count, this.mAdType, this.mGroupId, getSceneId());
        loopLoadAd(null);
    }

    /**
     * 处理ADX广告竞价胜出
     * 创建ADX广告信息并回调onAdReceive
     * @param platformPosInfo 胜出的ADX平台广告位信息
     */
    @SuppressWarnings("unchecked")
    private void handleAdxAdWin(IBasePlatformPosInfo platformPosInfo) {
        // 关键：设置当前平台广告位信息，供后续上报使用（EVENT_DISPLAY、EVENT_CLICK等）
        this.mCurPlatformPosId = platformPosInfo;

        if (isAdLoadFailed() || ADXiluAdUtil.isReleased(this.mXiluAd)) {
            return;
        }
        try {
            AdxAd adxAd = platformPosInfo.getAdxAdData();
            if (adxAd == null) {
                loopLoadAd(ADXiluError.createError(AdxApi.ADX_PLATFORM, platformPosInfo.getPlatformPosId(),
                        ADXiluErrorConfig.AD_FAILED_AD_IS_EMPTY, "ADX广告数据为空"));
                return;
            }
            // 停止单源超时计时器
            cleanSingleAdLoadHandler();
            // 创建ADX开屏广告信息
            AdxSplashAdInfo adInfo = new AdxSplashAdInfo(platformPosInfo.getPlatformPosId(), adxAd);
            adInfo.setAdListener((ADXiluSplashAdListener) this);
            adInfo.setBidECPMFen(platformPosInfo.getECPM());
            adInfo.setBidWinner(true);
            // 通过 ADXiluAdInfoListener 回调广告接收
            if (this instanceof com.xilu.sdk.ad.listener.ADXiluAdInfoListener) {
                ((com.xilu.sdk.ad.listener.ADXiluAdInfoListener<T>) this).onAdReceive((T) adInfo);
            } else {
                loopLoadAd(ADXiluError.createError(AdxApi.ADX_PLATFORM, platformPosInfo.getPlatformPosId(),
                        ADXiluErrorConfig.AD_FAILED_GET_AD_EXCEPTION, "Looper不支持ADX广告类型"));
            }
        } catch (Throwable t) {
            t.printStackTrace();
            loopLoadAd(ADXiluError.createError(AdxApi.ADX_PLATFORM, platformPosInfo.getPlatformPosId(),
                    ADXiluErrorConfig.AD_FAILED_GET_AD_EXCEPTION, "ADX广告展示异常: " + t.getMessage()));
        }
    }

    private void loopLoadAd(ADXiluError error) {
        cleanSingleAdLoadHandler();
        if (isAdLoadFailed() || ADXiluAdUtil.isReleased(this.mXiluAd)) {
            return;
        }
        if (error != null) {
            if (ADXiluLogUtil.needShowLog()) {
                ADXiluLogUtil.d("当前三方广告位轮循失败，错误信息 : " + error.toString());
            }
            // 错误已在 adapter 中上报，这里只打印日志，不再重复上报
            // errorReport(error);
        }
        errorAppend(error);
        if (nextAd()) {
            if (this.mLoadPlatformPosList == null || this.mLoadPlatformPosList.size() <= this.mCurIndex){
                setError(ADXiluErrorConfig.AD_FAILED_ALL_PLATFORM_NO_AD, ADXiluErrorConfig.MSG_AD_FAILED_ALL_PLATFORM_NO_AD);
                loadAdFailed();
                return;
            }
            if (this.mCurPlatformPosId == null) {
                loopLoadAd(ADXiluError.createError("unknown", null, ADXiluErrorConfig.AD_FAILED_PLATFORM_POS_ID_EMPTY, ADXiluErrorConfig.MSG_AD_FAILED_PLATFORM_POS_ID_EMPTY));
                return;
            }

            String platformKey = this.mCurPlatformPosId.getPlatform();
            String platformPosId = this.mCurPlatformPosId.getPlatformPosId();

            // ADX广告胜出：不走适配器加载流程，直接创建ADX广告信息并回调
            if (AdxApi.ADX_PLATFORM.equals(platformKey)) {
                handleAdxAdWin(this.mCurPlatformPosId);
                return;
            }

            ADXiluAdapterIniter adapterIniter = ADSdkManager.getInstance().getAdapterIniter(platformKey);
            IBasePlatformInfo platform = ADSdkManager.getInstance().getPlatform(platformKey);
            if (adapterIniter == null || platform == null){
                loopLoadAd(ADXiluError.createError(platformKey, platformPosId, ADXiluErrorConfig.ADAPTER_IS_NOT_INITED, platformKey + ADXiluErrorConfig.MSG_ADAPTER_IS_NOT_INITED));
                return;
            }
            // 适配器存在但尚未初始化完成（首次安装时第三方SDK异步初始化），等待重试
            if (!adapterIniter.inited()) {
                if (mAdapterInitRetryCount < getMaxAdapterInitRetry()) {
                    mAdapterInitRetryCount++;
                    if (mMainHandler != null) {
                        cleanSingleAdLoadHandler();
                        final int retryIndex = this.mCurIndex;
                        mWaitAdapterInitRunnable = new Runnable() {
                            @Override
                            public void run() {
                                if (!isAdLoadFailed() && !ADXiluAdUtil.isReleased(mXiluAd)) {
                                    // 回退索引，让nextAd()重新指向当前平台
                                    mCurIndex = retryIndex - 1;
                                    loopLoadAd(null);
                                }
                            }
                        };
                        mMainHandler.postDelayed(mWaitAdapterInitRunnable, getAdapterInitRetryDelay());
                    }
                    return;
                }
                loopLoadAd(ADXiluError.createError(platformKey, platformPosId, ADXiluErrorConfig.ADAPTER_IS_NOT_INITED, platformKey + " 适配器初始化超时"));
                return;
            }
            String onlySupportPlatform = (this.mXiluAd == null ? null : this.mXiluAd.getOnlySupportPlatform());
            if (!TextUtils.isEmpty(onlySupportPlatform) && !onlySupportPlatform.equals(platformKey)) {
                loopLoadAd(ADXiluError.createError(platformKey, platformPosId, -1, "当前广告设置了仅支持 " + onlySupportPlatform + " 平台，无法获取该平台之外的广告"));
                return;
            }
//            if (1 == this.mReward && !IBasePlatformInfo.PLAFORM_ADMOBILE.equals(platformKey)) {
//                loopLoadAd(ADXiluError.createError(platformKey, null, ADXiluErrorConfig.AD_FAILED_NOT_SUPPORT_REWARD_AD, ADXiluErrorConfig.MSG_AD_FAILED_NOT_SUPPORT_REWARD_AD));
//                return;
//            }
            if (checkFrequency()) {
                loopLoadAd(ADXiluError.createError(platformKey, platformPosId, ADXiluErrorConfig.AD_FAILED_ATTAIN_FREQUENCY, ADXiluErrorConfig.MSG_AD_FAILED_ATTAIN_FREQUENCY));
                return;
            }
            try {
                if (ADXiluAdUtil.isReleased(this.mXiluAd)) {
                    return;
                }
                cleanAdapterLoader();
                this.mLoadAdError = null;
                this.mAdapterLoader = getAdapterLoader(platformKey, platformPosId, adapterIniter);
                bindSizeListener(this.mAdapterLoader);
                if (this.mAdapterLoader == null) {
                    if (this.mLoadAdError != null) {
                        loopLoadAd(this.mLoadAdError);
                        return;
                    } else {
                        loopLoadAd(ADXiluError.createError(platformKey, platformPosId, ADXiluErrorConfig.AD_FAILED_ADAPTER_IS_NOT_SUPPORT_AD_TYPE, ADXiluErrorConfig.MSG_AD_FAILED_ADAPTER_IS_NOT_SUPPORT_AD_TYPE));
                        return;
                    }
                }
                apiLoadAd();
                if (this.mCurPlatformPosId.isBidType() || !this.mCurPlatformPosId.isRequest()) {
                    //ReportApi.report("request", this.posId, this.count, this.t, this.platformPosId, this.q, k());
                }

                ADXiluAdapterParams params = new ADXiluAdapterParams(this.mCurPlatformPosId, platform, this.mReward == 1, this.count, this.mPosId);

                // 预加载模式：通知平台适配器进入预加载模式
                if (this.mIsPreloadMode && this.mAdapterLoader instanceof ADXiluPreloadableLoader) {
                    ((ADXiluPreloadableLoader) this.mAdapterLoader).setPreloadMode(true);
                }

                startSingleAdLoadTimer(this.mSingleSourceTimeout);
                this.mAdapterLoader.loadAd(this.mXiluAd, params, this);
                return;
            } catch (Throwable e1) {
                e1.printStackTrace();
                loopLoadAd(ADXiluError.createError(getCurrentPlatform(), getCurrentPlatformPosIdStr(), ADXiluErrorConfig.AD_FAILED_GET_AD_EXCEPTION, ADXiluErrorConfig.MSG_AD_FAILED_GET_AD_EXCEPTION));
                return;
            }
        }

        //预加载的逻辑
        List<IBasePlatformPosInfo> parallelList = getParallelPlatformPosList(this.mLoadPlatformPosList, this.mCurIndex + 1);
        if (parallelList == null || parallelList.isEmpty()){
            setError(ADXiluErrorConfig.AD_FAILED_ALL_PLATFORM_NO_AD, ADXiluErrorConfig.MSG_AD_FAILED_ALL_PLATFORM_NO_AD);
            loadAdFailed();
            return;
        }
        ParallelStatus parallelStatus = new ParallelStatus();
        parallelStatus.setPlatformPosIdList(parallelList);
        E e2 = this.mXiluAd;
        this.mParallelLoader = new ParallelLoader(this.mXiluPosId, new ParallelCallback() {
            @Override
            public void onSuccess(IBasePlatformPosInfo platformPosId) {
                if (parallelStatus.isFinished()) {
                    return;
                }
                parallelStatus.setPlatformPosId(platformPosId);
                parallelStatus.setSuccess(true);
                parallelStatus.setFinished(true);
                sortParallelPlatformPosIdList(mLoadPlatformPosList, parallelStatus);
                loopLoadAd(null);
            }

            @Override
            public void onFailed() {
                if (parallelStatus.isFinished()) {
                    return;
                }
                parallelStatus.setSuccess(false);
                parallelStatus.setFinished(true);
                sortParallelPlatformPosIdList(mLoadPlatformPosList, parallelStatus);
                String platformStr = "";
                String platformPosIdStr = "";
                List<IBasePlatformPosInfo> a = parallelStatus.getPlatformPosIdList();
                if (a != null) {
                    for (IBasePlatformPosInfo platformPosId : a) {
                        platformStr = platformStr + platformPosId.getPlatform() + " , ";
                        platformPosIdStr = platformPosIdStr + platformPosId.getPlatformPosId() + " , ";
                    }
                }
                loopLoadAd(ADXiluError.createError(platformStr, platformPosIdStr, ADXiluErrorConfig.AD_FAILED_PARALLEL_LOADER, ADXiluErrorConfig.MSG_AD_FAILED_PARALLEL_LOADER));
            }
        }, this.mAdType, e2 == null ? null : e2.getOnlySupportPlatform());
        this.mParallelLoader.setPreloadMode(this.mIsPreloadMode);
        this.mParallelLoader.setReportAdFormat(this.mReportAdFormat);
        this.mParallelLoader.load(parallelList, this.mSingleSourceTimeout, this.count, this.mXiluAd, this);
    }

    private void loopLoadAd(ADXiluError error, ADXiluNetworkRequestInfo requestInfo) {
        if (isAdLoadFailed() || ADXiluAdUtil.isReleased(this.mXiluAd)) {
            return;
        }
        if (error != null) {
            if (ADXiluLogUtil.needShowLog()) {
                ADXiluLogUtil.d("当前三方广告位轮循失败，错误信息 : " + error.toString());
            }
            // 错误已在 adapter 中上报，这里只打印日志，不再重复上报
            // errorReport(error);
        }
        errorAppend(error);
        nextAd();
        List<IBasePlatformPosInfo> list = this.mLoadPlatformPosList;
        if (list != null && list.size() > this.mCurIndex) {
            IBasePlatformPosInfo platformPosId = this.mCurPlatformPosId;
            if (platformPosId == null) {
                loopLoadAd(ADXiluError.createError("unknown", null, ADXiluErrorConfig.AD_FAILED_PLATFORM_POS_ID_EMPTY, ADXiluErrorConfig.MSG_AD_FAILED_PLATFORM_POS_ID_EMPTY), (ADXiluNetworkRequestInfo) null);
                return;
            }
            String platformStr = platformPosId.getPlatform();
            String platformPosIdStr = this.mCurPlatformPosId.getPlatformPosId();
            ADXiluAdapterIniter adapterIniter = ClassUtil.getAdIniterObject(platformStr);
            if (adapterIniter == null) {
                ADXiluLogUtil.d(platformStr + " 平台的初始器获取失败，请检查是否导入相应平台的AdapterSdk，如果已导入并开启了混淆请检查混淆是否配置正确");
                return;
            }
            if (requestInfo == null) {
                ADXiluLogUtil.d(platformStr + " 平台的初始器获取失败，请检查是否配置相应平台的打底广告");
                return;
            }
            PlatformInfo platform = new PlatformInfo(platformStr, requestInfo.getAppId(), requestInfo.getAppKey(), "100001");
            adapterIniterInit(platformStr, platform, adapterIniter);
            ADSdkManager.getInstance().setDownTip(requestInfo.getmDownloadTip());
            try {
                if (ADXiluAdUtil.isReleased(this.mXiluAd)){
                    return;
                }
                cleanAdapterLoader();
                this.mAdapterLoader = AdLoaderUtil.getAdLoader(adapterIniter, platformStr, this.mAdType);
                bindSizeListener(this.mAdapterLoader);
                if (this.mAdapterLoader == null) {
                    BaseAdLoadLooper<K, T, R, E> kVar = this;
                    kVar.loopLoadAd(ADXiluError.createError(platformStr, platformPosIdStr, ADXiluErrorConfig.AD_FAILED_ADAPTER_IS_NOT_SUPPORT_AD_TYPE, ADXiluErrorConfig.MSG_AD_FAILED_ADAPTER_IS_NOT_SUPPORT_AD_TYPE), (ADXiluNetworkRequestInfo) null);
                } else {
                    this.w = true;
                    ReportAdPositionApi.report("request", this.mPosId, this.count, this.mAdType, this.mGroupId, getSceneId());
                    // 竞价阶段已上报过request的平台(isRequest=true)不再重复上报，
                    // 没有竞价适配器而降级到瀑布流的平台(isRequest=false)需正常上报
                    if (this.mCurPlatformPosId == null || !this.mCurPlatformPosId.isRequest()) {
                        AdRequestReportApi.reportQuick(ADXiluSdk.getInstance().getContext(), AdRequestReportApi.EVENT_REQUEST, this.mPosId, getReportAdFormat(), this.mCurPlatformPosId);
                    }
                    ADXiluAdapterParams adapterParams = new ADXiluAdapterParams(this.mCurPlatformPosId, platform, false, this.count, this.mPosId);
                    this.mAdapterLoader.loadAd(this.mXiluAd, adapterParams, this);
                }
                return;
            } catch (Throwable e) {
                e.printStackTrace();
                loopLoadAd(ADXiluError.createError(getCurrentPlatform(), getCurrentPlatformPosIdStr(), ADXiluErrorConfig.AD_FAILED_GET_AD_EXCEPTION, ADXiluErrorConfig.MSG_AD_FAILED_GET_AD_EXCEPTION), (ADXiluNetworkRequestInfo) null);
                return;
            }
        }
        setError(ADXiluErrorConfig.AD_FAILED_ALL_PLATFORM_NO_AD, ADXiluErrorConfig.MSG_AD_FAILED_ALL_PLATFORM_NO_AD);
        loadAdFailed();
    }

    private boolean z() {
        return this.mXiluPosId != null && this.mXiluPosId.getHbFallbackCtl() == 1.0d;
    }

    private boolean A() {
        return z() && this.mCurPlatformPosId != null && this.mCurPlatformPosId.isBidType() && this.C;
    }

    protected void onLoopFailure() {
        if (isReleased()) {
            return;
        }
        //I();
        setError(ADXiluErrorConfig.AD_FAILED_TIME_OUT, ADXiluErrorConfig.MSG_AD_FAILED_TIME_OUT);
        loadAdFailed();
    }

    private boolean checkFrequency() {
        if (this.mCurPlatformPosId == null) {
            return true;
        }
        return !this.mCurPlatformPosId.isBidType() && !this.mCurPlatformPosId.isLoopFrequencyType() && this.mCurPlatformPosId.isFrequencyFinished();
    }

    private boolean nextAd() {
        this.mCurIndex++;
        if (this.mLoadPlatformPosList != null && this.mLoadPlatformPosList.size() > this.mCurIndex) {
            IBasePlatformPosInfo posId = this.mLoadPlatformPosList.get(this.mCurIndex);
            if (hasNext(posId, this.mLoadPlatformPosList, this.mCurIndex)) {
                this.mCurPlatformPosId = posId;
                return true;
            }
            this.mCurIndex--;
            return false;
        }
        this.mCurPlatformPosId = null;
        return true;
    }

    private void cleanError() {
        if (this.mError != null) {
            this.mError.release();
            this.mError = null;
        }
    }

    /** 把平台尺寸的接收方挂到适配器装载器上（每次取到 loader 都要重新绑定） */
    private void bindSizeListener(ADXiluAdapterLoader adapterLoader) {
        if (!(adapterLoader instanceof IBaseAdLoader) || this.mXiluAd == null) {
            return;
        }
        if (this.mXiluAd instanceof ADXiluAdapterSizeListener) {
            ((IBaseAdLoader) adapterLoader).setSizeListener((ADXiluAdapterSizeListener) this.mXiluAd);
        }
    }

    private void cleanAdapterLoader() {
        if (this.mAdapterLoader != null) {
            if (this.mAdapterLoader instanceof IBaseAdLoader) {
                ((IBaseAdLoader) this.mAdapterLoader).setSizeListener(null);
            }
            this.mAdapterLoader.release();
            this.mAdapterLoader = null;
        }
    }

    private void cleanAdStatusMap() {
        if (this.mAdStatusMap != null) {
            this.mAdStatusMap.clear();
        }
    }

    private void H() {
        if (this.mAllPlatformPosIdList == null || this.mAllPlatformPosIdList.size() <= 0) {
            return;
        }
        Iterator<IBasePlatformPosInfo> it = this.mAllPlatformPosIdList.iterator();
        while (it.hasNext()) {
            IBasePlatformPosInfo next = it.next();
            if (isC2SBidType(next)) {
                PreLoaderCacheManager.getInstance().removePreAdapterLoader(this, next.getPlatformPosId());
            }
        }
    }

    private void J() {
        if (this.mAllPlatformPosIdList == null || this.mAllPlatformPosIdList.size() <= 0) {
            return;
        }
        Iterator<IBasePlatformPosInfo> it = this.mAllPlatformPosIdList.iterator();
        while (it.hasNext()) {
            PreLoaderCacheManager.getInstance().removePreAdapterLoader(this, it.next().getPlatformPosId());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    private void cleanParallelLoader() {
        if (this.mParallelLoader != null) {
            this.mParallelLoader.a();
            this.mParallelLoader = null;
        }
        this.mParallelStatus = null;
        this.mParallelSubject.remove();
    }

    /**
     * 竞价全败回调，子类可重写以触发预加载等逻辑
     */
    protected void onAllBidFailed() {
    }

    /* JADX INFO: Access modifiers changed from: private */
    private void loadAdFailed() {
        if (isAdLoadFailed()) {
            return;
        }

        // 按广告类型调整重试策略
        int currentMaxRetry = getMaxAdLoadRetry();
        long currentRetryDelay = getAdLoadRetryDelay();

        // 开屏广告：不重试（立即失败），普通广告：重试2次
        if (mAdLoadRetryCount < currentMaxRetry && !isReleased()) {
            mAdLoadRetryCount++;
            ADXiluLogUtil.d("【加载重试】广告加载失败，准备第 " + mAdLoadRetryCount + "/" + currentMaxRetry +
                " 次重试..." + (isSplashAd() ? "（开屏广告模式）" : ""));

            // 清理当前状态，准备重试
            this.mCurIndex = -1;
            this.mError = new ADXiluError();
            this.mAdLoading = false;
            this.mAdLoadFailed = false;

            // 延迟重试（根据广告类型动态调整间隔）
            if (mMainHandler != null) {
                mRetryLoadAdRunnable = new Runnable() {
                    @Override
                    public void run() {
                        if (!isReleased()) {
                            ADXiluLogUtil.d("【加载重试】开始第 " + mAdLoadRetryCount + " 次重新加载" +
                                (isSplashAd() ? "（开屏广告快速模式）" : ""));
                            BaseAdLoadLooper.this.loadAd(BaseAdLoadLooper.this.mXiluPosId, BaseAdLoadLooper.this.count);
                        }
                    }
                };
                mMainHandler.postDelayed(mRetryLoadAdRunnable, currentRetryDelay);
            }
            return;  // 不立即失败，等待重试
        }

        // 超过最大重试次数或正常超时，真正失败
        ADXiluLogUtil.d("【最终失败】广告加载失败，已重试 " + mAdLoadRetryCount + " 次");
        this.mAdLoadFailed = true;
        u();
        cleanSingleAdLoadHandler();
        if (this.mError != null) {
            errorReport(this.mError);
        }
        if (ADXiluAdUtil.canCallBack(this.mXiluAd) && q()) {
            getAdListener().onAdFailed(this.mError);
        }
        if (r()) {
            release();
        }
        a();
    }

    private void E() {
        if (this.mParallelAdLoaderList == null || this.mParallelAdLoaderList.isEmpty()) {
            return;
        }
        for (ADXiluAdapterLoader adapterLoader : this.mParallelAdLoaderList) {
            if (adapterLoader != null) {
                adapterLoader.release();
            }
        }
        this.mParallelAdLoaderList.clear();
    }

    private void G() {
        if (this.mParallelAdLoadControllerList == null || this.mParallelAdLoadControllerList.isEmpty()) {
            return;
        }
        for (ADXiluAdapterLoader adapterLoader : this.mParallelAdLoadControllerList) {
            if (adapterLoader != null) {
                adapterLoader.release();
            }
        }
        this.mParallelAdLoadControllerList.clear();
    }

    @Override
    public void onAdExpose(T t) {
        if (t == null){
            return;
        }
        K adStatus = this.mAdStatusMap.get(Integer.valueOf(t.hashCode()));
        if (adStatus == null){
            return;
        }
        if (adStatus.isAdExpose()){
            return;
        }
        adStatus.setAdExpose(true);
        // 从 adInfo 获取 materialId（广告素材ID）
        String materialId = "";
        if (t instanceof ADXiluAdInfo) {
            materialId = ((ADXiluAdInfo) t).getMaterialId();
        }

        // display ecpm：与竞价阶段保持一致，优先用 platformPosInfo 上已计算的最终 ecpm（含 fallback）
        double adInfoEcpm = (this.mCurPlatformPosId != null) ? this.mCurPlatformPosId.getECPM() : 0;
        if (adInfoEcpm < 0) {
            adInfoEcpm = 0;
        }

        // 上报 display 事件
        AdRequestReportApi.reportQuick(ADXiluSdk.getInstance().getContext(), AdRequestReportApi.EVENT_DISPLAY, this.mPosId, getReportAdFormat(), this.mCurPlatformPosId, null, materialId, adInfoEcpm);
        FrequencyManager.getInstance().a(this.mPosId, this.mCurPlatformPosId, a(this.mCurPlatformPosId));
        apiLoadAd();
        if (ADXiluAdUtil.canCallBack(this.mXiluAd)) {
            getAdListener().onAdExpose(t);
        }
    }

    @Override
    public void onAdClick(T t) {
        ADXiluLogUtil.d("TRACE_CLICK BaseAdLoadLooper.onAdClick called, hashCode=" + (t != null ? t.hashCode() : "null"));
        if (t == null){
            return;
        }
        K adStatus = this.mAdStatusMap.get(Integer.valueOf(t.hashCode()));
        ADXiluLogUtil.d("TRACE_CLICK BaseAdLoadLooper.onAdClick adStatus=" + adStatus + ", isAdClick=" + (adStatus != null ? adStatus.isAdClick() : "null") + ", clickTime=" + (adStatus != null ? adStatus.getAdClickTime() : "null"));
        long now = System.currentTimeMillis();
        if (adStatus != null) {
            long lastClickTime = adStatus.getAdClickTime();
            if (lastClickTime > 0 && now - lastClickTime < 1000) {
                ADXiluLogUtil.d("TRACE_CLICK BaseAdLoadLooper skipping duplicate click, interval=" + (now - lastClickTime) + "ms");
                return;
            }
            adStatus.setAdClick(now);
        }
        int i = 0;
        if (ADXiluAdType.TYPE_INNER_NOTICE.equals(this.mAdType) && (t instanceof ADXiluBaseAdInfo)) {
            Map map = ((ADXiluBaseAdInfo) t).getExtInfo();
            Object obj = map.get(ADXiluConfig.KEY_SP_CLICK);
            if(obj != null && obj instanceof Integer){
                i = ((Integer) obj).intValue();
            }
        }
        String platform = t.getPlatform();
        String materialId = t.getMaterialId();
        ADXiluLogUtil.d("TRACE_CLICK BaseAdLoadLooper reporting click, platform=" + platform + ", materialId=" + materialId + ", hashCode=" + t.hashCode());
        // click ecpm：与竞价阶段保持一致，优先用 platformPosInfo 上已计算的最终 ecpm（含 fallback）
        double clickEcpm = (this.mCurPlatformPosId != null) ? this.mCurPlatformPosId.getECPM() : 0;
        if (clickEcpm < 0) clickEcpm = 0;
        AdRequestReportApi.reportQuick(ADXiluSdk.getInstance().getContext(), AdRequestReportApi.EVENT_CLICK, this.mPosId, getReportAdFormat(), this.mCurPlatformPosId, platform, materialId, clickEcpm);
        apiLoadAd();
        if (ADXiluAdUtil.canCallBack(this.mXiluAd)) {
            getAdListener().onAdClick(t);
        }
    }

    @Override
    public void onAdClose(T t) {
        ADXiluLogUtil.d("TRACE_CLOSE BaseAdLoadLooper.onAdClose called, hashCode=" + t.hashCode() + ", mAdStatusMap.containsKey=" + (this.mAdStatusMap != null ? this.mAdStatusMap.containsKey(Integer.valueOf(t.hashCode())) : "null"));
        if (t == null || isAdLoadFailed()){
            return;
        }
        K adStatus = this.mAdStatusMap.get(Integer.valueOf(t.hashCode()));
        ADXiluLogUtil.d("TRACE_CLOSE BaseAdLoadLooper.onAdClose adStatus=" + (adStatus != null ? adStatus.isAdClose() : "null"));
        if (adStatus == null || adStatus.isAdClose()) {
            return;
        }
        adStatus.setAdClose(true);
        // 上报 close 事件，使用当前 ad 的信息
        if (t != null) {
            String platform = t.getPlatform();
            String materialId = t.getMaterialId();
            // close ecpm：与竞价阶段保持一致，优先用 platformPosInfo 上已计算的最终 ecpm（含 fallback）
            double closeEcpm = (this.mCurPlatformPosId != null) ? this.mCurPlatformPosId.getECPM() : 0;
            if (closeEcpm < 0) closeEcpm = 0;
            AdRequestReportApi.reportQuick(ADXiluSdk.getInstance().getContext(), AdRequestReportApi.EVENT_CLOSE, this.mPosId, getReportAdFormat(), this.mCurPlatformPosId, platform, materialId, closeEcpm);
        }
        s();
        apiLoadAd();
        if (ADXiluAdUtil.canCallBack(this.mXiluAd)) {
            getAdListener().onAdClose(t);
        }
        if (p()) {
            release();
        }
    }

    @Override
    public void onAdFailed(ADXiluError error) {
        FrequencyManager.getInstance().a(this.mPosId, this.mCurPlatformPosId, a(this.mCurPlatformPosId));

        if (A()) {
            errorAppend(error);
            onLoopFailure();
        } else if (this.w) {
            loopLoadAd(error, (ADXiluNetworkRequestInfo) null);
        } else {
            loopLoadAd(error);
        }
    }

    /**
     * 获取剩余未请求的平台数量
     */
    private int getRemainingPlatformCount() {
        if (this.mLoadPlatformPosList == null || this.mLoadPlatformPosList.isEmpty()) {
            return 0;
        }
        return Math.max(0, this.mLoadPlatformPosList.size() - (this.mCurIndex + 1));
    }

    @Override
    public void release() {
        if (this.mIsReleased) {
            return;
        }
        this.mIsReleased = true;
        this.mAdLoadFailed = true;
        try {
            this.H();
            this.J();
            this.cleanParallelLoader();
            this.mXiluAd = null;
            if (this.mLoadPlatformPosList != null) {
                this.mLoadPlatformPosList.clear();
                this.mLoadPlatformPosList = null;
            }
            if (this.mAllPlatformPosIdList != null) {
                this.mAllPlatformPosIdList.clear();
                this.mAllPlatformPosIdList = null;
            }
            this.mCurPlatformPosId = null;
            this.cleanError();
            this.cleanAdStatusMap();
            this.cleanAdapterLoader();
            this.u();
            this.cleanSingleAdLoadHandler();
            if (this.mMainHandler != null && this.mWaitAdapterInitRunnable != null) {
                this.mMainHandler.removeCallbacks(this.mWaitAdapterInitRunnable);
                this.mWaitAdapterInitRunnable = null;
            }
            this.mAdLoadTimeoutThread = null;
            this.mSingleAdLoadTimeoutThread = null;
            //this.B = null;
            this.E();
            this.G();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    protected void s() {
    }

    protected abstract K getAdStatus();

    protected boolean p() {
        return false;
    }

    protected boolean r() {
        return true;
    }

    protected boolean q() {
        return true;
    }

    protected void cleanSingleAdLoadHandler() {
        if (this.mSingleAdLoadHandler == null || this.mSingleAdLoadTimeoutThread == null) {
            return;
        }
        this.mSingleAdLoadHandler.removeCallbacks(this.mSingleAdLoadTimeoutThread);
    }

    protected void u() {
        if (this.mMainHandler == null || this.mAdLoadTimeoutThread == null) {
            return;
        }
        this.mMainHandler.removeCallbacks(this.mAdLoadTimeoutThread);
    }

    protected void a() {
    }

    public boolean isReleased() {
        return this.mIsReleased;
    }

    public String getPosIdStr() {
        return this.mPosId;
    }

    public long getGroupId() {
        return this.mGroupId;
    }

    protected E getAd() {
        return this.mXiluAd;
    }

    protected boolean isAdLoadFailed() {
        return this.mAdLoadFailed;
    }

    protected Map<Integer, K> getAdStatusMap() {
        return this.mAdStatusMap;
    }

    protected R getAdListener() {
        return (R) this.mXiluAd.getListener();
    }

    protected String getCurrentPlatform() {
        return this.mCurPlatformPosId == null ? "unknown" : this.mCurPlatformPosId.getPlatform();
    }

    protected String getCurrentPlatformPosIdStr() {
        if (this.mCurPlatformPosId == null) {
            return null;
        }
        return this.mCurPlatformPosId.getPlatformPosId();
    }

    /**
     * 获取整次广告加载的开始时间戳
     */
    protected long getLoadStartTime() {
        return this.mLoadStartTime;
    }

    /**
     * 判断当前是否为开屏广告
     */
    protected boolean isSplashAd() {
        return this.mAdType != null &&
            (this.mAdType.equalsIgnoreCase("splash") ||
             this.mAdType.equalsIgnoreCase("SPLASH") ||
             this.mAdType.contains("splash"));
    }

    /**
     * 根据广告类型获取适配器初始化最大重试次数
     */
    protected int getMaxAdapterInitRetry() {
        return isSplashAd() ? MAX_SPLASH_ADAPTER_INIT_RETRY : MAX_ADAPTER_INIT_RETRY;
    }

    /**
     * 根据广告类型获取适配器初始化重试间隔
     */
    protected long getAdapterInitRetryDelay() {
        return isSplashAd() ? SPLASH_ADAPTER_INIT_RETRY_DELAY_MS : ADAPTER_INIT_RETRY_DELAY_MS;
    }

    /**
     * 根据广告类型获取广告加载最大重试次数
     */
    protected int getMaxAdLoadRetry() {
        return isSplashAd() ? MAX_SPLASH_AD_LOAD_RETRY : MAX_AD_LOAD_RETRY;
    }

    /**
     * 根据广告类型获取广告加载重试间隔
     */
    protected long getAdLoadRetryDelay() {
        return isSplashAd() ? SPLASH_AD_LOAD_RETRY_DELAY_MS : AD_LOAD_RETRY_DELAY_MS;
    }

    protected IBasePlatformPosInfo getCurrentPlatformPosId() {
        return this.mCurPlatformPosId;
    }

    public void apiLoadAd() {
        if (this.mIsApiLoad || 1 != this.mApi) {
            return;
        }
        setApiLoad(ApiAdLoadManager.getInstance().loadAd(this.mAdType));
    }

    public String getSceneId() {
        return this.mXiluAd instanceof SceneAd ? ((SceneAd) this.mXiluAd).getSceneId() : "";
    }

    protected String getAdType() {
        return this.mAdType;
    }


    public void addParallelAdLoadController(ADXiluAdapterLoader adapterLoader) {
        if (this.mParallelAdLoadControllerList == null || this.mParallelAdLoadControllerList.contains(adapterLoader)) {
            return;
        }
        this.mParallelAdLoadControllerList.add(adapterLoader);
    }

    protected void setApiLoad(boolean z) {
        this.mIsApiLoad = z;
    }

    private boolean isParallelModel(IBasePosInfo posId) {
        return posId != null && posId.getRequestMode().equals(ADXiluConfig.RequestMode.PARALLEL);
    }

    private boolean isParallelType(IBasePlatformPosInfo posInfo) {
        return ADXiluParallelManager.getInstance().isParallelType(posInfo);
    }

    private IBasePlatformPosInfo hasParallelPlatformPos(List<IBasePlatformPosInfo> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        IBasePlatformPosInfo posInfo = list.get(0);
        if (isParallelType(posInfo)) {
            return posInfo;
        }
        return null;
    }


    private IBasePlatformPosInfo a(List<IBasePlatformPosInfo> list, int i) {
        for (int i2 = i + 1; i2 < list.size(); i2++) {
            IBasePlatformPosInfo platformPosInfo = list.get(i2);
            if (platformPosInfo.isBidType()) {
                return platformPosInfo;
            }
        }
        return null;
    }

    private void d(IBasePlatformPosInfo platformPosId) {
        cleanSingleAdLoadHandler();
        if (isAdLoadFailed() || ADXiluAdUtil.isReleased(this.mXiluAd)) {
            return;
        }
        this.mCurPlatformPosId = platformPosId;
        // 同步竞价胜出标记到 adInfo（供 BannerAdLooper 等子类 early return 路径使用）
        if (this.mXiluAd instanceof ADXiluBaseAdInfo && platformPosId.isBidWinner()) {
            ((ADXiluBaseAdInfo) this.mXiluAd).setBidWinner(true);
        }

        String platform = platformPosId.getPlatform();
        String platformPosIdStr = platformPosId.getPlatformPosId();

        ADXiluAdapterIniter c = ADSdkManager.getInstance().getAdapterIniter(platform);
        IBasePlatformInfo d = ADSdkManager.getInstance().getPlatform(platform);

        // 关键：检查适配器是否初始化，清理数据后可能未初始化导致不显示广告
        if (c == null || d == null) {
            ADXiluLogUtil.e("广告加载失败：适配器未初始化，platform=" + platform + ", isBidWinner=" + platformPosId.isBidWinner() + ", adapterIniter=" + (c != null) + ", platformInfo=" + (d != null));
            ADXiluError createErrorDesc = ADXiluError.createError(platform, platformPosIdStr,
                    -1, "广告加载失败：平台适配器未初始化");
            errorAppend(createErrorDesc);
            onLoopFailure();
            return;
        }

        // 检查平台限制
        E e = this.mXiluAd;
        String onlySupportPlatform = e == null ? null : e.getOnlySupportPlatform();
        if (!TextUtils.isEmpty(onlySupportPlatform) && !onlySupportPlatform.equals(platform)) {
            ADXiluError createErrorDesc = ADXiluError.createError(platform, platformPosIdStr, -1, "当前广告设置了仅支持 " + onlySupportPlatform + " 平台，无法获取该平台之外的广告");
            if (ADXiluLogUtil.needShowLog()) {
                ADXiluLogUtil.d("当前HBFallback广告位轮循失败，错误信息 : " + createErrorDesc.toString());
            }
            errorAppend(createErrorDesc);
            onLoopFailure();
            return;
        }

        // 所有检查通过后，才上报 success 或 fallback（避免"幽灵事件"）
        if (platformPosId.isBidWinner()) {
            // success上报：优先用真实ecpm（已在onBidResponsed中兜底），<=0时用后端配置ecpm
            double realEcpm = platformPosId.getECPM();
            double backendEcpm = platformPosId.getBackendECPM();
            double reportEcpm = (realEcpm > 0) ? realEcpm : backendEcpm;
            if (reportEcpm < 0) { reportEcpm = 0.0d; }
            AdRequestReportApi.reportQuick(ADXiluSdk.getInstance().getContext(),
                    AdRequestReportApi.EVENT_SUCCESS, this.mPosId, getReportAdFormat(),
                    platformPosId, null, "", reportEcpm);
        } else {
            // fallback上报：只在最终失败时上报（所有重试都用完后）
            // 如果还有重试机会，不上报fallback，等重试结果
            int currentMaxRetry = getMaxAdLoadRetry();
            if (mAdLoadRetryCount >= currentMaxRetry - 1) {
                // 已达到或即将达到最大重试次数，上报fallback
                double fbRealEcpm = platformPosId.getECPM();
                double fbBackendEcpm = platformPosId.getBackendECPM();
                double fbReportEcpm = (fbRealEcpm > 0) ? fbRealEcpm : fbBackendEcpm;
                if (fbReportEcpm < 0) { fbReportEcpm = 0.0d; }
                AdRequestReportApi.reportQuick(ADXiluSdk.getInstance().getContext(),
                        AdRequestReportApi.EVENT_FALLBACK, this.mPosId, getReportAdFormat(),
                        platformPosId, null, "", fbReportEcpm);
            }
        }
        try {
            if (ADXiluAdUtil.isReleased(this.mXiluAd)) {
                return;
            }
            cleanAdapterLoader();
            this.mLoadAdError = null;
            this.mAdapterLoader = getAdapterLoader(platform, platformPosIdStr, c);
            bindSizeListener(this.mAdapterLoader);
            if (this.mAdapterLoader == null) {
                ADXiluError createErrorDesc2 = ADXiluError.createError(platform, platformPosIdStr, ADXiluErrorConfig.AD_FAILED_ADAPTER_IS_NOT_SUPPORT_AD_TYPE, ADXiluErrorConfig.MSG_AD_FAILED_ADAPTER_IS_NOT_SUPPORT_AD_TYPE);
                if (ADXiluLogUtil.needShowLog()) {
                    ADXiluLogUtil.d("当前HBFallback广告位轮循失败，错误信息 : " + createErrorDesc2.toString());
                }
                errorAppend(createErrorDesc2);
                onLoopFailure();
                return;
            }
            apiLoadAd();
            // 竞价阶段已上报过request的平台(isRequest=true)不再重复上报，
            // 没有竞价适配器而降级到瀑布流的平台(isRequest=false)需正常上报
            if (platformPosId == null || !platformPosId.isRequest()) {
                AdRequestReportApi.reportQuick(ADXiluSdk.getInstance().getContext(), AdRequestReportApi.EVENT_REQUEST, this.mPosId, getReportAdFormat(), platformPosId);
            }
            ADXiluAdapterParams adapterParams = new ADXiluAdapterParams(platformPosId, d, this.mReward == 1, this.count, this.mPosId);

            // 预加载模式：通知平台适配器进入预加载模式
            if (this.mIsPreloadMode && this.mAdapterLoader instanceof ADXiluPreloadableLoader) {
                ((ADXiluPreloadableLoader) this.mAdapterLoader).setPreloadMode(true);
            }

            startSingleAdLoadTimer(this.mSingleSourceTimeout);
            this.mAdapterLoader.loadAd(this.mXiluAd, adapterParams, this);
            return;
        } catch (Throwable e1) {
            e1.printStackTrace();
            ADXiluError createErrorDesc3 = ADXiluError.createError(platform, platformPosIdStr, ADXiluErrorConfig.AD_FAILED_GET_AD_EXCEPTION, ADXiluErrorConfig.MSG_AD_FAILED_GET_AD_EXCEPTION);
            if (ADXiluLogUtil.needShowLog()) {
                ADXiluLogUtil.d("当前HBFallback广告位轮循失败，错误信息 : " + createErrorDesc3.toString());
            }
            errorAppend(createErrorDesc3);
            onLoopFailure();
            return;
        }
    }

    private boolean isC2SBidType(IBasePlatformPosInfo platformPosId) {
        return BidManagerFactory.getInstance().isC2SBidType(platformPosId);
    }

    private List<IBasePlatformPosInfo> getParallelPlatformPosList(List<IBasePlatformPosInfo> list, int start) {
        int index;
        if (list == null || list.isEmpty()) {
            return null;
        }
        ArrayList<IBasePlatformPosInfo> arrayList = new ArrayList<>();
        for (int i = 0; i < this.mSrcParallelReqCount && list.size() > (index = start + i); i++) {
            IBasePlatformPosInfo platformPosId = list.get(index);
            if (platformPosId == null || platformPosId.isBidType() || !isParallelType(platformPosId)){
                continue;
            }
            arrayList.add(platformPosId);
        }
        return arrayList;
    }

    private void errorAppend(ADXiluError error) {
        if (this.mError != null) {
            this.mError.appendDesc(error);
        }
    }

    private void filterRequestRate(List<IBasePlatformPosInfo> list) {
        ArrayList<IBasePlatformPosInfo> arrayList = new ArrayList<>();
        for (IBasePlatformPosInfo platformPosId : list) {
            if (!RandomUtil.check(platformPosId.getRequestRate())) {
                arrayList.add(platformPosId);
            }
        }
        list.removeAll(arrayList);
    }

    private void filterFrequencyFinished(List<IBasePlatformPosInfo> list) {
        ArrayList<IBasePlatformPosInfo> arrayList = new ArrayList<>();
        for (IBasePlatformPosInfo platformPosInfo : list) {
            if (platformPosInfo.isFrequencyFinished()) {
                arrayList.add(platformPosInfo);
            }
        }
        list.removeAll(arrayList);
    }

    /**
     * 按 launchType 过滤冷热广告位。
     * hotDiffSlot=0：不区分，不过滤。
     * hotDiffSlot=1：按 targetLaunchType 过滤对应的广告位。
     *
     * @param list             平台广告位列表
     * @param targetLaunchType 要过滤掉的 launchType：预加载传 1（过滤冷启），冷启动传 2（过滤热启）
     */
    private void filterByLaunchType(List<IBasePlatformPosInfo> list, int targetLaunchType) {
        if (list == null || list.isEmpty()) {
            return;
        }
        if (mHotDiffSlot != 1) {
            // hotDiffSlot=0：不区分冷热，所有广告位都参与竞价
            return;
        }
        Iterator<IBasePlatformPosInfo> iterator = list.iterator();
        while (iterator.hasNext()) {
            IBasePlatformPosInfo info = iterator.next();
            if (info == null) {
                continue;
            }
            int launchType = info.getLaunchType();
            if (launchType == targetLaunchType) {
                String scene = (targetLaunchType == 1) ? "热启动预加载过滤冷启广告位" : "冷启动过滤热启广告位";
                ADXiluLogUtil.d("HotStartPreload", scene + ": platform=" + info.getPlatform()
                        + ", posId=" + info.getPlatformPosId() + ", launchType=" + launchType);
                iterator.remove();
            }
        }
    }

    /**
     * 预加载模式过滤ADX平台项。
     * ADX不参与预加载（splashLaunch）竞价，广告位列表与竞价请求两层均需过滤：
     * 列表层在此过滤，竞价请求层由 BidSortOrder.startAdxBid 按 mIsPreloadMode 跳过。
     */
    private void filterAdxPlatform(List<IBasePlatformPosInfo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Iterator<IBasePlatformPosInfo> iterator = list.iterator();
        while (iterator.hasNext()) {
            IBasePlatformPosInfo platformPosInfo = iterator.next();
            if (platformPosInfo != null
                    && AdxApi.ADX_PLATFORM.equals(platformPosInfo.getPlatform())) {
                ADXiluLogUtil.d("PreloadFilter", "过滤预加载ADX平台项: " + platformPosInfo.getPlatformPosId());
                iterator.remove();
            }
        }
    }

    private void filterHotStartPreloadPlatforms(List<IBasePlatformPosInfo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Iterator<IBasePlatformPosInfo> iterator = list.iterator();
        while (iterator.hasNext()) {
            IBasePlatformPosInfo platformPosInfo = iterator.next();
            if (platformPosInfo == null) {
                continue;
            }
            String platform = platformPosInfo.getPlatform();
            if (HOT_START_PRELOAD_FILTERED_PLATFORMS.contains(platform)) {
                ADXiluLogUtil.d("HotStartPreload", "过滤热启动预加载不参与平台: " + platform
                        + ", posId=" + platformPosInfo.getPlatformPosId());
                iterator.remove();
            }
        }
    }

    /**
     * 向热启动预加载过滤集合中添加一个平台关键字。
     * 仅对开启 filterBzMs 的预加载竞价生效。
     */
    public static void addHotStartPreloadFilteredPlatform(String platform) {
        if (TextUtils.isEmpty(platform)) {
            return;
        }
        HOT_START_PRELOAD_FILTERED_PLATFORMS.add(platform);
    }

    private IBasePlatformPosInfo a(IBasePlatformPosInfo platformPosInfo) {
        if (platformPosInfo == null || this.mAllPlatformPosIdList == null || this.mAllPlatformPosIdList.isEmpty()) {
            return null;
        }
        String platformPosId = platformPosInfo.getPlatformPosId();
        Iterator<IBasePlatformPosInfo> it = this.mAllPlatformPosIdList.iterator();
        while (it.hasNext()) {
            IBasePlatformPosInfo next = it.next();
            if (next.getPlatformPosId().equals(platformPosId)) {
                return next;
            }
        }
        return null;
    }

    public void addAdLoader(ADXiluAdapterLoader adapterLoader) {
        if (this.mParallelAdLoaderList == null || this.mParallelAdLoaderList.contains(adapterLoader)) {
            return;
        }
        this.mParallelAdLoaderList.add(adapterLoader);
    }

    protected K getAdStatus(T t) {
        Map<Integer, K> map = this.mAdStatusMap;
        if (map != null) {
            return map.get(Integer.valueOf(t.hashCode()));
        }
        return null;
    }

    protected boolean hasAdStatus(Integer num) {
        Map<Integer, K> map = this.mAdStatusMap;
        if (map != null) {
            return map.containsKey(num);
        }
        return false;
    }

    protected boolean hasAdStatus(List<T> list) {
        if (this.mAdStatusMap == null || list == null || list.size() <= 0) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            if (this.mAdStatusMap.containsKey(Integer.valueOf(list.get(i).hashCode()))) {
                return true;
            }
        }
        return false;
    }

    private void parallelLoadAd(IBasePosInfo posInfo) {
        if (isParallelModel(posInfo)) {
            List<IBasePlatformPosInfo> list = sortParallelPlatformPosList(posInfo);
            if (hasParallelPlatformPos(list) != null) {
                parallelLoadAd(posInfo, list, this.mSingleSourceTimeout);
            }
        }
        bidSortOrder(posInfo);
    }

    private void bidSortOrder(IBasePosInfo posInfo) {
        String platform = this.mXiluAd == null ? null : this.mXiluAd.getOnlySupportPlatform();
        int bidTimeout = this.mBiddingTimeout > 0 ? this.mBiddingTimeout : 500;
        this.mBidSortOrder = new BidSortOrder(platform, this.mAdType, new BidSortOrderCallback() {
            @Override
            public void onSucceed() {
                mBidSortOrder = null;
                if (mIsPreloadMode) {
                    // 预加载模式：广告已通过 BidSortOrder.onBidWin() 缓存，不走 loopLoadAd
                    ADXiluLogUtil.d("预加载模式竞价完成，跳过 loopLoadAd");
                    return;
                }
                if (mHandler != null && BaseAdLoadLooper.this.mBidSortRunnable != null) {
                    mHandler.post(BaseAdLoadLooper.this.mBidSortRunnable);
                }
            }

            @Override
            public void onFailed() {
                mBidSortOrder = null;
                setError(ADXiluErrorConfig.AD_FAILED_ALL_PLATFORM_BID_NO_AD, ADXiluErrorConfig.MSG_AD_FAILED_ALL_PLATFORM_BID_NO_AD);
                onAllBidFailed();
                loadAdFailed();
            }
        }, bidTimeout);
        this.mBidSortOrder.setPreloadMode(this.mIsPreloadMode);
        this.mBidSortOrder.setReportAdFormat(this.mReportAdFormat);
        this.mBidSortOrder.sortList(posInfo, mLoadPlatformPosList, getSceneId(), this.mXiluAd, this);
    }

    private void parallelLoadAd(IBasePosInfo posId, List<IBasePlatformPosInfo> list, int timeout) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<IBasePlatformPosInfo> parallelList = getParallelPlatformPosList(list, 0);
        if (parallelList == null || parallelList.isEmpty()){
            return;
        }

        this.mParallelStatus = new ParallelStatus();
        this.mParallelStatus.setPlatformPosIdList(parallelList);
        this.mParallelLoader = new ParallelLoader(posId, new ParallelCallback() {
            @Override
            public void onSuccess(IBasePlatformPosInfo platformPosId) {
                if (mParallelStatus == null || mParallelStatus.isFinished()) {
                    return;
                }
                mParallelStatus.setPlatformPosId(platformPosId);
                mParallelStatus.setSuccess(true);
                mParallelStatus.setFinished(true);
                mParallelSubject.notifyObserver();
            }

            @Override
            public void onFailed() {
                if (mParallelStatus == null || mParallelStatus.isFinished()) {
                    return;
                }
                mParallelStatus.setSuccess(false);
                mParallelStatus.setFinished(true);
                mParallelSubject.notifyObserver();
            }
        }, this.mAdType, this.mXiluAd == null ? null : this.mXiluAd.getOnlySupportPlatform());
        this.mParallelLoader.setPreloadMode(this.mIsPreloadMode);
        this.mParallelLoader.setReportAdFormat(this.mReportAdFormat);
        this.mParallelLoader.load(parallelList, timeout, this.count, this.mXiluAd, this);
    }

    private void sortParallelPlatformPosIdList(List<IBasePlatformPosInfo> list, ParallelStatus parallelStatus) {
        if (parallelStatus == null) {
            return;
        }
        ArrayList<String> arrayList = new ArrayList<>();
        List<IBasePlatformPosInfo> a = parallelStatus.getPlatformPosIdList();
        if (a != null) {
            Iterator<IBasePlatformPosInfo> it = a.iterator();
            while (it.hasNext()) {
                arrayList.add(it.next().getPlatformPosId());
            }
        }
        ArrayList<IBasePlatformPosInfo> arrayList2 = new ArrayList<>();
        for (IBasePlatformPosInfo platformPosId : list) {
            if (platformPosId != null && arrayList.contains(platformPosId.getPlatformPosId())) {
                arrayList2.add(platformPosId);
            }
        }
        list.removeAll(arrayList2);
        if (parallelStatus.isSuccess()) {
            list.add(parallelStatus.getPlatformPosId());
            Collections.sort(list, this.mEcpmComparator);
        }
    }

    private List<IBasePlatformPosInfo> sortParallelPlatformPosList(IBasePosInfo posId) {
        if (posId == null ||this.mLoadPlatformPosList == null || this.mLoadPlatformPosList.isEmpty()) {
            return null;
        }
        ArrayList<IBasePlatformPosInfo> arrayList = new ArrayList<>();
        for (IBasePlatformPosInfo posInfo : this.mLoadPlatformPosList) {
            if (posInfo instanceof PlatformPosInfo) {
                arrayList.add(new PlatformPosInfo((PlatformPosInfo) posInfo));
            }
        }
        new WaterfallFlowOrder().sortList(posId, arrayList, null, null, null);
        return arrayList;
    }

    private boolean hasNext(IBasePlatformPosInfo platformPosId, List<IBasePlatformPosInfo> list, int index) {
        List<IBasePlatformPosInfo> b;
        return this.mSrcParallelReqCount == 1 || platformPosId == null || platformPosId.isRequest() || !isParallelType(platformPosId) || (b = getParallelPlatformPosList(list, index)) == null || b.size() == 1;
    }

    private void adapterIniterInit(String str, IBasePlatformInfo platform, ADXiluAdapterIniter adapterIniter) {
        try {
            adapterIniter.init(platform, null);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private ADXiluAdapterLoader getAdapterLoader(String platform, String platformPosId, ADXiluAdapterIniter adapterIniter) {
        if (isC2SBidType(this.mCurPlatformPosId)) {
            ADXiluAdapterLoader theLatestPreAdapterLoader = PreLoaderCacheManager.getInstance().getTheLatestPreAdapterLoader(this, platformPosId);
            if (theLatestPreAdapterLoader != null) {
                PreLoaderCacheManager.getInstance().removePreAdapterLoader(this, platformPosId);
                return theLatestPreAdapterLoader;
            }
            this.mLoadAdError = ADXiluError.createError(platform, platformPosId, ADXiluErrorConfig.AD_FAILED_ADAPTER_IS_C2S_BID_INIT_ERROR, ADXiluErrorConfig.MSG_AD_FAILED_ADAPTER_IS_C2S_BID_INIT_ERROR);
            return null;
        }
        if (isParallelType(this.mCurPlatformPosId) && this.mCurPlatformPosId.isRequest()) {
            ADXiluAdapterLoader theLatestPreAdapterLoader2 = PreLoaderCacheManager.getInstance().getTheLatestPreAdapterLoader(this, platformPosId);
            if (theLatestPreAdapterLoader2 != null) {
                PreLoaderCacheManager.getInstance().removePreAdapterLoader(this, platformPosId);
                return theLatestPreAdapterLoader2;
            }
            this.mLoadAdError = ADXiluError.createError(platform, platformPosId, ADXiluErrorConfig.AD_FAILED_ADAPTER_PRE_LOAD_AD_ERROR, ADXiluErrorConfig.MSG_AD_FAILED_ADAPTER_PRE_LOAD_AD_ERROR);
            return null;
        }
        return AdLoaderUtil.getAdLoader(adapterIniter, platform, this.mAdType);
    }




    private void errorReport(ADXiluError error) {
        if (error == null) {
            return;
        }
        // 统一报错（所有平台都失败）不上报，已在竞价失败时单独上报
        if (error.getCode() == ADXiluErrorConfig.AD_FAILED_ALL_PLATFORM_BID_NO_AD) {
            return;
        }
        // 错误已在 adapter 层上报，这里不再重复上报
        // AdEventPluginAdapter.getInstance().addErrorReportInfo(ADXiluSdk.getInstance().getAppId(), this.mPosId, this.mAdType, "failure", error.toString());
        // ErrorReportApi.reportErrorQuick(...);
    }

    private void setError(int i, String str) {
        if (this.mError != null) {
            this.mError.setCode(i);
            this.mError.setError(str);
        }
    }


    private void startTotalAdLoadTimer(boolean isHeadingBid) {
        if (this.mMainHandler == null || this.mAdLoadTimeoutThread == null || ADXiluAdUtil.isReleased(this.mXiluAd)) {
            return;
        }
        this.mMainHandler.removeCallbacks(this.mAdLoadTimeoutThread);
        int totalTimeout;
        if (this.mTotalTimeout > 0) {
            totalTimeout = this.mTotalTimeout;
        } else if (isHeadingBid) {
            totalTimeout = 5000;
        } else {
            totalTimeout = (int) this.mXiluAd.getTimeout();
        }
        this.mMainHandler.postDelayed(this.mAdLoadTimeoutThread, totalTimeout);
    }

    private Runnable mAdLoadTimeoutThread = new Runnable() {
        @Override
        public void run() {
            // 全局超时，取消竞价排序（如果正在进行）
            if (mBidSortOrder != null) {
                mBidSortOrder.cancelAll();
                mBidSortOrder = null;
            }
            cleanParallelLoader();
            cleanSingleAdLoadHandler();
            if (z() && mLoadPlatformPosList != null && mLoadPlatformPosList.size() > mCurIndex) {
                IBasePlatformPosInfo platformPosId = BaseAdLoadLooper.this.a(mLoadPlatformPosList, mCurIndex);
                if (platformPosId != null) {
                    C = true;
                    d(platformPosId);
                    return;
                }
            }
            onLoopFailure();
        }
    };

    private void startSingleAdLoadTimer(int timeout) {
        if (timeout == 0 || this.mSingleAdLoadHandler == null || this.mSingleAdLoadTimeoutThread == null || ADXiluAdUtil.isReleased(this.mXiluAd)) {
            return;
        }
        this.mSingleAdLoadHandler.postDelayed(this.mSingleAdLoadTimeoutThread, timeout);
    }

    private Runnable mSingleAdLoadTimeoutThread = new Runnable() {

        @Override
        public void run() {
            ADXiluLogUtil.d("adSourceTimeoutRunnable code : " + hashCode());
            if (isReleased()) {
                return;
            }
            if (mAdapterLoader != null) {
                mAdapterLoader.release();
            }
            if (mCurPlatformPosId != null) {
                String errorStr = "";
                try {
                    errorStr = String.format(ADXiluErrorConfig.MSG_AD_FAILED_AD_SOURCE_TIMEOUT, mSingleSourceTimeout, mTotalTimeout);
                } catch (Exception unused) {
                    errorStr = ADXiluErrorConfig.MSG_AD_FAILED_AD_SOURCE_TIMEOUT2;
                }
                ADXiluError error = null;
                if (A()) {
                    error = ADXiluError.createError(getCurrentPlatform(), getCurrentPlatformPosIdStr(), ADXiluErrorConfig.AD_FAILED_AD_SOURCE_TIMEOUT, errorStr);
                    if (ADXiluLogUtil.needShowLog()) {
                        ADXiluLogUtil.d("当前HBFallback广告位轮循失败，错误信息 : " + error);
                    }
                    errorAppend(error);
                    onLoopFailure();
                    return;
                }
                error = ADXiluError.createError(mCurPlatformPosId.getPlatform(), mCurPlatformPosId.getPlatformPosId(), ADXiluErrorConfig.AD_FAILED_AD_SOURCE_TIMEOUT, errorStr);
                loopLoadAd(error);
            }
        }
    };

    private void I() {
        if (this.mHandler == null || this.mBidSortRunnable == null) {
            return;
        }
        this.mHandler.removeCallbacks(this.mBidSortRunnable);
    }

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mBidSortRunnable = new Runnable (){
        @Override
        public void run() {
            if (isReleased()) {
                return;
            }
            if (mLoadPlatformPosList != null && !mLoadPlatformPosList.isEmpty()) {
                if (mLoadPlatformPosList.get(0).isBidType()) {
                    ADXiluLogUtil.ti("ADSSPParallel", "HB 出价排位第一");
                    w();
                    return;
                } else {
                    ADXiluLogUtil.ti("ADSSPParallel", "瀑布流 出价排位第一");
                    x();
                    return;
                }
            }
            ADXiluLogUtil.ti("ADSSPParallel", "广告请求列类数量异常状态，进入轮询器抛出错误回调");
            startLoopLoadAd();
        }
    };

    private void w() {
        if (this.mParallelStatus != null) {
            ADXiluLogUtil.ti("ADSSPParallel", "预加载不为空，有并发请求的情况下");
            if (!this.mParallelStatus.isFinished()) {
                ADXiluLogUtil.ti("ADSSPParallel", "瀑布流并发还没有返回值，则强制标记结束");
                this.mParallelStatus.setFinished(true);
                this.mParallelStatus.setSuccess(false);
                if (this.mParallelLoader != null) {
                    this.mParallelLoader.a();
                }
            } else {
                ADXiluLogUtil.ti("ADSSPParallel", "瀑布流第一位已经有返回状态了");
            }
            ADXiluLogUtil.ti("ADSSPParallel", "执行这里已经明确并发请求有结果了（无论是正常结束还是强制结束） 过滤结果，发起请求");
            sortParallelPlatformPosIdList(this.mLoadPlatformPosList, this.mParallelStatus);
        } else {
            ADXiluLogUtil.ti("ADSSPParallel", "没有并发请求预加载渠道，直接发起请求");
        }
        startLoopLoadAd();
    }

    private void x() {
        if (this.mParallelStatus != null) {
            ADXiluLogUtil.ti("ADSSPParallel", "预加载不为空，有并发请求的情况下");
            if (this.mParallelStatus.isFinished()) {
                ADXiluLogUtil.ti("ADSSPParallel", "并发请求已完成，过滤结果发起发起请求");
                sortParallelPlatformPosIdList(this.mLoadPlatformPosList, this.mParallelStatus);
                startLoopLoadAd();
                return;
            } else {
                ADXiluLogUtil.ti("ADSSPParallel", "并发请求未完成，开始注册监听并发回调结束发起请求");
                this.mParallelSubject.setObserver(new ParallelObserver() {
                    @Override 
                    public void update() {
                        mParallelSubject.remove();
                        ADXiluLogUtil.ti("ADSSPParallel", "并发请求结果已返回，过滤结果发起发起请求");
                        BaseAdLoadLooper.this.sortParallelPlatformPosIdList(mLoadPlatformPosList, mParallelStatus);
                        startLoopLoadAd();
                    }
                });
                return;
            }
        }
        ADXiluLogUtil.ti("ADSSPParallel", "没有并发请求预加载渠道，直接发起请求");
        startLoopLoadAd();
    }

}
