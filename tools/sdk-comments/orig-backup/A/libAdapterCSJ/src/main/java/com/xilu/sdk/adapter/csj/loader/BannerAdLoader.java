package com.xilu.sdk.adapter.csj.loader;

import com.bytedance.sdk.openadsdk.AdSlot;
import com.bytedance.sdk.openadsdk.TTAdNative;
import com.bytedance.sdk.openadsdk.mediation.ad.MediationAdSlot;
import com.xilu.sdk.ADXiluSdk;
import com.xilu.sdk.ad.ADXiluBannerAd;
import com.xilu.sdk.ad.adapter.ADXiluAdapterLoader;
import com.xilu.sdk.ad.adapter.ADXiluAdapterParams;
import com.xilu.sdk.ad.adapter.ADXiluAdapterSizeReporter;
import com.xilu.sdk.ad.data.IBasePlatformPosInfo;
import com.xilu.sdk.ad.entity.ADXiluAdSize;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluAdapterSizeListener;
import com.xilu.sdk.ad.listener.ADXiluBannerAdListener;
import com.xilu.sdk.adapter.csj.ADXiluIniter;
import com.xilu.sdk.adapter.csj.listener.BannerAdListener;
import com.xilu.sdk.adapter.csj.listener.BaseAdListener;
import com.xilu.sdk.adapter.csj.listener.BidBannerAdListener;
import com.xilu.sdk.adapter.csj.manager.TTAdInitManager;
import com.xilu.sdk.adapter.csj.strategy.AdError;
import com.xilu.sdk.adapter.csj.strategy.BidPreLoadAdStrategy;
import com.xilu.sdk.adapter.csj.strategy.PreLoadAdStrategy;
import com.xilu.sdk.adapter.csj.utils.TTAdUtil;
import com.xilu.sdk.adapter.csj.utils.UIUtils;
import com.xilu.sdk.bid.ADXiluBidAdapterCallback;
import com.xilu.sdk.bid.manager.ADXiluBidManager;
import com.xilu.sdk.config.ADXiluInitConfig;
import com.xilu.sdk.util.ADXiluAdUtil;

/**
 * Created by zhangqinglou on 2025/4/21.
 */
public class BannerAdLoader implements ADXiluAdapterLoader<ADXiluBannerAd, ADXiluBannerAdListener>, ADXiluBidManager {
    private TTAdNative.NativeExpressAdListener mNativeAdListener;
    private ADXiluBannerAd mAd;
    private ADXiluAdapterParams mParams;
    private ADXiluBannerAdListener mListener;
    private PreLoadAdStrategy mPreLoadStrategy;
    /** 请求尺寸转发器：把"请求时告诉平台的框"交回装载链 → ADXiluBannerAd → 桥接层 */
    private final ADXiluAdapterSizeReporter mSizeReporter = new ADXiluAdapterSizeReporter();

    @Override
    public void loadAd(ADXiluBannerAd bannerAd, ADXiluAdapterParams params, ADXiluBannerAdListener adListener) {
        this.mSizeReporter.bind(bannerAd);
        load(bannerAd, params, adListener);
    }

    /**
     * 装载链注册横幅尺寸的接收方（ADXiluBannerAd）。
     * 横幅回传的是**请求时告诉平台的框**（见 reportRequestBox），不是量渲染视图的结果。
     */
    public void setSizeListener(ADXiluAdapterSizeListener listener) {
        this.mSizeReporter.bind(listener);
    }

    /** 上报请求框尺寸（px）：后台配置，未配置退屏宽×340dp */
    /** App 指定的尺寸（可为 null），优先级高于后台配置与兜底 */
    private static com.xilu.sdk.ad.entity.ADXiluAdSize adSizeOf(ADXiluBannerAd ad) {
        if (ad == null || ad.getLocalExtraParams() == null) {
            return null;
        }
        return ad.getLocalExtraParams().getAdSize();
    }
    private void reportRequestBox(ADXiluBannerAd bannerAd, ADXiluAdapterParams params) {
        if (bannerAd == null || params == null) {
            return;
        }
        int screenWidthPx = UIUtils.getScreenWidthInPx(bannerAd.getContext());
        int fallbackHeightPx = UIUtils.dp2px(bannerAd.getContext(), 340);
        int[] box = params.computeAdSizePx(adSizeOf(bannerAd), screenWidthPx, fallbackHeightPx);
        this.mSizeReporter.report(box[0], box[1]);
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
        if (this.mNativeAdListener instanceof BannerAdListener) {
            ((BannerAdListener) this.mNativeAdListener).release();
        } else if (this.mNativeAdListener instanceof BidBannerAdListener) {
            ((BidBannerAdListener) this.mNativeAdListener).release();
        }
        this.mNativeAdListener = null;
    }

    private void load(ADXiluBannerAd bannerAd, ADXiluAdapterParams params, ADXiluBannerAdListener adListener) {
        if (ADXiluAdUtil.isReleased(bannerAd) || bannerAd.getContainer() == null || params == null || params.getPlatformPosInfo() == null || adListener == null) {
            return;
        }
        if (this.mPreLoadStrategy != null && this.mNativeAdListener instanceof BannerAdListener) {
            ((BannerAdListener) this.mNativeAdListener).onAdReceive();
            return;
        }
        IBasePlatformPosInfo platformPosInfo = params.getPlatformPosInfo();
        ADXiluInitConfig config = ADXiluSdk.getInstance().getConfig();
        if (config != null && config.isFilterThirdQuestion() && TTAdUtil.unavailableSysVer()) {
            adListener.onAdFailed(ADXiluError.createError(ADXiluIniter.PLATFORM, platformPosInfo.getPlatformPosId(), -1, "过滤Banner广告，经过测试头条的模板广告在安卓5.1及以下版本的手机上可能存在兼容性问题"));
            return;
        }
        TTAdNative adNative = TTAdInitManager.getInstance().getAdNative(bannerAd.getActivity());
        if (adNative == null) {
            adListener.onAdFailed(ADXiluError.createError(ADXiluIniter.PLATFORM, platformPosInfo.getPlatformPosId(), -1, "头条SDK createNative失败，可能初始化失败或初始化数据有误"));
            return;
        }
        ADXiluAdSize adSize;
        if (platformPosInfo.getAdSize() == null) {
            adSize = new ADXiluAdSize(UIUtils.getScreenWidthInPx(bannerAd.getContext()), UIUtils.dp2px(bannerAd.getContext(), 340));
        } else {
            adSize = platformPosInfo.getAdSize();
        }
        float ratio = (float) adSize.getWidth() / (float) adSize.getHeight();
        // 这是"请求时告诉平台的框"，先回传（桥接层拿它当槽位高度上限）
        reportRequestBox(bannerAd, params);
        int width = UIUtils.px2dip(bannerAd.getContext(), bannerAd.getContainer().getWidth());
        MediationAdSlot.Builder mediationBuilder = new MediationAdSlot.Builder();
        mediationBuilder.setExtraObject("show_adn_load_error_detail", true);
        final AdSlot adSlot = new AdSlot.Builder()
                .setCodeId(platformPosInfo.getPlatformPosId())
                .setImageAcceptedSize(adSize.getWidth(), adSize.getHeight())// 单位px
                .setAdCount(1) // 请求广告数量为1到3条 （优先采用平台配置的数量）

                //[start支持模板样式]:需要支持模板广告和原生广告样式的切换，需要调用supportRenderControl和setExpressViewAcceptedSize
                .supportRenderControl() //支持模板样式
                //.setExpressViewAcceptedSize(300,150)
                .setExpressViewAcceptedSize(width, width/ratio)//设置模板宽高（dp）
                //[end支持模板样式]

                .setMediationAdSlot(mediationBuilder.build())
                .build();
        BannerAdListener bannerListener = new BannerAdListener(bannerAd, params.getPosId(), platformPosInfo.getPlatformPosId(), adListener);
        bannerListener.setIsBidType(platformPosInfo.isBidType());
        this.mNativeAdListener = bannerListener;
        adNative.loadBannerExpressAd(adSlot, this.mNativeAdListener);
    }

    @Override
    public void init(IBasePlatformPosInfo platformPosInfo, String adType, com.xilu.sdk.bid.ADXiluBidParams bidParams) {
        if (bidParams != null) {
            if (bidParams.getAdapterParams() != null) {
                this.mParams = bidParams.getAdapterParams();
            }
            if (bidParams.getXiluAd() instanceof ADXiluBannerAd) {
                this.mAd = (ADXiluBannerAd) bidParams.getXiluAd();
            }
            if (bidParams.getListener() instanceof ADXiluBannerAdListener) {
                this.mListener = (ADXiluBannerAdListener) bidParams.getListener();
            }
        }
    }

    @Override
    public void bid(com.xilu.sdk.bid.ADXiluBidAdapterCallback callback) {
        IBasePlatformPosInfo platformPosInfo = null;
        String posId = null;
        if (this.mParams != null) {
            platformPosInfo = this.mParams.getPlatformPosInfo();
            posId = this.mParams.getPosId();
        }
        this.mPreLoadStrategy = new BidPreLoadAdStrategy(callback, platformPosInfo, posId, com.xilu.sdk.ad.data.ADXiluAdType.TYPE_BANNER);
        // 竞价模式下，只发起竞价请求获取ecpm，不加载展示广告
        // 等竞价胜出后，再由d()→loadAd()来加载展示
        loadBidOnly(this.mAd, this.mParams, this.mListener, this.mPreLoadStrategy);
    }

    /**
     * 竞价模式专用加载方法：只发起竞价请求获取ecpm，不触发广告展示
     */
    private void loadBidOnly(ADXiluBannerAd bannerAd, ADXiluAdapterParams params, ADXiluBannerAdListener adListener, PreLoadAdStrategy bidStrategy) {
        if (ADXiluAdUtil.isReleased(bannerAd) || params == null || params.getPlatformPosInfo() == null) {
            if (bidStrategy != null) {
                bidStrategy.onError(new AdError(-1, "参数错误"));
            }
            return;
        }
        IBasePlatformPosInfo platformPosInfo = params.getPlatformPosInfo();
        ADXiluInitConfig config = ADXiluSdk.getInstance().getConfig();
        if (config != null && config.isFilterThirdQuestion() && TTAdUtil.unavailableSysVer()) {
            if (bidStrategy != null) {
                bidStrategy.onError(new AdError(-1, "过滤Banner广告"));
            }
            return;
        }
        TTAdNative adNative = TTAdInitManager.getInstance().getAdNative(bannerAd.getActivity());
        if (adNative == null) {
            if (bidStrategy != null) {
                bidStrategy.onError(new AdError(-1, "头条SDK createNative失败"));
            }
            return;
        }
        ADXiluAdSize adSize;
        if (platformPosInfo.getAdSize() == null) {
            adSize = new ADXiluAdSize(UIUtils.getScreenWidthInPx(bannerAd.getContext()), UIUtils.dp2px(bannerAd.getContext(), 340));
        } else {
            adSize = platformPosInfo.getAdSize();
        }
        float ratio = (float) adSize.getWidth() / (float) adSize.getHeight();
        // 竞价分支同样要上报：竞价胜出后走的就是这个适配器，桥接层只会收到胜出之后的那个框
        reportRequestBox(bannerAd, params);
        int width = UIUtils.px2dip(bannerAd.getContext(), bannerAd.getContainer() != null ? bannerAd.getContainer().getWidth() : UIUtils.getScreenWidthInPx(bannerAd.getContext()));
        MediationAdSlot.Builder mediationBuilder = new MediationAdSlot.Builder();
        mediationBuilder.setExtraObject("show_adn_load_error_detail", true);
        final AdSlot adSlot = new AdSlot.Builder()
                .setCodeId(platformPosInfo.getPlatformPosId())
                .setImageAcceptedSize(adSize.getWidth(), adSize.getHeight())
                .setAdCount(1)
                .supportRenderControl()
                .setExpressViewAcceptedSize(width, width/ratio)
                .setMediationAdSlot(mediationBuilder.build())
                .build();
        // 使用BidBannerAdListener，竞价完成后不触发展示
        BidBannerAdListener bidListener = new BidBannerAdListener(bannerAd, params.getPosId(), platformPosInfo.getPlatformPosId(), adListener, bidStrategy);
        bidListener.setIsBidType(platformPosInfo.isBidType());
        this.mNativeAdListener = bidListener;
        adNative.loadBannerExpressAd(adSlot, this.mNativeAdListener);
    }
}
