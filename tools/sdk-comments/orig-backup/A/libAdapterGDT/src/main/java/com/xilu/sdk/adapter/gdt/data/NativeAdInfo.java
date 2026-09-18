package com.xilu.sdk.adapter.gdt.data;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.qq.e.ads.cfg.VideoOption;
import com.qq.e.ads.nativ.NativeADEventListener;
import com.qq.e.ads.nativ.NativeADMediaListener;
import com.qq.e.ads.nativ.NativeUnifiedADAppMiitInfo;
import com.qq.e.ads.nativ.NativeUnifiedADData;
import com.qq.e.ads.nativ.widget.NativeAdContainer;
import com.qq.e.comm.util.AdError;
import com.xilu.sdk.ad.adapter.ADXiluAdapterSizeReporter;
import com.xilu.sdk.adapter.gdt.manager.DownloadConfirmHelper;
import com.xilu.sdk.adapter.gdt.manager.MediaViewCacheManager;
import com.xilu.sdk.adapter.gdt.utils.VideoOptionUtil;
import com.xilu.sdk.adapter.gdt.widget.CustomizeMediaView;
import com.xilu.sdk.ADXiluSdk;
import com.xilu.sdk.ad.ADXiluNativeAd;
import com.xilu.sdk.ad.data.ADXiluAdAppInfo;
import com.xilu.sdk.ad.data.ADXiluNativeFeedAdInfo;
import com.xilu.sdk.ad.entity.ADXiluActionType;
import com.xilu.sdk.ad.entity.ADXiluAppInfo;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluNativeAdListener;
import com.xilu.sdk.ad.listener.ADXiluNativeVideoListener;
import com.xilu.sdk.util.ADXiluToastUtil;
import com.xilu.sdk.util.ADXiluViewUtil;

import java.util.Arrays;
import java.util.List;

/**
 * Created by zhangqinglou on 2025/4/27.
 */
public class NativeAdInfo  extends BaseAdInfo<ADXiluNativeAdListener, NativeUnifiedADData> implements ADXiluNativeFeedAdInfo, NativeADEventListener, NativeADMediaListener, ADXiluAdAppInfo {
    private ADXiluNativeAd mAd;
    private boolean isMute;
    private CustomizeMediaView mCustomizeMediaView;
    private ADXiluNativeVideoListener mVideoListener;
    private VideoOption mVideoOption;
    private boolean isExposeReported = false;
    private ViewGroup registeredViewGroup;
    private ViewTreeObserver.OnGlobalLayoutListener mLayoutListener;
    /** 平台渲染尺寸转发器（装载链未接通时为 null）：自渲染广告在曝光/布局完成后量卡片真实尺寸 */
    private final ADXiluAdapterSizeReporter mSizeReporter;
    private boolean isSizeReported = false;

    public NativeAdInfo(boolean mute, String platformPosId, ADXiluNativeAd ad) {
        this(mute, platformPosId, ad, null);
    }

    public NativeAdInfo(boolean mute, String platformPosId, ADXiluNativeAd ad, ADXiluAdapterSizeReporter sizeReporter) {
        super(platformPosId);
        this.isMute = mute;
        this.mAd = ad;
        this.mSizeReporter = sizeReporter;
    }

    private String b() {
        if (getAdapterAdInfo() == null) {
            return null;
        }
        if (3 == getAdapterAdInfo().getAdPatternType()) {
            List imgList = getAdapterAdInfo().getImgList();
            if (imgList == null || imgList.size() <= 0) {
                return null;
            }
            return (String) imgList.get(0);
        }
        return getAdapterAdInfo().getImgUrl();
    }

    public String getTitle() {
        if (getAdapterAdInfo() == null) {
            return null;
        }
        return getAdapterAdInfo().getTitle();
    }

    public String getDesc() {
        if (getAdapterAdInfo() == null) {
            return null;
        }
        return getAdapterAdInfo().getDesc();
    }

    public int getActionType() {
        return -1;
    }

    public String getCtaText() {
        return ADXiluActionType.getActionTex(getActionType(), getAdapterAdInfo() == null ? null : ((NativeUnifiedADData) getAdapterAdInfo()).getCTAText());
    }

    public String getIconUrl() {
        if (getAdapterAdInfo() == null) {
            return null;
        }
        return getAdapterAdInfo().getIconUrl();
    }

    public String getImageUrl() {
        return b();
    }

    public List<String> getImageUrlList() {
        if (getAdapterAdInfo() == null) {
            return null;
        }
        return  getAdapterAdInfo().getImgList();
    }

    public boolean hasMediaView() {
        return isVideo();
    }

    public boolean isNativeExpress() {
        return false;
    }

    public boolean isVideo() {
        return getAdapterAdInfo() != null && 2 == getAdapterAdInfo().getAdPatternType();
    }

    public View getMediaView(@NonNull ViewGroup viewGroup) {
        ADXiluViewUtil.releaseClickTouchListener(viewGroup, new View[0]);
        if (!isVideo()) {
            return null;
        }
        if (this.mVideoOption == null) {
            this.mVideoOption = VideoOptionUtil.createVideoOption(this.isMute);
        }
        this.mCustomizeMediaView = MediaViewCacheManager.a().a(viewGroup, this.mAd);
        return this.mCustomizeMediaView;
    }

    public void setVideoListener(ADXiluNativeVideoListener videoListener) {
        if (isVideo()) {
            this.mVideoListener = videoListener;
        }
    }

    public void registerViewForInteraction(@NonNull ViewGroup viewGroup, View... viewArr) {
        ADXiluViewUtil.releaseClickTouchListener(viewGroup, viewArr);
        this.registeredViewGroup = viewGroup;
        if (viewGroup == null || getAdapterAdInfo() == null) {
            return;
        }
        if (viewGroup instanceof NativeAdContainer) {
            getAdapterAdInfo().setNativeAdEventListener(this);
            List list = null;
            if (viewArr != null && viewArr.length > 0) {
                list = Arrays.asList(viewArr);
            }
            getAdapterAdInfo().bindAdToView(viewGroup.getContext(), (NativeAdContainer) viewGroup, new FrameLayout.LayoutParams(0, 0), list);
            CustomizeMediaView customizeMediaView = this.mCustomizeMediaView;
            if (customizeMediaView != null && customizeMediaView.getMediaView() != null && isVideo()) {
                getAdapterAdInfo().bindMediaView(this.mCustomizeMediaView.getMediaView(), this.mVideoOption, this);
            }
            getAdapterAdInfo().setVideoMute(this.isMute);
            return;
        }
        // Debug模式下可打印日志，但不弹出Toast
    }

    public void onADExposed() {
        reportCardSizeOnce();
        reportExposeWithViewCheck();
    }

    /** 自渲染信息流的平台尺寸 = 注册交互的卡片渲染完成后的真实宽高（px），只报一次 */
    private void reportCardSizeOnce() {
        if (this.isSizeReported || this.mSizeReporter == null || this.registeredViewGroup == null) {
            return;
        }
        ViewGroup viewGroup = this.registeredViewGroup;
        int widthPx = viewGroup.getWidth() > 0 ? viewGroup.getWidth() : viewGroup.getMeasuredWidth();
        int heightPx = viewGroup.getHeight() > 0 ? viewGroup.getHeight() : viewGroup.getMeasuredHeight();
        if (this.mSizeReporter.report(widthPx, heightPx)) {
            this.isSizeReported = true;
        }
    }

    private void reportExposeWithViewCheck() {
        if (isExposeReported) {
            return;
        }
        if (registeredViewGroup == null) {
            isExposeReported = true;
            if (getAdListener() != null) {
                getAdListener().onAdExpose(this);
            }
            return;
        }
        if (registeredViewGroup.getWidth() > 0 && registeredViewGroup.getHeight() > 0 && registeredViewGroup.isShown()) {
            isExposeReported = true;
            if (getAdListener() != null) {
                getAdListener().onAdExpose(this);
            }
            return;
        }
        final ViewTreeObserver.OnGlobalLayoutListener layoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (isExposeReported) {
                    if (registeredViewGroup != null) {
                        ViewTreeObserver vto = registeredViewGroup.getViewTreeObserver();
                        if (vto != null && vto.isAlive()) {
                            vto.removeOnGlobalLayoutListener(this);
                        }
                    }
                    return;
                }
                if (registeredViewGroup != null && registeredViewGroup.getWidth() > 0 && registeredViewGroup.getHeight() > 0 && registeredViewGroup.isShown()) {
                    isExposeReported = true;
                    ViewTreeObserver vto = registeredViewGroup.getViewTreeObserver();
                    if (vto != null && vto.isAlive()) {
                        vto.removeOnGlobalLayoutListener(this);
                    }
                    if (getAdListener() != null) {
                        getAdListener().onAdExpose(NativeAdInfo.this);
                    }
                }
            }
        };
        mLayoutListener = layoutListener;
        registeredViewGroup.getViewTreeObserver().addOnGlobalLayoutListener(mLayoutListener);
    }

    public void onADClicked() {
        if (getAdListener() != null) {
            getAdListener().onAdClick(this);
        }
    }

    public void onADError(AdError adError) {
    }

    public void onADStatusChanged() {
    }

    public void onVideoInit() {
    }

    public void onVideoLoading() {
    }

    public void onVideoReady() {
    }

    public void onVideoLoaded(int i) {
        if (this.mVideoListener != null) {
            this.mVideoListener.onVideoLoad(this);
        }
    }

    public void onVideoStart() {
        if (this.mVideoListener != null) {
            this.mVideoListener.onVideoStart(this);
        }
    }

    public void onVideoPause() {
        if (this.mVideoListener != null) {
            this.mVideoListener.onVideoPause(this);
        }
    }

    public void onVideoResume() {
    }

    public void onVideoCompleted() {
        if (this.mVideoListener != null) {
            this.mVideoListener.onVideoComplete(this);
        }
    }

    public void onVideoError(AdError adError) {
        if (this.mVideoListener != null) {
            this.mVideoListener.onVideoError(this, new ADXiluError(adError.getErrorCode(), adError.getErrorMsg()));
        }
    }

    public void onVideoStop() {
    }

    public void onVideoClicked() {
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
        if (registeredViewGroup != null && mLayoutListener != null) {
            ViewTreeObserver viewTreeObserver = registeredViewGroup.getViewTreeObserver();
            if (viewTreeObserver != null && viewTreeObserver.isAlive()) {
                viewTreeObserver.removeOnGlobalLayoutListener(mLayoutListener);
            }
        }
        mLayoutListener = null;
        registeredViewGroup = null;
        this.mAd = null;
        this.mCustomizeMediaView = null;
        this.mVideoListener = null;
        this.mVideoOption = null;
        if (getAdapterAdInfo() != null) {
            getAdapterAdInfo().destroy();
            setAdapterAdInfo(null);
        }
    }

    /* renamed from: a, reason: merged with bridge method [inline-methods] */
    public void setAdapterAdInfo(NativeUnifiedADData nativeUnifiedADData) {
        super.setAdapterAdInfo(nativeUnifiedADData);
        if (nativeUnifiedADData == null || !DownloadConfirmHelper.a()) {
            return;
        }
        nativeUnifiedADData.setDownloadConfirmListener(DownloadConfirmHelper.b);
    }

    public ADXiluAppInfo getAppInfo() {
        if (getAdapterAdInfo() == null || getAdapterAdInfo().getAppMiitInfo() == null) {
            return null;
        }
        ADXiluAppInfo appInfo = new ADXiluAppInfo();
        NativeUnifiedADAppMiitInfo miitInfo = getAdapterAdInfo().getAppMiitInfo();
        appInfo.setName(miitInfo.getAppName());
        appInfo.setDeveloper(miitInfo.getAuthorName());
        appInfo.setVersion(miitInfo.getVersionName());
        appInfo.setPermissionsUrl(miitInfo.getPermissionsUrl());
        appInfo.setPrivacyUrl(miitInfo.getPrivacyAgreement());
        appInfo.setDescriptionUrl(miitInfo.getDescriptionUrl());
        appInfo.setIcp(miitInfo.getIcpNumber());
        appInfo.setSize(miitInfo.getPackageSizeBytes());
        return appInfo;
    }

    public void onResume() {
        if (getAdapterAdInfo() != null) {
            getAdapterAdInfo().resume();
        }
    }
}
