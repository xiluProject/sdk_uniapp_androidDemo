package com.xilu.sdk.ad;

import android.app.Activity;
import androidx.fragment.app.Fragment;

import androidx.annotation.NonNull;

import com.xilu.sdk.ad.data.ADXiluAdType;
import com.xilu.sdk.ad.entity.ADXiluExtraParams;
import com.xilu.sdk.ad.listener.ADXiluAdapterSizeListener;
import com.xilu.sdk.ad.listener.ADXiluAdSizeListener;
import com.xilu.sdk.ad.listener.ADXiluNativeAdListener;
import com.xilu.sdk.ad.scene.SceneAd;
import com.xilu.sdk.core.base.BaseXiluAd;

/**
 * Created by zhangqinglou on 2025/4/18.
 */
public class ADXiluNativeAd extends BaseXiluAd<ADXiluNativeAdListener> implements SceneAd, ADXiluAdapterSizeListener {
    private ADXiluExtraParams m;
    private String n;

    /** 平台渲染完成后回传真实素材宽高的监听（uni 桥接层按它撑开容器高度） */
    private ADXiluAdSizeListener mAdSizeListener;

    public ADXiluNativeAd(@NonNull Activity activity) {
        super(activity);
        setTimeout(10000L);
    }

    @Override
    public String getAdType() {
        return ADXiluAdType.TYPE_FLOW;
    }

    public ADXiluExtraParams getLocalExtraParams() {
        return this.m;
    }

    public void setLocalExtraParams(ADXiluExtraParams extraParams) {
        this.m = extraParams;
    }

    public boolean isMute() {
        return this.m == null || this.m.isNativeAdPlayWithMute();
    }

    @Override
    public String getSceneId() {
        return this.n;
    }

    @Override
    public void setSceneId(String str) {
        this.n = str;
    }

    /**
     * 注册"平台素材尺寸"回调（uni 桥接层用：信息流不能只按请求时的框占位，
     * 必须按平台渲染后的真实高度撑开）。
     */
    public void setAdSizeListener(ADXiluAdSizeListener listener) {
        this.mAdSizeListener = listener;
    }

    /** 装载链回传平台渲染后的素材真实宽高（px） */
    @Override
    public void onAdSize(int widthPx, int heightPx) {
        // 装载链（BaseAdLoadLooper 已把本对象注册为接收方）把平台渲染尺寸送到这里，
        // 再转给桥接层注册的 ADXiluAdSizeListener —— 桥接层据此撑开信息流容器高度。
        ADXiluAdSizeListener l = this.mAdSizeListener;
        if (l != null && widthPx > 0 && heightPx > 0) {
            l.onAdSize(widthPx, heightPx);
        }
    }

    /**
     * @deprecated 语义与 {@link #onAdSize(int, int)} 相同；保留只是为了兼容早前按
     * "notifyAdSize" 命名接入的调用方，新代码请直接用 {@link #onAdSize(int, int)}。
     */
    @Deprecated
    public void notifyAdSize(int widthPx, int heightPx) {
        onAdSize(widthPx, heightPx);
    }

    public ADXiluNativeAd(@NonNull Fragment fragment) {
        super(fragment);
        setTimeout(10000L);
    }
}
