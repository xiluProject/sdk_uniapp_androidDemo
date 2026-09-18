package com.xilu.sdk.core.base;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.xilu.sdk.ADXiluSdk;
import com.xilu.sdk.ad.api.ADXiluNetworkRequestInfo;
import com.xilu.sdk.ad.data.ADXiluAdType;
import com.xilu.sdk.ad.data.IBasePosInfo;
import com.xilu.sdk.ad.data.IBasePlatformPosInfo;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.utils.ADXiluLogUtil;
import com.xilu.sdk.config.ADXiluConfig;
import com.xilu.sdk.config.ADXiluErrorConfig;
import com.xilu.sdk.core.api.ErrorReportApi;
import com.xilu.sdk.core.frequency.FrequencyManager;
import com.xilu.sdk.ad.listener.ADXiluAdapterSizeListener;
import com.xilu.sdk.core.listener.LoaderFrequencyListener;
import com.xilu.sdk.core.listener.LoaderInitListener;
import com.xilu.sdk.core.manager.ADSdkManager;
import com.xilu.sdk.core.manager.SPManager;
import com.xilu.sdk.core.model.InitData;
import com.xilu.sdk.core.model.PlatformPosInfo;
import com.xilu.sdk.core.model.PosInfo;
import com.xilu.sdk.util.ADXiluAdUtil;
import com.xilu.sdk.util.ADXiluPackageUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by zhangqinglou on 2025/4/15.
 */
public abstract class BaseAdLoader<T extends IBaseXiluAd, E extends IBaseAdLoadLooper> implements IBaseAdLoader<T> {
    private T mAd;
    private List<LoaderInitListener<E>> mLoaderInitListenerList;
    private List<E> mAdLoadLooperList = new ArrayList<>();
    private List<LoaderFrequencyListener<E>> mLoaderFrequencyListenerList = new ArrayList<>();
    private Handler mHandler = new Handler(Looper.getMainLooper());
    protected E mAdLoadLooper;
    /** 平台渲染尺寸的接收方（装载链注册，适配器回调时沿 loader 回传） */
    private ADXiluAdapterSizeListener mSizeListener;

    public BaseAdLoader(T ad) {
        this.mAd = ad;
    }

    @Override
    public void onResumed() {
        onResume(true);
    }

    @Override
    public void onPaused() {
        onResume(false);
    }

    @Override
    public void release() {
        try {
            this.mAd = null;
            this.mSizeListener = null;
            cleanHandler();
            cleanInitListener();
            cleanFrequencyListener();
            cleanLoadLooper();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void cleanInitListener() {
        ADSdkManager.getInstance().a(this.mLoaderInitListenerList);
        ADXiluAdUtil.releaseList(this.mLoaderInitListenerList);
        this.mLoaderInitListenerList = null;
    }

    private void cleanFrequencyListener() {
        ADXiluAdUtil.releaseList(this.mLoaderFrequencyListenerList);
        this.mLoaderFrequencyListenerList = null;
    }

    private void cleanLoadLooper() {
        ADXiluAdUtil.releaseList(this.mAdLoadLooperList);
        this.mAdLoadLooperList = null;
    }

    private void cleanHandler() {
        Handler handler = this.mHandler;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            this.mHandler = null;
        }
    }

    protected boolean b() {
        return false;
    }

    protected abstract E loadLooper(T t, Handler handler);

    public T getAd() {
        return this.mAd;
    }

    @Override
    public void setSizeListener(ADXiluAdapterSizeListener listener) {
        this.mSizeListener = listener;
    }

    /** 子类/适配器回传平台渲染尺寸（px），转交本次展示的广告对象 */
    protected void notifyAdSize(int widthPx, int heightPx) {
        ADXiluAdapterSizeListener listener = this.mSizeListener;
        if (listener != null) {
            listener.onAdSize(widthPx, heightPx);
        }
    }

    @Override
    public void loadAd(String posId, int count) {
        boolean isInited = ADSdkManager.getInstance().isInitSuccess();
        this.mAdLoadLooper = loadLooper(this.mAd, this.mHandler);
        this.mAdLoadLooperList.add(this.mAdLoadLooper);
        if (isInited) {
            ADSdkManager.getInstance().loadAdapterIniter();
            loadAd(this.mAdLoadLooper, posId, count);
            return;
        }
        MyLoaderInitListener initListener = new MyLoaderInitListener((BaseAdLoader<?, E>) this, this.mAdLoadLooper, posId, count);
        if (this.mLoaderInitListenerList == null) {
            this.mLoaderInitListenerList = new ArrayList<>();
        }
        this.mLoaderInitListenerList.add(initListener);
        ADSdkManager.getInstance().addInitListener(initListener);
    }

    public void loadAd(String posId, int count, ADXiluNetworkRequestInfo requestInfo) {
        boolean p = ADSdkManager.getInstance().isInitSuccess();
        ADXiluLogUtil.d("准备加载广告，初始化是否已完成 : " + p);
        boolean a = SPManager.getInstance().getBooleanByFile("defaultSplashAd", "SP_XILU_DEFAULT_SPLASH_AD_REQUEST");
        E adLoadLooper = loadLooper(this.mAd, this.mHandler);
        if (adLoadLooper != null) {
            this.mAdLoadLooperList.add(adLoadLooper);
        }
        if (p && a) {
            ADXiluLogUtil.d("直接开始加载广告");
            loadAd(adLoadLooper, posId, count);
        } else {
            SPManager.getInstance().putBooleanByFile("defaultSplashAd", "SP_XILU_DEFAULT_SPLASH_AD_REQUEST", true);
            ADXiluLogUtil.d("加载兜底广告...");
            loadAd(adLoadLooper, posId, count, requestInfo);
        }
    }

    private void loadAd(E adLoadLooper, String posId, int count) {
        if (ADXiluAdUtil.isReleased(this.mAd)) {
            return;
        }
        String adType = this.mAd.getAdType();
        if (ADSdkManager.getInstance().isInitDataFailed()) {
            onLoadAdFailed(adType, posId, adLoadLooper, new ADXiluError(ADXiluErrorConfig.AD_FAILED_INIT_REQUEST_IS_FAILED_NEED_PREVENT, ADXiluErrorConfig.MSG_AD_FAILED_INIT_REQUEST_IS_FAILED_NEED_PREVENT + ADSdkManager.getInstance().getInitDataError()));
            return;
        }
        if (TextUtils.isEmpty(posId)) {
            onLoadAdFailed(adType, posId, adLoadLooper, new ADXiluError(ADXiluErrorConfig.AD_FAILED_POS_ID_IS_EMPTY, ADXiluErrorConfig.MSG_AD_FAILED_POS_ID_IS_EMPTY));
            return;
        }
        InitData initData = ADSdkManager.getInstance().getInitData();
        if (initData == null) {
            onLoadAdFailed(adType, posId, adLoadLooper, new ADXiluError(ADXiluErrorConfig.AD_FAILED_INIT_DATA_IS_EMPTY, ADXiluErrorConfig.MSG_AD_FAILED_INIT_DATA_IS_EMPTY));
            return;
        }
        IBasePosInfo xiluPosId = ADSdkManager.getInstance().getXiluPosId(posId);
        if (xiluPosId != null && xiluPosId.getPlatformPosIdList() != null && !xiluPosId.getPlatformPosIdList().isEmpty()) {
            if (adType != null && adType.equals(xiluPosId.getAdType())) {
                if (FrequencyManager.getInstance().a(xiluPosId)) {
                    ADXiluLogUtil.d("开始控频校验...");
                    m mVar = new m((BaseAdLoader<?, E>) this, adLoadLooper, xiluPosId, count);
                    List<LoaderFrequencyListener<E>> list = this.mLoaderFrequencyListenerList;
                    if (list != null) {
                        list.add(mVar);
                    }
                    FrequencyManager.getInstance().a(xiluPosId, mVar);
                    return;
                }
                loadAd(adLoadLooper, xiluPosId, count);
                return;
            }
            onLoadAdFailed(adType, posId, adLoadLooper, new ADXiluError(ADXiluErrorConfig.AD_FAILED_POS_ID_MISMATCH, "该PosId对应的广告类型不匹配, 当前PosId应是 " + xiluPosId.getAdType() + " 广告的PosId"));
            return;
        }
        onLoadAdFailed(adType, posId, adLoadLooper, new ADXiluError(ADXiluErrorConfig.AD_FAILED_AD_FAILED_PLATFORM_POS_IDS_EMPTY, ADXiluErrorConfig.MSG_AD_FAILED_AD_FAILED_PLATFORM_POS_IDS_EMPTY));
    }

    private void loadAd(E loadLooper, String posId, int count, ADXiluNetworkRequestInfo requestInfo) {
        if (ADXiluAdUtil.isReleased(this.mAd)) {
            return;
        }
        String adType = this.mAd.getAdType();
        if (!TextUtils.isEmpty(posId) && requestInfo != null) {
            loadAd(loadLooper, count, requestInfo, posId);
        } else {
            onLoadAdFailed(adType, posId, null, new ADXiluError(ADXiluErrorConfig.AD_FAILED_AD_FAILED_DEFAULT_AD_EMPTY, ADXiluErrorConfig.MSG_AD_FAILED_AD_FAILED_DEFAULT_AD_EMPTY));
        }
    }

    private boolean checkPackageName(String str) {
        if (str == null) {
            return true;
        }
        if (ADXiluConfig.TEST_APP_ID.equals(ADXiluSdk.getInstance().getAppId())) {
            return false;
        }
        Context context = ADXiluSdk.getInstance().getContext();
        if (context == null) {
            return true;
        }
        return !str.equals(ADXiluPackageUtil.getPackageName(context));
    }

    protected void loadAd(E loadLooper, IBasePosInfo posId, int cont) {
        if (ADXiluAdUtil.isReleased(this.mAd) || loadLooper == null || posId == null) {
            return;
        }
        ADXiluLogUtil.d("开始轮循广告...");
        loadLooper.loadAd(posId, cont);
    }

    private void loadAd(E adLoadLooper, int count, ADXiluNetworkRequestInfo requestInfo, String posId) {
        if (ADXiluAdUtil.isReleased(this.mAd) || adLoadLooper == null) {
            return;
        }
        ADXiluLogUtil.d("开始轮循广告...");
        adLoadLooper.loadAd(createXiluPosId(requestInfo, posId), count, requestInfo);
    }

    private IBasePosInfo createXiluPosId(ADXiluNetworkRequestInfo requestInfo, String str) {
        PosInfo posId = new PosInfo(requestInfo.getmNetworkAdPosListID(), 0L, str, 0, 0, requestInfo.getmAdType(), true, 0, 0, 0, 0, 0.0d, 0.0d, ADXiluConfig.RequestMode.SERIAL, 1);
        posId.setHasFrequency(false);
        ArrayList<IBasePlatformPosInfo> arrayList = new ArrayList<>();
        arrayList.add(new PlatformPosInfo(requestInfo.getmNetworkAdPosListID(), requestInfo.getmPlatform(), requestInfo.getAdNetworkSlotId(), 0, requestInfo.getmRenderType(), ADXiluConfig.TemplateType.FLOW, 1, "", "", -1, 1, false, 0, 1, false, 0.0d, ADXiluConfig.EcpmType.ACCURATE, 100, ADXiluAdType.TYPE_SPLASH));
        posId.setPlatformPosIdList(arrayList);
        return posId;
    }

    private void onLoadAdFailed(String str, String str2, E e, ADXiluError error) {
        if (error != null && ADXiluSdk.getInstance().isDebug()) {
            ADXiluLogUtil.d("posid : " + str2 + ", loader failed : " + error.toString());
        }
        if (e != null) {
            e.release();
            List<E> list = this.mAdLoadLooperList;
            if (list != null) {
                list.remove(e);
            }
        }
        if (error != null) {
            ErrorReportApi.reportErrorQuick(ADXiluSdk.getInstance().getContext(),
                    ErrorReportApi.ERROR_TYPE_LOADING,
                    error.getCode(),
                    error.getError(),
                    ErrorReportApi.PLATFORM_XILU,
                    str2,
                    "",
                    null);
        }
        if (ADXiluAdUtil.canCallBack(this.mAd)) {
            if (error != null) {
                error.setAdType(str);
                error.setPosId(str2);
            }
            this.mAd.getListener().onAdFailed(error);
        }
    }

    private void onResume(boolean z) {
        List<E> list;
        if (!b() || (list = this.mAdLoadLooperList) == null || list.size() <= 0) {
            return;
        }
        int i = 0;
        while (true) {
            try {
                if (i >= this.mAdLoadLooperList.size()) {
                    return;
                }
                E e = this.mAdLoadLooperList.get(i);
                e.onResume(z);
                i++;
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }
        }
    }

    public class MyLoaderInitListener extends LoaderInitListener<E> {
        final  BaseAdLoader<?, E> adLoader;

        public MyLoaderInitListener(BaseAdLoader<?, E> adLoader, E loadLooper, String posId, int count) {
            super(loadLooper, posId, count);
            this.adLoader = adLoader;
        }

        @Override
        public void onInitSuccess(E loadLooper, String posId, int count) {
            ADSdkManager.getInstance().loadAdapterIniter();
            this.adLoader.loadAd(loadLooper, posId, count);
        }
    }

    public class m extends LoaderFrequencyListener<E> {
        final BaseAdLoader<?, E> adLoader;

        public m(BaseAdLoader<?, E> nVar, E loadLooper, IBasePosInfo posInfo, int count) {
            super(loadLooper, posInfo, count);
            this.adLoader = nVar;
        }

        @Override
        protected void a(E iBaseAdLoadLooper, IBasePosInfo posInfo, int count) {
            ADXiluLogUtil.d("控频校验完成...");
            this.adLoader.loadAd(iBaseAdLoadLooper, posInfo, count);
        }
    }
}
