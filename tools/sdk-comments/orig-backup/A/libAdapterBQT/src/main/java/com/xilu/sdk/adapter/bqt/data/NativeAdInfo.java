package com.xilu.sdk.adapter.bqt.data;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.baidu.mobads.sdk.api.NativeResponse;
import com.baidu.mobads.sdk.api.XNativeView;
import com.xilu.sdk.ad.adapter.ADXiluAdapterSizeReporter;
import com.xilu.sdk.ad.data.ADXiluAdAppInfo;
import com.xilu.sdk.ad.data.ADXiluNativeFeedAdInfo;
import com.xilu.sdk.ad.entity.ADXiluActionType;
import com.xilu.sdk.ad.entity.ADXiluAppInfo;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.expose.ADXiluExposeChecker;
import com.xilu.sdk.ad.expose.ADXiluExposeListener;
import com.xilu.sdk.ad.listener.ADXiluNativeAdListener;
import com.xilu.sdk.ad.listener.ADXiluNativeVideoListener;
import com.xilu.sdk.util.ADXiluViewUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by zhangqinglou on 2025/4/28.
 */
public class NativeAdInfo extends BaseAdInfo<ADXiluNativeAdListener, NativeResponse> implements ADXiluNativeFeedAdInfo, NativeResponse.AdInteractionListener, ADXiluExposeListener, ADXiluAdAppInfo {
    private boolean isMute;
    private XNativeView mAdView;
    private Handler mHandler;
    private ADXiluExposeChecker mExposeChecker;
    private boolean isExpose;
    private boolean adExposed;
    /** 注册交互的那张卡片：自渲染信息流的真实高度就是它渲染完成后的高度 */
    private ViewGroup registeredViewGroup;
    /** 平台渲染尺寸转发器（装载链未接通时为 null） */
    private final ADXiluAdapterSizeReporter mSizeReporter;
    private boolean isSizeReported = false;

    public NativeAdInfo(String platformPosId, boolean isMute) {
        this(platformPosId, isMute, null);
    }

    public NativeAdInfo(String platformPosId, boolean isMute, ADXiluAdapterSizeReporter sizeReporter) {
        super(platformPosId);
        this.mHandler = new Handler(Looper.getMainLooper());
        this.isMute = isMute;
        this.mExposeChecker = new ADXiluExposeChecker(true, true, this);
        this.mSizeReporter = sizeReporter;
    }

    private boolean a(NativeResponse nativeResponse) {
        return (TextUtils.isEmpty(nativeResponse.getAppVersion()) || TextUtils.isEmpty(nativeResponse.getPublisher()) || TextUtils.isEmpty(nativeResponse.getAppPrivacyLink()) || TextUtils.isEmpty(nativeResponse.getAppPermissionLink())) ? false : true;
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
        return (getAdapterAdInfo() == null || !a(getAdapterAdInfo())) ? -1 : 2;
    }

    public String getCtaText() {
        return ADXiluActionType.getActionText(getActionType());
    }

    public String getIconUrl() {
        if (getAdapterAdInfo() == null) {
            return null;
        }
        return getAdapterAdInfo().getIconUrl();
    }

    public String getImageUrl() {
        if (getAdapterAdInfo() == null) {
            return null;
        }
        return getAdapterAdInfo().getImageUrl();
    }

    public List<String> getImageUrlList() {
        if (getAdapterAdInfo() == null) {
            return null;
        }
        return getAdapterAdInfo().getMultiPicUrls();
    }

    public boolean hasMediaView() {
        return isVideo();
    }

    public boolean isNativeExpress() {
        return false;
    }

    public boolean isVideo() {
        return getAdapterAdInfo() != null && NativeResponse.MaterialType.VIDEO == ((NativeResponse) getAdapterAdInfo()).getMaterialType();
    }

    public View getMediaView(@NonNull ViewGroup viewGroup) {
        ADXiluViewUtil.releaseClickTouchListener(viewGroup, new View[0]);
        if (this.mAdView == null && isVideo()) {
            this.mAdView = new XNativeView(viewGroup.getContext());
            this.mAdView.setVideoMute(this.isMute);
            this.mAdView.setNativeViewClickListener(new XNativeView.INativeViewClickListener() {
                @Override
                public void onNativeViewClick(XNativeView xNativeView) {
                    onAdClick();
                }
            });
        }
        return this.mAdView;
    }

    public void setVideoListener(ADXiluNativeVideoListener videoListener) {
    }

    public void registerViewForInteraction(@NonNull ViewGroup viewGroup, View... viewArr) {
        ADXiluViewUtil.releaseClickTouchListener(viewGroup, viewArr);
        if (getAdapterAdInfo() == null || viewGroup == null) {
            return;
        }
        ArrayList arrayList = new ArrayList();
        if (viewArr != null) {
            arrayList.addAll(Arrays.asList(viewArr));
        }
        getAdapterAdInfo().registerViewForInteraction(viewGroup, arrayList,  null, this);
        this.registeredViewGroup = viewGroup;
        if (this.mAdView != null) {
            this.mAdView.setNativeItem(getAdapterAdInfo());
            this.mAdView.render();
        }
        if (this.mExposeChecker != null) {
            this.mExposeChecker.startExposeCheck(viewGroup);
        }
    }

    public void onAdClick() {
        if (this.mHandler != null) {
            this.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (getAdListener() != null) {
                        getAdListener().onAdClick(NativeAdInfo.this);
                    }
                }
            });
        }
    }

    public void onADExposed() {
        this.adExposed = true;
        onAdExpose();
    }

    public void onADExposureFailed(int code) {
        if (this.mHandler != null) {
            this.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (getAdListener() != null) {
                        getAdListener().onRenderFailed(NativeAdInfo.this, new ADXiluError(code, "信息流广告渲染失败"));
                    }
                }
            });
        }
    }

    public void onADStatusChanged() {
    }

    public void onAdUnionClick() {
        if (this.mHandler != null) {
            this.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (getAdListener() != null) {
                        getAdListener().onAdClick(NativeAdInfo.this);
                    }
                }
            });
        }
    }

    @Override
    public void adActReward(int i) {
    }

    @Override
    public void adActRewardSuccess() {
    }

    @Override
    public void onCloseClick(View view) {
        if (this.mHandler != null) {
            this.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (getAdListener() != null) {
                        getAdListener().onAdClose(NativeAdInfo.this);
                    }
                }
            });
        }
    }

    @Override
    public void releaseAdapter() {
        super.releaseAdapter();
        if (this.mAdView != null) {
            ADXiluViewUtil.removeSelfFromParent(new View[]{this.mAdView});
            this.mAdView.stop();
            this.mAdView = null;
        }
        if (this.mHandler != null) {
            this.mHandler.removeCallbacksAndMessages(null);
            this.mHandler = null;
        }
        if (this.mExposeChecker != null) {
            this.mExposeChecker.releaseExposeCheck();
            this.mExposeChecker = null;
        }
    }

    public void onExpose() {
        this.isExpose = true;
        onAdExpose();
    }

    public void onAdExpose() {
        reportCardSizeOnce();
        if (this.isExpose && this.adExposed && this.mHandler != null) {
            this.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (getAdListener() != null) {
                        getAdListener().onAdExpose(NativeAdInfo.this);
                    }
                }
            });
        }
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

    public ADXiluAppInfo getAppInfo() {
        if (getAdapterAdInfo() == null || getAdapterAdInfo().getAdActionType() != 1) {
            return null;
        }
        ADXiluAppInfo appInfo = new ADXiluAppInfo();
        appInfo.setName(getAdapterAdInfo().getBrandName());
        appInfo.setDeveloper(getAdapterAdInfo().getPublisher());
        appInfo.setVersion(getAdapterAdInfo().getAppVersion());
        appInfo.setPermissionsUrl(getAdapterAdInfo().getAppPermissionLink());
        appInfo.setPrivacyUrl(getAdapterAdInfo().getAppPrivacyLink());
        appInfo.setDescriptionUrl(getAdapterAdInfo().getAppFunctionLink());
        appInfo.setSize(getAdapterAdInfo().getAppSize());
        return appInfo;
    }
}
