package com.xilu.sdk.adapter.gdt.loader;

import android.view.View;

import com.qq.e.ads.banner2.UnifiedBannerView;
import com.xilu.sdk.adapter.gdt.listener.BannerAdListener;
import com.xilu.sdk.adapter.gdt.strategy.BidPreLoadAdStrategy;
import com.xilu.sdk.adapter.gdt.strategy.PreLoadAdStrategy;
import com.xilu.sdk.adapter.gdt.utils.AdPositionUtil;
import com.xilu.sdk.ad.ADXiluBannerAd;
import com.xilu.sdk.ad.adapter.ADXiluAdapterLoader;
import com.xilu.sdk.ad.adapter.ADXiluAdapterParams;
import com.xilu.sdk.ad.adapter.ADXiluAdapterSizeReporter;
import com.xilu.sdk.ad.data.IBasePlatformPosInfo;
import com.xilu.sdk.ad.entity.ADXiluAdSize;
import com.xilu.sdk.ad.listener.ADXiluAdapterSizeListener;
import com.xilu.sdk.ad.entity.ADXiluExtraParams;
import com.xilu.sdk.ad.listener.ADXiluBannerAdListener;
import com.xilu.sdk.adapter.gdt.utils.UIUtil;
import com.xilu.sdk.bid.ADXiluBidAdapterCallback;
import com.xilu.sdk.bid.ADXiluBidParams;
import com.xilu.sdk.bid.manager.ADXiluBidManager;
import com.xilu.sdk.util.ADXiluAdUtil;
import com.xilu.sdk.util.ADXiluViewUtil;

/**
 * Created by zhangqinglou on 2025/4/27.
 */
public class BannerAdLoader implements ADXiluAdapterLoader<ADXiluBannerAd, ADXiluBannerAdListener>, ADXiluBidManager {
    private ADXiluBannerAd mAd;
    private ADXiluAdapterParams mAdapterParams;
    private ADXiluBannerAdListener mAdListener;
    private BannerAdListener mNativeListener;
    private UnifiedBannerView mBannerView;
    private PreLoadAdStrategy mPreLoadAdStrategy;
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
    public void loadAd(ADXiluBannerAd ad, ADXiluAdapterParams adapterParams, ADXiluBannerAdListener adListener) {
        this.mAd = ad;
        this.mAdapterParams = adapterParams;
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

    /** 上报请求框尺寸（px）：桥接层当槽位高度上限，必须与发给平台的框一致 */
    private void reportRequestBox(ADXiluAdSize adSize) {
        if (adSize == null || this.mAd == null) {
            return;
        }
        this.mSizeReporter.report(adSize.getWidth(), adSize.getHeight());
    }

    /** App 通过 setLocalExtraParams(adSize) 指定的尺寸（可为 null） */
    private ADXiluAdSize adSizeOf() {
        if (this.mAd == null || this.mAd.getLocalExtraParams() == null) {
            return null;
        }
        return this.mAd.getLocalExtraParams().getAdSize();
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
        if (this.mBannerView != null) {
            ADXiluViewUtil.removeSelfFromParent(new View[]{this.mBannerView});
            this.mBannerView.destroy();
            this.mBannerView = null;
        }
        if (this.mNativeListener != null) {
            this.mNativeListener.release();
            this.mNativeListener = null;
        }
        this.mAd = null;
        this.mAdapterParams = null;
        this.mAdListener = null;
    }

    @Override
    public void bid(ADXiluBidAdapterCallback callback) {
        IBasePlatformPosInfo platformPosInfo = null;
        String posId = null;
        if (this.mAdapterParams != null) {
            platformPosInfo = this.mAdapterParams.getPlatformPosInfo();
            posId = this.mAdapterParams.getPosId();
        }
        this.mPreLoadAdStrategy = new BidPreLoadAdStrategy(callback, platformPosInfo, posId);
        load();
    }

    private void load() {
        if (ADXiluAdUtil.isReleased(this.mAd) || this.mAd.getContainer() == null || this.mAdapterParams == null || this.mAdapterParams.getPlatformPosInfo() == null || this.mAdListener == null) {
            return;
        }
        innerLoad();
    }

    private void innerLoad() {
        if (this.mPreLoadAdStrategy != null && this.mNativeListener != null) {
            this.mNativeListener.onAdReceiveCallback();
            return;
        }
        IBasePlatformPosInfo platformPosInfo = this.mAdapterParams.getPlatformPosInfo();

        // 请求框优先级：App 指定(localExtraParams.adSize) > 后台广告位配置 > 屏宽×340dp 兜底
        ADXiluAdSize appSize = adSizeOf();
        ADXiluAdSize adSize;
        if (appSize != null) {
            adSize = appSize;
        } else if (platformPosInfo.getAdSize() == null) {
            adSize = new ADXiluAdSize(UIUtil.getScreenWidth(this.mAd.getContext()), UIUtil.dp2px(this.mAd.getContext(), 340));
        } else {
            adSize = platformPosInfo.getAdSize();
        }
        float ratio = (float) adSize.getWidth() / (float) adSize.getHeight();
        // 这是"请求时告诉平台的框"，先回传（桥接层拿它当槽位高度上限）
        reportRequestBox(adSize);
        // container.getWidth()已是px，禁止再dp2px二次转换（会把1080px放大成约2835px，banner超宽被裁剪、点击区域错位）
        // 容器尚未布局完成时（getWidth()==0）用屏幕宽度兜底
        int width = this.mAd.getContainer().getWidth();
        if (width <= 0) {
            width = UIUtil.getScreenWidth(this.mAd.getContext());
        }
        String posId = this.mAdapterParams.getPosId();
        String platformPosId = platformPosInfo.getPlatformPosId();
        this.mNativeListener = new BannerAdListener(posId, platformPosId, this.mAdListener, this.mPreLoadAdStrategy);
        this.mNativeListener.setIsBidType(platformPosInfo.isBidType());
        this.mNativeListener.setSize(width, (int) (width/ratio));
        this.mBannerView = new UnifiedBannerView(this.mAd.getActivity(), AdPositionUtil.parsePlatformPosId(platformPosInfo.getPlatformPosId()), this.mNativeListener);
        this.mBannerView.setRefresh(0);
        this.mNativeListener.setBannerView(this.mBannerView);
        this.mBannerView.loadAD();
    }



}
