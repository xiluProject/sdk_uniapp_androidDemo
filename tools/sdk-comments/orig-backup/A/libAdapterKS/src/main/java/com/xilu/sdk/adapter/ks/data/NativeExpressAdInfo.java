package com.xilu.sdk.adapter.ks.data;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.kwad.sdk.api.KsFeedAd;
import com.xilu.sdk.ad.adapter.ADXiluAdapterSizeReporter;
import com.xilu.sdk.ad.data.ADXiluNativeExpressAdInfo;
import com.xilu.sdk.ad.listener.ADXiluNativeAdListener;
import com.xilu.sdk.ad.listener.ADXiluNativeVideoListener;
import com.xilu.sdk.ad.widget.ADXiluInterceptContainer;
import com.xilu.sdk.util.ADXiluViewUtil;

/**
 * Created by zhangqinglou on 2025/4/28.
 */
public class NativeExpressAdInfo extends BaseAdInfo<ADXiluNativeAdListener, KsFeedAd> implements ADXiluNativeExpressAdInfo {
    private String posId;
    private boolean soundEnable;
    private ADXiluInterceptContainer container;
    private boolean isExposeReported = false;
    private ViewTreeObserver.OnGlobalLayoutListener mLayoutListener;
    /** 平台渲染尺寸转发器：快手通过 KsFeedAd.render(AdRenderListener) 回调渲染成功的 View */
    private final ADXiluAdapterSizeReporter mSizeReporter;
    private boolean isSizeReported = false;

    public NativeExpressAdInfo(String posId, String platformPosId, boolean soundEnable) {
        this(posId, platformPosId, soundEnable, null);
    }

    public NativeExpressAdInfo(String posId, String platformPosId, boolean soundEnable, ADXiluAdapterSizeReporter sizeReporter) {
        super(platformPosId);
        this.posId = posId;
        this.soundEnable = soundEnable;
        this.mSizeReporter = sizeReporter;
    }

    public boolean isNativeExpress() {
        return true;
    }

    public boolean isVideo() {
        return false;
    }

    public void setVideoListener(ADXiluNativeVideoListener videoListener) {
    }

    public View getNativeExpressAdView(@NonNull ViewGroup viewGroup) {
        ADXiluViewUtil.releaseClickTouchListener(viewGroup, new View[0]);
        if (this.container == null && getAdapterAdInfo() != null && viewGroup != null) {
            FrameLayout frameLayout = new FrameLayout(viewGroup.getContext());
            frameLayout.setLayoutParams(new FrameLayout.LayoutParams(-1, -2));
            View feedView = ((KsFeedAd) getAdapterAdInfo()).getFeedView(viewGroup.getContext());
            if (feedView != null) {
                // 修复：如果feedView没有LayoutParams，手动设置合适的LayoutParams
                if (feedView.getLayoutParams() == null) {
                    feedView.setLayoutParams(new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT));
                }
                this.container = new ADXiluInterceptContainer(feedView.getContext());
                this.container.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
                this.container.setPosId(this.posId);
                frameLayout.addView(feedView);
                this.container.addResponseClickView(frameLayout);
            }
        }
        return this.container;
    }

    public void render(@NonNull ViewGroup viewGroup) {
        getNativeExpressAdView(viewGroup);
        if (getAdapterAdInfo() != null) {
            getAdapterAdInfo().setVideoSoundEnable(!this.soundEnable);
            getAdapterAdInfo().setAdInteractionListener(new KsFeedAd.AdInteractionListener() {
                public void onAdClicked() {
                    if (getAdListener() != null) {
                        getAdListener().onAdClick(NativeExpressAdInfo.this);
                    }
                }

                public void onAdShow() {
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

                public void onDislikeClicked() {
                    if (getAdListener() != null) {
                        getAdListener().onAdClose(NativeExpressAdInfo.this);
                    }
                }

                public void onDownloadTipsDialogShow() {
                }

                public void onDownloadTipsDialogDismiss() {
                }
            });
            // 关键：KsFeedAd.render(AdRenderListener) 才会回调 onAdRenderSuccess(View)，
            // 那才是"平台渲染完成"的时机，尺寸也只能从那里取（之前 adapter 从未调用它）。
            getAdapterAdInfo().render(new KsFeedAd.AdRenderListener() {
                @Override
                public void onAdRenderSuccess(View view) {
                    reportRenderSize(view != null ? view : container);
                }

                @Override
                public void onAdRenderFailed(int code, String message) {
                    if (getAdListener() != null) {
                        getAdListener().onRenderFailed(NativeExpressAdInfo.this,
                                new com.xilu.sdk.ad.error.ADXiluError(code, message));
                    }
                }
            });
        }
    }

    /** 上报渲染完成后的素材真实尺寸（px）；量不到不上报 */
    private void reportRenderSize(View view) {
        if (this.isSizeReported || this.mSizeReporter == null) {
            return;
        }
        if (reportSizeIfMeasured(view)) {
            this.isSizeReported = true;
            return;
        }
        View fallback = this.container;
        if (fallback != null && fallback != view) {
            final View target = fallback;
            target.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    if (isSizeReported) {
                        ViewTreeObserver vto = target.getViewTreeObserver();
                        if (vto != null && vto.isAlive()) {
                            vto.removeOnGlobalLayoutListener(this);
                        }
                        return;
                    }
                    if (reportSizeIfMeasured(target)) {
                        isSizeReported = true;
                        ViewTreeObserver vto = target.getViewTreeObserver();
                        if (vto != null && vto.isAlive()) {
                            vto.removeOnGlobalLayoutListener(this);
                        }
                    }
                }
            });
        }
    }

    private boolean reportSizeIfMeasured(View view) {
        if (this.mSizeReporter == null || view == null) {
            return false;
        }
        int widthPx = view.getWidth() > 0 ? view.getWidth() : view.getMeasuredWidth();
        int heightPx = view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight();
        return this.mSizeReporter.report(widthPx, heightPx);
    }

    @Override
    public void releaseAdapter() {
        super.releaseAdapter();
        setAdapterAdInfo(null);
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

    @Override
    public void onCloseClick(View view) {
        if (getAdListener() != null) {
            getAdListener().onAdClose(this);
        }
    }

}
