package com.xilu.sdk.adapter.bz.data;

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;

import com.beizi.fusion.NativeAd;
import com.xilu.sdk.ad.adapter.ADXiluAdapterSizeReporter;
import com.xilu.sdk.ad.data.ADXiluNativeExpressAdInfo;
import com.xilu.sdk.ad.listener.ADXiluNativeAdListener;
import com.xilu.sdk.ad.listener.ADXiluNativeVideoListener;
import com.xilu.sdk.ad.listener.ADXiluSingleClickListener;
import com.xilu.sdk.ad.widget.ADXiluInterceptContainer;
import com.xilu.sdk.adapter.bz.R;
import com.xilu.sdk.util.ADXiluDisplayUtil;
import com.xilu.sdk.util.ADXiluViewUtil;

/**
 * Created by zhangqinglou on 2025/10/7.
 */
public class NativeExpressAdInfo extends BaseAdInfo<ADXiluNativeAdListener, NativeAd> implements ADXiluNativeExpressAdInfo {
    private ADXiluInterceptContainer container;
    private View mAdView;
    private String posId;
    private boolean isExposeReported = false;
    private ViewTreeObserver.OnGlobalLayoutListener mLayoutListener;
    /** 平台渲染尺寸转发器（装载链未接通时为 null） */
    private final ADXiluAdapterSizeReporter mSizeReporter;
    private boolean isSizeReported = false;

    public NativeExpressAdInfo(String posId, String platformPosId, View adView) {
        this(posId, platformPosId, adView, null);
    }

    public NativeExpressAdInfo(String posId, String platformPosId, View adView, ADXiluAdapterSizeReporter sizeReporter) {
        super(platformPosId);
        this.posId = posId;
        this.mAdView = adView;
        this.mSizeReporter = sizeReporter;
    }

    @Override
    public View getNativeExpressAdView(@NonNull ViewGroup viewGroup) {
//        ADXiluViewUtil.releaseClickTouchListener(viewGroup, new View[0]);
        if (this.container == null && getAdapterAdInfo() != null && viewGroup != null) {
            FrameLayout frameLayout = new FrameLayout(viewGroup.getContext());
            frameLayout.setLayoutParams(new FrameLayout.LayoutParams(-1, -2));
            if (mAdView != null) {
                mAdView.setBackgroundColor(Color.RED);
                // 修复：如果mAdView没有LayoutParams，手动设置合适的LayoutParams
                if (mAdView.getLayoutParams() == null) {
                    mAdView.setLayoutParams(new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT));
                }
                this.container = new ADXiluInterceptContainer(mAdView.getContext());
                this.container.setBackgroundColor(Color.BLUE);
                this.container.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                this.container.setPosId(this.posId);
                frameLayout.addView(mAdView);
                this.container.addResponseClickView(frameLayout);
            }
        }
        return this.container;

    }

    @Override
    public void render(@NonNull ViewGroup viewGroup) {
        getNativeExpressAdView(viewGroup);
        if (getAdapterAdInfo() != null) {
            getAdapterAdInfo().resume();
        }
    }

    /** 上报倍孜信息流渲染完成后的真实尺寸（px），只报一次 */
    public void reportRenderSizeOnce() {
        if (this.isSizeReported || this.mSizeReporter == null) {
            return;
        }
        int widthPx = 0;
        int heightPx = 0;
        if (this.mAdView != null) {
            widthPx = this.mAdView.getWidth() > 0 ? this.mAdView.getWidth() : this.mAdView.getMeasuredWidth();
            heightPx = this.mAdView.getHeight() > 0 ? this.mAdView.getHeight() : this.mAdView.getMeasuredHeight();
        }
        View view = this.container;
        if (view != null) {
            int viewWidth = view.getWidth() > 0 ? view.getWidth() : view.getMeasuredWidth();
            int viewHeight = view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight();
            if (viewWidth > widthPx) {
                widthPx = viewWidth;
            }
            if (viewHeight > heightPx) {
                heightPx = viewHeight;
            }
        }
        if (this.mSizeReporter.report(widthPx, heightPx)) {
            this.isSizeReported = true;
        }
    }

    @Override
    public boolean isNativeExpress() {
        return true;
    }

    @Override
    public boolean isVideo() {
        return false;
    }

    @Override
    public void setVideoListener(ADXiluNativeVideoListener videoListener) {

    }

    /**
     * 处理广告曝光上报，使用ViewTreeObserver监听View的实际显示状态
     */
    public void handleAdExposure() {
        reportRenderSizeOnce();
        if (container == null || isExposeReported) {
            return;
        }

        // 先立即检查View是否已经可见
        if (container.getWidth() > 0 && container.getHeight() > 0 && container.isShown()) {
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
                    ViewTreeObserver vto = container.getViewTreeObserver();
                    if (vto != null && vto.isAlive()) {
                        vto.removeOnGlobalLayoutListener(this);
                    }
                    return;
                }
                if (container.getWidth() > 0 && container.getHeight() > 0 && container.isShown()) {
                    isExposeReported = true;
                    ViewTreeObserver vto = container.getViewTreeObserver();
                    if (vto != null && vto.isAlive()) {
                        vto.removeOnGlobalLayoutListener(this);
                    }
                    if (getAdListener() != null) {
                        getAdListener().onAdExpose(NativeExpressAdInfo.this);
                    }
                }
            }
        };
        container.getViewTreeObserver().addOnGlobalLayoutListener(mLayoutListener);
    }

    @Override
    public void releaseAdapter() {
        super.releaseAdapter();
        // 清理ViewTreeObserver监听器，防止内存泄漏
        if (this.container != null && mLayoutListener != null) {
            ViewTreeObserver vto = this.container.getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                vto.removeOnGlobalLayoutListener(mLayoutListener);
            }
            ADXiluViewUtil.removeSelfFromParent(new View[]{this.container});
            this.container = null;
        }
        mLayoutListener = null;
    }
}
