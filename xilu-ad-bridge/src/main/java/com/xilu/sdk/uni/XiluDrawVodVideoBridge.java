package com.xilu.sdk.uni;

import com.xilu.sdk.ad.data.ADXiluDrawVodAdInfo;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluDrawVodVideoListener;

/**
 * Draw 视频信息流视频回调桥
 */
public abstract class XiluDrawVodVideoBridge implements ADXiluDrawVodVideoListener {

    protected abstract void emit(String event);

    @Override
    public void onVideoLoad(ADXiluDrawVodAdInfo adInfo) {
        emit("onVideoLoad");
    }

    @Override
    public void onVideoStart(ADXiluDrawVodAdInfo adInfo) {
        emit("onVideoStart");
    }

    @Override
    public void onVideoPause(ADXiluDrawVodAdInfo adInfo) {
        emit("onVideoPause");
    }

    @Override
    public void onVideoComplete(ADXiluDrawVodAdInfo adInfo) {
        emit("onVideoComplete");
    }

    @Override
    public void onVideoError(ADXiluDrawVodAdInfo adInfo, ADXiluError error) {
        emit("onVideoError");
    }
}
