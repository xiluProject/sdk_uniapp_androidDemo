package com.xilu.sdk.ad.listener;

/** 平台素材尺寸回传口子：适配器在渲染完成回调里把素材实际宽高（px）交给装载链 */
public interface ADXiluAdapterSizeListener {

    /**
     * @param widthPx  平台渲染后的实际宽度（px）
     * @param heightPx 平台渲染后的实际高度（px）；<=0 表示这次回调没给出有效尺寸
     */
    void onAdSize(int widthPx, int heightPx);
}
