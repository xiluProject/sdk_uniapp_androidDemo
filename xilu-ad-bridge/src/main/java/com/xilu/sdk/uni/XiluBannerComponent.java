package com.xilu.sdk.uni;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.xilu.sdk.ad.ADXiluBannerAd;
import com.xilu.sdk.ad.data.ADXiluAdInfo;
import com.xilu.sdk.ad.entity.ADXiluAdSize;
import com.xilu.sdk.ad.entity.ADXiluExtraParams;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluBannerAdListener;
import com.xilu.sdk.ad.listener.ADXiluAdSizeListener;

import java.util.HashMap;
import java.util.Map;

import io.dcloud.feature.uniapp.UniSDKInstance;
import io.dcloud.feature.uniapp.ui.action.AbsComponentData;
import io.dcloud.feature.uniapp.ui.component.AbsVContainer;
import io.dcloud.feature.uniapp.ui.component.UniComponent;
import io.dcloud.feature.uniapp.ui.component.UniComponentProp;

/**
 * Banner 广告组件（nvue 页面用，需显式宽高）
 * 属性：posId、sceneId、onlySupportPlatform、refreshInterval、adShakeDisable、adSize
 * 事件：onAdReceive/onAdRender/onAdExpose/onAdClick/onAdClose/onAdFailed
 *
 * onAdRender 回传广告真实宽高（dp），nvue 不支持 wrap_content，页面据此设置槽位尺寸。
 */
public class XiluBannerComponent extends UniComponent<FrameLayout> {

    private static final int DEFAULT_REFRESH_INTERVAL = 30;
    /** SDK 限定范围：30~120 秒 */
    private static final int MIN_REFRESH_INTERVAL = 30;
    private static final int MAX_REFRESH_INTERVAL = 120;

    private ADXiluBannerAd bannerAd;
    private String sceneId = "";
    private String onlySupportPlatform;
    private int refreshInterval = DEFAULT_REFRESH_INTERVAL;
    private boolean adShakeDisable = false;
    /** App 指定的横幅请求框（"宽*高"，px）；空串表示不指定 */
    private String adSizeSpec = "";

    /** posId 只生效一次 */
    private boolean started;

    private static final String TAG = "XiluBannerComponent";
    /** 已上报过的宽高（dp），避免同值反复触发 JS */
    private int reportedWidth;
    private int reportedHeight;
    /** 收敛轮询：WebView 异步加载，读数连续两次一致才停 */
    private int probeTick;
    private int stableCount;
    private int lastRawH;
    /** SDK 回传的"请求时的框"（px），只作上限用 */
    private int sdkBoxWidthPx;
    private int sdkBoxHeightPx;
    /** 最近一次可信读数的高度（px）：后续某一拍量不到时沿用它，避免槽位被文字高度压扁 */
    private int lastGoodH;
    private static final int MAX_PROBE_TICKS = 15;
    private static final long PROBE_TICK_MS = 400L;
    /** 低于此高度（px）的视图不可能是横幅创意，按装饰/分隔处理 */
    private static final int MIN_CONTENT_H = 40;

    /**
     * 量广告内容实际占用的宽度（px）：取"从画布左边起头"的子树里最宽的那个，
     * 角标/关闭按钮贴右边会被排除。主要应对穿山甲固定 300dp 宽模板导致的右侧留白。
     *
     * @return 内容宽度；判断不出来返回 0，调用方按可用宽度处理
     */
    private static int measureContentWidth(View canvas) {
        int canvasW = canvas.getWidth();
        if (canvasW <= 0) return 0;
        int[] best = {0};
        if (canvas instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) canvas;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectContentWidth(g.getChildAt(i), canvasW, 1, best);
            }
        }
        return best[0];
    }

    private static void collectContentWidth(View v, int canvasW, int depth, int[] best) {
        if (v == null || depth > 12 || v.getVisibility() != View.VISIBLE) return;
        int w = v.getWidth();
        // 只认从画布左边起头的子树，角标/关闭按钮会被这条挡掉
        if (w > 0 && v.getLeft() <= canvasW / 10 && w > best[0]) {
            best[0] = w;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectContentWidth(g.getChildAt(i), canvasW, depth + 1, best);
            }
        }
    }

    /**
     * 轮询探测广告实际宽高并回传 JS。
     *
     * 模板创意渲染在 WebView 的 HTML 里，原生视图树量不到内容（只会回声画布），
     * 所以注入 JS 取 DOM 里非画布级元素的最大底边作为内容高度；取不到再退回素材图比例。
     * WebView 异步加载，循环复测到读数稳定才回传，避免页面高度横跳。
     * 单位：DOM 读数为 CSS px（等于 dp）直接用，视图树量到的物理像素需除以 density。
     */
    private static final String DOM_PROBE_JS =
            "(function(){" +
            "var W=window.innerWidth,H=window.innerHeight,best=0;" +
            "var a=document.body?document.body.getElementsByTagName('*'):[];" +
            "for(var i=0;i<a.length&&i<600;i++){" +
            "var r=a[i].getBoundingClientRect();" +
            "if(r.width<1||r.height<1)continue;" +
            "if(r.width>=W*0.95&&r.height>=H*0.95)continue;" + // 画布级包装层，跳过
            "if(r.bottom>best)best=r.bottom;}" +
            "return Math.round(best)+'|'+Math.round(H);})()";

    private final Runnable sizeProbe = new Runnable() {
        @Override
        public void run() {
            FrameLayout host = getHostView();
            View ad = host != null && host.getChildCount() > 0 ? host.getChildAt(0) : null;
            if (host == null || ad == null || bannerAd == null) return; // 已释放/未挂载，停止循环
            int availW = host.getWidth();
            if (availW <= 0) {
                // 尚未布局完成：退化为屏宽
                availW = host.getContext().getResources().getDisplayMetrics().widthPixels;
            }
            // 宽度：量已渲染内容的真实几何（固定 300dp 宽的模板只占画布左边一段）
            int contentW = measureContentWidth(ad);
            final int wPx = (contentW > 0 && contentW < availW) ? contentW : availW;
            // 备用高度①：设计宽高比换算
            ad.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            final int natW = ad.getMeasuredWidth();
            final int natH = ad.getMeasuredHeight();
            // 备用高度②：wrap 语义测量（EXACTLY(容器宽) × AT_MOST(屏高)，同原生 demo 的容器）
            int maxH = host.getResources().getDisplayMetrics().heightPixels;
            ad.measure(View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
            final int wrapH = ad.getMeasuredHeight();
            final String fallbackInfo = "nat=" + natW + "x" + natH + ", wrapH=" + wrapH;
            final float density = host.getResources().getDisplayMetrics().density;
            final int imgH = measureImageContentHeight(ad, wPx);
            final int textH = measureTextContentHeight(ad, wPx);
            // 原生视图树的创意高度最可靠：平台把创意渲染进自己的视图，外层包装层会被槽位拉满，
            // 里面那层才是素材真实高度。素材图比例（imgH）和文字行高（textH）都只是它的兜底。
            final int treeH = measureNativeContentHeight(ad, wPx);
            // 原生视图树的创意高度最可靠：平台把创意渲染进自己的视图，外层包装层会被槽位拉满，
    // 里面那层才是素材真实高度。素材图固有像素高（imgH）不能当横幅高度用（图不等于横幅），
    // 探针偶尔量不到 treeH 时沿用上一次的可信值，避免 169↔607 来回跳。
    final int mediaH = treeH > 0 ? Math.max(treeH, textH) : lastGoodH;
            // 「可信读数」= 平台自己的渲染几何。只有它缺失时才需要等下一拍。
            final boolean credible = treeH > 0 || imgH > 0;
            if (probeTick == 0) Log.d(TAG, "tree: " + dumpTree(ad, 0, 5, wPx));
            final WebView wv = findWebView(ad, 0);
            if (wv != null) {
                wv.evaluateJavascript(DOM_PROBE_JS, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        // value 形如 "\"184|340\""；184 = 内容底边（CSS px = dp），340 = innerHeight
                        int domBottom = -1;
                        int innerH = 0;
                        if (value != null) {
                            String[] p = value.replace("\"", "").split("\\|");
                            if (p.length == 2) {
                                try { domBottom = Integer.parseInt(p[0].trim()); }
                                catch (NumberFormatException ignored) { }
                                try { innerH = Integer.parseInt(p[1].trim()); }
                                catch (NumberFormatException ignored) { }
                            }
                        }
                        Log.d(TAG, "dom raw: bottom=" + domBottom + "dp, innerH=" + innerH
                                + ", treeH=" + treeH + ", imgH=" + imgH + ", textH=" + textH
                                + ", nat=" + natW + "x" + natH);
                        // 读数≈画布自身高度 = 只量到了画布级包装层（HTML 文档常比创意高），
                        // 这种值会把槽位撑成 SDK 的框、下面空一大片，判为无效。
                        // 阈值取 90%：真实横幅内容不会贴到画布底部，而误读的画布级元素基本都接近满高。
                        if (domBottom > 0 && innerH > 0 && domBottom >= innerH * 0.9f) {
                            Log.d(TAG, "domBottom=" + domBottom + " 已达画布高度 innerH=" + innerH + "，判为无效读数");
                            domBottom = -1;
                        }
                        // 没有任何可信读数时只剩文字高度，单独回传会把横幅槽位塌成一条，
                        // 这一拍不回传，继续轮询等创意渲染出来
                        if (domBottom <= 0 && !credible && probeTick < MAX_PROBE_TICKS) {
                            probeTick++;
                            host.postDelayed(sizeProbe, PROBE_TICK_MS);
                            return;
                        }
                        int hPx;
                        if (domBottom > 0) {
                            hPx = Math.round(domBottom * density);
                        } else if (mediaH > 0) {
                            hPx = mediaH;
                        } else if (natW > 0 && natH > 0) {
                            hPx = Math.round((float) wPx * natH / natW);
                        } else {
                            // natW == 0 表示 SDK 未就绪，这一拍不上报，继续轮询
                            if (probeTick < MAX_PROBE_TICKS) {
                                probeTick++;
                                host.postDelayed(sizeProbe, PROBE_TICK_MS);
                            }
                            return;
                        }
                        // 这一拍没有可信读数时沿用上一次可信高度：槽位已经收到那条高度上，
                        // 再退回文字高度会把创意直接压扁
                        if (domBottom > 0 || credible) {
                            lastGoodH = hPx;
                        } else if (lastGoodH > 0) {
                            hPx = lastGoodH;
                        }
                        settle(host, wPx, hPx, domBottom, fallbackInfo);
                    }
                });
            } else if (mediaH > 0) {
                // 没有可信读数时文字高度不足以定横幅高度：还有轮询机会就等创意渲染，最后一拍才兜底
                if (!credible && probeTick < MAX_PROBE_TICKS) {
                    probeTick++;
                    host.postDelayed(sizeProbe, PROBE_TICK_MS);
                    return;
                }
                int h = mediaH;
                if (credible) {
                    lastGoodH = h;
                } else if (lastGoodH > 0) {
                    h = lastGoodH;
                }
                settle(host, wPx, h, -1, fallbackInfo + ", treeH=" + treeH + ", imgH=" + imgH + ", textH=" + textH);
            } else if (natW > 0 && natH > 0) {
                settle(host, wPx, Math.round((float) wPx * natH / natW), -1, fallbackInfo);
            } else {
                // 同 WebView 分支：natW == 0 时这一拍不上报
                if (probeTick < MAX_PROBE_TICKS) {
                    probeTick++;
                    host.postDelayed(sizeProbe, PROBE_TICK_MS);
                }
            }
        }
    };

    /** 一轮读数落地：日志、收敛判断、回传、继续/停止轮询 */
    private void settle(FrameLayout host, int wPx, int hPx, int domBottom, String fallbackInfo) {
        if (host == null || wPx <= 0 || hPx <= 0) {
            if (probeTick < MAX_PROBE_TICKS) {
                probeTick++;
                host.postDelayed(sizeProbe, PROBE_TICK_MS);
            }
            return;
        }
        // 内容高度不可能超过平台接受的框：SDK 回传过框就夹一次（防探测把高度算大）
        if (sdkBoxHeightPx > 0 && hPx > sdkBoxHeightPx) {
            hPx = sdkBoxHeightPx;
        }
        // 读数≈容器高度 = 被 MATCH_PARENT 拉满的包装层，不是创意真实高度：丢弃，沿用上次可信值。
        // 不丢的话会"长高→撑满→再长高"来回跳（穿山甲自适应模板就是这个现象）
        int slotH = host.getHeight();
        if (slotH > 2 && hPx >= slotH - 2) {
            if (lastGoodH > 0) {
                hPx = lastGoodH;
            } else if (probeTick < MAX_PROBE_TICKS) {
                probeTick++;
                host.postDelayed(sizeProbe, PROBE_TICK_MS);
                return;
            }
        }
        Log.d(TAG, "size probe: tick=" + probeTick + ", slot=" + host.getWidth() + "x" + host.getHeight()
                + ", domBottom=" + domBottom + "dp, " + fallbackInfo + ", sdkBox=" + sdkBoxHeightPx
                + "px -> h=" + hPx + "px");
        // 收敛判断：连续两次相同读数才停，否则继续轮询（WebView 可能还在加载）
        if (hPx == lastRawH) stableCount++; else { lastRawH = hPx; stableCount = 0; }
        probeTick++;
        if (stableCount < 2 && probeTick < MAX_PROBE_TICKS) {
            host.postDelayed(sizeProbe, PROBE_TICK_MS);
        }
        // 读数稳定后才回传；一直不稳定时最后一拍兜底，避免页面停在占位高度
        if (stableCount < 1 && probeTick < MAX_PROBE_TICKS) {
            return;
        }
        // 物理像素 → dp 再回传（直接回传 px 会被页面当 dp 用，放大 density 倍）
        float density = host.getResources().getDisplayMetrics().density;
        if (density <= 0) density = 1f;
        int w = Math.round(wPx / density);
        // 高度向上取整，避免裁掉最后一行文案
        int h = (int) Math.ceil(hPx / density);
        if (w == reportedWidth && h == reportedHeight) return;
        reportedWidth = w;
        reportedHeight = h;
        Map<String, Object> params = new HashMap<>();
        params.put("width", w);
        params.put("height", h);
        fireEventWithDetail("onAdRender", params);
    }

    /** 在广告视图树里找 WebView（模板创意渲染在它里面） */
    private static WebView findWebView(View v, int depth) {
        if (v == null || depth > 12) return null;
        if (v instanceof WebView) return (WebView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                WebView r = findWebView(g.getChildAt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 打印祖先链真实宽高（px），排查"内容一小条、容器被撑高"时用 */
    private static String ancestorChain(View v, int max) {
        StringBuilder sb = new StringBuilder();
        int d = 0;
        while (v != null && d < max) {
            String name = v.getClass().getSimpleName();
            if (name == null || name.isEmpty()) name = "FrameLayout";
            sb.append(name).append('(').append(v.getWidth()).append('x').append(v.getHeight()).append(") ");
            android.view.ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
            d++;
        }
        return sb.toString();
    }

    /** 打印广告视图树（宽高 + ImageView 的固有尺寸/scaleType），排查尺寸问题时调用 */
    private static String dumpTree(View v, int depth, int maxDepth, int canvasW) {
        if (v == null || depth > maxDepth) return "";
        StringBuilder sb = new StringBuilder();
        String name = v.getClass().getSimpleName();
        if (name == null || name.isEmpty()) name = v.getClass().getName();
        sb.append(name).append('(').append(v.getWidth()).append('x').append(v.getHeight()).append(')');
        if (canvasW > 0) {
            v.measure(View.MeasureSpec.makeMeasureSpec(canvasW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            sb.append("{m=").append(v.getMeasuredHeight()).append('}');
        }
        if (v instanceof android.widget.ImageView) {
            android.graphics.drawable.Drawable dw = ((android.widget.ImageView) v).getDrawable();
            sb.append("{img=").append(dw == null ? "null" : dw.getIntrinsicWidth() + "x" + dw.getIntrinsicHeight())
                    .append(",scale=").append(((android.widget.ImageView) v).getScaleType()).append('}');
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            sb.append('[');
            for (int i = 0; i < g.getChildCount(); i++) {
                sb.append(dumpTree(g.getChildAt(i), depth + 1, maxDepth, canvasW)).append(' ');
            }
            sb.append(']');
        }
        return sb.toString();
    }

    /**
     * 量文字所需高度（px）：只量 TextView，宽度=槽位宽、高度不限。
     * 图片不能这样量（会被整宽放大），必须走 measureImageContentHeight 的固有比例。
     */
    private static int measureTextContentHeight(View canvas, int canvasW) {
        if (canvas == null || canvasW <= 0) return -1;
        int[] best = {-1};
        collectMaxTextHeight(canvas, canvasW, 0, best);
        return best[0];
    }

    private static void collectMaxTextHeight(View v, int canvasW, int depth, int[] best) {
        if (v == null || depth > 12) return;
        if (v instanceof android.widget.TextView && v.getVisibility() == View.VISIBLE) {
            android.widget.TextView tv = (android.widget.TextView) v;
            if (tv.getText() != null && tv.getText().length() > 0) {
                tv.measure(View.MeasureSpec.makeMeasureSpec(canvasW, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                int h = tv.getMeasuredHeight();
                // 上限 1.6×画布宽：横幅文案不可能比这更高，超出的必然是误读
                if (h > best[0] && h <= canvasW * 1.6f) best[0] = h;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectMaxTextHeight(g.getChildAt(i), canvasW, depth + 1, best);
            }
        }
    }

    /** 取主素材图按固有比例换算的高度（px）；没有可用图返回 -1 */
    private static int measureImageContentHeight(View canvas, int canvasW) {
        if (canvas == null || canvasW <= 0) return -1;
        int[] bestW = {-1};
        int[] bestH = {-1};
        collectMainImage(canvas, 0, canvasW, bestW, bestH);
        if (bestW[0] <= 0 || bestH[0] <= 0) return -1;
        int h = Math.round((float) canvasW * bestH[0] / bestW[0]);
        return (h > 0 && h <= canvasW * 3.2f) ? h : -1;
    }

    /** 取原生视图树里创意视图的高度（px）；量不到返回 -1 */
    private static int measureNativeContentHeight(View ad, int canvasW) {
        if (ad == null || canvasW <= 0) return -1;
        int result = -1;
        View v = ad;
        int prevH = v.getHeight();
        for (int depth = 0; depth < 12; depth++) {
            if (!(v instanceof ViewGroup)) break;
            ViewGroup g = (ViewGroup) v;
            if (g.getChildCount() != 1) break;
            View child = g.getChildAt(0);
            if (child == null) break;
            int ch = child.getHeight();
            if (ch <= 0) { v = child; continue; }
            if (ch >= prevH) break;
            if (child.getWidth() < canvasW / 2) break;
            if (ch < MIN_CONTENT_H) break;
            result = ch;
            v = child;
            prevH = ch;
        }
        return result;
    }

    private static void collectMainImage(View v, int depth, int canvasW, int[] bestW, int[] bestH) {
        if (v == null || depth > 12) return;
        if (v instanceof android.widget.ImageView && v.getVisibility() == View.VISIBLE) {
            android.graphics.drawable.Drawable d = ((android.widget.ImageView) v).getDrawable();
            if (d != null && d.getIntrinsicWidth() > 0 && d.getIntrinsicHeight() > 0) {
                int iw = d.getIntrinsicWidth();
                int ih = d.getIntrinsicHeight();
                // 源图才承载素材：太小的多是图标/占位
                boolean usable = iw >= canvasW / 4
                        && (float) canvasW * ih / iw <= canvasW * 3.2f;
                Log.d(TAG, "img cand: iw=" + iw + ", ih=" + ih + ", usable=" + usable
                        + ", minW=" + (canvasW / 4) + ", estH=" + Math.round((float) canvasW * ih / iw));
                if (usable && iw > bestW[0]) {
                    bestW[0] = iw;
                    bestH[0] = ih;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectMainImage(g.getChildAt(i), depth + 1, canvasW, bestW, bestH);
            }
        }
    }

    /** 启动/重启一轮收敛循环（onViewAdded 和 onAdReceive 都会调，需可重入） */
    private void startProbe() {
        FrameLayout host = getHostView();
        if (host == null) return;
        host.removeCallbacks(sizeProbe);
        probeTick = 0;
        stableCount = 0;
        lastRawH = -1;
        host.postDelayed(sizeProbe, 300);
    }

    public XiluBannerComponent(UniSDKInstance instance, AbsVContainer parent, int type, AbsComponentData data) {
        super(instance, parent, type, data);
    }

    public XiluBannerComponent(UniSDKInstance instance, AbsVContainer parent, AbsComponentData data) {
        super(instance, parent, data);
    }

    @Override
    protected FrameLayout initComponentHostView(@NonNull Context context) {
        // 容器不得拦截点击、触摸等事件
        return new FrameLayout(context) {
            @Override
            public void onViewAdded(View child) {
                super.onViewAdded(child);
                // 广告视图被加入：启动尺寸收敛循环（不能挂 onLayout：
                // 探测里的 measure() 会再触发一次 onLayout，挂上去就是无限重排（页面一直闪））
                startProbe();
            }
        };
    }

    @UniComponentProp(name = "sceneId")
    public void setSceneId(String sceneId) {
        this.sceneId = sceneId == null ? "" : sceneId;
        if (bannerAd != null) bannerAd.setSceneId(this.sceneId);
    }

    /** 仅 debug 模式生效，上线时建议不设置 */
    @UniComponentProp(name = "onlySupportPlatform")
    public void setOnlySupportPlatform(String platform) {
        this.onlySupportPlatform = platform;
        if (bannerAd != null) bannerAd.setOnlySupportPlatform(platform);
    }

    /** 自刷新间隔（秒），超出 30~120 会被夹到边界；不传则用 DEFAULT_REFRESH_INTERVAL */
    @UniComponentProp(name = "refreshInterval")
    public void setRefreshInterval(int interval) {
        int v = interval;
        if (v < MIN_REFRESH_INTERVAL) v = MIN_REFRESH_INTERVAL;
        if (v > MAX_REFRESH_INTERVAL) v = MAX_REFRESH_INTERVAL;
        this.refreshInterval = v;
        if (bannerAd != null) bannerAd.setAutoRefreshInterval(v);
    }

    /** 控制摇一摇是否禁用 */
    @UniComponentProp(name = "adShakeDisable")
    public void setAdShakeDisable(boolean disable) {
        this.adShakeDisable = disable;
    }

    /**
     * App 指定的横幅请求框（px），格式 "宽*高" 或 "宽:高"。
     * banner 素材比例由请求框决定，后台没配尺寸时可在这里指定（如 "1080*169" 得到 6.4:1）。
     * 优先级：本属性 > 后台广告位配置 > 屏宽×340dp 兜底；留空不生效。
     */
    @UniComponentProp(name = "adSize")
    public void setAdSize(String adSize) {
        this.adSizeSpec = adSize == null ? "" : adSize.trim();
    }

    @UniComponentProp(name = "posId")
    public void setPosId(String posId) {
        if (started || posId == null || posId.isEmpty()) return;
        started = true;
        final String id = posId;
        // 属性可能分多批下发，延后到本轮属性设置结束后再创建/加载，保证 sceneId/refreshInterval 等已就位
        getHostView().post(new Runnable() {
            @Override
            public void run() {
                startLoad(id);
            }
        });
    }

    private void startLoad(String posId) {
        Activity activity = (Activity) getContext();
        if (activity == null) return;
        // 第一个参数可以是 Activity 或 Fragment，第二个参数是广告容器
        bannerAd = new ADXiluBannerAd(activity, getHostView());
        // 自刷新时间范围 30~120 秒（初始化 SDK 须传入 Application context）
        bannerAd.setAutoRefreshInterval(refreshInterval);
        ADXiluExtraParams.Builder paramsBuilder = new ADXiluExtraParams.Builder()
                .setAdShakeDisable(adShakeDisable);
        // App 指定的请求框（优先级高于后台配置与兜底）：解析失败就当作没指定
        ADXiluAdSize requestedSize = parseAdSizeSpec(adSizeSpec);
        if (requestedSize != null) {
            paramsBuilder.adSize(requestedSize);
        }
        bannerAd.setLocalExtraParams(paramsBuilder.build());
        bannerAd.setOnlySupportPlatform(onlySupportPlatform);
        bannerAd.setSceneId(sceneId);
        bannerAd.setListener(new ADXiluBannerAdListener() {
            @Override
            public void onAdReceive(ADXiluAdInfo adInfo) {
                Map<String, Object> params = new HashMap<>();
                if (adInfo != null) {
                    params.put("platform", adInfo.getPlatform());
                    params.put("ecpm", adInfo.getECPM());
                    params.put("ecpmPrecision", adInfo.getEcpmPrecision());
                    params.put("materialId", adInfo.getMaterialId());
                    params.put("platformPosId", adInfo.getPlatformPosId());
                }
                fireEventWithDetail("onAdReceive", params);
                // 新素材（含自刷新换的）重新收敛一遍尺寸：模板可能不同、WebView 要重新加载
                startProbe();
            }

            @Override
            public void onAdExpose(ADXiluAdInfo adInfo) {
                fireEvent("onAdExpose");
            }

            @Override
            public void onAdClick(ADXiluAdInfo adInfo) {
                fireEvent("onAdClick");
            }

            @Override
            public void onAdClose(ADXiluAdInfo adInfo) {
                fireEvent("onAdClose");
                // Demo 的 onAdClose 释放两个对象：bannerAd + adInfo
                if (adInfo != null) {
                    adInfo.release();
                }
                release();
            }

            @Override
            public void onAdFailed(ADXiluError error) {
                // 与 Demo 差异：不 finish 页面，由 JS 决定隐藏容器（v-if）
                Map<String, Object> params = new HashMap<>();
                params.put("error", error == null ? "unknown" : error.toString());
                params.put("errorText", error == null ? "unknown" : plainError(error));
                params.put("errorCode", error == null ? 0 : error.getCode());
                fireEventWithDetail("onAdFailed", params);
                // 失败后立即释放：否则这个 bannerAd 仍被 SDK 持有并继续自刷新/重试，
                // 页面又把容器隐藏了，等于留了一个后台持续渲染的活广告对象。
                release();
            }
        });
        // 走 SDK 新增的重载：加载的同时拿到"平台真正采用的广告尺寸"（px）。
        // 这是适配器里按 platformPosInfo.getAdSize()（后台配置值，或屏宽×340dp 兜底）
        // 算出来交给平台的尺寸，比我们在外面猜可靠得多 —— 拿到就回传并停掉自己的探测。
        bannerAd.loadAd(posId, new ADXiluAdSizeListener() {
            @Override
            public void onAdSize(int widthPx, int heightPx) {
                onSdkSize(widthPx, heightPx);
            }
        });
    }

    /**
     * SDK 回传的"请求时告诉平台的框"（px）：只记录，并作为内容高度的上限夹一次，不当槽位高度。
     * 适配器上报的必须是请求框，不能是量渲染视图得到的尺寸（那会被当前槽位压扁、把槽位锁死）。
     */
    private void onSdkSize(int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) return;
        sdkBoxWidthPx = widthPx;
        sdkBoxHeightPx = heightPx;
        Log.d(TAG, "sdk box: " + widthPx + "x" + heightPx + "px（平台接受的框，槽位按内容实际高度）");
    }

    /**
     * 解析 {@code adSize} 属性：{@code "1080*169"} / {@code "1080:169"}。
     *
     * @return 解析成功且宽高都 &gt; 0 时返回尺寸；否则返回 null（调用方按"未指定"处理）
     */
    private static ADXiluAdSize parseAdSizeSpec(String spec) {
        if (spec == null || spec.isEmpty()) return null;
        try {
            String[] parts = spec.contains("*") ? spec.split("\\*") : spec.split(":");
            if (parts.length != 2) return null;
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            if (w <= 0 || h <= 0) return null;
            return new ADXiluAdSize(w, h);
        } catch (Throwable t) {
            Log.w(TAG, "adSize 属性解析失败: " + spec, t);
            return null;
        }
    }

    /** 不含花括号的纯文本错误描述，供页面直接展示 */
    private static String plainError(ADXiluError error) {
        String msg = error.getError();
        if (msg == null || msg.isEmpty()) msg = "广告加载失败";
        return msg + "（code=" + error.getCode() + "）";
    }

    /** 触发组件事件：业务数据必须放在 detail 键下，否则 JS 侧收到空对象；且必须回主线程（weex 会校验） */
    private void fireEventWithDetail(final String type, Map<String, Object> data) {
        final Map<String, Object> params = new HashMap<>();
        params.put("detail", data);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            fireEvent(type, params);
            return;
        }
        FrameLayout host = getHostView();
        if (host != null) {
            host.post(new Runnable() {
                @Override
                public void run() {
                    fireEvent(type, params);
                }
            });
        }
    }

    private void release() {
        FrameLayout host = getHostView();
        if (host != null) host.removeCallbacks(sizeProbe);
        if (bannerAd != null) {
            bannerAd.release();
            bannerAd = null;
        }
        // 允许 release 后重新传入 posId 重建（页面通常用 v-if 重建组件实例）
        started = false;
        // 新广告要重新量尺寸（可能换了条尺寸不同的创意）
        reportedWidth = 0;
        reportedHeight = 0;
    }

    /** 组件销毁钩子必须实现：nvue 里 v-if 重建组件不会触发 onActivityDestroy，不释放就会留下仍在自刷新的活广告对象 */
    @Override
    public void destroy() {
        release();
        super.destroy();
    }
}
