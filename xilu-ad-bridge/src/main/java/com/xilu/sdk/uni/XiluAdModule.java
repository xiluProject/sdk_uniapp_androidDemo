package com.xilu.sdk.uni;

import android.app.Activity;
import android.content.Intent;
import android.location.Location;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.xilu.sdk.ADXiluSdk;
import com.xilu.sdk.ad.ADXiluFullScreenVodAd;
import com.xilu.sdk.ad.ADXiluInterstitialAd;
import com.xilu.sdk.ad.ADXiluRewardVodAd;
import com.xilu.sdk.ad.data.ADXiluAdInfo;
import com.xilu.sdk.ad.data.ADXiluFullScreenVodAdInfo;
import com.xilu.sdk.ad.data.ADXiluInterstitialAdInfo;
import com.xilu.sdk.ad.data.ADXiluRewardVodAdInfo;
import com.xilu.sdk.ad.entity.ADXiluExtraParams;
import com.xilu.sdk.ad.entity.ADXiluRewardExtra;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluFullScreenVodAdListener;
import com.xilu.sdk.ad.listener.ADXiluInterstitialAdListener;
import com.xilu.sdk.ad.listener.ADXiluRewardVodAdListener;
import com.xilu.sdk.ad.utils.ADXiluLogUtil;
import com.xilu.sdk.config.ADXiluInitConfig;
import com.xilu.sdk.config.CustomDeviceInfoController;
import com.xilu.sdk.listener.ADXiluInitListener;

import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;
import io.dcloud.feature.uniapp.annotation.UniJSMethod;

/** Xilu-AD 桥接 Module：init / 激励视频 / 插屏 / 全屏视频 / 开屏。 */
public class XiluAdModule extends UniModule {

    static {
        // 装自己的 SIGALRM 处理器：signal 14 不再能杀进程
        SignalGuard.install();
    }

    /** 激励视频：每次 load 重建实例（展示后须重新 load，load 前先 release 旧 adInfo）。 */
    private ADXiluRewardVodAd rewardAd;
    private ADXiluRewardVodAdInfo rewardAdInfo;
    private UniJSCallback rewardCb;

    /** 插屏：同构 */
    private ADXiluInterstitialAd interstitialAd;
    private ADXiluInterstitialAdInfo interstitialAdInfo;
    private UniJSCallback interstitialCb;

    /** 全屏视频：同构 */
    private ADXiluFullScreenVodAd fullScreenAd;
    private ADXiluFullScreenVodAdInfo fullScreenAdInfo;
    private UniJSCallback fullScreenCb;

    // 统一事件出口：{ event, data }；页面销毁后静默丢弃

    void emit(UniJSCallback cb, String event, JSONObject data) {
        if (cb == null || mUniSDKInstance == null) return;
        JSONObject p = new JSONObject();
        p.put("event", event);
        p.put("data", data == null ? new JSONObject() : data);
        cb.invokeAndKeepAlive(p);
    }

    /** err(e) → {"error": e.toString()}，ADXiluError.toString() 含错误码。 */
    static JSONObject err(ADXiluError e) {
        return err(e == null ? "unknown" : e.toString());
    }

    static JSONObject err(String e) {
        return json("error", e);
    }

    /** json(k, v) → 单键对象。 */
    static JSONObject json(String k, Object v) {
        JSONObject o = new JSONObject();
        o.put(k, v);
        return o;
    }

    /** 广告信息透传（platform/ecpm 等），供 JS 展示与排查填充渠道。 */
    static JSONObject adInfo(ADXiluAdInfo info) {
        JSONObject d = new JSONObject();
        if (info == null) return d;
        d.put("platform", info.getPlatform());
        d.put("ecpm", info.getECPM());
        d.put("ecpmPrecision", info.getEcpmPrecision());
        d.put("materialId", info.getMaterialId());
        d.put("platformPosId", info.getPlatformPosId());
        return d;
    }

    /** fastjson 兼容取值（uni 基座内置版本无 optXxx/双参 getXxxValue）。 */
    static boolean bool(JSONObject o, String k, boolean def) {
        Boolean v = o.getBoolean(k);
        return v == null ? def : v;
    }

    static int integer(JSONObject o, String k, int def) {
        Integer v = o.getInteger(k);
        return v == null ? def : v;
    }

    static String string(JSONObject o, String k, String def) {
        String v = o.getString(k);
        return v == null ? def : v;
    }

    /** 未初始化守卫：未 init 时直接回调失败，JS 侧保证在 onSuccess 后再调用。 */
    private boolean guardInit(UniJSCallback cb) {
        if (ADXiluSdk.getInstance().isInit()) return false;
        emit(cb, "onAdFailed", err("SDK未初始化"));
        return true;
    }

    // init
    @UniJSMethod(uiThread = true)
    public void init(JSONObject o, final UniJSCallback cb) {
        // Xilu SDK 日志默认关闭（release 编译），宿主可按需开启 logDebug，tag 固定为 ADXiluLog。
        ADXiluLogUtil.setEnableLog(bool(o, "logDebug", false));

        ADXiluInitConfig.Builder builder = new ADXiluInitConfig.Builder()
                .appId(o.getString("appId"))
                .debug(bool(o, "debug", false))
                // 是否同意隐私政策；置 false 会禁用设备信息读取
                .agreePrivacyStrategy(bool(o, "agreePrivacyStrategy", false))
                .isCanUseLocation(bool(o, "isCanUseLocation", true))
                .isCanUsePhoneState(bool(o, "isCanUsePhoneState", true))
                .isCanReadInstallList(bool(o, "isCanReadInstallList", true))
                .isCanUseReadWriteExternal(bool(o, "isCanUseReadWriteExternal", false))
                .isCanUseWifiState(bool(o, "isCanUseWifiState", true))
                .isCanUseOaid(bool(o, "isCanUseOaid", true))
                .filterThirdQuestion(bool(o, "filterThirdQuestion", true))
                .isCanUseSensor(bool(o, "isCanUseSensor", true));

        // 可选字段 customDeviceInfo：隐私开关关闭时由宿主显式传入设备标识
        JSONObject cdi = o.getJSONObject("customDeviceInfo");
        if (cdi != null) {
            final String imei = cdi.getString("imei");
            final String oaid = cdi.getString("oaid");
            final String vaid = cdi.getString("vaid");
            final String macAddress = cdi.getString("macAddress");
            final JSONObject location = cdi.getJSONObject("location");
            builder.setCustomDeviceInfoController(new CustomDeviceInfoController() {
                @Override
                public String getImei() {
                    return imei;
                }

                @Override
                public String getOaid() {
                    return oaid;
                }

                @Override
                public String getVaid() {
                    return vaid;
                }

                @Override
                public String getMacAddress() {
                    return macAddress;
                }

                @Override
                public Location getLocation() {
                    if (location == null) return null;
                    Location l = new Location("custom");
                    l.setLatitude(location.getDouble("latitude") == null ? 0 : location.getDoubleValue("latitude"));
                    l.setLongitude(location.getDouble("longitude") == null ? 0 : location.getDoubleValue("longitude"));
                    return l;
                }
            });
        }

        // 必须在 ADXiluSdk.init 前注册热启动路由：修正透明壳导致的宿主错位，详见 XiluHotStartRouter。
        XiluHotStartRouter.install(
                (android.app.Application) mUniSDKInstance.getContext().getApplicationContext());

        // Application context 初始化（Banner 自刷新硬性要求）
        ADXiluSdk.getInstance().init(mUniSDKInstance.getContext().getApplicationContext(),
                builder.build(), new ADXiluInitListener() {
                    @Override
                    public void onSuccess() {
                        emit(cb, "onSuccess", null);
                    }

                    @Override
                    public void onFailed(String error) {
                        emit(cb, "onFailed", err(error));
                    }
                });
    }

    // 保持 uiThread=false：uni 的 NativeInvokeHelper 对 uiThread=true 方法 postOnUiThread 后立即 return null，故返回 boolean 的同步方法只能在 JS 线程执行；
// guardInit() 在 loadXxx/showSplash 内部（主线程），与 setInitListenerSuccess() 同线程，无可见性问题。
    @UniJSMethod(uiThread = false)
    public boolean isInit() {
        return ADXiluSdk.getInstance().isInit();
    }

    // 激励视频

    // 必须 uiThread=true：SDK 校验主线程，否则回调 onAdFailed(-20000)
    @UniJSMethod(uiThread = true)
    public void loadRewardVideo(JSONObject o, UniJSCallback cb) {
        rewardCb = cb;
        if (guardInit(cb)) return;
        // 重新加载前释放旧广告（一次成功拉取的广告数据只允许展示一次）。
        if (rewardAdInfo != null) {
            rewardAdInfo.release();
            rewardAdInfo = null;
        }
        Activity activity = (Activity) mUniSDKInstance.getContext();
        // 复用同一个 rewardAd 实例，避免多实例并发导致 SDK 内部状态冲突。
        if (rewardAd == null) {
            rewardAd = new ADXiluRewardVodAd(activity);
            rewardAd.setListener(new ADXiluRewardVodAdListener() {
                @Override
                public void onAdReceive(ADXiluRewardVodAdInfo adInfo) {
                    rewardAdInfo = adInfo;
                    emit(rewardCb, "onAdReceive", adInfo(adInfo));
                }

                @Override
                public void onReward(ADXiluRewardVodAdInfo adInfo) {
                    emit(rewardCb, "onReward", null);
                }

                @Override
                public void onVideoCache(ADXiluRewardVodAdInfo adInfo) {
                    // 部分渠道不会回调该方法，请在 onAdReceive 做广告展示处理。
                    emit(rewardCb, "onVideoCache", null);
                }

                @Override
                public void onVideoComplete(ADXiluRewardVodAdInfo adInfo) {
                    emit(rewardCb, "onVideoComplete", null);
                }

                @Override
                public void onVideoError(ADXiluRewardVodAdInfo adInfo, ADXiluError error) {
                    emit(rewardCb, "onVideoError", err(error));
                }

                @Override
                public void onAdExpose(ADXiluRewardVodAdInfo adInfo) {
                    emit(rewardCb, "onAdExpose", null);
                }

                @Override
                public void onAdClick(ADXiluRewardVodAdInfo adInfo) {
                    emit(rewardCb, "onAdClick", null);
                }

                @Override
                public void onAdClose(ADXiluRewardVodAdInfo adInfo) {
                    emit(rewardCb, "onAdClose", null);
                }

                @Override
                public void onAdFailed(ADXiluError error) {
                    emit(rewardCb, "onAdFailed", err(error));
                }
            });
        }
        ADXiluExtraParams.Builder eb = new ADXiluExtraParams.Builder()
                .setVideoWithMute(bool(o, "muted", false))
                .setAdShakeDisable(bool(o, "adShakeDisable", false));
        // 服务端验证（可选）：带 userId 时透传。
        String userId = string(o, "userId", "");
        if (!userId.isEmpty()) {
            ADXiluRewardExtra extra = new ADXiluRewardExtra(userId);
            extra.setRewardName(string(o, "rewardName", ""));
            extra.setRewardAmount(integer(o, "rewardAmount", 0));
            extra.setCustomData(string(o, "customData", ""));
            eb.rewardExtra(extra);
        }
        rewardAd.setLocalExtraParams(eb.build());
        rewardAd.setOnlySupportPlatform(string(o, "onlySupportPlatform", null));
        rewardAd.setSceneId(string(o, "sceneId", ""));
        rewardAd.loadAd(string(o, "posId", ""));
    }

    @UniJSMethod(uiThread = true)
    public void showRewardVideo(JSONObject o, UniJSCallback cb) {
        // 三重校验与 Demo 一致，失败经 onShowFailed 返回。
        if (rewardAdInfo == null) {
            emit(cb, "onShowFailed", err("无可用广告"));
            return;
        }
        if (!rewardAdInfo.isReady()) {
            emit(cb, "onShowFailed", err("广告未准备好"));
            return;
        }
        if (rewardAdInfo.hasExpired()) {
            emit(cb, "onShowFailed", err("广告已失效"));
            return;
        }
        rewardAdInfo.showRewardVod((Activity) mUniSDKInstance.getContext());
        // 展示后的过程事件继续走 rewardCb
    }

    // 插屏

    @UniJSMethod(uiThread = true)
    public void loadInterstitial(JSONObject o, UniJSCallback cb) {
        interstitialCb = cb;
        if (guardInit(cb)) return;
        if (interstitialAdInfo != null) {
            interstitialAdInfo.release();
            interstitialAdInfo = null;
        }
        Activity activity = (Activity) mUniSDKInstance.getContext();
        // 复用同一个 interstitialAd 实例，避免多实例并发导致 SDK 内部状态冲突。
        if (interstitialAd == null) {
            interstitialAd = new ADXiluInterstitialAd(activity);
            interstitialAd.setLocalExtraParams(new ADXiluExtraParams.Builder()
                    .setVideoWithMute(bool(o, "muted", false))
                    .build());
            interstitialAd.setOnlySupportPlatform(string(o, "onlySupportPlatform", null));
            interstitialAd.setListener(new ADXiluInterstitialAdListener() {
                @Override
                public void onAdReceive(ADXiluInterstitialAdInfo adInfo) {
                    interstitialAdInfo = adInfo;
                    emit(interstitialCb, "onAdReceive", adInfo(adInfo));
                }

                @Override
                public void onAdReady(ADXiluInterstitialAdInfo adInfo) {
                    // 部分渠道不会回调该方法，请在 onAdReceive 做广告展示处理。
                    emit(interstitialCb, "onAdReady", null);
                }

                @Override
                public void onAdExpose(ADXiluInterstitialAdInfo adInfo) {
                    emit(interstitialCb, "onAdExpose", null);
                }

                @Override
                public void onAdClick(ADXiluInterstitialAdInfo adInfo) {
                    emit(interstitialCb, "onAdClick", null);
                }

                @Override
                public void onAdClose(ADXiluInterstitialAdInfo adInfo) {
                    emit(interstitialCb, "onAdClose", null);
                }

                @Override
                public void onAdFailed(ADXiluError error) {
                    emit(interstitialCb, "onAdFailed", err(error));
                }
            });
        }
        interstitialAd.setSceneId(string(o, "sceneId", ""));
        interstitialAd.loadAd(string(o, "posId", ""));
    }

    @UniJSMethod(uiThread = true)
    public void showInterstitial(JSONObject o, UniJSCallback cb) {
        if (interstitialAdInfo == null) {
            emit(cb, "onShowFailed", err("无可用广告"));
            return;
        }
        if (!interstitialAdInfo.isReady()) {
            emit(cb, "onShowFailed", err("广告未准备好"));
            return;
        }
        if (interstitialAdInfo.hasExpired()) {
            emit(cb, "onShowFailed", err("广告已失效"));
            return;
        }
        interstitialAdInfo.showInterstitial((Activity) mUniSDKInstance.getContext());
    }

    // 全屏视频

    @UniJSMethod(uiThread = true)
    public void loadFullScreenVod(JSONObject o, UniJSCallback cb) {
        fullScreenCb = cb;
        if (guardInit(cb)) return;
        if (fullScreenAdInfo != null) {
            fullScreenAdInfo.release();
            fullScreenAdInfo = null;
        }
        Activity activity = (Activity) mUniSDKInstance.getContext();
        // 复用同一个 fullScreenAd 实例，避免多实例并发导致 SDK 内部状态冲突
        if (fullScreenAd == null) {
            fullScreenAd = new ADXiluFullScreenVodAd(activity);
            fullScreenAd.setListener(new ADXiluFullScreenVodAdListener() {
                @Override
                public void onAdReceive(ADXiluFullScreenVodAdInfo adInfo) {
                    fullScreenAdInfo = adInfo;
                    emit(fullScreenCb, "onAdReceive", adInfo(adInfo));
                }

                @Override
                public void onVideoCache(ADXiluFullScreenVodAdInfo adInfo) {
                    // 部分渠道不会回调该方法，请在 onAdReceive 做广告展示处理
                    emit(fullScreenCb, "onVideoCache", null);
                }

                @Override
                public void onVideoComplete(ADXiluFullScreenVodAdInfo adInfo) {
                    emit(fullScreenCb, "onVideoComplete", null);
                }

                @Override
                public void onVideoError(ADXiluFullScreenVodAdInfo adInfo, ADXiluError error) {
                    emit(fullScreenCb, "onVideoError", err(error));
                }

                @Override
                public void onAdExpose(ADXiluFullScreenVodAdInfo adInfo) {
                    emit(fullScreenCb, "onAdExpose", null);
                }

                @Override
                public void onAdClick(ADXiluFullScreenVodAdInfo adInfo) {
                    emit(fullScreenCb, "onAdClick", null);
                }

                @Override
                public void onAdClose(ADXiluFullScreenVodAdInfo adInfo) {
                    emit(fullScreenCb, "onAdClose", null);
                }

                @Override
                public void onAdFailed(ADXiluError error) {
                    emit(fullScreenCb, "onAdFailed", err(error));
                }
            });
        }
        fullScreenAd.setOnlySupportPlatform(string(o, "onlySupportPlatform", null));
        fullScreenAd.loadAd(string(o, "posId", ""));
    }

    @UniJSMethod(uiThread = true)
    public void showFullScreenVod(JSONObject o, UniJSCallback cb) {
        if (fullScreenAdInfo == null) {
            emit(cb, "onShowFailed", err("无可用广告"));
            return;
        }
        if (!fullScreenAdInfo.isReady()) {
            emit(cb, "onShowFailed", err("广告未准备好"));
            return;
        }
        if (fullScreenAdInfo.hasExpired()) {
            emit(cb, "onShowFailed", err("广告已失效"));
            return;
        }
        fullScreenAdInfo.showFullScreenVod((Activity) mUniSDKInstance.getContext());
    }

    // 开屏

    // 同时满足：SDK 主线程要求 + startActivity 必须在主线程
    @UniJSMethod(uiThread = true)
    public void showSplash(JSONObject o, final UniJSCallback cb) {
        if (guardInit(cb)) return;
        Activity activity = (Activity) mUniSDKInstance.getContext();
        Intent i = new Intent(activity, XiluSplashActivity.class);
        i.putExtra("posId", string(o, "posId", ""));
        i.putExtra("splashType", integer(o, "splashType", 0));   // 0沉浸全屏 1全屏 2半屏（实收值由 Activity 打印）
        i.putExtra("logoHeightPx", integer(o, "logoHeightPx", 0));
        i.putExtra("adShakeDisable", bool(o, "adShakeDisable", false));
        // 加载方式：0 = LOAD_AND_SHOW（收到即展示，默认）、1 = LOAD_ONLY（Activity 内手动加载/展示）
        i.putExtra("loadType", integer(o, "loadType", XiluSplashActivity.LOAD_AND_SHOW));
        // 自定义跳过按钮（setSkipView(tvSkip, 5000)）
        i.putExtra("customSkipView", bool(o, "customSkipView", false));
        i.putExtra("skipViewTimeMs", (long) integer(o, "skipViewTimeMs", 5000));
        // 开屏同样支持仅指定平台拉取，仅 debug 生效
        i.putExtra("onlySupportPlatform", string(o, "onlySupportPlatform", null));
        // Activity → JS 的事件桥（org.json 字符串 → fastjson 后统一走 emit）
        XiluSplashActivity.setCallback(new XiluSplashActivity.EventCallback() {
            @Override
            public void onEvent(String event, String dataJson) {
                JSONObject data = null;
                if (dataJson != null && !dataJson.isEmpty()) {
                    try {
                        data = JSON.parseObject(dataJson);
                    } catch (Exception ignore) {
                    }
                }
                emit(cb, event, data);
            }
        });
        activity.startActivity(i);
    }

    // 页面销毁：释放三类广告数据

    @Override
    public void onActivityDestroy() {
        if (rewardAdInfo != null) {
            rewardAdInfo.release();
            rewardAdInfo = null;
        }
        if (interstitialAdInfo != null) {
            interstitialAdInfo.release();
            interstitialAdInfo = null;
        }
        if (fullScreenAdInfo != null) {
            fullScreenAdInfo.release();
            fullScreenAdInfo = null;
        }
    }
}

