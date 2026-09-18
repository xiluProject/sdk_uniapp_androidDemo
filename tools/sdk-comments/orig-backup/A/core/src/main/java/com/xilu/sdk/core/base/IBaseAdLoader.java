package com.xilu.sdk.core.base;

import com.xilu.sdk.ad.listener.ADXiluAdapterSizeListener;

/**
 * Created by zhangqinglou on 2025/4/15.
 */
public interface IBaseAdLoader<T extends IBaseXiluAd> {
    void loadAd(String posId, int count);

    void onResumed();

    void onPaused();

    void release();

    /** 注册平台渲染尺寸的接收方（装载链在 loadAd 前调用）；传 null 解绑 */
    void setSizeListener(ADXiluAdapterSizeListener listener);
}
