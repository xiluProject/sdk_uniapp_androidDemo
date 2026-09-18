package com.xilu.sdk.adapter.bqt.loader;

import com.baidu.mobads.sdk.api.BaiduNativeManager;
import com.baidu.mobads.sdk.api.RequestParameters;
import com.xilu.sdk.ad.ADXiluBannerAd;
import com.xilu.sdk.ad.data.ADXiluAdType;
import com.xilu.sdk.ad.adapter.ADXiluAdapterLoader;
import com.xilu.sdk.ad.adapter.ADXiluAdapterParams;
import com.xilu.sdk.ad.adapter.ADXiluAdapterSizeReporter;
import com.xilu.sdk.ad.data.IBasePlatformPosInfo;
import com.xilu.sdk.ad.entity.ADXiluAdSize;
import com.xilu.sdk.ad.entity.ADXiluExtraParams;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluAdapterSizeListener;
import com.xilu.sdk.ad.listener.ADXiluBannerAdListener;
import com.xilu.sdk.adapter.bqt.listener.BannerAdListener2;
import com.xilu.sdk.adapter.bqt.listener.bid.AdError;
import com.xilu.sdk.adapter.bqt.strategy.BidPreLoadAdStrategy;
import com.xilu.sdk.adapter.bqt.strategy.PreLoadAdStrategy;
import com.xilu.sdk.adapter.bqt.utils.AdPositionUtil;
import com.xilu.sdk.adapter.bqt.utils.RequestParametersUtil;
import com.xilu.sdk.bid.ADXiluBidAdapterCallback;
import com.xilu.sdk.bid.ADXiluBidParams;
import com.xilu.sdk.bid.manager.ADXiluBidManager;
import com.xilu.sdk.util.ADXiluAdUtil;

/**
 * Created by zhangqinglou on 2025/4/28.
 */
public class BannerAdLoader implements ADXiluAdapterLoader<ADXiluBannerAd, ADXiluBannerAdListener>, ADXiluBidManager {
    private ADXiluBannerAd mAd;
    private ADXiluAdapterParams mAdapterParams;
    private ADXiluBannerAdListener mAdListener;
    private PreLoadAdStrategy mPreLoadAdStrategy;
    private BannerAdListener2 mNativeListener;
    /** 请求尺寸转发器：把"请求时告诉平台的框"交回装载链 → ADXiluBannerAd → 桥接层 */
    private final ADXiluAdapterSizeReporter mSizeReporter = new ADXiluAdapterSizeReporter();

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
        if (this.mPreLoadAdStrategy != null) {
            this.mPreLoadAdStrategy.release();
            this.mPreLoadAdStrategy = null;
        }
        this.mAd = null;
        this.mAdapterParams = null;
        this.mAdListener = null;
    }

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
    public void bid(ADXiluBidAdapterCallback callback) {
        IBasePlatformPosInfo posInfo = null;
        String posId = null;
        if (this.mAdapterParams != null) {
            posInfo = this.mAdapterParams.getPlatformPosInfo();
            posId = this.mAdapterParams.getPosId();
        }
        this.mPreLoadAdStrategy = new BidPreLoadAdStrategy(callback, posInfo, posId, ADXiluAdType.TYPE_BANNER);
        load();
    }

    private void load() {
        if (ADXiluAdUtil.isReleased(this.mAd) || this.mAd.getActivity() == null || this.mAd.getContainer() == null || this.mAdapterParams == null || this.mAdapterParams.getPlatformPosInfo() == null || this.mAdListener == null) {
            return;
        }
        IBasePlatformPosInfo platformPosId = this.mAdapterParams.getPlatformPosInfo();
        if ("flow".equals(platformPosId.getAdType())) {
            innerLoadFlow(this.mAd, platformPosId, this.mAdListener);
        } else {
            innerLoad(this.mAd, platformPosId, this.mAdListener);
        }
    }

    private void innerLoad(ADXiluBannerAd ad, IBasePlatformPosInfo platformPosInfo, ADXiluBannerAdListener adListener) {
        if (this.mPreLoadAdStrategy != null) {
            this.mPreLoadAdStrategy.onError(new AdError(-1, "横幅广告产品已下线，请使用自渲染信息流广告代替横幅广告"));
        } else if (adListener != null) {
            adListener.onAdFailed(new ADXiluError(-1, "横幅广告产品已下线，请使用自渲染信息流广告代替横幅广告"));
        }
    }

    private void innerLoadFlow(ADXiluBannerAd ad, IBasePlatformPosInfo platformPosInfo, ADXiluBannerAdListener adListener) {
        if (this.mPreLoadAdStrategy != null && this.mNativeListener != null) {
            this.mNativeListener.onAdReceive();
            return;
        }
        ADXiluExtraParams localExtraParams = ad.getLocalExtraParams();
        int width = ad.getActivity().getResources().getDisplayMetrics().widthPixels;
        if (localExtraParams != null && localExtraParams.getAdSize() != null) {
            if (localExtraParams.getAdSize().getWidth() > 0) {
                width = localExtraParams.getAdSize().getWidth();
            }
        }
        ADXiluAdSize adSize;
        if (platformPosInfo.getAdSize() == null) {
            adSize = new ADXiluAdSize(640, 100);
        } else {
            adSize = platformPosInfo.getAdSize();
        }
        // 上报请求框尺寸（px）：App 指定 > 后台配置 > 屏宽×340dp 兜底
        int screenWidthPx = ad.getActivity() != null
                ? ad.getActivity().getResources().getDisplayMetrics().widthPixels
                : ad.getContext().getResources().getDisplayMetrics().widthPixels;
        int fallbackHeightPx = Math.round(340f * ad.getContext().getResources().getDisplayMetrics().density);
        int[] box = this.mAdapterParams.computeAdSizePx(
                localExtraParams == null ? null : localExtraParams.getAdSize(), screenWidthPx, fallbackHeightPx);
        this.mSizeReporter.report(box[0], box[1]);
        this.mNativeListener = new BannerAdListener2(this.mAdapterParams.getPosId(), platformPosInfo.getPlatformPosId(), adListener, this.mPreLoadAdStrategy);
        this.mNativeListener.setIsBidType(platformPosInfo.isBidType());
        // 下面这个是给**自绘 View 用的**尺寸（BQT 自渲染横幅自己画 BannerLeftPicView），与"上报给桥接层的框"无关
        this.mNativeListener.setAdSize(width, (int) (width / (adSize.getWidth() / adSize.getHeight())));
        new BaiduNativeManager(ad.getActivity(), AdPositionUtil.parsePlatformPosId(platformPosInfo.getPlatformPosId())).loadFeedAd(new RequestParameters.Builder().downloadAppConfirmPolicy(RequestParametersUtil.a()).build(), this.mNativeListener);
    }


}
