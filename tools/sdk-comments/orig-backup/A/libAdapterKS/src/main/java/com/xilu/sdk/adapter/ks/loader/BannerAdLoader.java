package com.xilu.sdk.adapter.ks.loader;

import com.kwad.sdk.api.KsAdSDK;
import com.kwad.sdk.api.KsInitCallback;
import com.kwad.sdk.api.KsScene;
import com.xilu.sdk.ad.ADXiluBannerAd;
import com.xilu.sdk.ad.data.ADXiluAdType;
import com.xilu.sdk.ad.adapter.ADXiluAdapterLoader;
import com.xilu.sdk.ad.adapter.ADXiluAdapterParams;
import com.xilu.sdk.ad.adapter.ADXiluAdapterSizeReporter;
import com.xilu.sdk.ad.data.IBasePlatformPosInfo;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluAdapterSizeListener;
import com.xilu.sdk.ad.listener.ADXiluBannerAdListener;
import com.xilu.sdk.adapter.ks.ADXiluIniter;
import com.xilu.sdk.adapter.ks.listener.BannerAdListener;
import com.xilu.sdk.adapter.ks.listener.bid.AdError;
import com.xilu.sdk.adapter.ks.manager.KsadInitManager;
import com.xilu.sdk.adapter.ks.strategy.BidPreLoadAdStrategy;
import com.xilu.sdk.adapter.ks.strategy.PreLoadAdStrategy;
import com.xilu.sdk.adapter.ks.utils.KSPosIdUtil;
import com.xilu.sdk.bid.ADXiluBidAdapterCallback;
import com.xilu.sdk.bid.ADXiluBidParams;
import com.xilu.sdk.bid.manager.ADXiluBidManager;
import com.xilu.sdk.util.ADXiluAdUtil;

/**
 * Created by zhangqinglou on 2025/4/28.
 */
public class BannerAdLoader implements ADXiluAdapterLoader<ADXiluBannerAd, ADXiluBannerAdListener>, ADXiluBidManager, KsInitCallback {
    private ADXiluBannerAd mAd;
    private ADXiluAdapterParams mAdapterParams;
    private ADXiluBannerAdListener mAdListener;
    private BannerAdListener mNativeListener;
    private PreLoadAdStrategy mPreLoadAdStrategy;
    private boolean ksInited;
    /** 请求尺寸转发器：把"请求时告诉平台的框"交回装载链 → ADXiluBannerAd → 桥接层 */
    private final ADXiluAdapterSizeReporter mSizeReporter = new ADXiluAdapterSizeReporter();

    @Override
    public void init(IBasePlatformPosInfo platformPosInfo, String str, ADXiluBidParams bidParams) {
        if (bidParams != null) {
            if (bidParams.getXiluAd() instanceof ADXiluBannerAd) {
                this.mAd = (ADXiluBannerAd) bidParams.getXiluAd();
            }
            this.mAdapterParams = bidParams.getAdapterParams();
            if (bidParams.getListener() instanceof ADXiluBannerAdListener) {
                this.mAdListener = (ADXiluBannerAdListener) bidParams.getListener();
            }
        }
    }

    @Override
    public void loadAd(ADXiluBannerAd ad, ADXiluAdapterParams params, ADXiluBannerAdListener adListener) {
        this.mAd = ad;
        this.mAdapterParams = params;
        this.mAdListener = adListener;
        this.mSizeReporter.bind(ad);
        load();
    }

    /**
     * 装载链注册横幅尺寸的接收方（ADXiluBannerAd）。
     * 横幅回传的是**请求时告诉平台的框**，不是量渲染视图的结果。
     */
    public void setSizeListener(ADXiluAdapterSizeListener listener) {
        this.mSizeReporter.bind(listener);
    }

    @Override
    public void onResumed() {
    }

    @Override
    public void onPaused() {
    }

    @Override
    public void release() {
        this.mSizeReporter.unbind();
        if (this.mNativeListener != null) {
            this.mNativeListener.release();
            this.mNativeListener = null;
        }
        this.mAd = null;
        this.mAdapterParams = null;
        this.mAdListener = null;
        if (this.mPreLoadAdStrategy != null) {
            this.mPreLoadAdStrategy.release();
            this.mPreLoadAdStrategy = null;
        }
    }

    @Override
    public void bid(ADXiluBidAdapterCallback callback) {
        IBasePlatformPosInfo platformPosInfo = null;
        String posId = null;
        if (this.mAdapterParams != null) {
            platformPosInfo = this.mAdapterParams.getPlatformPosInfo();
            posId = this.mAdapterParams.getPosId();
        }
        this.mPreLoadAdStrategy = new BidPreLoadAdStrategy(callback, platformPosInfo, posId, ADXiluAdType.TYPE_BANNER);
        load();
    }

    @Override
    public void onSuccess() {
        if (this.ksInited) {
            return;
        }
        this.ksInited = true;
        innerLoad();
    }

    @Override
    public void onFail(int code, String str) {
        if (this.mPreLoadAdStrategy != null) {
            this.mPreLoadAdStrategy.onError(new AdError(code, str));
            return;
        }
        if (this.mAdListener != null) {
            this.mAdListener.onAdFailed(new ADXiluError(code, str));
        }
    }

    private void load() {
        if (this.ksInited) {
            innerLoad();
            return;
        }
        if (KsAdSDK.getAppId() != null && !KsAdSDK.getAppId().isEmpty()) {
            this.ksInited = true;
            innerLoad();
            return;
        }
        KsadInitManager.getInstance().resetAppId();
        KsadInitManager.getInstance().setSplashStartCallback(this);
    }

    private void innerLoad() {
        if (ADXiluAdUtil.isReleased(this.mAd) || this.mAdapterParams == null || this.mAdapterParams.getPlatformPosInfo() == null || this.mAdListener == null) {
            return;
        }
        String platformPosId = this.mAdapterParams.getPlatformPosInfo().getPlatformPosId();
        long id = KSPosIdUtil.parsePlatformId(platformPosId);
        if (id == 0) {
            PreLoadAdStrategy preLoadAdStrategy = this.mPreLoadAdStrategy;
            if (preLoadAdStrategy != null) {
                preLoadAdStrategy.onError(new AdError(-1, "广告位ID解析失败"));
                return;
            } else {
                this.mAdListener.onAdFailed(ADXiluError.createError(ADXiluIniter.PLATFORM, platformPosId, -1, "广告位ID解析失败"));
                return;
            }
        }
        if (this.mPreLoadAdStrategy != null && this.mNativeListener != null) {
            this.mNativeListener.onAdReceive();
            return;
        }
        KsScene build = new KsScene.Builder(id).adNum(1).build();
        String posId = this.mAdapterParams.getPosId();
        // 上报这次请求告诉平台的框（后台配置值，未配置退屏宽×340dp）
        reportRequestBox();
        this.mNativeListener = new BannerAdListener(posId, platformPosId, this.mAdListener, this.mPreLoadAdStrategy);
        this.mNativeListener.setIsBidType(this.mAdapterParams.getPlatformPosInfo().isBidType());
        KsAdSDK.getLoadManager().loadBannerAd(build, this.mNativeListener);
    }

    /** 上报请求框尺寸（px）：后台配置，未配置退屏宽×340dp */
    private void reportRequestBox() {
        if (this.mAd == null || this.mAdapterParams == null) {
            return;
        }
        int screenWidthPx = this.mAd.getContext().getResources().getDisplayMetrics().widthPixels;
        int fallbackHeightPx = Math.round(340f * this.mAd.getContext().getResources().getDisplayMetrics().density);
        int[] box = this.mAdapterParams.computeAdSizePx(this.mAd == null ? null : this.mAd.getLocalExtraParams() == null ? null : this.mAd.getLocalExtraParams().getAdSize(), screenWidthPx, fallbackHeightPx);
        this.mSizeReporter.report(box[0], box[1]);
    }
}
