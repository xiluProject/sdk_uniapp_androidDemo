package com.xilu.sdk.adapter.gdt.data;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;

import com.qq.e.ads.nativ.NativeExpressADView;
import com.qq.e.ads.nativ.NativeExpressMediaListener;
import com.qq.e.comm.util.AdError;
import com.xilu.sdk.ad.adapter.ADXiluAdapterSizeReporter;
import com.xilu.sdk.adapter.gdt.manager.DownloadConfirmHelper;
import com.xilu.sdk.ad.data.ADXiluNativeExpressAdInfo;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluNativeAdListener;
import com.xilu.sdk.ad.listener.ADXiluNativeVideoListener;
import com.xilu.sdk.ad.widget.ADXiluInterceptContainer;
import com.xilu.sdk.util.ADXiluViewUtil;

/**
 * Created by zhangqinglou on 2025/4/27.
 */
public class NativeExpressAdInfo extends BaseAdInfo<ADXiluNativeAdListener, NativeExpressADView> implements ADXiluNativeExpressAdInfo, NativeExpressMediaListener {
    private String mPosId;
    private ADXiluNativeVideoListener n;
    private ADXiluInterceptContainer o;
    private boolean isExposeReported = false;
    private ViewTreeObserver.OnGlobalLayoutListener mLayoutListener;
    /** 平台渲染尺寸转发器：优量汇在 onRenderSuccess 里只给 View，宽高要量它（装载链未接通时为 null） */
    private ADXiluAdapterSizeReporter sizeReporter;

    public NativeExpressAdInfo(String posId, String platformPosId) {
        this(posId, platformPosId, null);
    }

    public NativeExpressAdInfo(String posId, String platformPosId, ADXiluAdapterSizeReporter sizeReporter) {
        super(platformPosId);
        this.mPosId = posId;
        this.sizeReporter = sizeReporter;
    }

    public boolean isNativeExpress() {
        return true;
    }

    public boolean isVideo() {
        return (getAdapterAdInfo() == null || ((NativeExpressADView) getAdapterAdInfo()).getBoundData() == null || ((NativeExpressADView) getAdapterAdInfo()).getBoundData().getAdPatternType() != 2) ? false : true;
    }

    public void setVideoListener(ADXiluNativeVideoListener videoListener) {
        if (isVideo()) {
            this.n = videoListener;
            ((NativeExpressADView) getAdapterAdInfo()).setMediaListener(this);
        }
    }

    public View getNativeExpressAdView(@NonNull ViewGroup viewGroup) {
        ADXiluViewUtil.releaseClickTouchListener(viewGroup, new View[0]);
        if (this.o == null && getAdapterAdInfo() != null) {
            this.o = new ADXiluInterceptContainer(((NativeExpressADView) getAdapterAdInfo()).getContext());
            this.o.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
            this.o.setPosId(this.mPosId);
            this.o.addResponseClickView((View) getAdapterAdInfo());
        }
        return this.o;
    }

    public void render(@NonNull ViewGroup viewGroup) {
        ADXiluViewUtil.releaseClickTouchListener(viewGroup, new View[0]);
        if (getAdapterAdInfo() != null) {
            ((NativeExpressADView) getAdapterAdInfo()).render();
        }
    }

    /** 把渲染完成后的模板视图尺寸（px）交回装载链；视图未布局时等布局完成再量 */
    public void reportRenderSize(View view) {
        if (this.sizeReporter == null || view == null) {
            return;
        }
        if (reportSizeIfMeasured(view)) {
            return;
        }
        final View target = view;
        target.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (reportSizeIfMeasured(target)) {
                    ViewTreeObserver vto = target.getViewTreeObserver();
                    if (vto != null && vto.isAlive()) {
                        vto.removeOnGlobalLayoutListener(this);
                    }
                }
            }
        });
    }

    /** 视图已量出有效宽高就上报（px）；否则返回 false 交给布局回调重试 */
    private boolean reportSizeIfMeasured(View view) {
        ADXiluAdapterSizeReporter reporter = this.sizeReporter;
        if (reporter == null || view == null) {
            return false;
        }
        int widthPx = view.getWidth() > 0 ? view.getWidth() : view.getMeasuredWidth();
        int heightPx = view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight();
        return reporter.report(widthPx, heightPx);
    }

    public void onVideoInit(NativeExpressADView nativeExpressADView) {
    }

    public void onVideoLoading(NativeExpressADView nativeExpressADView) {
    }

    public void onVideoCached(NativeExpressADView nativeExpressADView) {
    }

    public void onVideoReady(NativeExpressADView nativeExpressADView, long j) {
        ADXiluNativeVideoListener videoListener = this.n;
        if (videoListener != null) {
            videoListener.onVideoLoad(this);
        }
    }

    public void onVideoStart(NativeExpressADView nativeExpressADView) {
        ADXiluNativeVideoListener videoListener = this.n;
        if (videoListener != null) {
            videoListener.onVideoStart(this);
        }
    }

    public void onVideoPause(NativeExpressADView nativeExpressADView) {
        ADXiluNativeVideoListener videoListener = this.n;
        if (videoListener != null) {
            videoListener.onVideoPause(this);
        }
    }

    public void onVideoComplete(NativeExpressADView nativeExpressADView) {
        ADXiluNativeVideoListener videoListener = this.n;
        if (videoListener != null) {
            videoListener.onVideoComplete(this);
        }
    }

    public void onVideoError(NativeExpressADView nativeExpressADView, AdError adError) {
        ADXiluNativeVideoListener videoListener = this.n;
        if (videoListener != null) {
            videoListener.onVideoError(this, new ADXiluError(adError.getErrorCode(), adError.getErrorMsg()));
        }
    }

    public void onVideoPageOpen(NativeExpressADView nativeExpressADView) {
    }

    public void onVideoPageClose(NativeExpressADView nativeExpressADView) {
    }

    @Override
    public void onCloseClick(View view) {
        if (getAdListener() != null) {
            getAdListener().onAdClose(this);
        }
    }

    @Override
    public void releaseAdapter() {
        super.releaseAdapter();
        this.n = null;
        this.sizeReporter = null;
        // 清理ViewTreeObserver监听器，防止内存泄漏
        if (this.o != null && mLayoutListener != null) {
            ViewTreeObserver vto = this.o.getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                vto.removeOnGlobalLayoutListener(mLayoutListener);
            }
            ADXiluViewUtil.removeSelfFromParent(new View[]{this.o});
            this.o = null;
        }
        mLayoutListener = null;
        if (getAdapterAdInfo() != null) {
            ((NativeExpressADView) getAdapterAdInfo()).destroy();
            setAdapterAdInfo((NativeExpressADView) null);
        }
    }

    public void setAdapterAdInfo(NativeExpressADView nativeExpressADView) {
        super.setAdapterAdInfo(nativeExpressADView);
        if (nativeExpressADView == null || !DownloadConfirmHelper.a()) {
            return;
        }
        nativeExpressADView.setDownloadConfirmListener(DownloadConfirmHelper.b);
    }

    /**
     * 处理广告曝光上报，使用ViewTreeObserver监听View的实际显示状态
     */
    public void handleAdExposure() {
        if (o == null || isExposeReported) {
            return;
        }

        // 先立即检查View是否已经可见
        if (o.getWidth() > 0 && o.getHeight() > 0 && o.isShown()) {
            isExposeReported = true;
            if (getAdListener() != null) {
                getAdListener().onAdExpose(NativeExpressAdInfo.this);
            }
            return;
        }

        // 如果还没显示，添加监听器等待
        mLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                // 如果已经上报，移除监听器并返回
                if (isExposeReported) {
                    ViewTreeObserver vto = o.getViewTreeObserver();
                    if (vto != null && vto.isAlive()) {
                        vto.removeOnGlobalLayoutListener(this);
                    }
                    return;
                }
                if (o.getWidth() > 0 && o.getHeight() > 0 && o.isShown()) {
                    isExposeReported = true;
                    ViewTreeObserver vto = o.getViewTreeObserver();
                    if (vto != null && vto.isAlive()) {
                        vto.removeOnGlobalLayoutListener(this);
                    }
                    if (getAdListener() != null) {
                        getAdListener().onAdExpose(NativeExpressAdInfo.this);
                    }
                }
            }
        };
        o.getViewTreeObserver().addOnGlobalLayoutListener(mLayoutListener);
    }
}
