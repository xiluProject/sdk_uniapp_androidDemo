package com.xilu.sdk.adapter.ms.loader;

import android.util.Log;
import android.widget.Toast;

import com.meishu.sdk.core.ad.MsAdSlot;
import com.meishu.sdk.core.ad.banner.BannerAdEventListener;
import com.meishu.sdk.core.ad.banner.BannerAdLoader;
import com.meishu.sdk.core.ad.banner.IBannerAd;
import com.meishu.sdk.core.utils.AdErrorInfo;
import com.xilu.sdk.ad.ADXiluBannerAd;
import com.xilu.sdk.ad.data.ADXiluAdType;
import com.xilu.sdk.ad.adapter.ADXiluAdapterLoader;
import com.xilu.sdk.ad.adapter.ADXiluAdapterParams;
import com.xilu.sdk.ad.adapter.ADXiluAdapterSizeReporter;
import com.xilu.sdk.ad.data.IBasePlatformPosInfo;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluAdapterSizeListener;
import com.xilu.sdk.ad.listener.ADXiluBannerAdListener;
import com.xilu.sdk.adapter.ms.listener.BannerAdListener;
import com.xilu.sdk.adapter.ms.strategy.BidPreLoadAdStrategy;
import com.xilu.sdk.adapter.ms.strategy.PreLoadAdStrategy;
import com.xilu.sdk.adapter.ms.utils.AdPositionUtil;
import com.xilu.sdk.bid.ADXiluBidAdapterCallback;
import com.xilu.sdk.bid.ADXiluBidParams;
import com.xilu.sdk.bid.manager.ADXiluBidManager;
import com.xilu.sdk.util.ADXiluAdUtil;

/**
 * Created by zhangqinglou on 2025/9/17.
 */
public class MsBannerAdLoader implements ADXiluAdapterLoader<ADXiluBannerAd, ADXiluBannerAdListener>, ADXiluBidManager {
    private ADXiluBannerAd mAd;
    private ADXiluAdapterParams mAdapterParams;
    private ADXiluBannerAdListener mAdListener;
    private BannerAdListener mNativeListener;
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

    /** 上报请求框尺寸（px）：后台配置，未配置退屏宽×340dp（不透传给 MS） */
    private void reportRequestBox() {
        if (this.mAd == null || this.mAdapterParams == null) {
            return;
        }
        int screenWidthPx = this.mAd.getContext().getResources().getDisplayMetrics().widthPixels;
        int fallbackHeightPx = Math.round(340f * this.mAd.getContext().getResources().getDisplayMetrics().density);
        int[] box = this.mAdapterParams.computeAdSizePx(this.mAd == null ? null : this.mAd.getLocalExtraParams() == null ? null : this.mAd.getLocalExtraParams().getAdSize(), screenWidthPx, fallbackHeightPx);
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

    private void load() {
        innerLoad();
    }

    private void innerLoad() {
        if (ADXiluAdUtil.isReleased(this.mAd) || this.mAdapterParams == null || this.mAdapterParams.getPlatformPosInfo() == null || this.mAdListener == null) {
            return;
        }
        if (this.mPreLoadAdStrategy != null && this.mNativeListener != null) {
            this.mNativeListener.onAdReceive();
            return;
        }

        String posId = this.mAdapterParams.getPosId();
        String platformPosId = this.mAdapterParams.getPlatformPosInfo().getPlatformPosId();
        // 上报这次请求的框（桥接层拿它当槽位高度上限；不透传给 MS，理由见 reportRequestBox）
        reportRequestBox();
        this.mNativeListener = new BannerAdListener(posId, platformPosId, this.mAdListener, this.mPreLoadAdStrategy);
        this.mNativeListener.setIsBidType(this.mAdapterParams.getPlatformPosInfo().isBidType());

        // 不再向MS SDK透传adSize宽高：MS的setWidthAndHeight会把宽高当px硬赋给图片LayoutParams，
        // 传adSize(600,300)会导致图片仅占半屏；MS默认布局为match_parent+adjustViewBounds，
        // 不设置时自动铺满容器宽度、高度按图片比例自适应
        MsAdSlot msAdSlot = new MsAdSlot.Builder()
                .setPid(AdPositionUtil.parsePlatformPosId(platformPosId))
                .build();
        BannerAdLoader bannerLoader = new BannerAdLoader(this.mAd.getActivity(), msAdSlot, mNativeListener);
        bannerLoader.loadAd();
        bannerLoader.destroy();
    }
}
