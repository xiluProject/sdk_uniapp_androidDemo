package com.xilu.sdk.ad.adapter;

import com.xilu.sdk.ad.listener.ADXiluAdapterSizeListener;

/** 适配器装载器用的平台尺寸转发器（px） */
public class ADXiluAdapterSizeReporter {

    /** 本次展示的广告对象（必须实现 ADXiluAdapterSizeListener 才会被接收） */
    private ADXiluAdapterSizeListener mTarget;

    /**
     * 绑定本次展示的广告对象。
     *
     * @param ad 广告对象；不是 ADXiluAdapterSizeListener 实现时等价于解绑
     */
    public void bind(Object ad) {
        this.mTarget = (ad instanceof ADXiluAdapterSizeListener) ? (ADXiluAdapterSizeListener) ad : null;
    }

    /** 解绑（装载器释放时调用，避免持有已释放的广告对象） */
    public void unbind() {
        this.mTarget = null;
    }

    /** 是否已绑定接收方（适配器可据此跳过无意义的尺寸记录） */
    public boolean isBound() {
        return this.mTarget != null;
    }

    /**
     * 上报平台渲染后的素材真实宽高。
     *
     * @param widthPx  宽度（px）；<=0 丢弃
     * @param heightPx 高度（px）；<=0 丢弃
     * @return true 表示已交给接收方；false 表示没绑定或尺寸无效
     */
    public boolean report(int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) {
            return false;
        }
        ADXiluAdapterSizeListener target = this.mTarget;
        if (target == null) {
            return false;
        }
        target.onAdSize(widthPx, heightPx);
        return true;
    }
}
