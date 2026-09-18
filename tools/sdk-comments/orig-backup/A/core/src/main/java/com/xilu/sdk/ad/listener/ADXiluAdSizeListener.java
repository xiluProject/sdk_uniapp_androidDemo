package com.xilu.sdk.ad.listener;

/** 广告素材尺寸回调：平台渲染完成后回传实际宽高（px） */
public interface ADXiluAdSizeListener {

    /**
     * @param widthPx  平台渲染后的实际宽度（px）
     * @param heightPx 平台渲染后的实际高度（px）；<=0 表示平台没给出有效高度
     */
    void onAdSize(int widthPx, int heightPx);
}
