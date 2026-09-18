package com.xilu.sdk.uni;

import com.xilu.sdk.ad.data.ADXiluNativeAdInfo;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluNativeVideoListener;

// 信息流视频回调桥
public abstract class XiluNativeVideoBridge implements ADXiluNativeVideoListener {

    /** 子类只需把事件名抛给 JS */
    protected abstract void emit(String event);

    @Override
    public void onVideoLoad(ADXiluNativeAdInfo adInfo) {
        emit("onVideoLoad");
    }

    @Override
    public void onVideoStart(ADXiluNativeAdInfo adInfo) {
        emit("onVideoStart");
    }

    @Override
    public void onVideoPause(ADXiluNativeAdInfo adInfo) {
        emit("onVideoPause");
    }

    @Override
    public void onVideoComplete(ADXiluNativeAdInfo adInfo) {
        emit("onVideoComplete");
    }

    @Override
    public void onVideoError(ADXiluNativeAdInfo adInfo, ADXiluError error) {
        emit("onVideoError");
    }
}
