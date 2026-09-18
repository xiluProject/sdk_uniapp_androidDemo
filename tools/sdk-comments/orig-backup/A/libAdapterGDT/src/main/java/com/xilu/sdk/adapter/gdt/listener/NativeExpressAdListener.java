package com.xilu.sdk.adapter.gdt.listener;

import com.qq.e.ads.nativ.NativeExpressAD;
import com.qq.e.ads.nativ.NativeExpressADView;
import com.qq.e.comm.util.AdError;
import com.xilu.sdk.ad.adapter.ADXiluAdapterSizeReporter;
import com.xilu.sdk.adapter.gdt.data.NativeExpressAdInfo;
import com.xilu.sdk.adapter.gdt.strategy.PreLoadAdStrategy;
import com.xilu.sdk.ad.data.ADXiluNativeAdInfo;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluNativeAdListener;
import com.xilu.sdk.util.ADXiluAdUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by zhangqinglou on 2025/4/27.
 */
public class NativeExpressAdListener extends BaseAdListener<ADXiluNativeAdListener> implements NativeExpressAD.NativeExpressADListener {
    private String mPosId;
    private List<ADXiluNativeAdInfo> mAdInfoList;
    private PreLoadAdStrategy mPreLoadAdStrategy;
    /** 平台渲染尺寸转发器：优量汇 onRenderSuccess 只给 View，宽高从视图量 */
    private final ADXiluAdapterSizeReporter mSizeReporter;

    public NativeExpressAdListener(String posId, String platformPosId, ADXiluNativeAdListener adListener, PreLoadAdStrategy preLoadAdStrategy) {
        this(posId, platformPosId, adListener, preLoadAdStrategy, null);
    }

    public NativeExpressAdListener(String posId, String platformPosId, ADXiluNativeAdListener adListener, PreLoadAdStrategy preLoadAdStrategy, ADXiluAdapterSizeReporter sizeReporter) {
        super(posId, platformPosId, adListener);
        this.mPosId = posId;
        this.mPreLoadAdStrategy = preLoadAdStrategy;
        this.mSizeReporter = sizeReporter;
    }

    public void onADLoaded(List<NativeExpressADView> list) {
        if (list != null && !list.isEmpty()) {
            if (getAdListener() != null) {
                this.mAdInfoList = new ArrayList();
                for (int i = 0; i < list.size(); i++) {
                    NativeExpressAdInfo nativeExpressAdInfo = new NativeExpressAdInfo(this.mPosId, getPlatformPosId(), this.mSizeReporter);
                    nativeExpressAdInfo.setAdapterAdInfo(list.get(i));
                    nativeExpressAdInfo.setAdListener(getAdListener());
                    nativeExpressAdInfo.setBidEcpm(list.get(i).getECPM());
                    this.mAdInfoList.add(nativeExpressAdInfo);
                }
                if (this.mPreLoadAdStrategy != null) {
                    this.mPreLoadAdStrategy.onSuccess(list.get(0));
                    return;
                } else {
                    onAdReceive();
                    return;
                }
            }
            return;
        }
        if (this.mPreLoadAdStrategy != null) {
            this.mPreLoadAdStrategy.onError(new AdError(-20110, "返回的广告数据为空"), null);
        } else {
            onAdFailed(-20110, "返回的广告数据为空");
        }
    }

    public void onAdReceive() {
        PreLoadAdStrategy preLoadAdStrategy = this.mPreLoadAdStrategy;
        if (preLoadAdStrategy != null) {
            preLoadAdStrategy.release();
            this.mPreLoadAdStrategy = null;
        }
        if (getAdListener() != null) {
            getAdListener().onAdReceive(this.mAdInfoList);
        }
    }

    public void onRenderFail(NativeExpressADView nativeExpressADView) {
        ADXiluNativeAdInfo adInfoWithAdapterAdInfo;
        if (getAdListener() == null || (adInfoWithAdapterAdInfo = ADXiluAdUtil.getAdInfoWithAdapterAdInfo(this.mAdInfoList, nativeExpressADView)) == null) {
            return;
        }
        getAdListener().onRenderFailed(adInfoWithAdapterAdInfo, new ADXiluError(-1, "unknown"));
    }

    /** 模板渲染成功：从渲染出的 View 上量尺寸（px） */
    public void onRenderSuccess(NativeExpressADView nativeExpressADView) {
        if (this.mSizeReporter == null || nativeExpressADView == null || this.mAdInfoList == null) {
            return;
        }
        for (int i = 0; i < this.mAdInfoList.size(); i++) {
            ADXiluNativeAdInfo info = this.mAdInfoList.get(i);
            if (!(info instanceof NativeExpressAdInfo)) {
                continue;
            }
            NativeExpressAdInfo expressInfo = (NativeExpressAdInfo) info;
            if (expressInfo.getAdapterAdInfo() != nativeExpressADView) {
                continue;
            }
            expressInfo.reportRenderSize(nativeExpressADView);
            return;
        }
    }

    public void onADExposure(NativeExpressADView nativeExpressADView) {
        ADXiluNativeAdInfo adInfoWithAdapterAdInfo;
        if (getAdListener() == null || (adInfoWithAdapterAdInfo = ADXiluAdUtil.getAdInfoWithAdapterAdInfo(this.mAdInfoList, nativeExpressADView)) == null) {
            return;
        }
        // 调用NativeExpressAdInfo的handleAdExposure方法处理曝光上报
        if (adInfoWithAdapterAdInfo instanceof com.xilu.sdk.adapter.gdt.data.NativeExpressAdInfo) {
            ((com.xilu.sdk.adapter.gdt.data.NativeExpressAdInfo) adInfoWithAdapterAdInfo).handleAdExposure();
        }
    }

    public void onADClicked(NativeExpressADView nativeExpressADView) {
        ADXiluNativeAdInfo adInfoWithAdapterAdInfo;
        if (getAdListener() == null || (adInfoWithAdapterAdInfo = ADXiluAdUtil.getAdInfoWithAdapterAdInfo(this.mAdInfoList, nativeExpressADView)) == null) {
            return;
        }
        getAdListener().onAdClick(adInfoWithAdapterAdInfo);
    }

    public void onADClosed(NativeExpressADView nativeExpressADView) {
        ADXiluNativeAdInfo adInfoWithAdapterAdInfo;
        if (getAdListener() == null || (adInfoWithAdapterAdInfo = ADXiluAdUtil.getAdInfoWithAdapterAdInfo(this.mAdInfoList, nativeExpressADView)) == null) {
            return;
        }
        getAdListener().onAdClose(adInfoWithAdapterAdInfo);
    }

    public void onADLeftApplication(NativeExpressADView nativeExpressADView) {
    }

    public void onNoAD(AdError adError) {
        PreLoadAdStrategy preLoadAdStrategy = this.mPreLoadAdStrategy;
        if (preLoadAdStrategy != null) {
            preLoadAdStrategy.onError(adError, null);
        } else {
            onAdFailed(adError.getErrorCode(), adError.getErrorMsg());
        }
    }

    public void release() {
        super.release();
        ADXiluAdUtil.releaseList(this.mAdInfoList);
        this.mAdInfoList = null;
    }
}
