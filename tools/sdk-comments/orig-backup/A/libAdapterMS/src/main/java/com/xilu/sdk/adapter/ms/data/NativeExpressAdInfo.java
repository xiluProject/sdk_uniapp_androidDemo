package com.xilu.sdk.adapter.ms.data;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.meishu.sdk.core.ad.recycler.ExpressMediaListener;
import com.meishu.sdk.core.ad.recycler.RecyclerAdData;
import com.meishu.sdk.core.ad.recycler.RecylcerAdInteractionListener;
import com.meishu.sdk.core.utils.MsAdPatternType;
import com.xilu.sdk.ad.adapter.ADXiluAdapterSizeReporter;
import com.xilu.sdk.ad.data.ADXiluNativeExpressAdInfo;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluNativeAdListener;
import com.xilu.sdk.ad.listener.ADXiluNativeVideoListener;
import com.xilu.sdk.ad.widget.ADXiluInterceptContainer;
import com.xilu.sdk.util.ADXiluViewUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by zhangqinglou on 2025/9/23.
 */
public class NativeExpressAdInfo extends BaseAdInfo<ADXiluNativeAdListener, RecyclerAdData> implements ADXiluNativeExpressAdInfo, ExpressMediaListener {
    private String mPosId;
    private boolean isMute;
    private ADXiluInterceptContainer container;
    private boolean isExposeReported = false;
    private ViewTreeObserver.OnGlobalLayoutListener mLayoutListener;
    /** 平台渲染尺寸转发器（装载链未接通时为 null） */
    private final ADXiluAdapterSizeReporter mSizeReporter;
    private boolean isSizeReported = false;

    public NativeExpressAdInfo(String platformPosId, String posId, boolean isMute) {
        this(platformPosId, posId, isMute, null);
    }

    public NativeExpressAdInfo(String platformPosId, String posId, boolean isMute, ADXiluAdapterSizeReporter sizeReporter) {
        super(platformPosId);
        this.mPosId = posId;
        this.isMute = isMute;
        this.mSizeReporter = sizeReporter;
    }

    @Override
    public void setAdapterAdInfo(RecyclerAdData recyclerAdData) {
        super.setAdapterAdInfo(recyclerAdData);
        if (isVideo()) {
            recyclerAdData.setExpressMediaListener(this);
        }
    }

    @Override
    public View getNativeExpressAdView(@NonNull ViewGroup viewGroup) {
        ADXiluViewUtil.releaseClickTouchListener(viewGroup, new View[0]);
        if (this.container == null && getAdapterAdInfo() != null && viewGroup != null) {
            FrameLayout frameLayout = new FrameLayout(viewGroup.getContext());
            frameLayout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View feedView = null;
            if (getAdapterAdInfo().isNativeExpress()) {
                feedView = getAdapterAdInfo().getAdView();
            }else {
                feedView = createAdView();
            }
            if (feedView != null) {
                // 修复：如果feedView没有LayoutParams，手动设置合适的LayoutParams
                if (feedView.getLayoutParams() == null) {
                    feedView.setLayoutParams(new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT));
                }
                this.container = new ADXiluInterceptContainer(feedView.getContext());
                this.container.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                this.container.setPosId(this.mPosId);
                frameLayout.addView(feedView);
                this.container.addResponseClickView(frameLayout);
            }
        }
        return this.container;
    }

    private View createAdView(){
        return null;
    }

    @Override
    public void render(@NonNull ViewGroup viewGroup) {
        getNativeExpressAdView(viewGroup);
        if (getAdapterAdInfo() != null) {
            List<View> clickableViews = new ArrayList<>();
            clickableViews.add(this.container);
            getAdapterAdInfo().bindAdToView(viewGroup.getContext(), viewGroup, clickableViews, new RecylcerAdInteractionListener() {
                @Override
                public void onAdClosed() {
                    if (getAdListener() != null) {
                        getAdListener().onAdClose(NativeExpressAdInfo.this);
                    }
                }

                @Override
                public void onAdRenderFailed() {
                    if (getAdListener() != null) {
                        getAdListener().onRenderFailed(NativeExpressAdInfo.this, new ADXiluError(-1002, "render failed"));
                    }
                }

                @Override
                public void onAdExposure() {
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
                public void onAdClicked() {
                    if (getAdListener() != null) {
                        getAdListener().onAdClick(NativeExpressAdInfo.this);
                    }
                }
            });
        }
    }

    @Override
    public boolean isNativeExpress() {
        if (getAdapterAdInfo() != null) {
            return getAdapterAdInfo().isNativeExpress();
        }
        return false;
    }

    /** 上报美数信息流渲染完成后的真实尺寸（px）：平台素材宽高优先，其次槽位，只报一次 */
    private void reportRenderSizeOnce() {
        if (this.isSizeReported || this.mSizeReporter == null) {
            return;
        }
        int widthPx = 0;
        int heightPx = 0;
        if (getAdapterAdInfo() != null) {
            widthPx = getAdapterAdInfo().getWidth();
            heightPx = getAdapterAdInfo().getHeight();
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
    public boolean isVideo() {
        if (getAdapterAdInfo() != null && getAdapterAdInfo().getAdPatternType() == MsAdPatternType.VIDEO) {
            return true;
        }
        return false;
    }

    @Override
    public void setVideoListener(ADXiluNativeVideoListener videoListener) {

    }


    @Override
    public void onVideoLoaded() {

    }

    @Override
    public void onVideoStart() {

    }

    @Override
    public void onVideoPause() {

    }

    @Override
    public void onVideoCompleted() {

    }

    @Override
    public void onVideoError(int i, String s) {

    }

    @Override
    public void onVideoResume() {

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
